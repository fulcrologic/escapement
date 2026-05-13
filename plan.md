# Statechart-Driven Autonomous Coding Agent — Plan

> Project renamed to Escapement on 2026-05-13; namespaces `escapement.*`. References to `deep-cookie` below are retained as historical record.

## Context

Greenfield project at `/Users/tonykay/fulcrologic/statechart-agents/`. A coding agent whose **control flow is a statechart**, not a free-form LLM loop. An LLM conversation is bound to a (typically compound) chart state via the statechart library's invocation mechanism. While that state is active, the LLM is alive and can interact with the world through tools; when the chart leaves the state, the conversation is killed.

Two kinds of tools are exposed to the LLM:

- **Real tools** (read file, run shell, eval Clojure, …) — results loop back into the conversation. The LLM uses these to gather information and act on the world. The chart never sees these calls.
- **Event tools** — one synthetic tool per event the binding declares allowed. When the LLM calls one, the runner posts the corresponding event (with the tool args as event data) onto the chart's event queue. The tool's "result" returned to the LLM is just an ack so the turn continues.

This gives us:
- **Async LLM execution** — the LLM runs in its own worker; the chart processes events as they arrive.
- **Parallel regions** — each region with its own `(invoke …)` has its own conversation, worker, and event-tool prefix.
- **Reproducibility / safety** — chart-author defines the LLM's vocabulary (allowed events) and the tools it can use. The LLM can only influence chart progression by calling event-tools the chart-author exposed.
- **Visibility** — every LLM call, tool call, transition, and event is a JSONL event. Full replay possible.
- **Backend portability** — dev/test against `claude -p` (uses Max subscription); swap in Anthropic API or other vendors later via one protocol.
- **Resumable** — working memory persisted atomically (write-temp → rename) after every event-processing pass. On resume the chart re-enters its configuration, which re-spawns invocations; processors are written to be idempotent on re-entry.

## Decisions (settled)

- **Runtime**: Babashka for the agent process and CLI. JVM for tests. Charts and code live in `.clj`/`.cljc`.
- **Use the statechart library's invocation mechanism.** Our LLM binding is a custom `InvocationProcessor` for `:type :llm-conversation`. Lifecycle (spawn on entry, kill on exit, parent-event routing) is provided by the library. The spike proved it works under bb.
- **No SCI layer beyond bb itself.** Charts are regular namespaces loaded via `require` (or `load-file` for hot reload).
- **Custom event queue, working-memory store, execution model, registry.** We implement the library's protocols ourselves rather than using `simple/simple-env` (it transitively pulls `promesa`, which crashes under bb).
- **Atomic checkpoint persistence.** After every event-processing pass, working memory is serialized as EDN and written via temp-file + `rename`. Resume = load latest checkpoint, restore configuration, library re-enters states, processors re-start.
- **LLM-as-event-source.** The LLM does not return a structured value; it emits chart events by calling event-tools. Multiple events per turn is natural (fan-out). One event causes a chart transition; the conversation continues unless the transition leaves the bound state.
- **LLM protocol shape**: mirrors Anthropic Messages API — `messages`, `content` blocks (`text`, `tool_use`, `tool_result`), `system`, `tools`, `stop_reason`. API backend is near-passthrough; `claude -p` adapter translates.
- **`claude -p` invocation**: stateless, `--output-format json` (investigate `stream-json` for tool-use), full transcript sent each turn. Use `--resume <session-id>` as a *prefix-continuation* optimization when a new turn extends the same prefix.
- **Caching**: Anthropic `cache_control` markers carried in the protocol. API backend honors them; `claude -p` uses `--resume` as the coarse equivalent.
- **Conversations**: stored inside the invocation worker's state, not the chart's data model. The data model holds chart-domain data only.
- **Guards**: pure predicates over the data model and event data. LLM-driven branching happens by the LLM choosing which event-tool to call.
- **Bad tool-use**: tool_use that fails Malli validation gets a corrective `tool_result {is_error: true, ...}` and the LLM tries again. One retry, then the invocation fires `:llm.error` and exits.
- **Authoring**: Clojure code, not raw EDN. IDE help + symbol references.
- **Transcript**: JSONL only, append-only, single-writer. Events: `:llm/request`, `:llm/response`, `:tool/call`, `:tool/result`, `:event-tool/fired`, `:chart/transition`, `:chart/guard-result`, `:chart/state-entered`, `:checkpoint/written`, `:invocation/started`, `:invocation/killed`.

