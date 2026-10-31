#!/bin/zsh
# Starts (or reattaches) the Claude session behind the TV's Claude screen: tmux session cc-claude-on-tv,
# interactive claude with Remote Control on so Amba's phone sees the same session. Run from any shell on
# the mini; the TV bridge finds the session by this tmux name.
set -eu
DIR="${0:A:h}"
NAME="cc-claude-on-tv"
TMUX=/opt/homebrew/bin/tmux
if "$TMUX" has-session -t "$NAME" 2>/dev/null; then
  echo "$NAME is already running; attach with: $TMUX attach -t $NAME"
  exit 0
fi
"$TMUX" new-session -d -s "$NAME" -c "$DIR" "command claude --remote-control moonkiln-tv"
echo "$NAME started in $DIR with Remote Control on. Attach with: $TMUX attach -t $NAME"
