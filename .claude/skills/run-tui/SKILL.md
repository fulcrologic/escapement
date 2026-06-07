---
name: run-tui
description: Launch and observe Escapement's interactive TUI (the live streaming-token panel + inspector). Three modes — headless (tmux text capture, cross-platform, default for driving/verifying), shot (pixel-accurate PNG rendered OFFSCREEN with no window, via cage), and headful (PNG in a visible window). For a screenshot prefer `shot`; only use headful when the user wants to watch it live on their desktop. Use when asked to run, drive, or screenshot the TUI (e.g. `bb haiku '…'`, `bb -m escapement.cli run …`), or to confirm a UI change renders.
---

# Running & screenshotting the Escapement TUI

The TUI **requires a real TTY**, so you can never `| tee` / capture stdout
directly (that strips the TTY and the app aborts with
`Interactive chart requires a TTY for the TUI`). Use the helper:

```bash
.claude/skills/run-tui/driver.sh headless ["PROMPT"]   # tmux → TEXT (+ /tmp/haiku.ansi). Default. Cross-platform.
.claude/skills/run-tui/driver.sh shot     ["PROMPT"]   # accurate PNG, NO window → /tmp/haiku-tui.png
.claude/skills/run-tui/driver.sh headful  ["PROMPT"]   # accurate PNG in a VISIBLE window → /tmp/haiku-tui.png
```

Pick by what you need:

- **Driving / verifying behavior / fast iteration / CI** → **headless**. Only
  needs `tmux`; works on macOS + Linux. Returns the screen as text and writes a
  colored `/tmp/haiku.ansi`. **It is NOT a faithful image** — do not screenshot
  the text capture (re-typesetting it misaligns box-drawing/half-block borders).
- **A real screenshot** → **shot**. Renders the *actual* terminal into a
  **headless** Wayland output (no window appears) and captures it with `grim` —
  pixel-identical to headful. This is the right default for "take a screenshot /
  show me the UI."
- **Watch it live on the user's own desktop** → **headful**. A real window pops
  up; use only when the user explicitly wants to see it happen.

| Mode | Output | Faithful image? | Platform |
|---|---|---|---|
| **headless** (default) | text on stdout + color `/tmp/haiku.ansi` | ❌ text only | macOS + Linux (`tmux`) |
| **shot** | `/tmp/haiku-tui.png`, **no window** | ✅ real terminal render | Linux (cage+grim); macOS → falls back to headful |
| **headful** | `/tmp/haiku-tui.png`, visible window | ✅ real terminal render | Linux: alacritty+`grim` (Wayland/Hyprland) · macOS: Terminal.app+`screencapture` |

`shot` uses [`cage`](https://github.com/cage-kiosk/cage) (a tiny headless
wlroots compositor) to draw a real alacritty into an offscreen output, optionally
sized up by `wlr-randr` for the full 200-col layout. The helper **auto-installs**
`cage` (+ `wlr-randr`) via the system package manager on first use (see
`ensure-cage.sh`); if it can't, `shot` falls back to **headful**. macOS has no
offscreen terminal path, so `shot` there is just headful (a window appears).
Override the pre-screenshot stream delay with `SHOT_SECS=20 driver.sh shot …`.

After a `shot`/`headful` run, Read `/tmp/haiku-tui.png` yourself (vision), then
open it for the user (`xdg-open` on Linux, `open` on macOS).

## Prerequisites

- `ollama serve` running with the model pulled — `haiku` uses `gemma3:1b`
  (`ollama pull gemma3:1b`). Check: `ollama list`.
- The helper sets `OLLAMA_NUM_PARALLEL=4` so poets/judges stream concurrently
  (ollama defaults to 1 = sequential).
- Default prompt: `Run a tournament with 6 poets and 5 judges. Theme: newly wed`
  (pass your own as the 2nd arg).

## Driving interactively (headless)

The helper captures one frame and exits. To drive the TUI, talk to the tmux
session it left running (`-s haiku`):

