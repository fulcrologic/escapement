---
name: run-tui
description: Launch and observe Escapement's interactive TUI (the live streaming-token panel + inspector) in two modes — headless (tmux + text capture, for agents/CI) and headful (real alacritty window + grim PNG, visible on the user's Wayland desktop). Use when asked to run, drive, or screenshot the TUI (e.g. `bb haiku '…'`, `bb -m escapement.cli run …`), or to confirm a UI change renders.
---

# Running & screenshotting the Escapement TUI

The TUI **requires a real TTY**. Run `bb haiku '…'` (or any chart with the TUI)
through a normal pipe and it aborts:

```
Interactive chart requires a TTY for the TUI.
Run from a real terminal, or pass --no-tui …
```

So you can never `| tee` / capture stdout directly — that strips the TTY. Use
one of the two modes below. Both give you the rendered screen; pick by audience.

| Mode | How you "see" it | When |
|---|---|---|
| **Headless** | `tmux capture-pane` → text (optionally ANSI color) | Agent/CI, fast iteration, no desktop needed. Default. |
| **Headful** | real `alacritty` window + `grim` → **PNG** | User is at the machine and wants to watch it live / see a pixel-accurate image (colors, unicode, layout). |

A helper script wraps both: [`driver.sh`](driver.sh). Raw commands are below so
you can drive interactively too.

## Prerequisites (both modes)

- `ollama serve` running, with the model pulled. The `haiku` task uses
  `gemma3:1b` (`ollama pull gemma3:1b`). Check: `ollama list` and
  `curl -s localhost:11434/api/tags >/dev/null && echo up`.
- For **concurrent** streaming (poets/judges at once, not one-at-a-time) prefix
  with `OLLAMA_NUM_PARALLEL=4` — ollama defaults to 1 (sequential).
- The example command used throughout:
  `bb haiku 'Run a tournament with 6 poets and 5 judges. Theme: newly wed'`

## Mode 1 — Headless (tmux, text)

tmux gives the process a real PTY; you read the screen back with `capture-pane`
instead of piping (piping would re-break the TTY).

```bash
cd /home/naomarik/github/escapement
tmux kill-session -t haiku 2>/dev/null
tmux new-session -d -s haiku -x 200 -y 50          # generous size; panel needs width
tmux set-option -t haiku remain-on-exit on          # server survives if the proc exits
tmux send-keys -t haiku \
  "OLLAMA_NUM_PARALLEL=4 bb haiku 'Run a tournament with 6 poets and 5 judges. Theme: newly wed'" Enter

# Poll for the ready marker rather than a fixed sleep:
timeout 20 bash -c 'until tmux capture-pane -t haiku -p | grep -q "runner started"; do sleep 0.3; done'

# "Screenshot" = capture the pane. Plain text:
tmux capture-pane -t haiku -p
# With ANSI colors preserved (view by `cat`-ing in a real terminal):
tmux capture-pane -t haiku -e -p > /tmp/haiku.ansi
```

Drive it (see key table below), then quit:

```bash
tmux send-keys -t haiku '?'        # toggle inspector
tmux send-keys -t haiku 'Enter'    # open transcript of the selected invocation
tmux send-keys -t haiku 'PgDn'     # scroll the transcript
tmux send-keys -t haiku C-c        # quit
tmux kill-session -t haiku 2>/dev/null || true
```

### Headless gotchas
- **Never pipe the launch command** (`| tee`, `$(…)`) — kills the TTY, same
  abort as a non-terminal. Read output via `capture-pane`, not stdout.
- **`remain-on-exit on` matters.** Without it, if the command exits/fails the
  only session closes and tmux shuts the server down — you'll see
  `no server running on /tmp/tmux-1000/default` on the next command and think
  tmux is broken. It isn't; the app died fast (usually the TTY/ollama error).
- Use `-x 200 -y 50` (or wider). The live panel hides columns when narrow.

## Mode 2 — Headful (real window + PNG screenshot)

Launches a **visible** alacritty window on the user's Wayland/Hyprland session
and screenshots just that window with `grim`. This is what produces a real
image the user can see pop up.

