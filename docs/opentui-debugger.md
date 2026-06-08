# OpenTUI time-travel debugger — architecture

The time-travel debugger is an **opt-in** feature of the OpenTUI sidecar (`--tui=opentui` only). It
lets the user select a live LLM conversation, **re-enter the statechart at that conversation node and
continue forward in a new forked branch** — after optionally changing the LLM parameters
(provider/model, temperature, system prompt) and editing the conversation transcript at a chosen
turn. It also offers a **breakpoint mode** that runs the chart until just before the next LLM turn,
where the user can **step next / back across turns** and then **continue**.

The JLine in-process TUI and the browser/RAD web UI are out of scope; only the OpenTUI sidecar
gains a debugger.

> **Contract:** every frame and control op named here is specified — with exact field names and
> types — in [`docs/opentui-wire.md`](opentui-wire.md) §9. That section is the source of truth both
> halves build against; this doc is the architecture/flow narrative around it.

---

## 1. The four resolved decisions

These are the design choices that shape the whole feature (from the spec). Everything below is a
consequence of them.

1. **Re-run = re-enter & continue.** There is **no rollback primitive** in the statechart library.
   "Re-enter at a point" means: restore the working-memory **node-entry checkpoint that has the
   selected conversation node *in* the configuration** (post-entry, re-invokable — written by the
   **runner** post-macrostep, keyed by `{node-id, visit}`; `engine/store.clj`
   `save-node-entry-checkpoint!`), apply the overrides, and let the statechart run **forward**
   through downstream states. We never re-run the whole chart from zero.

   The load-bearing subtlety: the statechart library does **not** re-invoke a state that is already
   present in a restored configuration on resume (invocations start only for states *entered this
   macrostep*; resume restores a config without entering anything). A node-in-config checkpoint
   would therefore restore but never re-call the LLM. The fix is the engine **re-invoke-on-resume
   primitive** (`escapement.engine.reinvoke`, the single seam touching library invocation
   internals): on resume it seeds `::sc/states-to-invoke` with the invoking states present in the
   restored configuration and calls the library `run-invocations!`, so the conversation worker
   actually re-starts. This is a **general** engine fix (it also repairs crash-resume
   mid-invocation), not a debugger hack.

2. **Persistence = fork a new branch.** The original run is **immutable**. A re-run is a brand-new
   **branch session** with its own session id, its own transcript + artifact dirs, and recorded
   parentage: `{:parent <parent-session-id> :branch-point {:node-id :visit :turn}}`. The parent's
   transcript/checkpoint/artifacts are read-only inputs; nothing about it is mutated.

3. **Side effects = replay captured results by match.** On the branch, the deterministic prefix is
   restored from the checkpoint (no re-execution). The **changed LLM turn hits the provider live**.
   Tool calls are served from **captured results when they match** (same node/turn/tool + args);
   genuinely new (unmatched, post-divergence) side effects execute **live** and are **flagged** in
   the event stream. The flag rides on the existing `event` frame's `data` as
   `:replay/source "captured"|"live"` (wire §9.4); a destructive-tool guard is the documented
   mitigation for unmatched writes.

4. **Step/rewind granularity = turns within the conversation.** Turns are first-class and captured
   losslessly as EDN (`capture.cljc`, `nodes/<node-id>/<visit>/turns/<turn>/…`; transcript rows
   tagged `:transcript/{node-id,visit,turn}`). So **"next"** advances to the next turn, **"back"** is
   a cheap **pointer move** over captured turns, and re-running from an earlier turn re-issues from
   there. There is **no event-level chart rewind / undo of side effects**.

---

## 2. Two halves, one wire contract

```
            WebSocket (api-server /ws)
  ┌─────────────────┐  forward frames (event/phase/prompt/debug/        ┌──────────────────────┐
  │  AGENT (bb/SCI)  │  model-catalog/conversation)  ───────────────▶   │  SIDECAR (Bun/SolidJS)│
  │  escapement.*    │                                                  │  tui/opentui/         │
  │  headless engine │  ◀───────────  control / answer back-channel     │  owns the TTY         │
  └─────────────────┘    (rerun-from / arm-llm-breakpoint / turn-* /     └──────────────────────┘
                          request-* / continue)
```

