# OpenTUI sidecar wire schema

This is the **canonical JSON wire contract** between the Escapement agent (Clojure / Babashka,
`escapement.ui.*` add-on) and the OpenTUI sidecar (Bun / SolidJS, `tui/opentui/`). It is the
authoritative spec that tasks 002 (WS push), 003 (RemoteUiRenderer), 004 (CLI spawn), and
005/006 (UI clients/domain) build against. Keep it **additive**: prefer new fields over
restructuring.

The schema is **transport-agnostic**. The chosen transport is recorded below; everything else in
this doc holds regardless of how bytes move.

---

## 1. Transport decision (de-risked in task 001)

**WebSocket (http-kit, bidirectional) — CHOSEN.**

http-kit's WebSocket works under Babashka/SCI. A throwaway bb proof (`org.httpkit.server/as-channel`
with `:on-open` / `:on-receive` / `:on-close` + `send!`, plus a raw RFC-6455 client handshake)
produced:

```
[server] open, channel: org.httpkit.server.AsyncChannel
[client] handshake response status line: HTTP/1.1 101 Switching Protocols
[client] upgraded? true
[server] received: {"k":"v"}      ; masked client frame decoded server-side
[client] server received message: {"k":"v"}
[server] close :going-away
```

bb's `org.httpkit.server` publics include: `as-channel`, `send!`, `close`, `on-close`,
`run-server`, `server-port`, `with-channel`, `websocket-handshake-check`,
`send-websocket-handshake!`, `sec-websocket-accept`. There is **no** `websocket?` predicate
exposed (it exists in JVM http-kit) — do not call it under bb; rely on the upgrade headers or
treat any `as-channel` channel as a candidate and gate on the request's `Upgrade` header.

- **Forward (agent → UI)** transcript event stream, phase snapshots, and human-input prompts:
  **WebSocket text frames, one JSON object per frame.**
- **Back-channel (UI → agent)** control ops, human-input answers, interrupt/quit:
  **same WebSocket** (bidirectional JSON frames). The existing transit EQL `POST /api`
  (`escapement.control/*` mutations) remains available as a secondary/fallback for control, but
  the WS message forms below are the primary path. No new transit dependency is forced into the
  Bun bundle.

**Fallback (NOT needed, documented for completeness):** had WS failed under bb, the forward stream
would be Server-Sent Events / chunked HTTP (`text/event-stream`, each `data:` line = one envelope
below) and the back-channel would be the transit EQL `POST /api` mutations plus a new
`escapement.human/answer` mutation. Because the envelope is transport-agnostic, the schema would not
change — only the framing. **WS is chosen; downstream tasks must not re-litigate this.**

---

## 2. Encoding & serialization rules

- **Encoder:** JSON (UTF-8). The agent already writes the transcript as JSONL via
  `cheshire` (`escapement.transcript`), so forward events are produced JSON-native. The sidecar
  decodes with `JSON.parse`. **Not transit** on the hot path.
- **One JSON object per frame / per line.** No framing beyond the WS frame (or the JSONL newline
  for the replay fixture).
- **Keywords → strings.** Clojure keywords serialize to their **name without the leading colon**.
  Namespaced keywords keep the namespace with a `/`:
  - `:llm/delta` → `"llm/delta"`, `:text-delta` → `"text-delta"`, `:end_turn` → `"end_turn"`.
  - The UI decodes the string back to a discriminant as-is (string compare; it does **not** need a
    keyword type). To reconstruct a Clojure keyword agent-side, strip a leading `:` if present and
    `keyword` the rest.
  - **Caveat:** some agent-side maps stringify a keyword *value* WITH its colon (e.g.
    `runner/started` `data.session-id` is `":session/<uuid>"` and `chart-id` is
    `":escapement.runner/chart"`), because those values were `pr-str`/`str`'d keywords upstream,
    whereas `llm/*` `data.session-id` is `"session/<uuid>"` (no colon). **The UI must treat
    `session-id` as an opaque string and compare for equality only** — never parse it. Downstream
    tasks: do not depend on colon presence.