```bash
cd /home/naomarik/github/escapement

# 1. Launch a visible terminal running the TUI. `; sleep 60` keeps the window
#    up after the run finishes so you can screenshot the final frame.
alacritty --title haiku-tui -o font.size=9 -e zsh -c \
  "OLLAMA_NUM_PARALLEL=4 bb haiku 'Run a tournament with 6 poets and 5 judges. Theme: newly wed'; sleep 60" &

# 2. Wait for the window to map.
timeout 15 bash -c 'until hyprctl clients -j | grep -q "\"title\": \"haiku-tui\""; do sleep 0.3; done'
sleep 3   # let the chart start streaming

# 3. Screenshot exactly that window (geometry from hyprland → grim -g).
geom=$(hyprctl clients -j | python3 -c "import json,sys
for c in json.load(sys.stdin):
    if c.get('title')=='haiku-tui':
        x,y=c['at']; w,h=c['size']; print(f'{x},{y} {w}x{h}')")
grim -g "$geom" /tmp/haiku-tui.png
magick identify /tmp/haiku-tui.png    # sanity: non-zero size, sane dimensions
```

Then **look at it yourself** by Read-ing the PNG (the agent's image input), and
**open it for the user**:

```bash
xdg-open /tmp/haiku-tui.png    # pops up in the user's image viewer
```

Drive a headful window the same way as headless but target the window, not a
tmux pane — send keys via Hyprland focus, or simpler, run headless to *drive*
and headful only to *capture a frame*. To grab successive frames (streaming →
final), just re-run the `grim -g "$geom"` line at intervals to new filenames.

Clean up: the window self-closes after the `sleep 60`, or
`hyprctl dispatch closewindow title:haiku-tui`.

### Headful gotchas
- Needs a live graphical session: `echo $WAYLAND_DISPLAY` must be set (e.g.
  `wayland-1`). On a pure SSH/headless box this mode is unavailable — use
  Mode 1. Tools required: `alacritty`, `grim`, `hyprctl`, `python3`, `magick`.
- The window genuinely appears on the user's screen. If they "didn't notice it
  pop up," it may have opened on another workspace or closed fast — keep the
  trailing `sleep 60` so it lingers, and tell the user it's opening.
- `grim` with no `-g` captures the whole output (all monitors). Always pass the
  per-window geometry from `hyprctl` to get a tight, readable shot.
- This is Hyprland-specific (`hyprctl`). On other Wayland compositors get
  geometry differently (e.g. `swaymsg -t get_tree`); on X11 use `import`/`maim`.

## Key reference (driving the TUI)

| Key | Action |
|---|---|
| `?` | Toggle inspector |
| `Esc` / `h` | Back / close inspector pane |
| `Ctrl-C` | Quit |
| `Esc` | Interrupt the running chart |
| `PgUp` / `PgDn` | Scroll the log / transcript |
| `1` / `2` / `3` | Inspector tabs: Invocations / Chart / Status |
| `j` / `k`, `g` / `G` | Navigate the invocation list |
| `Enter` | Open transcript of the selected invocation |
| `o` | Show artifacts for the selection |
| `v` | Statechart visualizer |
| `s` / `c` / `p` / `P` | Live control: step / continue / pause / arm-pause |

## What "done" looks like

- Live panel header: `live · N active · M LLMs · K roles`; poets show `✓ done`,
  judges `▸ streaming` with live `tok` + `t/s`.
- Terminal state `[:run :finished]`; the chart logs
  `captured {:name "tournament-summary.md", :bytes …}`.
- The artifact lands at `./.escapement/<session>/artifacts/tournament-summary.md`.

## Quick reference via the helper

```bash
.claude/skills/run-tui/driver.sh headless   # tmux text capture
.claude/skills/run-tui/driver.sh headful    # alacritty window + /tmp/haiku-tui.png
.claude/skills/run-tui/driver.sh headful "Run a tournament with 4 poets and 3 judges. Theme: first snow"
```