The agent runs **headless**; the sidecar owns the TTY. Both build against the single wire contract
(wire §9) defined before either half is implemented, so the agent (tasks 002–006) and sidecar (tasks
008–012) proceed in parallel.

**Boundary:** all new agent glue lives in `escapement.ui.*` / `escapement.debug.*` and is reached
from `cli.clj` only via `requiring-resolve`, so `test/escapement/architecture_boundary_test.clj`
stays green (no new static core→add-on requires).

---

## 3. Agent-side mechanism

Re-uses existing machinery rather than inventing rollback. The pieces (forward-looking namespaces):

- **Re-invoke-on-resume primitive** (`escapement.engine.reinvoke`, engine core) — THE single seam
  reaching into the library's invocation internals. Given a restored working memory whose
  configuration contains invoking states with no live worker, it builds a processing-env, seeds
  `::sc/states-to-invoke` with exactly those states, and calls the library `run-invocations!`. The
  runner calls it on the `:resume?` path (`runner.clj`), so a resumed conversation node is actually
  re-invoked. General fix (also repairs crash-resume mid-invocation); behavior-pinned by
  `test/escapement/engine/reinvoke_test.clj`.
- **Node-entry checkpoint** (`engine/store.clj` `save-node-entry-checkpoint!`, written by `runner.clj`
  in `process-targeted-event!`) — a restorable checkpoint **locatable per LLM-conversation node-entry**
  (`node-id`/`visit`). Written **post-macrostep** when an invoking state is entered, so the snapshot
  has the conversation node **in** `::sc/configuration` (re-invokable), keyed by the same
  `{node-id, visit}` the capture layer stamps (read from the shared `:escapement/visit-counts` atom).
  This **replaced** the old in-processor pre-entry write, which captured the wrong (pre-entry) config.
  Falls back to the nearest `:latest` checkpoint when no node-entry file exists.
- **Branch fork** (`escapement.debug.branch`) — create a new session id seeded from the parent's
  node-entry checkpoint, with its own transcript/artifact dirs and parentage metadata
  `{:parent :branch-point}`. The seed wmem's `::sc/session-id` is rekeyed to the branch id (so the
  re-invoked worker posts back to the branch, not the parent). The parent is never mutated
  (decision 2 / R6).
- **Resume-safe env setup seam** (`runner.clj` `:chart-env-ready` ← chart var metadata
  `:escapement/on-env-ready`) — a chart's own `on-entry` registration (e.g. a multiplex chart
  registering its poet/judge sub-charts) does **NOT** re-fire on resume: the owning state is already
  in the restored configuration, so it is never re-entered. Any sub-charts the chart stashed in the
  registry at entry are therefore missing on a branch/crash resume, and a re-invoked multiplex/
  sub-chart node would then silently fail to resolve its child chart (the classic dead 3-line
  branch: `started → resumed → done`, nothing ran). The runner now invokes a **chart-declared**
  `:chart-env-ready` hook on **every** `run!` — fresh start AND resume — before start!/re-invoke, so
  that registration is idempotently re-applied. The CLI threads it from the chart var's
  `:escapement/on-env-ready` metadata; the runner also stashes it in the env
  (`:escapement/chart-env-ready`) so a downstream branch resume can reuse it. A `:runner/reinvoked`
  transcript event names the invoking states re-started on resume, so a dead branch is diagnosable.
- **Rerun engine entry** (`escapement.debug.rerun/rerun-from-checkpoint!`, engine core) — the clean
  seam that ties fork + `run! :resume? true` + the `:chart-env-ready` hook together and runs to
  completion. **Single-session**: fork at the conversation node, override it, resume its chart.
  **Multiplex (lifts the prior limitation)**: fork the **ROOT** at the multiplex parent state (e.g.
  `:composing`) with `:multi-session? true`, scope the override to the **child** conversation node
  (e.g. `:musing`); resuming re-invokes the multiplex (re-spawning every child), the override hits
  only the targeted child turn, and the downstream phases (judging, summary) re-run — so the **whole
  chart finishes** and is re-judged, not just one sub-chart in isolation. The debugger control
  surface (`rerun-from!`) exposes this via `:scope :root` + a caller-supplied `:root-branch-point`
  (mapping a child selection to its root re-entry point is a UI/wire concern). Tested by
  `test/escapement/debug/rerun_test.clj` and the `:scope :root` spec in
  `ui/rerun_from_integration_test`.
