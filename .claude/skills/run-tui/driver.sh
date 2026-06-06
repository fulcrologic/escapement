#!/usr/bin/env bash
# Launch + observe the Escapement TUI. See SKILL.md for full docs.
#
#   driver.sh headless [PROMPT]   # tmux PTY, prints the rendered screen as text
#   driver.sh headful  [PROMPT]   # real alacritty window, screenshots to PNG (Wayland/Hyprland)
#
# Both need `ollama serve` up with gemma3:1b pulled. OLLAMA_NUM_PARALLEL=4 makes
# poets/judges stream concurrently instead of one-at-a-time.
set -euo pipefail

MODE="${1:-headless}"
PROMPT="${2:-Run a tournament with 6 poets and 5 judges. Theme: newly wed}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
CMD="OLLAMA_NUM_PARALLEL=4 bb haiku '${PROMPT//\'/\'\\\'\'}'"
cd "$REPO"

case "$MODE" in
  headless)
    SESS=haiku
    tmux kill-session -t "$SESS" 2>/dev/null || true
    tmux new-session -d -s "$SESS" -x 200 -y 50
    tmux set-option -t "$SESS" remain-on-exit on
    tmux send-keys -t "$SESS" "$CMD" Enter
    echo "[driver] launched in tmux session '$SESS'; waiting for runner…" >&2
    timeout 20 bash -c "until tmux capture-pane -t $SESS -p | grep -q 'runner started'; do sleep 0.3; done" \
      || { echo "[driver] runner never started — check ollama / TTY" >&2; exit 1; }
    sleep 8   # let it stream a bit
    echo "===== TUI (text capture) =====" >&2
    tmux capture-pane -t "$SESS" -p
    tmux capture-pane -t "$SESS" -e -p > /tmp/haiku.ansi
    echo "[driver] color capture: /tmp/haiku.ansi (cat in a real terminal). Drive with: tmux send-keys -t $SESS …" >&2
    echo "[driver] quit with: tmux send-keys -t $SESS C-c; tmux kill-session -t $SESS" >&2
    ;;

  headful)
    : "${WAYLAND_DISPLAY:?headful needs a Wayland session (WAYLAND_DISPLAY unset). Use headless.}"
    for t in alacritty grim hyprctl python3 magick; do
      command -v "$t" >/dev/null || { echo "[driver] missing tool: $t" >&2; exit 1; }
    done
    OUT=/tmp/haiku-tui.png
    alacritty --title haiku-tui -o font.size=9 -e zsh -c "$CMD; sleep 60" &
    echo "[driver] opening a visible alacritty window 'haiku-tui'…" >&2
    timeout 15 bash -c 'until hyprctl clients -j | grep -q "\"title\": \"haiku-tui\""; do sleep 0.3; done' \
      || { echo "[driver] window never mapped" >&2; exit 1; }
    sleep 3
    geom=$(hyprctl clients -j | python3 -c "import json,sys
for c in json.load(sys.stdin):
    if c.get('title')=='haiku-tui':
        x,y=c['at']; w,h=c['size']; print(f'{x},{y} {w}x{h}')")
    grim -g "$geom" "$OUT"
    magick identify "$OUT" >&2
    echo "[driver] screenshot: $OUT  (open with: xdg-open $OUT)" >&2
    echo "[driver] re-run the grim line for more frames; close with: hyprctl dispatch closewindow title:haiku-tui" >&2
    ;;

  *)
    echo "usage: driver.sh {headless|headful} [PROMPT]" >&2
    exit 2
    ;;
esac
