#!/usr/bin/env bash
# Launch + observe the Escapement TUI. See SKILL.md for full docs.
#
#   driver.sh headless [PROMPT]   # tmux PTY → rendered screen as TEXT (+ /tmp/haiku.ansi). Cross-platform. Default.
#   driver.sh shot     [PROMPT]   # accurate PNG, NO visible window:
#                                 #   Linux → cage (headless wlroots) + alacritty + grim   (auto-installs cage)
#                                 #   macOS → no offscreen path; falls back to headful
#   driver.sh headful  [PROMPT]   # accurate PNG in a VISIBLE window:
#                                 #   Linux → alacritty + grim (Wayland/Hyprland)   macOS → Terminal.app + screencapture
#
# `shot` and `headful` render a real terminal (pixel-accurate). `headless` is text
# only — fast, drivable via tmux, but NOT a faithful image (don't screenshot it).
#
# All need `ollama serve` up with gemma3:1b pulled. OLLAMA_NUM_PARALLEL=4 streams
# poets/judges concurrently instead of one-at-a-time.
set -euo pipefail

MODE="${1:-headless}"
PROMPT="${2:-Run a tournament with 6 poets and 5 judges. Theme: newly wed}"
SHOT_SECS="${SHOT_SECS:-12}"      # how long to let it stream before the screenshot
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
# RUN_CMD lets a caller swap the launch command (e.g. the OpenTUI sidecar via
# `bb haiku-opentui`) while keeping the same capture machinery. {PROMPT} in
# RUN_CMD is replaced with the shell-escaped prompt; if absent the prompt is
# appended. Default = the in-process JLine `bb haiku`.
ESC_PROMPT="${PROMPT//\'/\'\\\'\'}"
if [ -n "${RUN_CMD:-}" ]; then
  if [[ "$RUN_CMD" == *"{PROMPT}"* ]]; then
    CMD="${RUN_CMD//\{PROMPT\}/'$ESC_PROMPT'}"
  else
    CMD="$RUN_CMD '$ESC_PROMPT'"
  fi
else
  CMD="OLLAMA_NUM_PARALLEL=4 bb haiku '$ESC_PROMPT'"
fi
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
    sleep 8
    echo "===== TUI (text capture) =====" >&2
    tmux capture-pane -t "$SESS" -p
    tmux capture-pane -t "$SESS" -e -p > /tmp/haiku.ansi
    echo "[driver] color capture: /tmp/haiku.ansi (cat in a real terminal)." >&2
    echo "[driver] for a real screenshot use: driver.sh shot   (text capture is not a faithful image)" >&2
    echo "[driver] drive with: tmux send-keys -t $SESS … ; quit: tmux send-keys -t $SESS C-c; tmux kill-session -t $SESS" >&2
    ;;

  shot)
    case "$(uname -s)" in
      Linux)
        "$HERE/ensure-cage.sh" || { echo "[driver] cage unavailable — falling back to headful (visible window)" >&2; exec "$0" headful "$PROMPT"; }
        for t in alacritty grim magick; do
          command -v "$t" >/dev/null || { echo "[driver] missing tool: $t" >&2; exit 1; }
        done
        echo "[driver] rendering offscreen via cage (headless wlroots) — no window will appear…" >&2
        export OUT SHOT_SECS
        export INNER_CMD="$CMD"
        # Everything runs INSIDE cage so grim talks to cage's headless output, not your desktop.
        WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 LIBSEAT_BACKEND="${LIBSEAT_BACKEND:-noop}" \
          cage -- bash -c '
            # Roomy output (full 200-col layout) if wlr-randr is available; else cage default 1280x720.
            if command -v wlr-randr >/dev/null; then
              out=$(wlr-randr --json 2>/dev/null | grep -o "HEADLESS-[0-9]*" | head -1)
              [ -n "$out" ] && wlr-randr --output "$out" --custom-mode 2400x1350 >/dev/null 2>&1 || true
              sleep 0.5
            fi
            alacritty -o font.size=11 -e zsh -c "$INNER_CMD" &
            ALA=$!
            sleep "$SHOT_SECS"
            grim "$OUT" || true
            kill "$ALA" 2>/dev/null || true
          ' 2>/dev/null || { echo "[driver] cage render failed — try headful" >&2; exit 1; }
        magick "$OUT" -trim +repage "$OUT" 2>/dev/null || true
        command -v magick >/dev/null && magick identify "$OUT" >&2 || true
        echo "[driver] screenshot: $OUT  (open with: xdg-open $OUT)" >&2
        ;;
      Darwin)
        echo "[driver] macOS has no offscreen terminal path — using headful (a window will appear)." >&2
        exec "$0" headful "$PROMPT"
        ;;
      *)
        echo "[driver] 'shot' unsupported on $(uname -s) — use headless." >&2; exit 1 ;;
    esac
    ;;

  headful)
    case "$(uname -s)" in
      Linux)
        : "${WAYLAND_DISPLAY:?headful needs a Wayland session (WAYLAND_DISPLAY unset). Use shot or headless.}"
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
        osascript >/dev/null <<OSA
tell application "Terminal"
  activate
  do script "cd '$REPO'; $CMD; echo; echo '[done — window stays open for screenshot]'"
end tell
OSA
        sleep 6
        region=$(osascript -e 'tell application "Terminal" to get bounds of front window' \
          | tr -d ' ' | awk -F, '{print $1","$2","($3-$1)","($4-$2)}')
        screencapture -x -R"$region" "$OUT"
        echo "[driver] screenshot: $OUT  (open with: open $OUT)" >&2
        echo "[driver] re-run for more frames; close the Terminal window when done." >&2
        ;;
      *)
        echo "[driver] headful unsupported on $(uname -s) — use headless." >&2; exit 1 ;;
    esac
    ;;

  *)
    echo "usage: driver.sh {headless|shot|headful} [PROMPT]" >&2
    exit 2
    ;;
esac