- **Map keys** are emitted as the keyword/string name (e.g. `"output-tokens"`, `"io/ref"`,
  `"config-after"`). Hyphenated names are preserved (`first-ts`, not `firstTs`). The UI keeps the
  hyphenated keys.
- **`nil` → JSON `null`.** Absent vs. null are both treated as "unset" by the UI.
- **Timestamps** are integer **epoch milliseconds** (`System/currentTimeMillis`), under `ts` at the
  envelope top level (and never re-encoded as strings). `java.util.Date` values, if any, are encoded
  as their `.getTime` long (see `transcript/sanitize-value`).
- **`:llm/delta` data is all JSON-trivial scalars** — confirmed against a live `gemma3:1b` run:
  `{"type":"text-delta","text":"Line","model":"gemma3:1b","invokeid":"planner","session-id":"session/…"}`.
  No nested objects, no functions, no dates. A delta is therefore a tiny standalone envelope safe to
  fan out without touching the runner's render/processing locks (that non-blocking fan-out is task
  002).
- **Unserializable values** never reach the wire: the agent's transcript sink emits a
  `transcript/serialize-error` row + a `pr-str`-sanitized retry instead of a half line. The UI should
  tolerate (skip / log) a `transcript/serialize-error` envelope.

---

## 3. Forward event envelope (agent → UI)

Every forward transcript event is one envelope. It **mirrors the on-disk transcript line shape**
(which is already this shape — see `tui/opentui/test/fixtures/haiku-sample.jsonl`), so the WS push, the
JSONL replay, and snapshot tests all consume the identical structure:

```jsonc
{
  "kind":  "event",         // discriminant for the sidecar's frame router (see §7)
  "seq":   12,              // monotonic long, single-writer transcript counter
  "ts":    1780798849355,   // epoch ms
  "event": "llm/delta",     // the transcript event keyword as a string
  "data":  { /* event-specific, see below */ }
}
```

Notes:
- `seq` and `ts` are injected by the single-writer transcript thread; `seq` is **monotonic and
  gap-free per session** — the UI uses it for ordering and de-dup on reconnect (request "events since
  seq N").
- The raw transcript line **omits `"kind"`** (it is `{"event","seq","ts","data",…}` only). The WS
  push wraps each line by adding `"kind":"event"`; the **replay fixture lines also include
  `"kind":"event"`** so the UI's decoder has a single uniform path. (Some transcript lines also carry
  bookkeeping keys like `"transcript/node-id"`, `"transcript/visit"` at the top level — the UI may
  ignore any top-level key it does not recognize.)

### 3.1 Event types the UI consumes

Grounded in the JLine TUI's `entries-for` (`src/escapement/tui.clj:175`) and `fold-live-event`
(`tui.clj:1656`) plus the inspector (`tui/inspector.clj`). `data` fields shown are those the UI
reads; events may carry more (ignore extras).

**Runner lifecycle** (`source` → debug/error lane):

| event | `data` keys the UI reads |
|---|---|
| `runner/started` | `session-id` (string, may have leading colon), `chart-id`, `resume?` |
| `runner/start-config` | `config` (vector of strings, e.g. `["run","planning"]`) |
| `runner/event-processed` | `event-name`, `config-before` `[..]`, **`config-after` `[..]`**, `entered` `[..]`, `exited` `[..]`, `event-data` `{}` |
| `runner/done` | `final-config` `[..]`, `status` |
| `runner/aborted` | `reason` |
| `runner/error` | `message` |
| `runner/tick` | *(suppressed by the UI)* |

`config-after` is the **authoritative active-config signal**: the UI updates its phase model from
the last seen `config-after` (see §4). `runner/started`, `runner/done`, `runner/event-processed`
carry `session-id`.

**LLM stream / turn** (`source` → per-`invokeid` hue):

