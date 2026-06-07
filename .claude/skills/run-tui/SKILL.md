---
name: run-tui
description: Launch and observe Escapement's interactive TUI (the live streaming-token panel + inspector). Default to headless (tmux + text capture; cross-platform, for agents/CI) — it ALSO emits a real PNG screenshot rendered offscreen, so you rarely need headful. Only use headful (a visible window) when the user explicitly wants to watch it live on their desktop. Use when asked to run, drive, or screenshot the TUI (e.g. `bb haiku '…'`, `bb -m escapement.cli run …`), or to confirm a UI change renders.
---

# Running & screenshotting the Escapement TUI

The TUI **requires a real TTY**, so you can never `| tee` / capture stdout
directly (that strips the TTY and the app aborts with
`Interactive chart requires a TTY for the TUI`). Use the helper, which gives you
the rendered screen either way:

```bash
.claude/skills/run-tui/driver.sh headless ["PROMPT"]   # tmux → text  (default, cross-platform)
.claude/skills/run-tui/driver.sh headful  ["PROMPT"]   # real window → /tmp/haiku-tui.png
```

**Default to headless** for essentially everything — runs, verification,
iteration, *and* screenshots. It needs only `tmux` and works on macOS and Linux,
and it produces **all three** of: live text on stdout, a colored
`/tmp/haiku.ansi`, and a real **`/tmp/haiku-tui.png`** rendered offscreen (no
window). **Only use headful** when the user *explicitly* wants to watch the TUI
live in a real window on their own desktop ("open a window", "let me watch it").
A plain "take a screenshot / show me the UI" → still headless (you get the PNG).

| Mode | How you "see" it | Platform |
|---|---|---|
| **Headless** (default) | text on stdout · color `/tmp/haiku.ansi` · **PNG `/tmp/haiku-tui.png`** (offscreen, no window) | macOS + Linux |
| **Headful** | a *visible* terminal window + screenshot → `/tmp/haiku-tui.png` | Linux: alacritty + `grim` (Wayland/Hyprland) · macOS: Terminal.app + `screencapture` |

The headless PNG is produced by [`charmbracelet/freeze`](https://github.com/charmbracelet/freeze)
(ANSI → PNG). The helper **auto-downloads** the right freeze binary for the OS/arch
on first use into `.claude/skills/run-tui/bin/freeze` (see `ensure-freeze.sh`) —
no manual install. Needs `curl`/`wget` + `tar` once; offline with no cached
binary, the PNG is skipped (text + `.ansi` still produced).

After either run, Read `/tmp/haiku-tui.png` yourself (vision), then open it for
the user (`xdg-open` on Linux, `open` on macOS).

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

For more frames in headful, just re-run `driver.sh headful` (or re-shoot the
window directly). To drive a headful window, run headless to *drive* and headful
only to *capture* — simpler than sending keys to a floating window.

### Gotchas
- **Never pipe the launch command** — kills the TTY (same abort as a non-terminal).
- **Headless:** the helper sets `remain-on-exit on`; without it a fast crash
  closes the only session and tmux reports `no server running` — the app died
  (usually ollama/TTY), tmux isn't broken. Width matters: it uses `-x 200`; the
  live panel hides columns when narrow.
- **Headful Linux:** needs a Wayland session (`$WAYLAND_DISPLAY` set) plus
  `alacritty`, `grim`, `hyprctl`, `python3`. Hyprland-specific; on other Wayland
  compositors get geometry differently (e.g. `swaymsg -t get_tree`). On a pure
  SSH/headless box, headful is unavailable — use headless.
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
