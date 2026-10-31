#!/usr/bin/env python3
"""Moon Kiln today: a tiny read-only JSON endpoint for the TV launcher's today card.

Serves, on the LAN only and behind a bearer token:
  GET /today    orders today, revenue today, pieces awaiting Delhivery pickup
  GET /health   liveness

Sources, in this order:
  1. The warehouse DuckDB file ig.duckdb (marts fct_orders and fct_shipments), opened read-only for
     the few milliseconds a query takes, then closed, so the nightly dbt writer is never blocked.
     DuckDB allows one writer per file; while dbt holds the file our read fails and the last good
     answer is served with "stale": true.
  2. Optional Shopify Admin API top-up for today's orders, because the warehouse is rebuilt once a
     day at 09:30 IST and would otherwise show zero orders for most of the day. Used only when the
     token file exists and the call succeeds; the warehouse number is the fallback.

Runs under launchd (com.moonkiln.tv-today) from the warehouse venv. No third party web framework:
the standard library http.server is enough for one client on the sofa.
"""
from __future__ import annotations

import datetime as dt
import ipaddress
import json
import os
import socket
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

import duckdb

IST = ZoneInfo("Asia/Kolkata")
APP_SUPPORT = Path.home() / "Library" / "Application Support"
DATA_DIR = Path(os.environ.get("MK_TODAY_DATA_DIR", APP_SUPPORT / "mk-ig-metrics"))
SECRETS = Path(os.environ.get("MK_TODAY_SECRETS", APP_SUPPORT / "mk-airbyte" / "secrets"))
TOKEN_FILE = SECRETS / "mk-tv-today-token"
SHOPIFY_TOKEN_FILE = SECRETS / "mk-shopify-admin-token"
SHOPIFY_STORE = "cbbkep-a2.myshopify.com"
SHOPIFY_API = "2026-07"
PORT = int(os.environ.get("MK_TODAY_PORT", "9470"))
CACHE_SECONDS = int(os.environ.get("MK_TODAY_CACHE", "120"))

# LAN only: home network, Tailscale range, loopback. Anything else gets 403 before the token check.
ALLOWED_NETS = [ipaddress.ip_network(n) for n in (
    "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "100.64.0.0/10", "127.0.0.0/8", "::1/128", "fd7a:115c:a1e0::/48",
)]


def log(msg: str) -> None:
    print(f"{dt.datetime.now(IST).strftime('%Y-%m-%d %H:%M:%S')} {msg}", flush=True)


def inr(amount: float | int | None) -> str:
    """Rs. 1,23,456 with Indian grouping, no paise on the TV."""
    if amount is None:
        return "Rs. 0"
    n = int(round(float(amount)))
    sign = "-" if n < 0 else ""
    s = str(abs(n))
    if len(s) <= 3:
        grouped = s
    else:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        grouped = ",".join(parts) + "," + tail
    return f"{sign}Rs. {grouped}"


def read_token(path: Path) -> str | None:
    try:
        t = path.read_text().strip()
        return t or None
    except OSError:
        return None


