#!/bin/zsh
# Installs the Moon Kiln today endpoint on the Mac mini: copies the server out of ~/Documents (launchd
# children cannot run from the iCloud-synced Documents folder), creates the bearer token if missing,
# and (re)loads the launchd agent. Re-run after editing today_server.py.
set -euo pipefail
HERE="${0:A:h}"
APP="$HOME/Library/Application Support/mk-tv-today"
SECRETS="$HOME/Library/Application Support/mk-airbyte/secrets"
PLIST="$HOME/Library/LaunchAgents/com.moonkiln.tv-today.plist"
VENV="$HOME/Library/Application Support/mk-ig-metrics/.venv/bin/python"

[ -x "$VENV" ] || { echo "warehouse venv missing at $VENV (run scripts/ig-metrics/install.sh first)"; exit 1; }
mkdir -p "$APP" "$SECRETS"
cp "$HERE/today_server.py" "$APP/today_server.py"
cp "$HERE/say.py" "$APP/say.py"
mkdir -p "$APP/announce"
cp "$HERE/announce/index.html" "$APP/announce/index.html"

if [ ! -s "$SECRETS/mk-tv-today-token" ]; then
  umask 077
  LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40 > "$SECRETS/mk-tv-today-token"
  echo "created a new bearer token at $SECRETS/mk-tv-today-token (copy it into the launcher's local.properties as mk.todayToken)"
fi
chmod 600 "$SECRETS/mk-tv-today-token"

cp "$HERE/com.moonkiln.tv-today.plist" "$PLIST"
launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
sleep 1
curl -sf "http://127.0.0.1:${MK_TODAY_PORT:-9470}/health" && echo " mk-today is up (log: ~/Library/Logs/mk-tv-today.log)"