## Architecture

```
+----------------------------------------------------------+
|  bb entry: deep-cookie.cli                                |
+----------------------------------------------------------+
                       |
                       v
+----------------------------------------------------------+
|  Driver loop (deep-cookie.runner)                         |
|   - drains event queue through chart processor            |
|   - checkpoints working memory after each pass            |
|   - terminates when queue empty AND no live invocations   |
+----------------------------------------------------------+
   |              |              |
   v              v              v
 Chart        Custom         Custom InvocationProcessors
 (statechart) {queue,         - :llm-conversation
              store,            (one worker per active invoke)
              exec model,     - (future: :tool, :sub-chart)
              registry}
                            |       |
                            v       v
                       LLMBackend  Tool registry
                        |       |   (real tools)
                        v       v
                     claude-p   api
                       |
                       v
              Transcript JSONL sink (single writer)
```

## Namespaces

### Engine (replaces `simple/simple-env` for bb compatibility)
- `deep-cookie.engine.queue` — `EventQueue` impl (in-process FIFO, delayed sends).
- `deep-cookie.engine.store` — `WorkingMemoryStore` with atomic file checkpointing.
- `deep-cookie.engine.exec` — `ExecutionModel` impl (lambda style).
- `deep-cookie.engine.env` — assembles env map; registers our custom processors.
- `deep-cookie.engine.testing` — JVM-only test harness (mocked LLMBackend, sync drive, transcript capture).

### LLM
- `deep-cookie.llm.protocol` — `LLMBackend` defprotocol (`send-turn`).
- `deep-cookie.llm.types` — Malli schemas for request/response/content-blocks/tool-defs.
- `deep-cookie.llm.claude-p` — shell-out adapter, synthesizes Anthropic-shape content blocks from `:result`, tracks `:session_id` for `--resume`.
- `deep-cookie.llm.api` — Anthropic Messages API backend (stub for v0, honors `cache_control`).
- `deep-cookie.llm.cache` — local replay cache (dev opt-in).

### Tools
- `deep-cookie.tools.protocol` — `Tool` protocol (`schema`, `invoke`) + keyword registry.
- `deep-cookie.tools.builtin` — `:fs/read`, `:fs/write`, `:fs/edit`, `:shell/run`, `:repl/eval`.

### Invocation processor (the heart)
- `deep-cookie.invocation.llm-conversation` — `InvocationProcessor` for `:type :llm-conversation`.
  - `start-invocation!`: spawn a worker thread/future for the conversation; build the Anthropic tools list from `:real-tools` (looked up in the registry) and `:event-tools` (synthesized from `:allowed-events`).
  - Worker loop: send messages → on `tool_use`, dispatch (real → execute, event → post chart event with `(env/parent-session-id env)` as target; both produce a `tool_result` back into the conversation) → continue until `stop_reason = end_turn` and the parent's awaiting either another `Send` from the chart (push more user-message) or exits the bound state.
  - `stop-invocation!`: cancel the worker, drain pending tool calls, write `:invocation/killed`.
  - `forward-event!`: chart-to-LLM messages (e.g. `(send {:event :tell.llm ...})` while inside the bound state) translate to appending a user message to the live conversation.
  - Idempotent on re-entry: if the chart re-enters the bound state on resume, a fresh worker is spawned with the conversation state pulled from checkpointed data (or restarts cleanly if not persisted).

### Chart authoring
- `deep-cookie.chart.helpers` — element wrappers:
  - `(llm-conversation {:id :main :system "..." :real-tools [:fs/read :shell/run] :allowed-events [{:event :approve :data-schema X} {:event :reject :data-schema Y}] :initial-user-message "..."})` → expands to `(invoke {:type :llm-conversation :id "..." :params ...})`.
  - `(tell-llm {:expr (fn [env data] "next user message text")})` → expands to a `(send …)` targeting the active invocation, used inside the bound state to push another user message into the live conversation.

