#!/usr/bin/env bash
# Launch + observe the Escapement TUI. See SKILL.md for full docs.
#
#   driver.sh headless [PROMPT]   # tmux PTY, prints the rendered screen as text  (cross-platform: macOS + Linux)
#   driver.sh headful  [PROMPT]   # real terminal window, screenshots to PNG
#                                 #   Linux  → alacritty + grim (Wayland/Hyprland)
#                                 #   macOS  → Terminal.app + screencapture
#
# Both need `ollama serve` up with gemma3:1b pulled. OLLAMA_NUM_PARALLEL=4 makes
# poets/judges stream concurrently instead of one-at-a-time.
#
# Default to headless — it works everywhere with only tmux. Use headful ONLY when
# the user explicitly asks for a real window / screenshot / pixel-accurate visual.
set -euo pipefail

MODE="${1:-headless}"
PROMPT="${2:-Run a tournament with 6 poets and 5 judges. Theme: newly wed}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
CMD="OLLAMA_NUM_PARALLEL=4 bb haiku '${PROMPT//\'/\'\\\'\'}'"
OUT=/tmp/haiku-tui.png
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
    echo "[driver] color capture: /tmp/haiku.ansi (cat in a real terminal)." >&2
    # Headless PNG: render the colored capture offscreen via freeze (auto-downloaded).
    if FREEZE="$("$(dirname "$0")/ensure-freeze.sh")"; then
      "$FREEZE" /tmp/haiku.ansi --output "$OUT" >/dev/null 2>&1 \
        && echo "[driver] screenshot: $OUT  (open with: $( [ "$(uname -s)" = Darwin ] && echo open || echo xdg-open ) $OUT)" >&2 \
        || echo "[driver] freeze render failed; text + /tmp/haiku.ansi still available" >&2
    else
      echo "[driver] freeze unavailable; skipping PNG (text + /tmp/haiku.ansi still available)" >&2
    fi
    echo "[driver] drive with: tmux send-keys -t $SESS … ; quit: tmux send-keys -t $SESS C-c; tmux kill-session -t $SESS" >&2
    ;;

  headful)
    case "$(uname -s)" in
      Linux)
        : "${WAYLAND_DISPLAY:?headful needs a Wayland session (WAYLAND_DISPLAY unset). Use headless.}"
        for t in alacritty grim hyprctl python3 magick; do
          command -v "$t" >/dev/null || { echo "[driver] missing tool: $t" >&2; exit 1; }
        done
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
        command -v magick >/dev/null && magick identify "$OUT" >&2 || true
        echo "[driver] screenshot: $OUT  (open with: xdg-open $OUT)" >&2
        echo "[driver] re-run for more frames; close with: hyprctl dispatch closewindow title:haiku-tui" >&2
        ;;

      Darwin)
        for t in osascript screencapture; do
          command -v "$t" >/dev/null || { echo "[driver] missing tool: $t" >&2; exit 1; }
        done
        echo "[driver] opening a visible Terminal.app window…" >&2
        # `do script` opens a new Terminal window running the chart; leave it up for the shot.
        osascript >/dev/null <<OSA
tell application "Terminal"
  activate
  do script "cd '$REPO'; $CMD; echo; echo '[done — window stays open for screenshot]'"
end tell
OSA
        sleep 6   # let the window map + chart start streaming
        # Front Terminal window bounds {x1,y1,x2,y2} → screencapture region "x,y,w,h".
        region=$(osascript -e 'tell application "Terminal" to get bounds of front window' \
          | tr -d ' ' | awk -F, '{print $1","$2","($3-$1)","($4-$2)}')
        screencapture -x -R"$region" "$OUT"
        echo "[driver] screenshot: $OUT  (open with: open $OUT)" >&2
        echo "[driver] re-run for more frames; close the Terminal window when done." >&2
        ;;

      *)
        echo "[driver] headful unsupported on $(uname -s) — use headless." >&2
        exit 1
        ;;
    esac
    ;;

  *)
    echo "usage: driver.sh {headless|headful} [PROMPT]" >&2
    exit 2
    ;;
esac
