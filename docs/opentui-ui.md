# OpenTUI sidecar terminal UI

Escapement has a **second, opt-in terminal UI** rendered by [OpenTUI](https://opentui.com) (a Zig
core driven through its **SolidJS** reconciler). It runs as a **sidecar Bun process** that owns the
terminal and renders a live session, while the bb agent runs **headless** and streams its transcript
event stream to the sidecar over a WebSocket on the existing http-kit api-server.

It is **purely additive**. The default terminal UI is still the in-process **JLine TUI**
(`escapement.tui`); OpenTUI is selected with `--tui=opentui`. A normal `escapement run` never loads
any of this code, and the engine library (`escapement.lib`) never pulls it.

This doc covers the architecture, how to run it, how to develop and test it, the `tui/opentui/` layout,
how it relates to the JLine and browser UIs, and the known limitations. For the byte-level message
contract see [`docs/opentui-wire.md`](opentui-wire.md); for the original feasibility analysis see
[`docs/opentui-port-analysis.md`](opentui-port-analysis.md).

---

## Architecture

OpenTUI's renderer is a Zig native core reached over Bun FFI. Babashka/SCI has no usable C-FFI, so
OpenTUI **cannot** run in-process — a separate Bun process is mandatory. The design is therefore a
sidecar:

```
  ┌─────────────────────────── bb agent process (headless) ───────────────────────────┐
  │  runner ──transcript-tap──▶ JLine event! (skipped under --tui=opentui)             │
  │                          └▶ ws-push/publish!  ┐                                    │
  │  RemoteUiRenderer (HumanRenderer) ─broadcast!─┤  escapement.ui.ws-push  (the hub)  │
  │  control-handle / debug controller            │                                    │
  │  http-kit api-server  ── GET /ws  ◀───────────┘   POST /api  (transit EQL: reads+control) │
  │  *out*/*err* ▶ <session-dir>/escapement.log (never the terminal)                   │
  └───────────────────────────────────┬───────────────────────────────────────────────┘
                                       │  WebSocket (JSON, bidirectional)   + shared filesystem
                                       │  agent→UI: event / phase / prompt / progress / debug-snap  (session-dir/artifacts)
                                       │  UI→agent: answer / control                          ▲
  ┌────────────────────────────────────▼──────────────────────────────────────────────┐    │
  │  Bun sidecar (tui/opentui/) — OWNS the real TTY (raw mode, alt-screen, keys)            │    │
  │   transport ▶ domain store (Solid) ▶ OpenTUI/Solid render tree                      │────┘
  │   Shell: header/phase · LIVE token panel │ LOG pane · inspector/transcript · modals │
  └─────────────────────────────────────────────────────────────────────────────────────┘
```

**Key pieces**

