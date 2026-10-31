#!/usr/bin/env python3
"""Moon Kiln today: a small read-only JSON endpoint on the Mac mini for the TV launcher.

Serves, on the LAN only and behind a bearer token:
  GET /today                 the home card: orders and revenue today, pieces awaiting Delhivery pickup,
                             what is left to fulfil (shop vs custom), the latest sale with its photo
  GET /dashboard?days=7|30|90  the What's going on page: seven tiles with a comparison to the previous
                             period, daily (weekly for 90) series for the three charts, the top four posts
  GET /thumbs/<media id>.jpg a post thumbnail from the warehouse thumbs folder
  GET /health                liveness, no token needed

The Claude bridge (v2, Claude on the TV), same token:
  POST /bridge/event         the Claude session's hooks post prompts, tool markers, answers and status here
  GET  /bridge/events?since=N&wait=25   the TV long-polls for events after sequence N
  POST /bridge/prompt        a prompt typed on the TV, typed into the tmux session cc-claude-on-tv
  GET  /bridge/status        live / working / listening / offline, and whether the tmux session exists

Sources:
  1. The warehouse DuckDB files (ig.duckdb and raw_youtube.duckdb), opened read-only for the few
     milliseconds a query takes, then closed, so the nightly dbt writer is never blocked. DuckDB allows
     one writer per file; while dbt holds a file our read fails and the last good answer is served with
     "stale": true and the time it was computed.
  2. The Shopify Admin API for what the warehouse cannot know until its nightly rebuild: today's orders
     and revenue, the open orders still to fulfil, and the latest sale. Used only when the token file
     exists and the call succeeds; the warehouse is the fallback.

Runs under launchd (com.moonkiln.tv-today) from the warehouse venv. No third party web framework: the
standard library http.server is enough for one client on the sofa.
"""
from __future__ import annotations

import datetime as dt
import ipaddress
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

import duckdb

IST = ZoneInfo("Asia/Kolkata")
APP_SUPPORT = Path.home() / "Library" / "Application Support"
DATA_DIR = Path(os.environ.get("MK_TODAY_DATA_DIR", APP_SUPPORT / "mk-ig-metrics"))
SECRETS = Path(os.environ.get("MK_TODAY_SECRETS", APP_SUPPORT / "mk-airbyte" / "secrets"))
THUMBS_DIR = DATA_DIR / "thumbs"
TOKEN_FILE = SECRETS / "mk-tv-today-token"
SHOPIFY_TOKEN_FILE = SECRETS / "mk-shopify-admin-token"
SHOPIFY_STORE = "cbbkep-a2.myshopify.com"
SHOPIFY_API = "2026-07"
PORT = int(os.environ.get("MK_TODAY_PORT", "9470"))
CACHE_SECONDS = int(os.environ.get("MK_TODAY_CACHE", "60"))
DASHBOARD_CACHE_SECONDS = int(os.environ.get("MK_DASHBOARD_CACHE", "600"))

# LAN only: home network, Tailscale range, loopback. Anything else gets 403 before the token check.
ALLOWED_NETS = [ipaddress.ip_network(n) for n in (
    "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "100.64.0.0/10", "127.0.0.0/8", "::1/128", "fd7a:115c:a1e0::/48",
)]

# Channel groups on the sessions chart, bottom to top. Paid Search, Owned and Shopping Organic fold into Other.
CHANNEL_GROUPS = ["Paid Social", "Direct", "Social", "Search Organic", "PLA", "Referral", "Other"]
MEDIA_ID = re.compile(r"^[0-9]{5,30}$")


def log(msg: str) -> None:
    print(f"{dt.datetime.now(IST).strftime('%Y-%m-%d %H:%M:%S')} {msg}", flush=True)


def inr(amount: float | int | None) -> str:
    """Rs. 1,23,456 with Indian grouping, no paise on the TV."""
    return "Rs. " + group_indian(amount)


def group_indian(amount: float | int | None) -> str:
    if amount is None:
        return "0"
    n = int(round(float(amount)))
    sign = "-" if n < 0 else ""
    s = str(abs(n))
    if len(s) <= 3:
        return sign + s
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return sign + ",".join(parts) + "," + tail


def read_token(path: Path) -> str | None:
    try:
        t = path.read_text().strip()
        return t or None
    except OSError:
        return None


def now_ist() -> dt.datetime:
    return dt.datetime.now(IST)