### Glue
- `deep-cookie.transcript` — single-writer JSONL sink.
- `deep-cookie.runner` — driver loop: process events, checkpoint, repeat. Terminates when queue is quiescent AND no live invocations remain.
- `deep-cookie.cli` — bb entry. `bb -m deep-cookie.cli run my.charts/my-agent --input foo.edn [--resume <checkpoint>]`.

## Authoring example

```clojure
(state {:id :scan-codebase :initial :scanning}

  (invoke {:type :llm-conversation
           :id   "scanner"
           :params (fn [env data]
                     {:system          "You are scanning for bugs. Call :found-bug per finding, then :scan-complete."
                      :real-tools      [:fs/read :shell/run]
                      :allowed-events  [{:event :found-bug
                                         :data-schema [:map [:file string?] [:line int?] [:summary string?]]}
                                        {:event :scan-complete
                                         :data-schema [:map [:summary string?]]}]
                      :initial-user-message (str "Scan repo at: " (:repo-path data))})})

  (state {:id :scanning}
    (handle :found-bug
      (fn [env data]
        [(ops/assign :findings (conj (:findings data []) (-> data :_event :data)))]))
    (on :scan-complete :done))

  (final {:id :done}))
```

The chart never handles `tool_use` for `:fs/read` or `:shell/run`. Those are invisible. The chart only sees `:found-bug` and `:scan-complete`, because those are the event-tools we exposed.

## Spike findings folded in (see SPIKE_FINDINGS.md)

- Avoid `com.fulcrologic.statecharts.simple` and `com.fulcrologic.statecharts.testing` under bb — they transitively load `promesa`, which crashes SCI. Build env manually; build our own testing helpers.
- Custom `InvocationProcessor` works under bb (`spike/custom_invocation.clj` proves it).
- `claude -p --output-format json` returns flat `:result` text, not Anthropic content blocks. Adapter must synthesize `{:type "text" :text result}`. `:session_id` is available for `--resume`. `:usage` has cache token counts. For tool-use, investigate `--output-format stream-json`.
- Doc errata: top-level `final` empties the configuration — wrap finals in a compound parent. Processor receives `:invokeid` (not `:id`). `forward-event!` is 3-arity. `invocation.future`'s `:src` is called with one arg `(src params)`.
- Library: `com.fulcrologic/statecharts 1.4.0-RC13`. bb 1.12.218.

## Open questions (not v0 blockers)

- **TUI**: deferred. JSONL-first; viewers tail the file.
- **EDN portability of charts**: deferred. If/when needed, add `chart->edn` that replaces fn refs with keyword lookups.
- **Sandboxing third-party charts**: deferred.
- **Conversation persistence across crash**: v0 re-starts conversations from scratch on resume (idempotent re-entry). Later we can serialize message history into the data model under the invocation's id.
- **Concurrent real-tool calls within one turn**: Anthropic supports multiple `tool_use` blocks per assistant message; v0 executes them serially in the worker. Parallelize later if it matters.

## Verification

1. **Unit (JVM)**: `claude_p_test` shells real `claude -p`; asserts parsed response matches protocol schema. Skips if `claude` not on PATH.
2. **Unit (JVM)**: chart-test via `deep-cookie.engine.testing` with a mocked backend — asserts `:done` reached, data model populated, transcript event sequence correct.
3. **Smoke (bb)**: `bb -m deep-cookie.cli run deep-cookie.charts.hello` against real `claude -p` — produces `transcript.jsonl` whose `jq '.event'` shows the expected sequence.
4. **Event-tool routing**: a chart where the LLM is told to fire two `:found-bug` then `:scan-complete` produces three chart events in order; chart reaches `:done`; data model accumulates both findings.
5. **Resume optimization**: two-turn binding — second turn shows `:via :resume`.
6. **Crash-resume**: kill agent mid-run, restart with `--resume <checkpoint>`, verify completion and that the invocation re-spawned on state re-entry.
7. **Parallel regions**: a chart with two parallel regions each running their own `:llm-conversation` invocation completes both without event-tool collision.