| event | `data` keys the UI reads |
|---|---|
| `llm/start` | **`invokeid`**, **`session-id`**, optional `model` |
| `llm/delta` | **`type`** (`"text-delta"` \| `"thinking-delta"`), **`text`**, `model`, **`invokeid`**, **`session-id`**, optional `usage.output-tokens` |
| `llm/request` | `invokeid`, `model`, `n-messages`, `user-blocks` `[{type,text}]`, `system-preview`, `io/ref` |
| `llm/response` | **`invokeid`**, `session-id`, **`content`** `[{type,text|thinking|name+input}]`, **`stop-reason`**, `usage.{input-tokens,output-tokens}`, `output-tps`, `elapsed-ms`, `model`, `io/ref`, `n-blocks` |
| `llm/error` | `message`, `invokeid` |
| `llm/model-down` | `model`, `message`, `category`, `remaining`, `invokeid`, `session-id` |
| `llm/tool-result` | `tool`, `is-error` (bool), `content-preview`, `invokeid` |
| `llm/worker-exit` | **`invokeid`**, **`reason`**, `session-id` |
| `llm/user-message` | `text`, `invokeid` |
| `llm/context-warning` | `used-frac`, `invokeid` |
| `llm/model-policy-empty` | `policy`, `strict?`, `invokeid` |
| `llm/retry` | `model`, `category`, `attempt`, `max-retries`, `invokeid` |

`invokeid` is the live-panel/scrollback **row key**. Parallel multiplex children **share one
`invokeid`** (e.g. every `judge1` child); `session-id` disambiguates concurrent siblings — the live
panel groups by `invokeid` then nests `session-id`. A live row is `:waiting` on `llm/start`,
`:streaming` on `llm/delta`, `:done` on `llm/response`, dropped/`:done|:error` on `llm/worker-exit`.
tok/s re-anchors `first-ts` to the **first delta** (not `llm/start`) so the rate measures generation,
not time-to-first-token; prefer `llm/response`'s `output-tps` when present.

**Human input** (`source` → human lane; see also §5):

| event | `data` keys |
|---|---|
| `human-input/start` | `kind` (`"text"`\|`"select"`\|`"multi-select"`\|`"confirm"`\|`"progress"`\|`"custom"`), `invokeid`, optional `prompt` |
| `human-input/answer` | `kind`, `invokeid`, `answer` (scalar/array/bool, kind-dependent) |
| `human-input/cancelled` | `{}` |
| `human-input/error` | `message` |

These transcript events are **observational** (they appear in scrollback). The **interactive**
prompt/answer round-trip uses the dedicated `prompt`/`answer` messages in §5 (the agent blocks a
worker on the answer); a `human-input/answer` event is then also emitted for the log.

**Checkpoint / debug:**

| event | `data` keys |
|---|---|
| `checkpoint/written` | `session-id` *(UI: no scrollback line; may flash a status)* |
| `debug/awaiting-quit` | `msg` |
| `debug/awaiting-step` | `event-name`, `external?` |