| Piece | Where | Role |
|-------|-------|------|
| WS push hub | `src/escapement/ui/ws_push.clj` | Fans out the runner's transcript tap (incl. high-frequency `:llm/delta`) to WS clients **without** taking the runner's render/processing locks. Non-blocking send + bounded per-client queue with coalesce/drop-oldest overflow. Wraps each raw transcript line into the JSON wire envelope, derives `phase` snapshots on config change, remembers + replays the open `prompt` to late-joining clients. |
| WS route | `src/escapement/ui/server.clj` | Adds `GET /ws` (http-kit `as-channel`) when the ctx carries a hub; `POST /api` (transit EQL reads + control) is unchanged. |
| Remote renderer | `src/escapement/ui/remote_renderer.clj` | `RemoteUiRenderer` satisfies the `HumanRenderer` protocol (`escapement.invocation.human-input`): serializes each prompt to the wire, parks the chart worker on a promise keyed by `prompt-id`, delivers the answer when it arrives, rejects with `{:reason :cancelled}` on cancel, and integrates with the debug pause gate (`human-input-active?`). A process-wide registry (`deliver-answer!` / `cancel-answer!` / `cancel-all!`) is fed identically by the WS `answer` frame and the `escapement.human/answer` EQL mutation. |
| Answer mutation | `src/escapement/ui/resolvers.cljc` | `escapement.human/answer` EQL mutation (secondary/fallback to the WS `answer` frame). |
| Spawn / lifecycle | `src/escapement/ui/opentui_sidecar.clj` | `--tui=opentui` glue: pick a free port, resolve `tui/opentui/src/main.tsx`, build the WS back-channel handler seam, **spawn** `bun run` with **CWD = `tui/opentui/`** (so `bunfig.toml`'s Solid preload loads) and the real TTY **inherited**, supervise, and **restore the terminal** (`/dev/tty` ANSI + `stty sane; tput cnorm; tput sgr0`) if the sidecar dies abnormally. |
| CLI wiring | `src/escapement/cli.clj` | `--tui=opentui` mode: forces the api-server+WS on, installs the `RemoteUiRenderer`, supplies the WS handler seam, spawns+supervises the sidecar, runs the agent headless with `*out*/*err*` captured to the session log, and tears down cleanly. All add-on code is reached via `requiring-resolve`. |
| Bun sidecar | `tui/opentui/` | The TypeScript/SolidJS/OpenTUI UI (its own layout below). |

**Why these choices** (full rationale in the plan and `opentui-port-analysis.md`):
- **Sidecar, not in-process** — bb has no C-FFI; OpenTUI's Zig core is only reachable from a JS runtime.
- **WebSocket push** (not JSONL-tail) — one bidirectional transport for forward events + the
  back-channel; also upgrades the browser UI from polling. http-kit WebSocket was proven under bb
  first (task 001). No SSE fallback was needed.
- **JSON wire** (not transit) on the hot path — Bun-native `JSON.parse`, deltas are scalars, no
  transit-js in the bundle. The transit EQL `POST /api` remains available for control + live reads.
- **TTY ownership** — the Bun child owns `/dev/tty`; the agent goes headless and its stdout/stderr
  are captured to `<session-dir>/escapement.log` so chart prints never corrupt the UI-owned terminal.
- **Artifacts by shared filesystem** — the local sidecar reads artifact/blob files straight from
  `session-dir`; no bytes streamed over the wire.
- **Re-implemented pure logic** — the JLine TUI's aggregation, tok/s, theme/palette, transcript block
  model, and line-wrapping are **re-implemented in TypeScript** in the sidecar's domain layer. The
  Clojure originals (`tui/live.clj`, `tui/transcript.clj`, `tui/theme.clj`) are the reference spec;
  snapshot tests over recorded JSONL enforce equivalence.

### The architecture boundary

The engine core must never statically require a presentation add-on
(`test/escapement/architecture_boundary_test.clj`). This is preserved here:
- All agent-side glue lives in the **`escapement.ui.*`** add-on (`ws_push`, `remote_renderer`,
  `opentui_sidecar`, the `resolvers` mutation).
- `cli.clj` reaches it **only via `requiring-resolve`** (same pattern as `--api-server`).
- The `tui/opentui/` TypeScript tree is **outside `src/`**, so the boundary scanner never sees it.

---

## Wire schema

The canonical contract is [`docs/opentui-wire.md`](opentui-wire.md). In brief:

- **Transport:** one bidirectional WebSocket, `GET /ws`, JSON text frames, one object per frame.
- **Envelope:** `{"kind":"event","seq":N,"ts":ms,"event":"<kw-as-string>","data":{…}}`. The raw
  on-disk transcript line already *is* this envelope; the push layer only prepends `"kind"`. `seq` is
  monotonic and gap-free per session (use it for ordering + reconnect de-dup).
- **Forward (agent→UI):** `event` (fold into the store), `phase` (header/breadcrumb snapshot, sent on
  connect + config change), `prompt` (open a human-input modal), `progress`, `debug-snap`.
- **Back-channel (UI→agent):** `answer`
  (`{"kind":"answer","prompt-id":…,"value":…}` or `{…,"cancelled":true}`) and `control`
  (`{"kind":"control","op":"pause|step|continue|arm|ui-interrupt|ui-quit"}`).
- **Encoding:** keywords → name without the colon (`:llm/delta`→`"llm/delta"`), map keys keep hyphens,
  `nil`→`null`, `session-id` is **opaque** (equality only — its string form is inconsistent across
  event families; never parse it).

---

## How to run

**Prereqs**
- A real TTY (the sidecar owns the terminal; non-TTY/headless never spawns it).
- `bun` on `PATH` (or `BUN_BIN=/path/to/bun`). Bun ≥ 1.3.0 (1.3.5 is the tested version).
- `bun install` already run **in `tui/opentui/`** (creates `tui/opentui/node_modules`).
- For the local-model commands below: `ollama serve` running with `gemma3:1b` pulled, and
  `OLLAMA_NUM_PARALLEL=4` exported so concurrent poets/judges actually stream in parallel.

**One-command live tournament** (the streaming-dashboard showcase):

```bash
OLLAMA_NUM_PARALLEL=4 bb haiku-opentui ['<prompt>']
```

`bb haiku-opentui` is `bb haiku` rerouted through the sidecar (`--tui=opentui`,
`escapement.examples.haiku-tournament-dynamic`, gemma3:1b).

**Any chart**

```bash
OLLAMA_API_KEY=dummy bb -m escapement.cli run <chart> --tui=opentui \
  --backend ollama --api-base-url http://localhost:11434/v1 --model gemma3:1b
```

- `--tui=opentui` selects the sidecar; `--no-tui` still wins outright; a plain run defaults to JLine.
- The api-server+WS is forced on automatically on a **free port** (or reuses an explicit
  `--api-server <port>`); the sidecar connects to `ws://127.0.0.1:<port>/ws` (passed via
  `OPENTUI_WS_URL`). `ESCAPEMENT_SESSION_ID` / `ESCAPEMENT_SESSION_DIR` are passed too, so the
  sidecar reads artifacts straight off the shared session dir.
- Agent stdout/stderr + logs go to `<session-dir>/escapement.log`, never the terminal.
- On the sidecar exiting (normal or abnormal), or on agent finish/error, the CLI tears down: cancels
  parked prompts, stops the api-server, kills the child, and **restores the terminal**.

**Human-input** (`ask`): the prompt opens a modal in the sidecar; typing + Enter sends an `answer`
frame; Esc cancels (→ `{:reason :cancelled}` → interrupt). Verified end-to-end against a live model.

**Debugger** (`--debug`): `--debug --tui=opentui` is partially wired — control ops (`s`/`c`/`p`/`P`)
flow over the WS `control` channel to the debug controller, but driving the full pause/step UI live
is a known follow-up (see Known limitations). `--debug` without `--tui=opentui` is unaffected.

### Open a saved session (read-only replay)

Re-open any persisted session directory in the sidecar to review its recorded transcript,
artifacts, and drill-in views — no model, api-server, or WS required:

```bash
escapement open <session-dir> [--timing instant|paced|wallclock]
# e.g.
escapement open .escapement/049151c1-e3f6-45aa-9f82-8b9fa445261f
```

- `<session-dir>` — a saved session dir (e.g. `.escapement/<uuid>`) holding
  `transcript.jsonl`. A trailing slash is tolerated.
- `--timing` — replay pacing; one of `instant | paced | wallclock`. **Default `instant`**
  (renders the whole transcript immediately). `paced`/`wallclock` re-time the stream.

**How it works:** the CLI validates the dir, transforms its `transcript.jsonl` into a temp
wire-envelope JSONL via `escapement.ui.replay-source/session-dir->wire-file`, and spawns the
sidecar pointed at that file instead of a live WebSocket. The sidecar's offline `ReplaySource`
(`tui/opentui/src/transport/replay.ts`) feeds the same envelopes the live WS push emits, so a
replay renders identically to the original live run. Artifact / drill-in reads resolve against
the original session dir on disk.

**Limitations (read-only):**
- No live agent, no api-server/WS, and no human-input back channel — `ask` prompts in the
  recording are shown but not answerable (the session already happened).
- The Debugger / pause-step controls are inert in replay (nothing is executing).
- Requires a real TTY and `bun` on `PATH`, same as a live `--tui=opentui` run; a non-TTY
  invocation prints an error and exits non-zero without spawning.

**Env vars involved** (set by the CLI/sidecar — listed for debugging):
- `OPENTUI_REPLAY` — absolute path to the temp wire JSONL the sidecar replays.
- `OPENTUI_REPLAY_TIMING` — `instant | paced | wallclock` (the `--timing` value).
- `ESCAPEMENT_SESSION_DIR` — the **original** session dir (not the temp file), so the sidecar
  reads artifacts off the real session on disk.

In replay mode the live `OPENTUI_WS_URL` / `OPENTUI_WS_PORT` are explicitly removed from the
sidecar's environment, so no stale live endpoint can leak in.

---

## How to develop

The sidecar can be built and exercised **without a live agent or model** using recorded JSONL:

```bash
cd opentui
bun install                       # once
bun run dev                       # live: connects to OPENTUI_WS_URL / OPENTUI_WS_PORT (default ws://127.0.0.1:3737/ws)
bun run replay                    # offline: replays test/fixtures/haiku-sample.jsonl through the real store
bun run smoke                     # headless (no renderer): drains a fixture and prints counts (CI-gateable)
bun run typecheck                 # bun x tsc --noEmit  (== `bb opentui-build`)
```

Replay tuning: `OPENTUI_REPLAY=<path>`, `OPENTUI_REPLAY_TIMING=instant|paced|wallclock`,
`OPENTUI_REPLAY_LOOP=1`. Live vs. replay is chosen entirely inside `createEventSource(env)` — the
domain store never knows which transport it is fed by, which is what makes offline UI dev + the
deterministic snapshot tests possible.

---

## How to test

| What | Command | Notes |
|------|---------|-------|
| Agent-side (WS push + RemoteUiRenderer + answer round-trip + boundary) | `bb test` | `test/escapement/ui/opentui_push_test.clj`. Part of the normal bb suite; the architecture-boundary test is in the same run. |
| Engine smoke | `bb sanity` | |
| UI unit + snapshot | `bb opentui-test` (= `bun test` in `tui/opentui/`) | Pure-logic unit tests (aggregation, tok/s, theme capability, wrapping, transcript blocks) + reconciler-driven snapshot tests over recorded JSONL. No model, no network, deterministic. |
| UI typecheck | `bb opentui-build` (= `bun x tsc --noEmit`) | |
| Visual E2E (vision-verified) | `OLLAMA_NUM_PARALLEL=4 bb haiku-opentui '<prompt>'` + capture | See below. |
| Functional E2E (local model) | `--tui=opentui` runs of `hello`/`ask`/`scan`/`parallel_demo` against gemma3:1b | See below. |

**Current green state** (recorded at task 020):
- `bb test` — **390 tests / 2084 assertions / 0 failures / 0 errors** (incl. the WS-push +
  RemoteUiRenderer + architecture-boundary tests).
- `bb opentui-test` (`bun test`) — **162 pass / 0 fail / 16 snapshots**.
- `bb opentui-build` (`tsc --noEmit`) — **0 errors**.
- `bb sanity` — PASS.

**Visual capture** (cage/grim, offscreen, via the `run-tui` skill driver):

```bash
RUN_CMD="OLLAMA_NUM_PARALLEL=4 bb haiku-opentui" SHOT_SECS=9 \
  .claude/skills/run-tui/driver.sh shot '<prompt>'
```

Use a LARGE tournament (e.g. 8 poets / 6 judges) + `SHOT_SECS≈9` to catch streaming/shimmer; a small
tournament + `SHOT_SECS≈22` for the finished frame. Vision-verified frames are committed under
`tui/opentui/test/visual/` (`haiku-opentui-streaming.png`, `haiku-opentui-finished.png`). The same driver
also has a `headless` (tmux text capture) and `headful` mode.

**What the E2E confirmed** (tasks 018/019): live in-flight token streaming with growing token counts +
live tok/s + model; multi-session tournament grouping (group header + completion bar + indented child
rows + "…+N more"); shimmer on in-flight rows vs. determinate bars on done groups; the full `ask`
prompt→answer round-trip (and Esc→cancel at the wire/modal level); inspector overlay, transcript
drill-in (SENT/REPLY blocks + meta), Tab focus, maximize, and clean Ctrl-C teardown — all on a real
terminal with a live model.

---

## The `tui/opentui/` layout

```
tui/opentui/
  package.json        @tui/opentui/core@0.3.2 + @tui/opentui/solid@0.3.2 + solid-js@1.9.12 (exact pins); scripts
  tsconfig.json       jsxImportSource @tui/opentui/solid; module Preserve + moduleResolution bundler (NOT NodeNext)
  bunfig.toml         preload = ["@tui/opentui/solid/preload"]  ← MANDATORY: compiles Solid JSX (loaded via CWD)
  src/
    main.tsx          render() entry: createEventSource → domain store → Shell + panes + overlays + modals
    transport/        wire (JSON envelope codec), event-source (the one interface), ws-client, replay, eql-client, index (factory)
    domain/           store/solid-store, aggregate (live-agg/tok-s), transcript (SENT/REPLY blocks), theme (palette+capability), wrap, time, entries, types
    input/            dispatch (control back-channel + mode resolver), keybindings (useKeyboard map → actions)
    ui/               Shell, Header, Footer, LivePanel (+ live/{rows,Shimmer,CompletionBar,tick}), LogPane,
                      Inspector (+ inspector/artifacts), Transcript, Pager, Modals, Debugger, styled, layout
  scripts/            replay-smoke, domain-smoke (headless, CI-gateable)
  test/
    fixtures/         haiku-sample.jsonl (recorded gemma3:1b run), live-streaming.jsonl
    unit/             aggregate, theme, transcript, wrap  (pure-logic parity vs. the Clojure originals)
    snapshot/         live-panel, log-pane, inspector, transcript, layout, modals, color-capability (reconciler-driven; committed .snap)
    transport.test.ts wire/replay/eql round-trips
    visual/           haiku-opentui-{streaming,finished}.png (vision-verified E2E evidence — tracked)
```

`node_modules/` and build/cache artifacts are gitignored (`tui/opentui/.gitignore`); the committed
`test/visual/*.png` frames are intentionally tracked.

> Updating snapshots: `cd opentui && bun test test/snapshot/ -u` rewrites the `.snap` files; review
> the diff and commit, then a plain `bun test test/snapshot/` must stay green.

---

## Relationship to the other UIs

- **JLine TUI** (`escapement.tui`) — still the **default** and **unchanged**. OpenTUI is additive and
  opt-in (`--tui=opentui`); non-TTY/headless never spawns the sidecar. The shared pure logic was
  re-implemented in TypeScript, not extracted from the Clojure original, so the JLine TUI's behavior
  is untouched. There are now **two terminal-UI add-ons**: JLine (in-process, default) and the
  OpenTUI sidecar (out-of-process, opt-in).
- **Browser UI** (`escapement.ui.*` RAD explorer, `--api-server`) — the WS push endpoint added for
  the sidecar is an incidental **upgrade** to the same api-server (previously poll-only for app data).
  The browser explorer is otherwise unchanged.

---

## Known limitations / follow-ups

Collected from the task results. None block the feature; all are documented for a future pass.

**Render / layout**
- **LIVE group-bar column overrun** (tasks 016/018) — on a group-header row the completion bar abuts
  the variable-width `N/M done` label instead of sitting in a fixed column, so the bar's right edge
  and the `N tok / t·s / model` tail float at different offsets across groups. Cosmetic
  misalignment, fully legible, **not** corruption. Fix is a focused `LivePanel.tsx` pass: compose each
  group/single/child left block as one pre-padded `<text>` to a fixed display width (`tail-w=22` in
  the JLine renderer) with the bar/shimmer as a sibling at a fixed column; it would also require
  regenerating the two live-panel snapshots.
- **LIVE selection highlight is a background bar, not reverse-video** (task 012) — OpenTUI `<box>` has
  no `reverse`; the drill-in cursor uses a `backgroundColor` accent bar. To get byte-parity with the
  JLine whole-row reverse, rebuild LIVE rows as a single styled `<text>` and reverse the spans.

**Transcript / inspector**
- **Markdown / code / diff highlighting deferred** (task 011) — transcript bodies render as plain text
  (parity-first). The JLine `markdown.clj` / `<code>` / `<diff>` highlighting is the documented
  optional upgrade; `Transcript.tsx`'s `bodyLines` is the single swap-in hook.
- **`makeBlobReaders.content` absent** (task 011) — no EDN parser in the sidecar, so a nested-content
  assistant turn falls back to the inline event snippet rather than the full blob. The system prompt
  and tool-result text *are* read full from the blobs.

**Agent-side**
- **Prompt↔invokeid association gap** (tasks 003/013) — the `HumanRenderer` methods receive only the
  chart-author `params`, not `invokeid` (it lives in `parent-ctx`), so a wire `prompt`'s `invokeid` is
  `nil` and `prompt-id` falls back to `prompt#<n>`. Round-trip uniqueness is unaffected; threading
  invokeid into opts would let the UI bind a modal to its invocation row.
- **`send-next!` drop-on-failed-send** (task 017) — if an http-kit `send!` fails/closes, the popped
  envelope is dropped, not retained, so a failing/slow socket loses frames one-by-one (rather than the
  bounded-queue overflow policy absorbing them). Untested at the integration level.

**E2E coverage gaps**
- **`iterate` not driven live** (task 019) — needs `--spec-path/--target-path/--test-cmd` fixtures; its
  read-spec→patch→test→reflect loop uses the same `llm-conversation` streaming path proven on haiku.
- **Live pause/step/continue not driven E2E** (task 019) — the path is HTTP-proven
  (`live_control_http_test.clj`) and key→WS→controller was smoke-proven (task 014), but
  `--debug --tui=opentui` was not driven live through the full pause/step UI.
- **`ask` cancel chart-final not frame-captured** (task 019) — Esc→clean-exit was observed and the
  `{:reason :cancelled}`→`:cancelled` mapping is unit-tested, but teardown is too fast to capture the
  post-Esc `:cancelled` final frame on a non-transcript-persisting chart.
- **Short charts have no live render window on gemma3:1b** (task 019) — `hello`/`scan`/`parallel_demo`
  finish before Bun finishes cold-starting + painting, so they prove "ran to completion + clean
  teardown" rather than live streaming. Streaming is proven on the long haiku run.

**Input / platform**
- **Mouse support skipped** (task 012) — mouse scroll/click was a bonus in the spec; not implemented.
- **Packaging, Windows, remote attach** (spec out-of-scope) — the sidecar runs from source under Bun;
  there is no distributable binary. Windows is unsupported (Linux/macOS only). `--no-spawn` (attach an
  externally-launched UI) is implemented but only path-smoke-tested. Remote/SSH multi-host operation,
  auth, and multi-client fan-in are out of scope.