- **Override injection** (`invocation/llm_conversation.clj`, `llm.clj`) — the llm-conversation
  invocation consults a **debug-overrides** map on (re)entry/turn-assembly: `provider`/`model`,
  `temperature`, `system`, edited `messages`-prefix, resume-`turn`. These layer **node-over-alias**
  exactly like existing node params (R8). The env key for this (`:debug/overrides`) is defined once
  in `engine/env.cljc` (task 002).
- **Replay-aware layer** (`replay.cljc`) — restore the deterministic prefix (no re-run), let the
  changed turn hit the provider, serve tool results from captured EDN **by match** (reuse
  `replay/refine-turn` + a new tool-result-by-match helper), and **flag** unmatched/new side effects
  with `:replay/source "live"` (decision 3 / R7). The replay policy env key (`:debug/replay-policy`)
  is also defined once in task 002.
- **LLM-turn breakpoint** (`debug/controller.clj`, `invocation/llm_conversation.clj`) — the LLM
  worker is a **long-lived thread** (`drive-turn!`), not a queue event, so the existing per-queue
  pause gate (`engine/instrumented_queue.cljc`) cannot catch "before next LLM turn". A **second,
  finer gate** is added inside the worker turn loop, parked on the controller's
  arm-pause-before-next-LLM-turn flag, with turn next/back/continue state. The existing
  per-event gate is untouched.
- **Model catalog** (resolver in `escapement.ui.debug-control` or sibling) — enumerate aliases +
  expanded `{:provider :model}` targets from config (`config.clj` `:llm/aliases` +
  `llm/preferences.cljc`), pushed as the `model-catalog` frame (R10).
- **Control surface + WS** (`escapement.ui.debug-control`, `escapement.ui.ws-push`,
  `tui/opentui/sidecar.clj` `make-ws-handlers`) — `make-ws-handlers` dispatches the new `control`
  ops (`rerun-from`, `arm-llm-breakpoint`, `turn-next`/`turn-back`, `request-model-catalog`,
  `request-conversation`, debugger-`continue`) into `escapement.ui.debug-control`; new forward frames
  (`model-catalog`, `conversation`, extended `debug`, replay-marked `event`) are pushed via
  `ws-push/broadcast!`.

### 3.1 Live wiring (how the control surface reaches the running engine)

`make-ws-handlers` reconstructs everything it needs from the **shared control handle**
(`escapement.debug.control-handle`), which `cli.clj` fills in `run!`'s `:on-env-ready` callback with
`{:env :session-id :queue :controller}`. The agent-side debugger requires two keys on that live env,
both already provided by `runner/run!` (no extra cli wiring needed):

- **`::sc/statechart-registry`** — `rerun-from!` resolves the chart via
  `(sp/get-statechart registry chart-id)` with default `chart-id` `:escapement.runner/chart` (the key
  `runner/run!` registers under). If this is missing, `rerun-from!` returns nil and the branch
  **silently never starts** — so it must be present on the live env.
- **`:escapement/artifact-store`** — `request-conversation` and the replay layer read captured turns
  off it. The runner sets it whenever `:session-dir` is supplied (the cli always supplies one), so it
  is present on every live `--tui=opentui` run.

The sidecar reaches `escapement.ui.debug-control` and the `ws-push` publishers **only via
`requiring-resolve`** (resolved once inside `make-ws-handlers`), keeping the architecture boundary
green. `make-ws-handlers` takes a `:ws-hub` opt (wired from `cli.clj`); without it the debugger
control ops still run their controller-side effect but push no forward frame (nil-safe).

---

## 4. Sidecar-side mechanism