---

## Progress Checklist

### Milestone 0 — Project skeleton & sanity ✅
- [x] Spike: verify statecharts + custom InvocationProcessor under bb (see `SPIKE_FINDINGS.md`)
- [x] Decision: **hybrid — bb runtime, JVM tests**
- [x] Decision: **use the library's invocation mechanism, with custom processors**
- [x] `deps.edn`, `bb.edn` (spike-level; finalize as Milestone 1 starts)
- [ ] `.gitignore`, README stub

### Milestone 1 — Custom engine pieces
- [ ] `deep-cookie.engine.queue` — `EventQueue` impl
- [ ] `deep-cookie.engine.store` — `WorkingMemoryStore` with atomic checkpoint (write-temp → rename)
- [ ] `deep-cookie.engine.exec` — `ExecutionModel` impl
- [ ] `deep-cookie.engine.env` — assemble env map (replaces `simple/simple-env`)
- [ ] `deep-cookie.engine.testing` — JVM test harness (mocks + sync drive + transcript capture)
- [ ] Test: trivial 2-state chart runs end-to-end through our env (bb)
- [ ] Test: same chart through the testing harness (JVM)
- [ ] Test: checkpoint round-trip — kill mid-run, reload, complete

### Milestone 2 — LLM protocol & backends
- [ ] `deep-cookie.llm.types` — Malli schemas
- [ ] `deep-cookie.llm.protocol` — `LLMBackend` defprotocol
- [ ] `deep-cookie.llm.claude-p` — shell-out, JSON parse, synthesize content blocks
- [ ] `deep-cookie.llm.claude-p` — `--resume` prefix-continuation; investigate `--output-format stream-json` for tool-use
- [ ] `deep-cookie.llm.cache` — dev replay cache
- [ ] `deep-cookie.llm.api` — stub API backend with `cache_control`
- [ ] Test: `claude_p_test` against real CLI (skips if absent)

### Milestone 3 — Tools
- [ ] `deep-cookie.tools.protocol` — `Tool` protocol + registry
- [ ] `deep-cookie.tools.builtin` — `:fs/read`, `:fs/write`, `:fs/edit`, `:shell/run`, `:repl/eval`
- [ ] Per-tool tests

### Milestone 4 — `:llm-conversation` invocation processor
- [ ] `deep-cookie.invocation.llm-conversation` skeleton: start/stop/forward
- [ ] Worker loop: send-turn, dispatch real tools, loop on tool_use
- [ ] Event tools: synthesize Anthropic tool defs from `:allowed-events`; on call, post event to parent session
- [ ] Bad tool_use: corrective `tool_result :is_error true`, one retry, then `:llm.error`
- [ ] Idempotent re-entry on chart resume
- [ ] `deep-cookie.chart.helpers` — `llm-conversation`, `tell-llm` wrappers

### Milestone 5 — Transcript & driver loop
- [ ] `deep-cookie.transcript` — single-writer JSONL sink
- [ ] `deep-cookie.runner` — drain events → checkpoint → repeat; terminate on quiescent + no live invocations
- [ ] `deep-cookie.cli` — bb entry, `--resume` flag

### Milestone 6 — End-to-end demo
- [ ] `deep-cookie.charts.hello` — single-region chart with one `:llm-conversation` and one event-tool
- [ ] `deep-cookie.charts.scan` — fan-out demo (`:found-bug` multiple times, `:scan-complete` once)
- [ ] `deep-cookie.charts.parallel-demo` — two parallel regions, independent conversations
- [ ] Chart tests via `deep-cookie.engine.testing` for each
- [ ] Smoke runs against real `claude -p`; inspect `transcript.jsonl`
- [ ] Verify kill-and-resume from checkpoint completes correctly

### Milestone 7 — Polish (v0.1)
- [ ] Non-trivial demo chart ("read spec → propose patch → run tests → iterate")
- [ ] README with recipes + idioms (error transitions, `tell-llm` mid-binding, parallel regions)
- [ ] Doc the `:llm-conversation` params schema and how authors add new event-tools