def warehouse(path: Path = DATA_DIR / "ig.duckdb"):
    """A read-only connection, or None while the nightly writer holds the file."""
    try:
        return duckdb.connect(str(path), read_only=True)
    except Exception as e:
        log(f"warehouse open failed ({path.name}): {e}")
        return None


def shopify_graphql(query: str, variables: dict | None = None) -> dict | None:
    token = read_token(SHOPIFY_TOKEN_FILE)
    if not token:
        return None
    body = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(
        f"https://{SHOPIFY_STORE}/admin/api/{SHOPIFY_API}/graphql.json", data=body,
        headers={"Content-Type": "application/json", "X-Shopify-Access-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.load(resp)
    except Exception as e:
        log(f"shopify failed: {e}")
        return None
    if "errors" in data:
        log(f"shopify errors: {data['errors']}")
        return None
    return data["data"]


def is_custom(order: dict) -> bool:
    """A custom pet commission: tagged mk-custom, or any line whose product type starts with Custom."""
    if "mk-custom" in (order.get("tags") or []):
        return True
    for li in order.get("lineItems", {}).get("nodes", []):
        ptype = ((li.get("product") or {}).get("productType") or "")
        if ptype.lower().startswith("custom"):
            return True
    return False


class Cached:
    """Serves the last good answer while a recompute fails, and says how old it is."""

    def __init__(self, ttl: int) -> None:
        self.ttl = ttl
        self.lock = threading.Lock()
        self.value: dict | None = None
        self.computed_at = 0.0

    def get(self, compute) -> dict:
        with self.lock:
            now = time.time()
            if self.value and now - self.computed_at < self.ttl:
                return self.value
            try:
                fresh = compute(self.value)
            except Exception as e:
                log(f"compute failed: {e}")
                fresh = None
            if fresh is not None:
                self.value, self.computed_at = fresh, now
                return fresh
            if self.value:
                stale = dict(self.value)
                stale["stale"] = True
                return stale
            return {"stale": True, "errors": ["nothing computed yet"]}


# ------------------------------------------------------------------------------------------------
# /today
# ------------------------------------------------------------------------------------------------

def today_from_warehouse(today: dt.date) -> dict | None:
    con = warehouse()
    if con is None:
        return None
    try:
        orders, revenue = con.execute(
            "select count(*), coalesce(sum(net_sales), 0) from main_marts.fct_orders "
            "where date_day = ? and not is_cancelled", [today]).fetchone()
        ships, pieces = con.execute(
            "select count(*), coalesce(sum(items), 0) from main_marts.fct_shipments "
            "where stage = 'awaiting pickup'").fetchone()
        shop, custom = con.execute(
            "select count(*) filter (where product_types not ilike '%custom%'), "
            "count(*) filter (where product_types ilike '%custom%') from main_marts.fct_orders "
            "where not is_cancelled and fulfillment_status in ('unfulfilled', 'partial')").fetchone()
        built = con.execute("select max(tracked_at) from main_marts.fct_shipments").fetchone()[0]
        latest = con.execute(
            "select order_id, order_name, net_sales, ship_city, created_at, product_types from main_marts.fct_orders "
            "where not is_cancelled order by created_at desc limit 1").fetchone()
        out = {
            "orders_today": int(orders),
            "revenue_today": float(revenue),
            "awaiting_pickup_shipments": int(ships),
            "awaiting_pickup_pieces": int(pieces),
            "to_fulfil_shop": int(shop),
            "to_fulfil_custom": int(custom),
            "warehouse_built_at": built.astimezone(IST).isoformat(timespec="seconds") if built else None,
            "latest_sale": None,
        }
        if latest:
            created = latest[4].astimezone(IST) if latest[4] else None
            out["latest_sale"] = {
                "order_id": str(latest[0]), "order_name": latest[1], "title": (latest[5] or "an order").split(",")[0].strip(),
                "amount": float(latest[2] or 0), "amount_inr": inr(latest[2]), "city": latest[3],
                "created_at": created.isoformat(timespec="seconds") if created else None, "image_url": None,
            }
        return out
    except Exception as e:
        log(f"warehouse today query failed: {e}")
        return None
    finally:
        con.close()


def today_from_shopify(today: dt.date) -> dict | None:
    """Today's orders and revenue, the open orders to fulfil, and the latest sale, straight from the shop."""
    start = dt.datetime.combine(today, dt.time.min, IST).isoformat(timespec="seconds")
    query = """
    query($q: String!) {
      today: orders(first: 100, query: $q) {
        nodes { id cancelledAt currentSubtotalPriceSet { shopMoney { amount } } }
      }
      open: orders(first: 100, query: "fulfillment_status:unfulfilled status:open") {
        nodes { id cancelledAt tags lineItems(first: 10) { nodes { product { productType } } } }
      }
      latest: orders(first: 3, sortKey: CREATED_AT, reverse: true) {
        nodes {
          id name createdAt cancelledAt
          currentSubtotalPriceSet { shopMoney { amount } }
          shippingAddress { city }
          lineItems(first: 1) { nodes { title image { url(transform: {maxWidth: 160, maxHeight: 160, preferredContentType: JPG}) } } }
        }
      }
    }"""
    data = shopify_graphql(query, {"q": f"created_at:>='{start}'"})
    if data is None:
        return None
    count, revenue = 0, 0.0
    for o in data["today"]["nodes"]:
        if o.get("cancelledAt"):
            continue
        count += 1
        revenue += float(o["currentSubtotalPriceSet"]["shopMoney"]["amount"])
    shop = custom = 0
    for o in data["open"]["nodes"]:
        if o.get("cancelledAt"):
            continue
        if is_custom(o):
            custom += 1
        else:
            shop += 1
    latest = None
    for o in data["latest"]["nodes"]:
        if o.get("cancelledAt"):
            continue
        line = (o["lineItems"]["nodes"] or [{}])[0]
        amount = float(o["currentSubtotalPriceSet"]["shopMoney"]["amount"])
        created = dt.datetime.fromisoformat(o["createdAt"].replace("Z", "+00:00")).astimezone(IST)
        latest = {
            "order_id": o["id"].rsplit("/", 1)[-1], "order_name": o["name"], "title": line.get("title") or "an order",
            "amount": amount, "amount_inr": inr(amount), "city": (o.get("shippingAddress") or {}).get("city"),
            "created_at": created.isoformat(timespec="seconds"),
            "image_url": (line.get("image") or {}).get("url"),
        }
        break
    return {"orders_today": count, "revenue_today": revenue, "to_fulfil_shop": shop, "to_fulfil_custom": custom,
            "latest_sale": latest}


def compute_today(previous: dict | None) -> dict | None:
    today = now_ist().date()
    out: dict = {
        "date": today.isoformat(),
        "as_of": now_ist().isoformat(timespec="seconds"),
        "source": [],
        "stale": False,
        "errors": [],
    }
    wh = today_from_warehouse(today)
    live = today_from_shopify(today)
    if wh is None and live is None:
        return None
    if wh is not None:
        out.update(wh)
        out["source"].append("warehouse")
    else:
        out["errors"].append("warehouse unavailable")
        if previous:
            for k in ("awaiting_pickup_shipments", "awaiting_pickup_pieces", "warehouse_built_at"):
                out[k] = previous.get(k)
        else:
            out.update({"awaiting_pickup_shipments": 0, "awaiting_pickup_pieces": 0, "warehouse_built_at": None})
    if live is not None:
        out.update(live)
        out["source"].append("shopify")
    else:
        out["errors"].append("shopify unavailable")
    out["revenue_today_inr"] = inr(out.get("revenue_today"))
    return out


# ------------------------------------------------------------------------------------------------
# /dashboard
# ------------------------------------------------------------------------------------------------

def pct_delta(cur: float, prev: float) -> dict:
    if prev > 0:
        p = round((cur - prev) / prev * 100)
        return {"delta": f"{'+' if p > 0 else ''}{p}%", "dir": "up" if p > 0 else "down" if p < 0 else "flat"}
    if cur > 0:
        return {"delta": "new", "dir": "up"}
    return {"delta": "0%", "dir": "flat"}


def abs_delta(cur: float, prev: float) -> dict:
    d = int(round(cur - prev))
    return {"delta": f"{'+' if d > 0 else ''}{group_indian(d)}", "dir": "up" if d > 0 else "down" if d < 0 else "flat"}


def weekly(rows: list[dict], keys: list[str]) -> list[dict]:
    """Sum seven-day chunks counted back from the newest day; the chunk keeps its first date."""
    out: list[dict] = []
    z = len(rows)
    while z > 0:
        chunk = rows[max(0, z - 7):z]
        agg = {"d": chunk[0]["d"]}
        for k in keys:
            agg[k] = sum((r.get(k) or 0) for r in chunk)
        out.insert(0, agg)
        z -= 7
    return out


def compute_dashboard(days: int):
    def compute(previous: dict | None) -> dict | None:
        con = warehouse()
        if con is None:
            return None
        try:
            today = now_ist().date()
            end = today - dt.timedelta(days=1)          # yesterday is the last full day
            start = end - dt.timedelta(days=days - 1)
            prev_start = start - dt.timedelta(days=days)
            prev_end = start - dt.timedelta(days=1)

            # Business daily, both periods
            biz = {r[0]: r for r in con.execute(
                "select date_day, coalesce(sessions,0), coalesce(ga4_adds_to_cart,0), coalesce(paid_orders,0), "
                "coalesce(net_revenue,0) from main_marts.fct_business_daily "
                "where date_day between ? and ? order by 1", [prev_start, end]).fetchall()}

            # Sessions by channel group
            ch_rows = con.execute(
                "select date_day, channel, coalesce(sum(sessions),0) from main_marts.fct_channel_daily "
                "where date_day between ? and ? group by 1,2", [start, end]).fetchall()
            by_day: dict[dt.date, list[float]] = {}
            for d, ch, s in ch_rows:
                g = ch if ch in CHANNEL_GROUPS else "Other"
                by_day.setdefault(d, [0.0] * len(CHANNEL_GROUPS))[CHANNEL_GROUPS.index(g)] += float(s)

            # Social: reach and engagement per day (all Social subchannels), YouTube added in below
            soc = {r[0]: [float(r[1] or 0), float(r[2] or 0)] for r in con.execute(
                "select date_day, sum(impressions), sum(engagements) from main_marts.fct_channel_daily "
                "where channel = 'Social' and date_day between ? and ? group by 1", [prev_start - dt.timedelta(days=6), end]).fetchall()}
            ig = {r[0]: int(r[1]) for r in con.execute(
                "select snapshot_date, followers_count from main_marts.fct_followers where followers_count is not null").fetchall()}
            fb = {r[0]: int(r[1]) for r in con.execute(
                "select snapshot_date, fan_count from main_intermediate.int_facebook_pages__page_totals_daily "
                "where fan_count is not null").fetchall()}
            pin = {r[0]: int(r[1]) for r in con.execute(
                "select date_day, followers_count from main_marts.fct_pinterest_account_daily "
                "where followers_count is not null").fetchall()}

            # Search: web only, plus orders from search as the last touch model gives them
            search = {r[0]: [int(r[1] or 0), int(r[2] or 0)] for r in con.execute(
                "select date_day, sum(impressions), sum(clicks) from main_marts.fct_search_daily "
                "where search_type = 'web' and date_day between ? and ? group by 1", [prev_start, end]).fetchall()}
            s_orders = {r[0]: float(r[1] or 0) for r in con.execute(
                "select date_day, sum(orders_last_touch) from main_marts.fct_channel_daily "
                "where channel = 'Search Organic' and date_day between ? and ? group by 1", [prev_start, end]).fetchall()}

            posts = con.execute(
                "select media_id, published_date, is_reel, caption_short, coalesce(views,0), coalesce(reach,0), "
                "coalesce(likes,0), thumb_path from main_marts.fct_media_latest "
                "where published_date >= ? order by coalesce(views,0) + coalesce(reach,0) desc limit 4",
                [today - dt.timedelta(days=90)]).fetchall()
            built = con.execute("select max(date_day) from main_marts.fct_business_daily where sessions is not null").fetchone()[0]
        except Exception as e:
            log(f"dashboard query failed: {e}")
            return None
        finally:
            con.close()

        # YouTube: one subscriber snapshot so far, views and likes per day
        yt_subs = 0
        yt_daily: dict[dt.date, list[float]] = {}
        ycon = warehouse(DATA_DIR / "raw_youtube.duckdb")
        if ycon is not None:
            try:
                row = ycon.execute("select subscriber_count from channel_snapshot order by snapshot_date desc limit 1").fetchone()
                yt_subs = int(row[0]) if row and row[0] is not None else 0
                for d, v, l, c, s in ycon.execute(
                        "select date, coalesce(views,0), coalesce(likes,0), coalesce(comments,0), coalesce(shares,0) "
                        "from channel_daily where date between ? and ?", [prev_start - dt.timedelta(days=6), end]).fetchall():
                    dd = d if isinstance(d, dt.date) else dt.date.fromisoformat(str(d)[:10])
                    yt_daily[dd] = [float(v), float(l + c + s)]
            except Exception as e:
                log(f"youtube query failed: {e}")
            finally:
                ycon.close()

        def carried(series: dict[dt.date, int]):
            """Value on a day: the latest snapshot on or before it, else the first one ever (carried back)."""
            if not series:
                return lambda d: 0
            keys = sorted(series)
            first = series[keys[0]]

            def at(d: dt.date) -> int:
                best = None
                for k in keys:
                    if k <= d:
                        best = k
                    else:
                        break
                return series[best] if best is not None else first
            return at

        fb_at, pin_at, ig_at = carried(fb), carried(pin), carried(ig)

        def followers_on(d: dt.date) -> int | None:
            # Instagram is the anchor: before its first snapshot there is no honest total.
            if not ig or d < min(ig):
                return None
            return ig_at(d) + fb_at(d) + pin_at(d) + yt_subs

        def day_series(a: dt.date, b: dt.date):
            d = a
            while d <= b:
                yield d
                d += dt.timedelta(days=1)

        def reach_eng(d: dt.date) -> tuple[float, float]:
            r, e = soc.get(d, [0.0, 0.0])
            yv, ye = yt_daily.get(d, [0.0, 0.0])
            return r + yv, e + ye

        def roll(d: dt.date, idx: int) -> float:
            vals = [reach_eng(d - dt.timedelta(days=k))[idx] for k in range(7)]
            return sum(vals) / 7.0

        rows = []
        for d in day_series(start, end):
            b = biz.get(d)
            ch = by_day.get(d, [0.0] * len(CHANNEL_GROUPS))
            r, e = reach_eng(d)
            sr = search.get(d, [0, 0])
            rows.append({
                "d": d.isoformat(),
                "sessions": int(b[1]) if b else 0, "atc": int(b[2]) if b else 0,
                "orders": int(b[3]) if b else 0, "revenue": float(b[4]) if b else 0.0,
                "channels": [round(v) for v in ch],
                "followers": followers_on(d), "reach": r, "eng": e,
                "reach7": roll(d, 0), "eng7": roll(d, 1),
                "impressions": sr[0], "clicks": sr[1], "search_orders": s_orders.get(d, 0.0),
            })

        def sums(a: dt.date, b: dt.date):
            s = {"sessions": 0, "atc": 0, "orders": 0, "revenue": 0.0, "reach": 0.0, "eng": 0.0}
            for d in day_series(a, b):
                bz = biz.get(d)
                if bz:
                    s["sessions"] += int(bz[1])
                    s["atc"] += int(bz[2])
                    s["orders"] += int(bz[3])
                    s["revenue"] += float(bz[4])
                r, e = reach_eng(d)
                s["reach"] += r
                s["eng"] += e
            return s

        cur, prev = sums(start, end), sums(prev_start, prev_end)
        f_now = followers_on(end)
        f_then = followers_on(start)
        f_known = [r["followers"] for r in rows if r["followers"] is not None]
        if f_now is None and f_known:
            f_now = f_known[-1]
        if f_then is None and f_known:
            f_then = f_known[0]

        kpis = [
            {"key": "sessions", "label": "Sessions", "value": cur["sessions"], "display": group_indian(cur["sessions"]), **pct_delta(cur["sessions"], prev["sessions"])},
            {"key": "atc", "label": "Add to carts", "value": cur["atc"], "display": group_indian(cur["atc"]), **pct_delta(cur["atc"], prev["atc"])},
            {"key": "orders", "label": "Orders", "value": cur["orders"], "display": group_indian(cur["orders"]), **pct_delta(cur["orders"], prev["orders"])},
            {"key": "revenue", "label": "Revenue", "value": cur["revenue"], "display": "₹" + group_indian(cur["revenue"]), **pct_delta(cur["revenue"], prev["revenue"])},
            {"key": "followers", "label": "Followers", "value": f_now or 0, "display": group_indian(f_now or 0), **abs_delta(f_now or 0, f_then or 0)},
            {"key": "reach", "label": "Reach", "value": cur["reach"], "display": group_indian(cur["reach"]), **pct_delta(cur["reach"], prev["reach"])},
            {"key": "eng", "label": "Engagement", "value": cur["eng"], "display": group_indian(cur["eng"]), **pct_delta(cur["eng"], prev["eng"])},
        ]

        is_weekly = days == 90
        ch_series = [{"d": r["d"], "v": r["channels"]} for r in rows]
        if is_weekly:
            keys = [f"c{i}" for i in range(len(CHANNEL_GROUPS))]
            flat = [{"d": r["d"], **{k: r["channels"][i] for i, k in enumerate(keys)}} for r in rows]
            ch_series = [{"d": w["d"], "v": [int(w[k]) for k in keys]} for w in weekly(flat, keys)]
        s_series = [{"d": r["d"], "impressions": r["impressions"], "clicks": r["clicks"], "orders": round(r["search_orders"], 2)} for r in rows]
        if is_weekly:
            s_series = weekly(s_series, ["impressions", "clicks", "orders"])
        last = rows[-1] if rows else {}
        return {
            "days": days,
            "weekly": is_weekly,
            "as_of": now_ist().isoformat(timespec="seconds"),
            "from": start.isoformat(), "to": end.isoformat(),
            "built_through": built.isoformat() if built else None,
            "stale": False,
            "kpis": kpis,
            "channels": {"series": CHANNEL_GROUPS, "rows": ch_series, "total": int(sum(sum(r["v"]) for r in ch_series))},
            "social": {
                "rows": [{"d": r["d"], "followers": r["followers"], "reach7": round(r["reach7"], 1), "eng7": round(r["eng7"], 1)} for r in rows],
                "latest": {"followers": f_now, "reach7": round(last.get("reach7", 0.0), 1), "eng7": round(last.get("eng7", 0.0), 1)},
            },
            "search": {
                "rows": s_series,
                "totals": {"impressions": sum(r["impressions"] for r in rows), "clicks": sum(r["clicks"] for r in rows),
                           "orders": round(sum(r["search_orders"] for r in rows))},
            },
            "posts": [{
                "id": p[0], "kind": "Reel" if p[2] else "Post",
                "date": p[1].strftime("%-d %b") if p[1] else "",
                "caption": (p[3] or "").strip(),
                "views": int(p[4]), "reach": int(p[5]), "likes": int(p[6]),
                "thumb": f"/thumbs/{p[0]}.jpg" if p[7] and Path(p[7]).exists() else None,
            } for p in posts],
        }
    return compute


TODAY = Cached(CACHE_SECONDS)
DASHBOARDS = {d: Cached(DASHBOARD_CACHE_SECONDS) for d in (7, 30, 90)}


# ------------------------------------------------------------------------------------------------
# The Claude bridge: a ring of events the TV long-polls, and a way to type into the tmux session
# ------------------------------------------------------------------------------------------------

TMUX = os.environ.get("MK_TMUX", "/opt/homebrew/bin/tmux")
CLAUDE_TMUX_SESSION = os.environ.get("MK_CLAUDE_TMUX", "cc-claude-on-tv")
BRIDGE_RING = 300


class Bridge:
    """Events from the Claude session, in order, with a sequence number the TV resumes from."""

    def __init__(self) -> None:
        self.cond = threading.Condition()
        self.events: list[dict] = []
        self.seq = 0
        self.status = "offline"
        self.status_at = 0.0

    def post(self, ev: dict) -> dict:
        with self.cond:
            self.seq += 1
            ev = dict(ev)
            ev["seq"] = self.seq
            ev["ts"] = time.time()
            t = ev.get("type")
            if t == "prompt":
                self.status = "working"
            elif t == "text":
                self.status = "live"
            elif t == "status" and ev.get("status") in ("live", "working", "listening", "offline"):
                self.status = ev["status"]
            self.status_at = ev["ts"]
            self.events.append(ev)
            if len(self.events) > BRIDGE_RING:
                del self.events[: len(self.events) - BRIDGE_RING]
            self.cond.notify_all()
            return ev

    def after(self, since: int, wait: float) -> list[dict]:
        deadline = time.time() + wait
        with self.cond:
            while self.seq <= since:
                remaining = deadline - time.time()
                if remaining <= 0:
                    return []
                self.cond.wait(remaining)
            return [e for e in self.events if e["seq"] > since][-100:]

    def session_alive(self) -> bool:
        if not shutil.which(TMUX) and not os.path.exists(TMUX):
            return False
        try:
            return subprocess.run([TMUX, "has-session", "-t", CLAUDE_TMUX_SESSION], capture_output=True, timeout=3).returncode == 0
        except Exception:
            return False

    def status_now(self) -> dict:
        alive = self.session_alive()
        status = self.status if alive else "offline"
        return {"status": status, "session": CLAUDE_TMUX_SESSION, "alive": alive, "seq": self.seq, "since": self.status_at}

    def send_prompt(self, text: str) -> bool:
        """Types the prompt into the tmux session and presses Enter, as a person at the keyboard would."""
        text = " ".join(text.split())
        if not text or not self.session_alive():
            return False
        try:
            target = CLAUDE_TMUX_SESSION  # tmux 3.7 on the mini does not take the =name exact form
            subprocess.run([TMUX, "send-keys", "-t", target, "-l", text], check=True, timeout=3)
            time.sleep(0.15)
            subprocess.run([TMUX, "send-keys", "-t", target, "Enter"], check=True, timeout=3)
            return True
        except Exception as e:
            log(f"send_prompt failed: {e}")
            return False


BRIDGE = Bridge()


class Handler(BaseHTTPRequestHandler):
    server_version = "mk-today/2"
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

    def send_file(self, path: Path, content_type: str) -> None:
        try:
            data = path.read_bytes()
        except OSError:
            self.send_json(404, {"error": "not found"})
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def allowed_client(self) -> bool:
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
        except ValueError:
            return False
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        return any(ip in n for n in ALLOWED_NETS)

    def authorised(self) -> bool:
        token = read_token(TOKEN_FILE)
        auth = self.headers.get("Authorization", "")
        return bool(token) and auth == f"Bearer {token}"

    def read_body(self) -> dict:
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > 65536:
                return {}
            return json.loads(self.rfile.read(n).decode() or "{}")
        except Exception:
            return {}

    def do_POST(self):
        if not self.allowed_client():
            self.send_json(403, {"error": "lan only"})
            return
        if not self.authorised():
            self.send_json(401, {"error": "bearer token required"})
            return
        url = urlparse(self.path)
        body = self.read_body()
        if url.path == "/bridge/event":
            t = body.get("type")
            if t not in ("prompt", "text", "tool_start", "tool_done", "status", "announce"):
                self.send_json(400, {"error": "unknown event type"})
                return
            ev = BRIDGE.post(body)
            self.send_json(200, {"seq": ev["seq"]})
            return
        if url.path == "/bridge/prompt":
            text = str(body.get("text", ""))[:2000]
            ok = BRIDGE.send_prompt(text)
            self.send_json(200 if ok else 503, {"sent": ok, **BRIDGE.status_now()})
            return
        self.send_json(404, {"error": "not found"})

    def do_GET(self):
        if not self.allowed_client():
            self.send_json(403, {"error": "lan only"})
            return
        url = urlparse(self.path)
        if url.path == "/health":
            self.send_json(200, {"ok": True})
            return
        if not self.authorised():
            self.send_json(401, {"error": "bearer token required"})
            return
        if url.path == "/bridge/status":
            self.send_json(200, BRIDGE.status_now())
            return
        if url.path == "/bridge/events":
            q = parse_qs(url.query)
            try:
                since = int(q.get("since", ["0"])[0])
                wait = min(30.0, max(0.0, float(q.get("wait", ["0"])[0])))
            except ValueError:
                since, wait = 0, 0.0
            events = BRIDGE.after(since, wait)
            self.send_json(200, {"events": events, **BRIDGE.status_now()})
            return
        if url.path == "/today":
            self.send_json(200, TODAY.get(compute_today))
            return
        if url.path == "/dashboard":
            try:
                days = int(parse_qs(url.query).get("days", ["30"])[0])
            except ValueError:
                days = 30
            if days not in DASHBOARDS:
                self.send_json(400, {"error": "days must be 7, 30 or 90"})
                return
            self.send_json(200, DASHBOARDS[days].get(compute_dashboard(days)))
            return
        if url.path.startswith("/thumbs/") and url.path.endswith(".jpg"):
            media_id = url.path[len("/thumbs/"):-4]
            if not MEDIA_ID.match(media_id):
                self.send_json(404, {"error": "not found"})
                return
            self.send_file(THUMBS_DIR / f"{media_id}.jpg", "image/jpeg")
            return
        self.send_json(404, {"error": "not found"})


class DualStackServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    if not read_token(TOKEN_FILE):
        log(f"no token at {TOKEN_FILE}; every call will be refused until install.sh creates one")
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