- **Conversation menu** (`ui/ConversationMenu.tsx`) — opened from the existing cursor+Enter seam in
  both `LivePanel.tsx` (an LLM row) and `Inspector.tsx` (an invocation row). First item is
  **Transcript** (the existing read-only pager); then **Re-run from here…** and
  **Break before next LLM**.
- **Debug form** (`ui/Debugger.tsx`) — an overlay reusing `Modals.tsx` select + text-input
  primitives: a **turn selector** (when >1 turn), editable **system prompt** + **messages**, a
  **model/alias dropdown** fed by the `model-catalog` frame, a **temperature** field, and **Run** +
  breakpoint controls (arm / next / back / continue).
- **Domain store** (`domain/store.ts`, `domain/types.ts`) — new slices/reducers: debug active,
  current branch, turn index, overrides draft, model catalog, editable conversation. The extended
  `debug` frame folds the active-branch banner + turn-index + breakpoint-armed state.
- **Wire decode** (`transport/wire.ts`) — decode the new forward frames; encode the new outbound
  control ops. Per wire §9.6 the router learns `model-catalog` + `conversation`; the existing `debug`
  decode picks up the new optional fields; outbound stays `answer` + `control`.
- **Keybindings** (`input/keybindings.ts`, `input/dispatch.ts`) — open the menu, navigate the form,
  emit the new control ops, respecting the existing modal > overlay > mission-control tiers.

---

## 5. End-to-end flow

### 5.1 Re-run a branch (select → menu → debug form → run)

1. **Select.** User moves the cursor to an LLM row (LIVE) and presses **`o`** ("open actions", LIVE
   pane only) → `ConversationMenu` opens for that conversation. **Enter** keeps its existing
   direct drill-into-transcript behavior; the menu is the additive path, not a replacement.
2. **Menu.** First item **Transcript** opens the read-only pager. **Re-run from here…** enters debug
   mode; the sidecar sends `request-model-catalog` and `request-conversation { invokeid }` →
   receives the `model-catalog` and `conversation` frames (wire §9.2).
3. **Choose a turn.** If the conversation has >1 turn, the **turn selector** lets the user pick the
   resume turn.
4. **Edit.** The user edits system prompt / message text, picks a model+provider from the dropdown
   (a catalog target sets both at once), and sets temperature. Edits accumulate in the overrides
   draft.
5. **Run.** The sidecar sends `rerun-from` (wire §9.1) with `{session-id, invokeid, node-id, visit,
   turn, overrides:{…, messages:[…]}}`. The `session-id` is the **source/parent** session, sourced
   sidecar-side from the `ESCAPEMENT_SESSION_ID` env var the agent exports at spawn (`sidecar.clj`);
   in a `escapement open` replay it is `""` and the back-channel send is a no-op (no live engine).
   The agent: forks a branch session from the pre-conversation checkpoint, applies the overrides +
   edited transcript prefix, and continues the chart forward.
6. **Follow the branch.** The agent pushes an extended `debug` frame with `mode:"branch-running"` and
   a `branch` object (wire §9.3). The sidecar re-roots its view onto the branch `session-id`, shows
   the active-branch banner (parent + branch-point), and follows the branch's live event stream.
   Replayed side effects arrive with `data["replay/source"]="captured"`; new/live ones with `"live"`
   and get a badge (wire §9.4).

### 5.2 Breakpoint mode (arm → break → step → continue)

1. **Arm.** From the menu, **Break before next LLM** → the sidecar sends `arm-llm-breakpoint`. The
   agent sets the controller flag and pushes `debug { breakpoint-armed:true }`.
2. **Break.** The chart runs until the worker turn gate; the agent parks the worker and pushes
   `debug { mode:"paused-at-turn", turn-index:N }`.
3. **Step.** `turn-next` advances one turn, `turn-back` moves the pointer back one (over captured
   turns); each pushes a refreshed `debug` snapshot with the new `turn-index`.
4. **Continue.** `continue` resumes the parked worker free-running (`make-ws-handlers` routes
   `continue` to the engaged turn gate); the agent pushes `debug { mode:"running" }`.

### 5.3 Keymap (as built)

Mission-control tier (no overlay/modal open):

