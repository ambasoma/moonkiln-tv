#!/usr/bin/env python3
"""Claude Code hook that feeds the TV bridge. Stdlib only, exits fast.

Registered in .claude/settings.json for UserPromptSubmit, PreToolUse, PostToolUse, Stop, SessionStart
and SessionEnd. Reads the hook JSON from stdin, turns it into one bridge event, posts it to the mini's
bridge (loopback, bearer token from the same file the launcher's token comes from) and exits. The TV
reveals Claude's text word by word itself, so there is no streaming here: the Stop hook carries the
whole answer.
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

BRIDGE = os.environ.get("MK_BRIDGE", "http://127.0.0.1:9470")
TOKEN_FILE = Path.home() / "Library" / "Application Support" / "mk-airbyte" / "secrets" / "mk-tv-today-token"
MAX_TEXT = 2000


def token() -> str:
    try:
        return TOKEN_FILE.read_text().strip()
    except OSError:
        return ""


def post(event: dict) -> None:
    body = json.dumps(event).encode()
    req = urllib.request.Request(f"{BRIDGE}/bridge/event", data=body, headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {token()}"})
    try:
        urllib.request.urlopen(req, timeout=2).read()
    except Exception:
        pass  # the TV is a window on the session, never a reason to slow it down


def short(s: str, n: int = 56) -> str:
    s = " ".join(str(s).split())
    return s if len(s) <= n else s[: n - 1] + "…"


# Claude's own housekeeping (loading tools, keeping a to-do list) is not something to show on the TV.
QUIET_TOOLS = {"ToolSearch", "TodoWrite", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet", "TaskOutput", "TaskStop", "ExitPlanMode", "EnterPlanMode"}


def marker(tool: str, inp: dict) -> str | None:
    """One grey line on the TV for a tool call, in the board's voice: 'tv · switched to HDMI 1'. None hides it."""
    inp = inp or {}
    if tool in QUIET_TOOLS:
        return None
    if tool == "Skill":
        return "skill · " + short(str(inp.get("skill", "")).split(":")[-1].replace("-", " "), 40)
    if tool == "Bash":
        cmd = str(inp.get("command", ""))
        desc = str(inp.get("description", "")).strip()
        if "/announce" in cmd and "POST" in cmd.upper():
            return "announce · on the TV"
        if "adb" in cmd:
            if "passthrough" in cmd and "HW5" in cmd:
                return "tv · switched to HDMI 1"
            if "passthrough" in cmd:
                return "tv · switched input"
            if "screencap" in cmd:
                return "tv · screenshot"
            if "input keyevent 3" in cmd:
                return "tv · went home"
            if "input keyevent" in cmd:
                return "tv · pressed a key"
            if "dumpsys" in cmd:
                return "tv · read the set's state"
            return "tv · " + short(desc.lower() if desc else cmd, 48)
        if "9470" in cmd and "/today" in cmd:
            return "mini · today's numbers"
        if "9470" in cmd and "/dashboard" in cmd:
            return "mini · the last weeks"
        return "mini · " + short(desc.lower() if desc else cmd, 48)
    if tool in ("Read", "Grep", "Glob"):
        target = inp.get("file_path") or inp.get("pattern") or inp.get("path") or ""
        return "read · " + short(os.path.basename(str(target)) or tool.lower(), 40)
    if tool in ("Edit", "Write", "MultiEdit"):
        return "edit · " + short(os.path.basename(str(inp.get("file_path", ""))), 40)
    if tool in ("WebFetch", "WebSearch"):
        return "web · " + short(inp.get("url") or inp.get("query") or "", 44)
    if tool == "Agent":
        return "agent · " + short(inp.get("description") or "", 44)
    if tool.startswith("mcp__"):
        parts = tool.split("__")
        name = parts[-1].replace("_", " ")
        server = parts[1] if len(parts) > 2 else "mcp"
        if re.fullmatch(r"[0-9a-f-]{20,}", server):
            server = "tool"  # a connector id, not a name
        for key in ("linear", "shopify", "higgsfield", "gmail", "calendar"):
            if key in tool.lower():
                server = key
        return f"{server} · {short(name, 40)}"
    # Never raw input on the TV: an unknown tool shows by name only.
    return re.sub(r"(?<!^)(?=[A-Z])", " ", tool).lower()


def last_text(payload: dict) -> str:
    raw = payload.get("last_assistant_message") or ""
    if isinstance(raw, dict):
        raw = "\n".join(b.get("text", "") for b in raw.get("content", []) if isinstance(b, dict) and b.get("type") == "text")
    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    # Fall back to the transcript on disk.
    text = ""
    try:
        with open(payload.get("transcript_path", "")) as f:
            for line in f:
                try:
                    entry = json.loads(line)
                except Exception:
                    continue
                if entry.get("type") != "assistant":
                    continue
                content = (entry.get("message") or {}).get("content") or []
                parts = [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
                if any(p.strip() for p in parts):
                    text = "\n".join(p for p in parts if p.strip())
    except Exception:
        pass
    return text.strip()


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        payload = json.load(sys.stdin)
    except Exception:
        payload = {}
    sid = payload.get("session_id", "")
    if mode == "prompt":
        text = str(payload.get("prompt", "")).strip()
        if text:
            post({"type": "prompt", "text": text[:MAX_TEXT], "sid": sid})
    elif mode in ("pre", "post"):
        text = marker(payload.get("tool_name", ""), payload.get("tool_input") or {})
        if text:
            kind = "tool_start" if mode == "pre" else "tool_done"
            post({"type": kind, "tool_id": payload.get("tool_use_id") or "", "text": text, "sid": sid})
    elif mode == "stop":
        if payload.get("stop_hook_active"):
            return
        text = last_text(payload)
        post({"type": "text", "text": text[:MAX_TEXT], "sid": sid})
    elif mode == "start":
        post({"type": "status", "status": "live", "sid": sid})
    elif mode == "end":
        post({"type": "status", "status": "offline", "sid": sid})


if __name__ == "__main__":
    main()