class Today:
    """Computes the card numbers and caches the last good answer."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.cached: dict | None = None
        self.cached_at = 0.0

    def get(self) -> dict:
        with self.lock:
            now = time.time()
            if self.cached and now - self.cached_at < CACHE_SECONDS:
                return self.cached
            fresh = self.compute(self.cached)
            self.cached, self.cached_at = fresh, now
            return fresh

    def compute(self, previous: dict | None) -> dict:
        today = dt.datetime.now(IST).date()
        out: dict = {
            "date": today.isoformat(),
            "as_of": dt.datetime.now(IST).isoformat(timespec="seconds"),
            "source": [],
            "stale": False,
            "errors": [],
        }
        wh = self.from_warehouse(today)
        if wh is None:
            # dbt is probably writing. Keep the last good numbers and say so.
            if previous:
                out.update({k: previous[k] for k in previous if k not in ("as_of", "stale", "errors", "source")})
                out["source"] = previous.get("source", [])
            else:
                out.update({"orders_today": 0, "revenue_today": 0, "awaiting_pickup_pieces": 0,
                            "awaiting_pickup_shipments": 0, "warehouse_built_at": None})
            out["stale"] = True
            out["errors"].append("warehouse unavailable")
        else:
            out.update(wh)
            out["source"].append("warehouse")
        live = self.from_shopify(today)
        if live is not None:
            out["orders_today"], out["revenue_today"] = live
            out["source"].append("shopify")
        out["revenue_today_inr"] = inr(out.get("revenue_today"))
        return out

    def from_warehouse(self, today: dt.date) -> dict | None:
        db = DATA_DIR / "ig.duckdb"
        try:
            con = duckdb.connect(str(db), read_only=True)
        except Exception as e:  # locked by the nightly writer, or missing
            log(f"warehouse open failed: {e}")
            return None
        try:
            orders, revenue = con.execute(
                "select count(*), coalesce(sum(net_sales), 0) from main_marts.fct_orders "
                "where date_day = ? and not is_cancelled", [today]).fetchone()
            ships, pieces = con.execute(
                "select count(*), coalesce(sum(items), 0) from main_marts.fct_shipments "
                "where stage = 'awaiting pickup'").fetchone()
            built = con.execute("select max(tracked_at) from main_marts.fct_shipments").fetchone()[0]
            return {
                "orders_today": int(orders),
                "revenue_today": float(revenue),
                "awaiting_pickup_shipments": int(ships),
                "awaiting_pickup_pieces": int(pieces),
                "warehouse_built_at": built.astimezone(IST).isoformat(timespec="seconds") if built else None,
            }
        except Exception as e:
            log(f"warehouse query failed: {e}")
            return None
        finally:
            con.close()

    def from_shopify(self, today: dt.date) -> tuple[int, float] | None:
        token = read_token(SHOPIFY_TOKEN_FILE)
        if not token:
            return None
        start = dt.datetime.combine(today, dt.time.min, IST).isoformat(timespec="seconds")
        query = """
        query($q: String!, $after: String) {
          orders(first: 100, query: $q, after: $after) {
            pageInfo { hasNextPage endCursor }
            nodes { id cancelledAt currentSubtotalPriceSet { shopMoney { amount } } }
          }
        }"""
        count, revenue, after = 0, 0.0, None
        try:
            for _ in range(10):
                body = json.dumps({"query": query, "variables": {
                    "q": f"created_at:>='{start}'", "after": after}}).encode()
                req = urllib.request.Request(
                    f"https://{SHOPIFY_STORE}/admin/api/{SHOPIFY_API}/graphql.json", data=body,
                    headers={"Content-Type": "application/json", "X-Shopify-Access-Token": token})
                with urllib.request.urlopen(req, timeout=8) as resp:
                    data = json.load(resp)
                if "errors" in data:
                    log(f"shopify errors: {data['errors']}")
                    return None
                orders = data["data"]["orders"]
                for o in orders["nodes"]:
                    if o.get("cancelledAt"):
                        continue
                    count += 1
                    revenue += float(o["currentSubtotalPriceSet"]["shopMoney"]["amount"])
                if not orders["pageInfo"]["hasNextPage"]:
                    break
                after = orders["pageInfo"]["endCursor"]
            return count, revenue
        except Exception as e:
            log(f"shopify failed: {e}")
            return None


TODAY = Today()


class Handler(BaseHTTPRequestHandler):
    server_version = "mk-today/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter than the default, one line per request
        log(f"{self.client_address[0]} {fmt % args}")

    def send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def allowed_client(self) -> bool:
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
        except ValueError:
            return False
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        return any(ip in n for n in ALLOWED_NETS)

    def do_GET(self):
        if not self.allowed_client():
            self.send_json(403, {"error": "lan only"})
            return
        if self.path == "/health":
            self.send_json(200, {"ok": True})
            return
        token = read_token(TOKEN_FILE)
        auth = self.headers.get("Authorization", "")
        if not token or auth != f"Bearer {token}":
            self.send_json(401, {"error": "bearer token required"})
            return
        if self.path == "/today":
            self.send_json(200, TODAY.get())
            return
        self.send_json(404, {"error": "not found"})


class DualStackServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    if not read_token(TOKEN_FILE):
        log(f"no token at {TOKEN_FILE}; every /today call will be refused until install.sh creates one")
    # Bind the LAN address 0.0.0.0 on purpose: the per-client network check above is the LAN guard, and the
    # Mac's own firewall plus the home router keep the port off the internet.
    srv = DualStackServer(("0.0.0.0", PORT), Handler)
    log(f"mk-today listening on :{PORT}, data {DATA_DIR}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