```bash
tmux send-keys -t haiku 'Tab'      # cycle pane focus
tmux send-keys -t haiku 'Enter'    # open transcript overlay for the selection
tmux send-keys -t haiku 'PgDn'     # scroll the focused pane
tmux capture-pane  -t haiku -p     # re-read the screen
tmux send-keys -t haiku C-c        # quit
tmux kill-session  -t haiku 2>/dev/null || true
```

`shot`/`headful` capture a single frame and exit; re-run for more frames. To
*drive* the TUI (keys), use headless (talk to the tmux session); `shot`/`headful`
are for *capturing* a faithful image, not interaction.

### Gotchas
- **Never pipe the launch command** — kills the TTY (same abort as a non-terminal).
- **Don't screenshot the headless text.** The tmux text/ANSI capture is for
  reading, not imaging — re-rendering it (freeze/aha/etc.) misaligns the
  box-drawing and half-block (`▌▐▀`) borders. For an image use `shot`.
- **Headless:** the helper sets `remain-on-exit on`; without it a fast crash
  closes the only session and tmux reports `no server running` — the app died
  (usually ollama/TTY), tmux isn't broken. Width matters: it uses `-x 200`; the
  live panel hides columns when narrow.
- **shot (Linux):** needs `cage` + `grim` (+ optional `wlr-randr` for the full
  200-col grid; without it you get cage's 1280×720 default → narrower responsive
  layout). The helper auto-installs via the system package manager (needs sudo
  once); if install fails it falls back to headful. Works even on a pure
  SSH/headless box (no `$WAYLAND_DISPLAY` needed — cage makes its own). Tune the
  stream-before-capture delay with `SHOT_SECS=…`.
- **shot (macOS):** no offscreen path — it transparently becomes headful (a
  window appears).
- **Headful Linux:** needs a Wayland session (`$WAYLAND_DISPLAY` set) plus
  `alacritty`, `grim`, `hyprctl`, `python3`. Hyprland-specific; on other Wayland
  compositors get geometry differently (e.g. `swaymsg -t get_tree`). On a pure
  SSH/headless box, prefer `shot`.
- **Headful macOS:** uses built-in `osascript` + `screencapture` and Terminal.app
  (no extra installs). May prompt once for Screen Recording / Automation
  permission. Other compositors/terminals aren't handled — only Terminal.app.

## Key reference (driving the TUI)

Mission-control frame: header strip + side-by-side LIVE / LOG panes + footer;
the focused pane gets a heavy/bright border. Narrow terminals (< 100 cols) fall
back to a single stacked column.

| Key | Action |
|---|---|
| `Tab` | Cycle focus between LIVE and LOG panes |
| `Enter` | Open transcript overlay for the selection (LIVE: row's session, live-updating; LOG: the line's invocation) |
| `m` | Maximize the focused pane (toggle split ↔ full) |
| `Esc` | Close overlay; else restore split; else interrupt the chart |
| `PgUp` / `PgDn` | Scroll the focused pane (page) |
| `j` / `k` | LIVE: move selection cursor; LOG: scroll line |
| `g` / `G` | LIVE: cursor first / last row; LOG: top / bottom |
| `?` | Toggle inspector overlay |
| `o` | Show artifacts for the selection |
| `v` | Statechart visualizer |
| `s` / `c` / `p` / `P` | Live control: step / continue / pause / arm-pause |
| `Ctrl-C` | Quit |
| **Inspector (`?` open):** | `Esc`/`h` back · `1`/`2`/`3` tabs (Invocations/Chart/Status) · `j`/`k`,`g`/`G` navigate · `Enter` open transcript · `PgUp`/`PgDn` scroll pager |

## What "done" looks like

- Live panel header: `live · N active · M LLMs · K roles`; poets show `✓ done`,
  judges `▸ streaming` with live `tok` + `t/s`.
- Terminal state `[:run :finished]`; chart logs
  `captured {:name "tournament-summary.md", :bytes …}`.
- Artifact lands at `./.escapement/<session>/artifacts/tournament-summary.md`.