| Key    | Action                                              | Notes                              |
|--------|-----------------------------------------------------|------------------------------------|
| `o`    | open Conversation menu for the selected LIVE LLM row | LIVE pane only; no-op if no target |
| `n`    | `turn-next`                                          | only while paused-at-turn          |
| `b`    | `turn-back`                                          | only while paused-at-turn          |
| `c`    | `continue` — turn gate when paused-at-turn, else per-event/step gate | dual meaning, one key |
| `P`    | `arm` (existing per-event arm)                       | distinct from menu's Break → `arm-llm-breakpoint` |
| Enter  | LIVE: drill into selected row's transcript (unchanged) | menu is additive, not a replacement |

Modal tier (routed via the composed `modal` hook in `main.tsx`, priority **menu → re-run form →
human prompt**):

| Surface          | Keys                                                       | Result                          |
|------------------|------------------------------------------------------------|---------------------------------|
| ConversationMenu | ↑/↓/j/k/←/→ move (wrap), Enter select, Esc cancel           | Transcript / Re-run… / Break / Cancel |
| RerunForm        | Tab cycle fields, ↑/↓ move/edit, type to edit, Ctrl-R run, Esc cancel | Run → `rerun-from` send |

Menu selections (the menu self-closes on select): **Transcript** → inspector open-then-drill (same as
Enter); **Re-run…** → `request-conversation` + `request-model-catalog`, then the form opens once the
`conversation` frame arrives; **Break before next LLM** → `arm-llm-breakpoint`.

---

## 6. Boundaries, edge cases, tests

- **Unmatched post-divergence side effects** (the new LLM output triggers tool calls with no captured
  result) are the single biggest correctness nuance: they execute **live** and are flagged
  `:replay/source "live"`; a destructive-tool guard (config/allowlist) can withhold or confirm
  file/shell writes. Documented, not silently swallowed.
- **Pausing the long-lived worker** happens only at the **turn boundary** (before issuing a turn),
  reusing the promise-gate pattern from `instrumented_queue.cljc`; no mid-tool pausing.
- **session-id switches mid-stream** when a branch starts; the UI treats `session-id` as opaque
  (existing convention) and re-roots on the explicit `branch`/`debug` frame.
- **Tests.** `bb test` covers the agent half. Beyond the isolated unit tests (branch fork, override
  injection, replay policy, breakpoint/turn-nav, wire round-trip), the load-bearing **integration**
  tests that prove the feature actually re-runs end-to-end:
  `engine/reinvoke_test` (re-invoke primitive pinned against the library),
  `debug/reinvoke_resume_test` (fork → resume → conversation worker re-invoked & ran, + crash-resume
  regression), `debug/branch_continue_test` (fork → resume → advances into a downstream state),
  `debug/override_reinvoke_test` (overrides reflected on the re-issued turn),
  `debug/replay_continuation_test` (matched=captured/unmatched=live through the real worker),
  `ui/rerun_from_integration_test` (`rerun-from!` live in-process, single + multiplex-child),
  `engine/store_roundtrip_test` (checkpoint EDN round-trip + `:session/<uuid>` poison + corpus scan),
  `debug/corpus_resume_test` (real `.escapement/` corpus: dead branch reproduced + readable session
  re-invokes), and `debug/cli_selfrun_resume_test` (live gemma self-run + `--resume` + `rerun-from`,
  env-gated). These keep `architecture_boundary_test` green;
  `bb opentui-test` + `bb opentui-build` cover the TS half (unit + snapshot); `bb sanity` stays
  green.

---

## 7. Out of scope (this iteration)

- The JLine TUI and browser/RAD web UI (no debugger there).
- Re-running from a read-only `escapement open` replay with no live engine (offline boot from stored
  artifacts) — possible follow-on; this targets a LIVE running session's in-process engine.
- True per-event chart rewind / undo of side effects (no library rollback; "back" is turn-pointer +
  replay-forward only).
- Re-running non-LLM invocation types (multiplex, human-input) as debug targets — only
  `llm-conversation` nodes are debug targets here.
- New provider integrations or changes to LLM resilience/failover semantics.
