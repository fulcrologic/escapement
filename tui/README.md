# TUI Render Stress-Test — Manifest & Run Recipes

This folder holds the **notes, screenshots, and run manifest** for the TUI render
stress-test campaign. It is data collection only — nothing here fixes rendering.

- `stress/` — all screenshots (PNG) + raw text/ANSI captures (tasks 003–006).
- `README.md` (this file) — the per-example run table, the capture workflow, and
  the environment probe.

The runnable **stress charts** are NOT here — they live on the classpath under
`tui/stress/` (created by task 002) so `cli run` can resolve
them. This README's stress-scenario section is their manifest.

---

## Two renderers

| Renderer | How | Process model | Prereqs |
|----------|-----|---------------|---------|
| **JLine TUI** (default) | `bb -m escapement.cli run <sym>` | in-process | real TTY |
| **OpenTUI sidecar** (opt-in) | add `--tui=opentui` | bb agent headless + Bun/SolidJS sidecar over WS | real TTY, `bun` on PATH, `bun install` done in `opentui/` |

`--no-tui` disables the TUI entirely (plain stdout) — useful for non-visual smoke,
but NOT a render capture. For captures always use a real TTY (see "Capture
workflow").

---

## Existing examples — run inventory

All symbols are under `escapement.examples.*`. **JLine** = the command shown;
**OpenTUI** = the SAME command + `--tui=opentui`. Model-backed charts need either
a live API key or a local model (ollama). Where an example's docstring suggested
z.ai (`ZAI_API_KEY`, default model `glm-4.6`), the substitute when no key is set
is ollama gemma3:1b via the explicit backend flags (shown in the haiku rows).

Ollama backend flag block (reused below, abbreviated as **`<OLLAMA>`**):
```
--backend ollama --api-base-url http://localhost:11434/v1 --model gemma3:1b \
  --max-tokens 2048 --overrun-retries 2 --overrun-temp-bump 0.3
```
(prefix with env `OLLAMA_API_KEY=dummy`; start the server `OLLAMA_NUM_PARALLEL=4 ollama serve`).

| Example file | Entry symbol | Needs LLM? | Model / key | Input / param | JLine command |
|---|---|---|---|---|---|
| `hello.cljc` | `escapement.examples.hello/agent` | yes | any backend (1 short turn); ollama gemma3:1b ok | none | `bb -m escapement.cli run escapement.examples.hello/agent <OLLAMA>` |
| `ask.cljc` | `escapement.examples.ask/agent` (`:interactive?`) | **no** (human-input only) | none | interactive: types name + confirm; Esc cancels | `bb -m escapement.cli run escapement.examples.ask/agent` |
| `artifacts-demo` (`artifacts_demo.cljc`) | `escapement.examples.artifacts-demo/agent` | yes (writer→critic, 2 convos) | any backend; ollama ok | none | `bb -m escapement.cli run escapement.examples.artifacts-demo/agent <OLLAMA>` |
| `clj-refactor` (`clj_refactor.cljc`) | `escapement.examples.clj-refactor/agent` | yes (fs_read/fs_edit tools) | any backend; ollama may be weak at tools | optional `--param target-path=...` (default `src/example.clj`) | `bb -m escapement.cli run escapement.examples.clj-refactor/agent <OLLAMA>` |
| `parallel-demo` (`parallel_demo.cljc`) | `escapement.examples.parallel-demo/agent` | yes (2 parallel convos) | any backend; ollama ok | none | `bb -m escapement.cli run escapement.examples.parallel-demo/agent <OLLAMA>` |
| `iterate.cljc` | `escapement.examples.iterate/agent` | yes (fs + shell tools) | any backend | **required params**: `spec-path`, `target-path`, `test-cmd` (via `--param k=v`) | `bb -m escapement.cli run escapement.examples.iterate/agent --param spec-path=... --param target-path=... --param 'test-cmd=...' <OLLAMA>` |
| `scan.cljc` | `escapement.examples.scan/agent` | yes (fs_read + shell) | any backend | optional `--param repo-path=<abs>` (default = cwd) | `bb -m escapement.cli run escapement.examples.scan/agent <OLLAMA>` |
| `large-files` (`large_files.cljc`) | `escapement.examples.large-files/agent` | yes (shell + glob) | **real backend recommended** (ANTHROPIC/OPENAI); ollama tool-calling weak | none | `bb -m escapement.cli run escapement.examples.large-files/agent <OLLAMA>` |
| `inspectable.cljc` | `escapement.examples.inspectable/agent` | yes (fs_write) | docstring: z.ai glm-4.5-air; sub ollama | none (optional `--session`/`--work-dir`) | `bb -m escapement.cli run escapement.examples.inspectable/agent <OLLAMA>` |
| `inspect-showcase` (`inspect_showcase.cljc`) | `escapement.examples.inspect-showcase/agent` | yes (fs_write) | docstring: z.ai glm-4.5-air; sub ollama | none | `bb -m escapement.cli run escapement.examples.inspect-showcase/agent <OLLAMA>` |
| `turn-loop` (`turn_loop.cljc`) | `escapement.examples.turn-loop/agent` | yes (fs_read/fs_write) | docstring: z.ai glm-4.6; sub ollama | none (uses scratch file in work dir) | `bb -m escapement.cli run escapement.examples.turn-loop/agent <OLLAMA>` |
| `steered-convo` (`steered_convo.cljc`) | `escapement.examples.steered-convo/agent` | yes (no real tools) | docstring: z.ai glm-4.6; sub ollama | none | `bb -m escapement.cli run escapement.examples.steered-convo/agent <OLLAMA>` |
| `steer-midturn` (`steer_midturn.cljc`) | `escapement.examples.steer-midturn/agent` | yes (no real tools) | docstring: z.ai glm-4.6; sub ollama | none | `bb -m escapement.cli run escapement.examples.steer-midturn/agent <OLLAMA>` |
| `supervisor.cljc` | `escapement.examples.supervisor/agent` | yes (no real tools; sub-charts) | docstring: z.ai glm-4.6; sub ollama | none | `bb -m escapement.cli run escapement.examples.supervisor/agent <OLLAMA>` |
| `n-subagents-demo` (`n_subagents_demo.clj`) | `escapement.examples.n-subagents-demo/agent` (`:multi-session?`) | **no** (workers upper-case strings, no LLM) | none | optional task list (defaults to 6 tasks) | `bb -m escapement.cli run escapement.examples.n-subagents-demo/agent` |
| `haiku-tournament-dynamic` (`haiku_tournament_dynamic.clj`) | `escapement.examples.haiku-tournament-dynamic/agent` (`:multi-session? :interactive?`) | yes (poets + judges stream) | ollama gemma3:1b (wired) | `--param 'user-input="..."'` (default in bb task) | `bb haiku '<prompt>'` (= the wired command; see below) |

### Wired bb tasks (haiku)

`bb.edn` wires two convenience tasks for the haiku tournament:

- `bb haiku '<prompt>'` → JLine. Expands to:
  `OLLAMA_API_KEY=dummy bb -m escapement.cli run escapement.examples.haiku-tournament-dynamic/agent --backend ollama --api-base-url http://localhost:11434/v1 --model gemma3:1b --max-tokens 2048 --overrun-retries 2 --overrun-temp-bump 0.3 --param 'user-input="<prompt>"'`
- `bb haiku-opentui '<prompt>'` → same but with `--tui=opentui`.

Default haiku prompt for this campaign:
`Run a tournament with 6 poets and 5 judges. Theme: newly wed`.

### Notes that affect capture

- `ask` and `n-subagents-demo` need **no model** — capturable with zero creds.
- `ask` is **interactive**: drive it (type a name, confirm) via tmux send-keys in
  headless mode, or just capture the prompt frame in shot mode.
- `iterate` has **required** `--param`s — pick a tiny real spec/target/test triple
  (e.g. a throwaway file + `--param 'test-cmd=true'`) or document as a setup
  blocker; without them it will not progress.
- `large-files` docstring asks for a real backend; ollama gemma3:1b tool-calling
  is unreliable (see `haiku_tournament_dynamic.clj` docstring on ollama
  tool-call emission). If ollama mis-emits tool calls, capture whatever frame
  renders and record it as a model/backend limitation, not a render bug.
- z.ai examples (`turn-loop`, `steered-convo`, `steer-midturn`, `supervisor`,
  `inspectable`, `inspect-showcase`) default to glm via `ZAI_API_KEY`; with no
  key set, run them on the `<OLLAMA>` block. The render is what we're testing, so
  model quality matters less than that frames draw.

---

## Stress scenarios — manifest

Four purpose-built stress charts live under
`tui/stress/` (on the classpath so `cli run` resolves them).
Each targets the RENDERER specifically. All four LOAD cleanly under bb/SCI and
run to `[:run :finished]` where deterministic.

**Backend note:** the CLI refuses to start without *some* backend, even though
`worst` and `edge` never call an LLM. For those two, pass the ollama flags only
to satisfy the guard (no network traffic actually occurs). `easy`/`mid` DO stream
real tokens (that is the point — the live-token panel is driven by real deltas),
so they need a working backend; with no API key, ollama gemma3:1b is used.

Abbreviations below: **`<OLLAMA>`** = the ollama flag block from the top of this
file (`--backend ollama --api-base-url … --model gemma3:1b …`), prefixed with env
`OLLAMA_API_KEY=dummy`. Add `--tui=opentui` to any command for the OpenTUI
renderer; omit `--no-tui` (shown nowhere below — captures use a real TTY via the
driver, see "Capture workflow").

| Tier | Chart file | Entry symbol | Model? | Intent |
|------|-----------|--------------|--------|--------|
| easy | `stress/easy.cljc` | `stress.easy/agent` | yes (1 stream) | Baseline: one short single-turn LLM reply → one small artifact. The control case — one invocation row, one live stream, one artifact must render cleanly with no overflow/garble. |
| mid  | `stress/mid.cljc`  | `stress.mid/agent`  | yes (4 streams) | Moderate concurrency + structure: 4 LLM conversations streaming AT ONCE in a parallel region, each steered into a 2nd turn (multi-turn), each captured to an artifact, plus 2 deterministic seed artifacts (`manifest.md`, `plan.json`). Stresses 4 live invocation rows, split/aggregated streams, tokens/sec, mixed artifacts. |
| worst| `stress/worst.cljc`| `stress.worst/agent`| no (deterministic) | Volume + concurrency flood, MODEL-FREE via multiplex workers (default 12). Each worker writes several large artifacts (default 4 → ~48 files) containing very-long single lines (~4000 chars, no spaces), token floods (~400 words), deep nested trees (~30 levels), and emits a rapid burst of `:artifact/captured` scrollback lines. Bounded + time-boxable (fixed counts/sizes, one pass). Tunable via `--param workers/artifacts-each/long-line-len/flood-words`. |
| edge | `stress/edge.cljc` | `stress.edge/agent` | no (deterministic) | Content edge cases, MODEL-FREE. Writes 10 artifacts with pathological BYTES: emoji/ZWJ/skin-tone/flags, CJK wide glyphs, RTL (Arabic/Hebrew) bidi, zero-width + combining marks + BOM, embedded ANSI SGR/cursor/OSC escapes, raw control chars (CR/TAB/BS/FF/VT/NUL/BEL/lone-ESC), box-drawing + block + braille, an ~8000-char no-space word, an empty file, and a combined "everything interleaved" file. Inspect via the artifact pager (`a`). |

### Run commands

**easy** (JLine; add `--tui=opentui` for OpenTUI):
```bash
OLLAMA_API_KEY=dummy bb -m escapement.cli run \
  stress.easy/agent <OLLAMA>
```

**mid** (set `OLLAMA_NUM_PARALLEL>=4` so the four workers truly stream at once):
```bash
OLLAMA_API_KEY=dummy OLLAMA_NUM_PARALLEL=4 bb -m escapement.cli run \
  stress.mid/agent <OLLAMA>
```

**worst** (deterministic; ollama flags only satisfy the backend guard, no calls):
```bash
OLLAMA_API_KEY=dummy bb -m escapement.cli run \
  stress.worst/agent <OLLAMA>
# faster/smaller capture:
#   ... --param workers=6 --param artifacts-each=2 --param long-line-len=2000
```

**edge** (deterministic; ollama flags only satisfy the backend guard, no calls):
```bash
OLLAMA_API_KEY=dummy bb -m escapement.cli run \
  stress.edge/agent <OLLAMA>
```

For captures, wrap any of these in the `RUN_CMD=… driver.sh shot ''` recipe under
"Capture workflow" below (use distinct output filenames per chart×renderer).

#### Load verification (task 002)

All four resolve and compile under bb with no load error. `worst` and `edge` run
fully to `[:run :finished]` headless and write all expected artifacts (verified:
edge wrote 10 files incl. real ESC/NUL/BEL bytes + a 0-byte `edge-empty.txt` + an
8000-char line; worst wrote N×artifacts-each files + emitted `:artifact/captured`
bursts). `easy`/`mid` load and reach the LLM-streaming stage (mid spawns all four
parallel streams + writes its 2 seed artifacts); whether they reach `:finished`
depends on the tiny model emitting the terminating event-tool, which is a model
limit, not a render/load defect.

---

## Capture workflow

The capture driver is the **run-tui skill**:
`.claude/skills/run-tui/driver.sh {headless|shot|headful} [PROMPT]`.

| Mode | What it produces | Use for |
|------|------------------|---------|
| `headless` | tmux PTY → rendered screen as **text** + `/tmp/haiku.ansi` (color). Fast, drivable. **NOT a faithful image.** | driving interactive charts, ANSI/text capture, verifying flow |
| `shot` | **pixel-accurate PNG**, rendered OFFSCREEN via cage (headless wlroots) + alacritty + grim. No visible window. → `/tmp/haiku-tui.png` | the faithful screenshots this campaign needs |
| `headful` | pixel-accurate PNG in a **visible** window (Wayland/Hyprland). → `/tmp/haiku-tui.png` | watching live; fallback if `shot` cage path fails |

**Golden rules (apply to ANY chart, not just haiku):**

1. **A real TTY is required.** The TUI owns the terminal. `headless` makes its own
   PTY via tmux; `shot`/`headful` make their own via cage/alacritty. Never run a
   TUI capture from a non-TTY shell directly.
2. **Never pipe the TUI's stdout** (`| tee`, `> file`, `| head`). It corrupts the
   render and the JLine TUI may silently no-op or garble. Capture via tmux
   `capture-pane` (headless) or grim (shot/headful) instead.
3. **`shot` for the PNGs that go in `stress/`.** `headless` text is supplementary
   raw capture only — don't screenshot the tmux text.
4. After a `shot`/`headful`, copy `/tmp/haiku-tui.png` to a descriptive name in
   `tui/stress/` (e.g. `cp /tmp/haiku-tui.png tui/stress/hello-jline.png`).
   For headless, copy `/tmp/haiku.ansi` similarly (e.g. `hello-jline.ansi`).
   Use distinct filenames per chart×renderer so parallel tasks don't collide.

### Running the wired haiku chart

```bash
.claude/skills/run-tui/driver.sh shot 'Run a tournament with 6 poets and 5 judges. Theme: newly wed'
cp /tmp/haiku-tui.png tui/stress/haiku-jline.png
```

OpenTUI haiku (driver supports swapping the launch command via `RUN_CMD`, with
`{PROMPT}` replaced by the escaped prompt):

```bash
RUN_CMD='OLLAMA_NUM_PARALLEL=4 bb haiku-opentui {PROMPT}' \
  .claude/skills/run-tui/driver.sh shot 'Run a tournament with 6 poets and 5 judges. Theme: newly wed'
cp /tmp/haiku-tui.png tui/stress/haiku-opentui.png
```

### Running an ARBITRARY (non-haiku) chart  ← capture tasks 003–006 use this

The driver defaults to `bb haiku`, but it honors a **`RUN_CMD`** env override
(any command; `{PROMPT}` is substituted with the shell-escaped prompt, or the
prompt is appended if `{PROMPT}` is absent). This is the parameterized path for
launching any `cli run` symbol through the same offscreen capture machinery.

**JLine, arbitrary chart (shot PNG):**
```bash
RUN_CMD='OLLAMA_API_KEY=dummy bb -m escapement.cli run escapement.examples.hello/agent \
  --backend ollama --api-base-url http://localhost:11434/v1 --model gemma3:1b \
  --max-tokens 2048 --overrun-retries 2 --overrun-temp-bump 0.3' \
  .claude/skills/run-tui/driver.sh shot ''
cp /tmp/haiku-tui.png tui/stress/hello-jline.png
```

**OpenTUI, arbitrary chart:** append `--tui=opentui` to the `RUN_CMD`:
```bash
RUN_CMD='OLLAMA_API_KEY=dummy bb -m escapement.cli run escapement.examples.hello/agent \
  --tui=opentui --backend ollama --api-base-url http://localhost:11434/v1 \
  --model gemma3:1b --max-tokens 2048 --overrun-retries 2 --overrun-temp-bump 0.3' \
  .claude/skills/run-tui/driver.sh shot ''
cp /tmp/haiku-tui.png tui/stress/hello-opentui.png
```

**No-model charts** (`ask`, `n-subagents-demo`) drop the ollama flags entirely:
```bash
RUN_CMD='bb -m escapement.cli run escapement.examples.n-subagents-demo/agent' \
  .claude/skills/run-tui/driver.sh shot ''
```

**Direct tmux fallback** (if you want full control, e.g. to drive interactive
`ask`, or a longer settle time than the driver's `SHOT_SECS`):
```bash
tmux kill-session -t cap 2>/dev/null || true
tmux new-session -d -s cap -x 200 -y 50          # 200-col width matches the driver
tmux send-keys -t cap "OLLAMA_API_KEY=dummy bb -m escapement.cli run <sym> [flags]" Enter
# wait, drive (tmux send-keys -t cap 'text' Enter), then:
tmux capture-pane -t cap -p              # text
tmux capture-pane -t cap -e -p > tui/stress/<name>.ansi   # color
tmux send-keys -t cap C-c ; tmux kill-session -t cap
```

Tuning knobs: `SHOT_SECS` (seconds to stream before the grab; default 12 —
increase for slow/long charts), tmux geometry `-x 200 -y 50` (the campaign width).
The driver sets `OLLAMA_NUM_PARALLEL=4` only inside its own default haiku command;
when you pass `RUN_CMD` you control the env yourself (prefix with
`OLLAMA_NUM_PARALLEL=4` if you want concurrent streaming).

---

## Environment Probe

Probed 2026-06-07 on this host (Arch Linux, Wayland/Hyprland; `WAYLAND_DISPLAY=wayland-1`):

| Prereq | Status |
|--------|--------|
| `bun` on PATH | **yes** — `/home/naomarik/.bun/bin/bun`, v1.3.5 |
| `opentui/node_modules` (bun install done) | **yes** — present (70 entries) |
| `ollama` binary | **yes** — `/usr/bin/ollama` |
| `ollama serve` reachable (`:11434`) | **yes** — `/api/tags` responds |
| gemma3:1b pulled | **yes** (815 MB). Also present: `gemma3:270m`, `deepseek-r1:14b` |
| `cage` (offscreen render) | **yes** |
| `grim` | **yes** |
| `wlr-randr` | **yes** |
| `alacritty` | **yes** |
| `magick` (ImageMagick) | **yes** |
| `tmux` | **yes** |
| Wayland session | **yes** (`WAYLAND_DISPLAY=wayland-1`) → `headful` viable too |
| `OPENAI_API_KEY` | unset |
| `ANTHROPIC_API_KEY` | unset |
| `ZAI_API_KEY` | unset |
| `OLLAMA_API_KEY` | unset (use `OLLAMA_API_KEY=dummy` inline; the haiku task sets it) |
| `GEMINI_API_KEY` | unset |
| `GROQ_API_KEY` | unset |
| Real TTY in the agent shell | **no** — agent stdin is NOT a tty (`tty` → "not a tty") |

**Implications for capture tasks:**

- **No live API keys are set.** Every model-backed example must run on local
  **ollama gemma3:1b** (use the `<OLLAMA>` flag block). z.ai/OpenAI/Anthropic
  paths are unavailable; charts whose docstrings assume them substitute ollama.
- **All `shot` tooling is present** (cage + grim + wlr-randr + alacritty + magick)
  and ollama gemma3:1b is pulled + serving → faithful offscreen PNGs are viable
  for every chart that can run on a local model.
- **The agent shell has no TTY**, but that's fine: the `shot`/`headless` driver
  modes create their own PTY (cage/tmux), so captures don't depend on the agent's
  own stdin being a tty. Run captures THROUGH the driver, never as a bare
  `bb -m escapement.cli run …` in the agent shell.
- OpenTUI prereqs (bun + node_modules) are satisfied → the `--tui=opentui`
  campaign is unblocked.
- `gemma3:1b` tool-calling is known-unreliable (per the haiku docstring); tool-
  heavy examples (`large-files`, `clj-refactor`, `iterate`, `scan`) may mis-emit
  tool calls. Capture whatever frame renders and record model limits separately
  from render defects.
