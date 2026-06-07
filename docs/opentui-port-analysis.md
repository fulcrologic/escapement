# Porting the Escapement TUI to OpenTUI + SolidJS — feasibility & work analysis

> **Implemented.** This scoping doc was carried out as the `opentui-sidecar-tui` spec. The shipped
> sidecar UI is documented in [`docs/opentui-ui.md`](opentui-ui.md) (architecture, run, develop,
> test, limits) and the wire contract in [`docs/opentui-wire.md`](opentui-wire.md). The verdict below
> held: it landed as an additive, opt-in `--tui=opentui` sidecar (Bun + SolidJS) with the JLine TUI
> unchanged as the default.

**Status:** examination / scoping doc. No code written. Date: 2026-06-07.

**Question:** What would it take to use [OpenTUI](https://opentui.com) (`~/github/opentui`,
v0.3.2) as the rendering engine — via its **SolidJS** reconciler (`@opentui/solid`) — to fully
replace `escapement.tui` with complete feature parity, including live token streaming?

**One-line answer:** It is feasible and the *rendering* gets easier and richer, but it is not a
"port" in the in-process sense — OpenTUI cannot run inside Babashka. It forces a **sidecar UI
process** (Bun + Solid + OpenTUI) talking to the bb agent over IPC. The real cost is not the
widgets; it's (1) the transport/IPC, (2) bidirectional human-input prompts, and (3) the deployment
regression from "one bb binary" to "bb + Bun + a native shared library + two-process lifecycle."

---

## 0. TL;DR / verdict

| Dimension | Finding |
|---|---|
| Can OpenTUI run inside bb (in-process)? | **No.** OpenTUI is a Zig native core driven over **Bun FFI**; bb/SCI has no usable C-FFI and cannot `dlopen` it. |
| Architecture forced | **Sidecar**: a separate Bun process owns the terminal; the bb agent runs headless and streams to it. |
| Does that fit the codebase? | **Yes, well.** It mirrors the existing add-on boundary (`add-on → core`, one-way) and the existing client/server web-UI split. An OpenTUI front-end is a *third* render target conceptually. |
| Is feature parity achievable? | **Yes**, and several features get *better* (real flexbox, sticky-bottom scrollbox, syntax highlighting, rich text, mouse). |
| Streaming tokens? | **Demonstrably yes** — OpenTUI's own `session.tsx` Solid example does exactly this (chunked streaming text, sticky-scroll-to-bottom, in-flight cursor at ~60fps). |
| Hardest parts | Live push transport (none exists today — web UI is poll-only); bidirectional blocking human-input across a process boundary; deployment + TTY-ownership + two-process lifecycle. |
| Biggest strategic cost | Loses the "everything runs under bb, single binary via `bbin`" property. Adds a Bun toolchain + per-platform native `.so`. |
| Recommendation | Prototype the transport + the live streaming panel behind a `--tui=opentui` flag first (highest value, lowest risk, directly answers "can it stream tokens"). Decide on full parity after that spike. |

---

## 1. The two systems are different runtimes

### 1.1 Escapement TUI (today)
- **Clojure under Babashka/SCI**, in-process with the agent. No JVM.
- Renders with **JLine** to `*err*` (keeps chart stdout clean), hand-rolled compositor
  (`tui/compositor.clj`): absolute cursor positioning, manual box-drawing, row-by-row diff-free
  repaint with `clear-eol`, optional Mode-2026 synchronized output.
- Single render lock; an **input thread** (JLine `NonBlockingReader`, manual ESC-sequence
  disambiguation) and a **33 ms ticker thread** that coalesces repaints (`:render-dirty`).
- ~4,500 lines across `tui.clj` (1,976) + `tui/{compositor,inspector,live,log,phase,theme,
  transcript,util}.clj`.

### 1.2 OpenTUI
- **Zig native core** exposing a C ABI, driven from TypeScript over **Bun FFI** (`bun:ffi`);
  Node only via *experimental* `node:ffi`. `package.json` declares `engines.bun >= 1.3.0`.