Any unknown `event` string → the UI renders a default one-line `"<event> <pr-str data>"` row
(mirrors `entries-for`'s default branch) and otherwise ignores it.

---

## 4. Phase / config snapshot (agent → UI)

**Decision: the UI derives phase locally from the event stream; the agent additionally sends an
explicit `phase` snapshot on connect and on config change.**

- The **active config** is already on the wire: every `runner/event-processed` carries
  `config-after`, and `runner/start-config` carries the initial `config`. The UI keeps the last seen
  config as the active phase — no chart needed for the basic header.
- On **connect/reconnect** (and to render breadcrumb/siblings without replaying every event), the
  agent sends a compact snapshot:

```jsonc
{
  "kind": "phase",
  "ts": 1780798849608,
  "config": ["run", "route-planner"],     // active leaf-path config (strings)
  "breadcrumb": ["run", "route-planner"],  // ancestor chain for the header strip
  "siblings": ["planning", "route-planner", "aborted"]  // sibling states of the active leaf
}
```

`breadcrumb` and `siblings` are **optional** (omit if the agent has no cheap chart access).
If the UI needs full chart structure (e.g. the visualizer), it fetches the chart **once** via the
existing EQL `POST /api` (`:chart/definition` resolver) and computes breadcrumb/siblings locally —
the `phase` message is the fast path, the EQL chart is the authoritative fallback. Pick the `phase`
message for the header; reserve the EQL chart for the visualizer view.

---

## 5. Human-input prompt & answer

### 5.1 Prompt (agent → UI)

When a `:human-input` invocation enters, the `RemoteUiRenderer` (task 003) serializes the prompt and
**parks the chart worker on a promise** until the matching answer arrives (mirrors
`TuiRenderer/ask!`). `opts` is the **flat HumanRenderer param map** (the chart-author keys —
`prompt`, `options`, `default`, etc.; see `human_input.clj:54`).

```jsonc
{
  "kind": "prompt",
  "prompt-id": "ask-name#1",     // unique per prompt; UI echoes it in the answer
  "invokeid": "ask-name",        // the invocation id (also the transcript invokeid)
  "type": "text",                // "text" | "select" | "multi" | "confirm"  (= human-input :kind, normalized: :multi-select → "multi")
  "opts": {
    "prompt":  "What's your name?",
    "options": [{"label": "Blue",  "value": "blue"},
                {"label": "Green", "value": "green"}],   // select/multi only
    "default": true                                       // confirm only
  }
}
```

`type` mapping from the chart `:kind`: `:text`→`"text"`, `:select`→`"select"`,
`:multi-select`→`"multi"`, `:confirm`→`"confirm"`. (`:progress`/`:custom` are not interactive modals;
`:custom` renders agent-side, `:progress` ends immediately — neither produces a `prompt` message.)

### 5.2 Answer (UI → agent)

```jsonc
{
  "kind": "answer",
  "prompt-id": "ask-name#1",   // MUST match the prompt's id
  "value": "blue"              // type-dependent (see below)
}
```

`value` by `type`:
- `text` → string (`""` allowed).
- `select` → the chosen option's **`value`** (scalar), not its label.
- `multi` → an **array** of chosen option `value`s (`[]` = none).
- `confirm` → boolean.

**Cancellation** (UI closes the modal with Esc):

```jsonc
{ "kind": "answer", "prompt-id": "ask-name#1", "cancelled": true }
```

The agent resolves the parked promise's rejection with `{:reason :cancelled}`; the worker posts the
chart's `:on-cancel-event` (default `:human.cancelled`) and emits a `human-input/cancelled` event. A
cancelled modal therefore maps to **interrupt** semantics for that invocation (spec R3/R13).

**Binding:** the answer travels as a WS `answer` message keyed by `prompt-id` to the parked promise.
(Secondary path: an `escapement.human/answer` EQL mutation carrying the same `{prompt-id, value |
cancelled}` — task 003 may add it for the transit fallback, but WS is primary.)

**Pause-gate:** the agent already treats human-input as pause-relevant (`human-input-active?`); the
renderer integrates with the debug pause gate so a paused session still delivers prompts and accepts
answers (task 003).

---

## 6. Control messages (UI → agent)

Live debugger + interrupt/quit. Primary form is a WS `control` message; each maps 1:1 to an existing
`escapement.control/*` op (the transit EQL `POST /api` mutation is the documented fallback).

```jsonc
{ "kind": "control", "op": "pause" }
{ "kind": "control", "op": "step",     "n": 1 }   // n optional, default 1 (step-budget bump)
{ "kind": "control", "op": "continue" }
{ "kind": "control", "op": "arm" }                 // arm-pause-on-next-external
{ "kind": "control", "op": "ui-interrupt" }        // Esc — interrupt current activity (:ui.interrupt)
{ "kind": "control", "op": "ui-quit" }             // Ctrl-C — quit the run (:ui.quit)
```

`op` → agent action:
- `pause` → `escapement.control/pause`
- `step` → `escapement.control/step` (with `n`)
- `continue` → `escapement.control/continue`
- `arm` → `escapement.control/arm-pause-on-next-external`
- `ui-interrupt` → forward `:ui.interrupt` to the runner (task 004 wires the flag → event)
- `ui-quit` → forward `:ui.quit` (clean teardown + TTY restore is task 004)

**Agent→UI control op (the one exception to UI→agent direction):**

```jsonc
{ "kind": "control", "op": "run-finished", "final-config": "[:done]" }  // chart reached final-config under keep-alive
```

- `run-finished` is pushed ONCE by the agent (via `ws-push/broadcast!`) when a chart reaches
  final-config and keep-alive is active (`--keep-alive`, default on for interactive-TTY runs). It is
  the only `control` frame that flows agent→UI. `final-config` is a `pr-str` of the final
  configuration vector (informational; the UI may ignore it).
- On receipt the sidecar keeps the renderer **live** (it does NOT tear down on this frame or on WS
  close) and overlays a `✓ Run finished — press Ctrl-C to quit` banner above the footer; the
  LIVE/LOG panes + inspector stay browsable. The Bun process exits only on the user's Ctrl-C, which
  sends `ui-quit` back over this same channel.

Live debugger **reads** (`paused?`, `step-budget`, `live-configuration`, `pending-events`) come from
the existing live resolvers via EQL `POST /api` (poll) — or, optionally, the agent may push a
`debug/awaiting-step` / `debug/awaiting-quit` forward event (§3.1) on state change so the UI need not
poll. Forward push for these is preferred; EQL read is the fallback.

**`debug` snapshot (agent → UI, implemented for `--debug --tui=opentui`, task 014).** When a debug
controller is active the agent pushes a `debug` forward frame (a sibling of the `phase` snapshot —
remembered on the hub and re-sent on connect/catch-up) on initial auto-pause and after every
pause/step/continue/arm op, so the UI's **PAUSED banner** + Inspector **Status** view stay live
without polling:

```jsonc
{ "kind": "debug", "paused": true,  "step-budget": 0 }   // halted
{ "kind": "debug", "paused": false, "step-budget": 1 }   // stepping (one event budgeted)
{ "kind": "debug", "paused": false, "step-budget": 0 }   // running
```

`paused` mirrors `escapement.debug.controller/paused?`; `step-budget` is the controller's remaining
budget. An optional `config` (active states) field may accompany it. The UI folds this into
`state.debug` (`reduceDebug`); `kind:"debug"` is part of the forward decode path. It is emitted
**only** when `--debug` accompanies `--tui=opentui` (a controller exists); a plain `--tui=opentui`
run never sends it and `state.debug` stays `null`.

---

## 7. Frame routing (sidecar)

The sidecar dispatches each inbound frame on top-level `"kind"`:

| `kind` | direction | handler |
|---|---|---|
| `event` | agent→UI | fold into the event store (§3) |
| `phase` | agent→UI | update the phase/header model (§4) |
| `prompt` | agent→UI | open the matching human-input modal (§5.1) |
| `answer` | UI→agent | reply to a parked prompt (§5.2) |
| `control` | UI→agent | control / interrupt / quit (§6) |

Outbound from the UI: only `answer` and `control`. Everything else flows agent→UI.

---

## 8. Recorded-JSONL replay fixture format

`tui/opentui/test/fixtures/*.jsonl` — **one forward envelope per line** (§3 shape, including
`"kind":"event"`; `phase`/`prompt` lines may also appear to exercise those routes). This is what the
UI's offline **replay dev mode** (task 005) and the **snapshot tests** (task 016) consume: feed the
lines through the same decoder + store the live WS path uses, in `seq` order, to get a deterministic
render with **no live agent and no model**.

- Lines are ordered by `seq` (gap-free within a session).
- A replay run is exactly the byte stream of a real transcript with each line wrapped to add
  `"kind":"event"` (the wrapper is what the WS push prepends; the fixture pre-wraps it so replay and
  live share one path).
- `tui/opentui/test/fixtures/haiku-sample.jsonl` is the canonical sample: a real `gemma3:1b` haiku
  tournament transcript (start → request → 28 text deltas → response with usage/tps → worker-exit →
  event-processed/config transitions → done), plus appended representative lines the short real run
  did not exercise: a `llm/tool-result`, a `llm/error`, a second concurrent `invokeid` to exercise
  multi-session grouping, and a human-input `prompt`/`answer`/`human-input.answer` round-trip.