- Native core distributed as **prebuilt per-platform npm packages** (`@opentui/core-linux-x64`,
  `-darwin-arm64`, `-linux-x64-musl`, `-win32-x64`, … 8 targets) — no Zig needed to *install* on a
  supported platform; Zig needed only to build from source / unsupported targets.
- Layout is **Yoga flexbox** (`yoga-layout` 3.2.1). Rich primitives: `BoxRenderable`,
  `TextRenderable`, `ScrollBoxRenderable`, `InputRenderable`, `TextareaRenderable`,
  `SelectRenderable`, `TabSelectRenderable`, `CodeRenderable` (syntax highlight), `DiffRenderable`,
  `MarkdownRenderable`, `TextTableRenderable`, etc.
- **`@opentui/solid`** is a SolidJS universal reconciler. JSX intrinsics: `<box> <text> <scrollbox>
  <input> <textarea> <select> <tab_select> <code> <diff> <line_number> <ascii_font>` plus inline
  text modifiers `<span> <b> <i> <u> <a> <br>`. Reactivity via Solid signals/stores; control flow
  via `<For> <Show> <Switch>/<Match> <Index> <ErrorBoundary>`; input via `useKeyboard`/`usePaste`
  hooks; bootstrap via `render(() => <App/>, cfg)`.

### 1.3 Why in-process is impossible
bb/SCI exposes no `java.lang.foreign` Panama API and no JNA in this project (confirmed: zero
native/FFI usage anywhere in `src/`, `bb.edn`, `deps.edn`). OpenTUI's *entire* value is the native
core + the JS reconciler; re-implementing its FFI in Clojure would discard the Solid layer the
question is premised on. **Therefore the UI must be a separate OS process in a JS runtime (Bun).**

---

## 2. Target architecture (sidecar)

```
        ┌────────────────────────── one terminal ──────────────────────────┐
        │                                                                   │
        │   ╔══════════════════════╗        owns /dev/tty (raw mode,        │
        │   ║  Bun process         ║◀────── keyboard, mouse, alt-screen,    │
        │   ║  @opentui/solid app  ║        rendering)                      │
        │   ╚══════════╤═══════════╝                                        │
        └──────────────┼────────────────────────────────────────────────────┘
                       │  IPC (localhost): 3 logical channels
        ┌──────────────┼────────────────────────────────────────────────────┐
        │  A. forward event+token stream   (agent → UI)                       │
        │  B. control: pause/step/continue (UI → agent)  [already exists]     │
        │  C. human-input prompts+answers  (agent ⇄ UI, blocking) [new]       │
        └──────────────┼────────────────────────────────────────────────────┘
                       │
        ╔══════════════╧═══════════╗
        ║  bb agent (headless)      ║  writes transcript JSONL to session-dir;
        ║  runner + statechart +    ║  artifacts on shared filesystem;
        ║  llm + tools              ║  stdout/stderr captured (not the TTY).
        ╚═══════════════════════════╝
```

Key consequence: **only one process may hold the TTY in raw mode.** Today that's the bb TUI. In the
sidecar model the **Bun process owns the terminal**; the bb agent runs *headless* (as if
`--no-tui`), and its `*out*`/`*err*` must be captured and forwarded to the UI's log pane (or a file)
rather than printed to the now-UI-owned terminal. The CLI spawns/supervises the Bun child and hands
off the TTY.

This is the same shape as the existing browser UI (`--api-server`), except the "client" is a local
terminal app instead of a browser, and it can read artifact files directly off the shared disk.

---

## 3. The integration contract (where the real work is)

The rendering is the easy half. The half that doesn't exist yet is the wire between a Clojure agent
and a JS UI. Three logical channels:

### Channel A — forward event + token stream (agent → UI)
Everything the UI paints derives from the **transcript event stream**. Two viable transports:

1. **Tail the transcript JSONL** (`escapement.transcript`, single-writer JSONL sink in session-dir).
   - Pros: the agent *already writes it* — near-zero new agent code; ordered; every row carries
     `:seq` + `:ts`; `:llm/delta` rows are pure scalars (`{:event :llm/delta :data {:type :text-delta
     :text "…" :model … :invokeid … :session-id … :usage {…}}}`) → trivially JSON. Survives
     restart/replay; the UI can render history by reading the file head.
   - Cons: file-tail latency (negligible with inotify or 16 ms poll); must handle partial trailing
     lines; UI must discover the active session's transcript path.
2. **Add a WebSocket/SSE push endpoint to the existing http-kit `--api-server`**, fed by fanning out
   the runner's `transcript-tap`.
   - Pros: structured, same origin as control, reuses transit; **also fixes the browser UI**, which
     is currently **poll-only** (`df/load!` + a "Refresh" button — there is *no* live push for app
     data today; the only websocket is shadow's dev hot-reload).
   - Cons: net-new server code; http-kit websocket-under-bb needs verifying; backpressure handling.

> The existing `POST /api` EQL surface is **not** adequate for token streaming on its own — polling
> `:session/events` cannot keep up with ~100+ tokens/s. A push channel (1 or 2) is required.

**Recommendation:** start with **JSONL tail** (fastest to a working streaming demo, no agent
changes), and consider promoting to a WebSocket later (so the browser UI benefits too).

### Channel B — control (UI → agent) — *already exists*
`escapement.debug.controller` (a plain atom: `:mode`, `:step-budget`, `:gate` promise,
`:pause-on-next-external?`) is already exposed as **transit EQL mutations** over `POST /api`:
`escapement.control/{pause,step,continue,arm-pause-on-next-external}`, with live reads
`:session/{paused?,step-budget,live-configuration,pending-events}`. This is proven end-to-end under
`bb test` by `test/escapement/ui/live_control_http_test.clj`. **Reuse verbatim.** Also reuse this
path for the UI→agent events the current TUI sends: `:ui.interrupt` (Esc) and `:ui.quit` (Ctrl-C).

### Channel C — human-input prompts (agent ⇄ UI, blocking) — *new*
The chart pauses for the human via the `HumanRenderer` protocol
(`escapement.invocation.human_input`):

```clojure
(defprotocol HumanRenderer
  (prompt-text    [this opts])   ; → promise<string>
  (prompt-select  [this opts])   ; → promise<value>
  (prompt-multi   [this opts])   ; → promise<vector>
  (prompt-confirm [this opts])   ; → promise<bool>
  (start-progress [this opts]) (update-progress [this h pct label]) (end-progress [this h])
  (custom-render  [this f env data]))
```

Today there are two impls: `StdinRenderer` (headless fallback) and `TuiRenderer` (parks on a modal
promise that the input thread delivers). For the sidecar we add a third: **`RemoteUiRenderer`** that

1. serializes the prompt (`opts`) to the UI over the wire,
2. parks the chart on a `p/promise` (exactly as `TuiRenderer/ask!` parks on the modal promise today),
3. the UI renders an `<input>`/`<select>`/etc., and on submit/cancel sends the answer back,
4. the agent delivers the parked promise (or rejects with `{:reason :cancelled}` on Esc).

This is the trickiest piece because it is **bidirectional + blocking + must integrate with the debug
pause gate** (`human-input-active?` currently gates the pause). A clean implementation: a new EQL
mutation `escapement.human/answer` (over the same `POST /api`) keyed by a prompt-id the agent emits.
The agent side mirrors the existing `ask!`/`complete-modal!` park/deliver pattern — only the delivery
arrives over the wire instead of from the local input thread.

### Serialization & artifacts
- Transcript/transit infra already exists; deltas are fully serializable; non-serializable values in
  general events degrade gracefully (`pr-str`, `:transcript/sanitized?`).
- **Artifacts need not cross the wire.** Because the sidecar is local, the UI reads artifact files
  (captured I/O, author files, request/response blobs) **directly from the shared `session-dir`** —
  a big simplification over the browser UI, which loads them via resolvers for remote use.

---

## 4. Feature-by-feature parity map

For each existing TUI capability: the OpenTUI/Solid mechanism, relative effort, and notes. "Easier"
means OpenTUI does natively what we hand-rolled.

| # | Escapement feature (file) | OpenTUI + Solid mechanism | Effort | Notes |
|---|---|---|---|---|
| 1 | **Compositor / layout** (`compositor.clj` — `layout`, `draw-box`, absolute positioning, two-pane/narrow/maximized) | `<box>` tree with **Yoga flexbox** (`flexDirection`, `flexGrow`, `width/height %`, `border`, `title`, `padding`) | **Easier** | Delete ~all of `compositor.clj`. Responsive split = flex; "maximize" = toggle a flex child. Borders/titles are box props. |
| 2 | **Per-frame repaint, sync-output, cursor/alt-screen mgmt** (`render-frame!`, `detect-sync-output!`) | OpenTUI's native frame loop (`createCliRenderer`, `targetFps`/`maxFps`, double-buffered in Zig) | **Easier (delete)** | The 33 ms ticker + `:render-dirty` coalescing + Mode-2026 detection all go away — handled by the engine + Solid fine-grained reactivity. |
| 3 | **Live token streaming panel** (`live.clj`, `fold-live-event`) | `createStore` per-invoke/session map → `<scrollbox stickyScroll stickyStart="bottom">` + `<text>` with in-flight `<span>` cursor | **Medium** | OpenTUI's `packages/solid/examples/session.tsx` is a near-exact template (chunked streaming, sticky bottom, `▊` cursor, ~60fps). Port the *aggregation* logic (`live-agg`, `live-tps`, per-role/per-session rollups, multi-session tournament groups) as pure TS. |
| 4 | **Shimmer (indeterminate)** + **completion bars (determinate)** (`shimmer`, `completion-bar`) | Reactive `width` bound to a frame-tick signal, or `useTimeline`; bars = `<box>` widths or `█/░` text | **Easy** | Current impl is frame-counter-deterministic; reproduce with a `createSignal` tick or timeline. |
| 5 | **Inspector** (`inspector.clj` — invocations list, chart view, status view, drilldown) | `<Switch>/<Match>` for views; `<select>`/`<For>` lists with keyboard cursor; `<scrollbox>` bodies | **Medium** | List selection/`j k g G` map onto `<select>` or manual cursor + `useKeyboard`. |
| 6 | **Pager** (word-wrap, PgUp/PgDn/g/G) | `<scrollbox focused>` (native scroll, wheel + keys, scrollbar) | **Easier** | Native viewport culling; drop hand-rolled `wrap-line`/offset math. |
| 7 | **Transcript rendering** (`transcript.clj` — SENT/REPLY blocks, themed tags, in-flight cursor) | `<For>` over blocks → styled `<text>`/`<span>`; optionally `<code>` (syntax highlight!), `<markdown>`, `<diff>` for richer bodies | **Medium** | Parity straightforward; **upgrade opportunity**: real syntax highlighting + diffs for tool I/O. Blob caching becomes plain TS. |
| 8 | **Phases / header strip** (`phase.clj` — breadcrumb, sibling sliding window, metrics) | `<box flexDirection="row">` breadcrumb; sibling strip = horizontal flex list with per-item color | **Medium** | The statechart introspection (`phase-model`) stays **agent-side**; emit a compact "phase snapshot" event the UI renders. Avoid shipping the whole chart map. |
| 9 | **LOG pane** (`log.clj` — role-hued entries, glyphs, scroll) | `<scrollbox>` + `<For>` of `<text>` rows with role color | **Easy** | |
| 10 | **Theming** (`theme.clj` — semantic keys, 256/16/none, NO_COLOR, per-role hue palette) | A Solid theme object of RGBA colors + `StyledText`; map each semantic key | **Medium** | OpenTUI does truecolor natively. Re-implement capability/`NO_COLOR` detection and the round-robin invoke-id palette in TS. |
| 11 | **Modals / human-input** (text/confirm/select/multi-select) | `<input>` / `<textarea>` (text), `<select>` (select), custom box (confirm, multi-select w/ `[x]`) | **Medium (render) / Hard (wire)** | Rendering is trivial; the difficulty is Channel C blocking semantics + cancel + debug-gate integration (§3). |
| 12 | **Keyboard input** (`key-from-bytes`, ESC disambiguation, every binding) | `useKeyboard((key) => …)` with `key.name/ctrl/alt/shift` + `key.preventDefault()` | **Easier** | OpenTUI handles raw mode, CSI/SS3, the **Kitty keyboard protocol**, and mouse. Drop the manual 50 ms ESC-timeout disambiguation. Map each binding (Esc, Ctrl-C, Tab focus, j/k/g/G, PgUp/PgDn, m, ?, s/c/p/P, v, o, h/Backspace). |
| 13 | **Resize** (per-frame dimension fetch + relayout) | `renderer.on("resize", …)` + Yoga auto-reflow; `useTerminalDimensions()` | **Easier (delete)** | |
| 14 | **No-TTY fallback** (disabled handle → StdinRenderer) | CLI keeps the bb `StdinRenderer`/`--no-tui` path; only spawn Bun when interactive | **Easy** | Non-TTY/headless never launches the sidecar. |
| 15 | **Mouse** | Native (`onMouseDown/Up/Move/scroll`, drag) | **New capability** | Not parity — a free upgrade (scroll, click-to-select). |

Net: of the ~4,500 lines, the large mechanical chunks (compositor, frame loop, key parsing, resize,
scroll math) **shrink or disappear**. The *logic* worth keeping (aggregation, phase model, transcript
block model, theme semantics) is pure and ports to TS — or, better, **stays in Clojure** and is
emitted as compact snapshot events so it isn't duplicated.

---

## 5. Streaming tokens — the deep dive (explicitly requested)

**Source.** `escapement.invocation.llm_conversation` installs a `:delta-sink` (~line 708) that, when
`:stream?`, emits one `:llm/delta` transcript event per token chunk:
`{:event :llm/delta :data {:type :text-delta|:thinking-delta :text s :model m :invokeid id
:session-id sid :usage {…optional}}}`.

**Current path (in-process).** `event!` fast-paths `:llm/delta` → `fold-live-event` updates the
`:live` map (per-invoke → per-session counts/chars/text/status/tps) → marks `:render-dirty` → the
33 ms ticker repaints `live-pane-lines`. This decoupling exists *specifically* so a 120 t/s token
stream isn't throttled to render speed by taking the render lock per token.

**Sidecar path.** `:llm/delta` rows → (JSONL tail **or** WebSocket) → Bun reads them → `setStore(...,
produce(...))` updates the per-invoke/session store → SolidJS fine-grained reactivity updates only the
changed `<text>` → OpenTUI's frame loop paints at `targetFps`. **The decoupling we hand-built is now
intrinsic**: Solid batches signal writes and OpenTUI renders on its own clock, so token *arrival*
(wire) and *painting* (frame loop) are already independent.

**Proof it works.** `packages/solid/examples/session.tsx` streams message content char-by-char via
`setInterval(16ms)` into a `createStore`, inside a `<scrollbox stickyScroll stickyStart="bottom">`,
with a `<Show>`-gated in-flight cursor `<span>` — i.e. exactly our live panel's behavior, at 60fps,
already implemented in the framework's own examples.

**What to port (pure TS):** `live-agg`, `live-tps` (prefer LLM-reported `:output-tps`, else
`tokens/(last-ts−first-ts)`), the first-delta re-anchor (exclude time-to-first-token from t/s), and
the multi-session tournament grouping (group header + completion bar + indented child sessions +
"…+N more"). None of this touches rendering; it's arithmetic over the event stream.

**Throughput caveat:** this only holds over a **push/tail** transport. Do **not** try to stream
tokens by polling `POST /api :session/events` — it can't keep up. (This is also why the existing
poll-only browser debugger doesn't show token-level streaming.)

---

## 6. Work breakdown (phased)

Effort = engineer-weeks, rough, assuming familiarity with Solid + the escapement internals. Ranges
reflect genuine uncertainty (OpenTUI is pre-1.0; IPC edge cases dominate).

| Phase | Scope | Deliverable | Effort |
|---|---|---|---|
| **0. Transport** | Pick JSONL-tail vs WS; define wire schema (reuse transcript event shape); Bun-side reader; agent-side fan-out if WS. | Bun process prints a live event log from a real run. | 0.5–1.5 wk |
| **1. Scaffold + lifecycle** | Bun + `@opentui/solid` app skeleton; CLI `--tui=opentui` spawns/supervises the child; **TTY handoff**; capture agent stdout/err; clean teardown + terminal restoration on crash; keep `StdinRenderer` for non-TTY. | `escapement run --tui=opentui` opens a blank OpenTUI screen wired to a run; Ctrl-C exits cleanly. | 1–2 wk |
| **2. Read-only mirror (the showcase)** | Header/phases, LOG pane, and the **LIVE token streaming panel** (shimmer, bars, per-role/tournament rollups, sticky-bottom). | The "wow" demo; answers "can it stream tokens" (yes). | 1.5–3 wk |
| **3. Inspector + transcript** | Invocations list, chart-event view, status view, pagers, drilldown; transcript SENT/REPLY rendering; artifacts read from shared `session-dir`; optional syntax-highlight/diff upgrade. | Full read-only parity + drill-in. | 2–3 wk |
| **4. Interactivity** | Control (reuse existing EQL mutations: pause/step/continue/arm, `:ui.interrupt`/`:ui.quit`); **human-input** `RemoteUiRenderer` + modals (text/confirm/select/multi) + cancel + debug-gate integration. | Full interactive parity. | 2–4 wk |
| **5. Theming + polish + QA** | Semantic theme map, truecolor/256/16/NO_COLOR, per-role palette; resize/edge cases; side-by-side parity pass vs JLine TUI; docs. | Shippable behind the flag. | 1–2 wk |
| | **Total** | | **~8–15 wk** |

Plus ongoing: a **second front-end codebase in a second language** to maintain, and a release/packaging
story (below).

---

## 7. Risks, gotchas, open questions

- **Deployment regression (biggest).** Today: one bb artifact, `bbin install .`, no JVM, no native
  deps. After: + a Bun runtime, + `node_modules`, + a per-platform native `.so` (8 targets to ship
  or fetch), + two-process orchestration. The clean "single binary" story is gone for TUI users.
  Decide how the Bun bundle ships (vendored? fetched like the web bundle? `bun build --compile`
  single-file executable per platform?).
- **Bun dependency.** `engines.bun >= 1.3.0`. Node path is *experimental* (`node:ffi`) — don't rely
  on it. Contributors/users now need Bun.
- **TTY ownership / stdout.** Exactly one process can be in raw mode. The agent must go fully headless
  and its `*out*`/`*err*` (chart output, logs) must be captured and surfaced in the UI or a file —
  otherwise it corrupts the OpenTUI-owned screen. Today the TUI sidesteps this by writing to `*err*`
  in-process; that trick doesn't survive the split.
- **Crash safety.** If the Bun child dies mid-frame, the terminal can be left in raw mode / alt-screen
  with a hidden cursor. Need robust restoration (the bb parent should reset the TTY if the child dies
  abnormally). OpenTUI cleans up on its own `destroy`, but not on `SIGKILL`.
- **Human-input across the wire.** Blocking semantics, cancellation (Esc → reject `{:reason
  :cancelled}`), and the `human-input-active?` pause-gate must be preserved exactly. Latency must be
  imperceptible. This is the single most behavior-sensitive piece.
- **Two UIs during migration.** Either maintain the JLine TUI and the OpenTUI UI in parallel (double
  maintenance) or big-bang switch (risky). The flag approach (`--tui=opentui`) enables gradual cutover.
- **Architecture boundary.** `architecture_boundary_test.clj` forbids core→presentation static deps.
  The new agent-side glue (`RemoteUiRenderer`, the fan-out/WS) must live in the CLI/add-on layer
  behind `requiring-resolve`, like `--api-server` does — *not* in core. (The Bun app lives outside
  `src/` entirely, e.g. a new `tui-opentui/` workspace.)
- **OpenTUI maturity.** v0.3.2, pre-1.0, active churn (recent commits touch grapheme pool lifetimes,
  native handle guarding). Expect API drift; pin versions.
- **WebSocket-under-bb (if chosen).** http-kit is already the api-server; confirm its websocket
  (`as-channel`/`on-receive`/`send!`) works under bb/SCI before committing to the WS transport. The
  JSONL-tail transport avoids this question entirely.

---

## 8. Alternatives worth weighing

1. **Keep the JLine TUI.** It already does live streaming, inspector, modals, themes, tournaments —
   in-process, zero extra deps. OpenTUI buys richer rendering + mouse + less custom layout code, at a
   real deployment cost. If the JLine TUI is "good enough," the ROI is questionable.
2. **Lean on the existing browser UI** for rich visualization (it already renders sessions/events/
   artifacts + a live debugger + statechart viz), and keep JLine for the terminal. You already have a
   two-render-target story (Semantic-UI + a fulcro-tui RAD target).
3. **Scope OpenTUI to the showcase only** — build just the live streaming/tournament dashboard in
   OpenTUI (Phases 0–2) for demos, keep JLine for everyday runs. Best risk/reward if the motivation
   is "a beautiful live token dashboard."
4. **`@opentui/react` instead of Solid** — exists in the same repo. (The question specifies Solid;
   noted only for completeness. Solid's fine-grained reactivity is arguably the better fit for
   high-frequency token updates.)

---

## 9. Recommendation

The sidecar architecture is the *only* viable way to use OpenTUI here, and it fits the project's
existing add-on/client-server grain. Full parity is achievable in roughly **8–15 engineer-weeks**,
with the rendering being the *easy* part and the IPC + human-input + deployment being the cost
centers.

Before committing to a full port, **spike Phases 0–2 behind `--tui=opentui`**: JSONL-tail transport +
the live token streaming panel. It is the highest-value, lowest-risk slice, it directly validates the
streaming claim (which OpenTUI's own `session.tsx` already strongly implies), and it surfaces the
transport + TTY-handoff + lifecycle issues early — which is where the genuine unknowns live. Decide on
the remaining phases (inspector, human-input, theming) once the spike has de-risked the seam.

---

### Appendix: key source references

**Escapement (Clojure):**
- TUI facade / lifecycle: `src/escapement/tui.clj` (`start!` ~1477, `attach-session!` ~1583,
  `event!` ~1776, `render-frame!` ~761, ticker ~1537, `ask!` ~1929, `TuiRenderer` ~1945).
- TUI modules: `src/escapement/tui/{compositor,inspector,live,log,phase,theme,transcript}.clj`.
- HumanRenderer protocol + StdinRenderer: `src/escapement/invocation/human_input.clj:54-198`.
- Streaming source (`:delta-sink`): `src/escapement/invocation/llm_conversation.clj:708`.
- Transcript JSONL sink: `src/escapement/transcript.clj`.
- Debug controller: `src/escapement/debug/controller.clj`.
- HTTP/EQL surface + control mutations: `src/escapement/ui/server.clj`,
  `src/escapement/ui/resolvers.cljc`; proof `test/escapement/ui/live_control_http_test.clj`.
- Boundary rule: `test/escapement/architecture_boundary_test.clj`, `CLAUDE.md` "Layering".

**OpenTUI (`~/github/opentui`, v0.3.2):**
- Core exports/renderer: `packages/core/src/{index.ts,renderer.ts}`; FFI/native resolve
  `packages/core/src/{platform/ffi.ts,zig.ts}`; renderables `packages/core/src/renderables/`.
- Solid reconciler + bootstrap: `packages/solid/{index.ts,src/reconciler.ts}`; JSX intrinsics
  `packages/solid/jsx-runtime.d.ts`; hooks `packages/solid/src/elements/hooks.ts`.
- **Streaming template:** `packages/solid/examples/session.tsx`; scrolling
  `packages/solid/examples/components/scroll-demo.tsx`; input/forms `…/input-demo.tsx`,
  `…/textarea-*.tsx`, `…/tab-select-demo.tsx`.
