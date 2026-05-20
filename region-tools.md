# Plan — Service Regions: chart regions that act as stateful tools

## Context

Today an LLM-driven region of an Escapement chart talks to other regions
through shared data + ad-hoc events. There is already a working but bespoke
instance of "region as a service" in `demos/unit_test/chart.clj:370-408` —
the `:repl-mgr` parallel region manages an nREPL lifecycle and signals
readiness via `:repl/available`. The pipeline doesn't *call* the manager,
it just waits.

We want to generalize that pattern: a chart author drops in a region that
**owns stateful resources** (a REPL process, a cached DB connection, an
indexer) and **declares which states handle which tools**. Other LLM regions
call those tools as if they were ordinary tools; the plumbing rides the
chart's existing event bus; the LLM sees a normal tool_use → tool_result.

## Design principles

1. **The service author's model is one sentence**: *this state handles this
   tool by running this function*. No queue, no busy flag, no internal events
   imposed by the framework.
2. **Handlers are synchronous.** SCXML serializes events per session, so two
   in-flight requests are processed in sequence by the engine for free. Slow
   async work is modeled with substates the author writes (`:ready` vs
   `:evaluating`); a request that lands in a busy substate gets an
   author-defined "busy, retry" reply. No framework queue.
3. **The consumer's model is one sentence**: *this conversation can call
   tools from these regions*. A `:chart-tools` vector with optional `:as`
   aliasing.
4. **Replies are hard-routed**, not broadcast. Engine-injected `reply-to`
   names one worker; the engine delivers directly to that worker's reply
   queue.
5. **The worker dispatches region tools synchronously inside
   `handle-tool-use-block`.** Post the event, poll the reply queue with the
   per-call timeout, return the result block. No `::pending`, no two-phase
   assembly, no new loop state.
6. **Babashka-first.** SCI-compatible throughout — plain atoms, existing
   `ArrayBlockingQueue`/`TimeUnit` poll, no new threads, no new deps.

## Architecture

### 1. Wire protocol

Request (chart event, posted by the conversation worker on a region-tool
`tool_use`):

```clojure
{:event :repl/eval
 :data  {:expr "(+ 1 2)"                          ;; LLM-supplied payload (validated)
         :escapement.tool/reply-id   "tr_abc"     ;; engine-injected correlation id
         :escapement.tool/reply-to   "coder"      ;; caller invokeid (string)
         :escapement.tool/owner      :repl-A      ;; snapshot-resolved owner state-id
         :escapement.tool/timeout-ms 30000}}      ;; relative duration in ms
```

Reply (chart event, fired by the service region's handler):

```clojure
{:event :escapement.tool/reply
 :data  {:escapement.tool/reply-id "tr_abc"      ;; correlates with the request
         :escapement.tool/reply-to "coder"       ;; routing target
         :result   "3"                           ;; → tool_result.content (string)
         :is-error false}}
```

One generic correlation event. `:timeout-ms` is a relative duration on the
wire; the worker computes its own absolute deadline locally. No clock
synchronization concerns.

### 2. Per-chart service registry

A new env entry: `::service/registry` → `(atom {tool-kw entry})` where
`entry = {:owner <state-id> :description <s> :input-schema <malli>}`.

Built once when the engine env is constructed (`escapement/engine/env.clj`,
around line 36). Empty at first; populated by on-entry actions.

Multiple owners MAY register the same `:tool` keyword — this is required by
the owner-tag-routing pattern (§3, §Verification #4): two sibling service
regions both expose `:repl/eval`, and the consumer's `:chart-tools` selects
which owner to pull. Collision detection happens at **palette-snapshot time**
(§5a), not at registration time: the error fires only if `:chart-tools`
declarations produce two entries with the same LLM-facing tool name (e.g.
two owners pulled with no disambiguating `:as` prefix). The exception names
both owners.

Same-owner double-registration with a *different* schema IS a hard error at
registration time, naming the owner.

### 3. New ns: `escapement.chart.service`

Three public functions:

```clojure
(defn register-tool!
  "On-entry action element. Adds the tool declaration to the chart's
   ::service/registry. Records :owner from env's current state-id.
   Declaration: {:tool <kw> :description <s> :input-schema <malli>}."
  [decl] ...)

(defn unregister-tool!
  "On-exit action element. Removes the (tool-kw, current owner) entry
   from the registry. Stray calls from non-owners are silently ignored.
   Stale entries left behind by an unexpected exit path can be scrubbed
   via the public `prune-owners!` helper (the statecharts library has no
   state-exit hook protocol we can hang an auto-prune off of)."
  [tool-kw] ...)

(defn handle
  "Returns a transition element that matches the tool's request event on
   the enclosing state. The transition has no :target (internal — state
   stays put). Action: runs handler-fn with [env request], where request is
   {:data <user-payload> :reply-id <s> :reply-to <s> :timeout-ms <int>},
   and posts an :escapement.tool/reply event with the handler's return.

   Handler returns {:result <string> :is-error <bool>}.

   The transition is automatically guarded by an owner-equality check so
   that two sibling service regions registering the same tool keyword
   route only to the intended one. The owner is derived at runtime from
   the chart's normalized structure (the transition is tagged with an id
   at build time; the cond looks the id up in ::sc/elements-by-id and
   walks `:parent` to find the enclosing owner state). Authors never
   pass the owner state-id — there is no risk of forgetting it."
  [event-kw handler-fn] ...)
```

The author writes:

```clojure
(state {:id :repl-A :initial :idle}
  (on-entry {} (service/register-tool!
                 {:tool :repl/eval
                  :description "Evaluate a Clojure form."
                  :input-schema [:map [:expr :string]]}))
  (on-exit  {} (service/unregister-tool! :repl/eval))

  (state {:id :idle}
    (service/handle :repl/eval (constantly {:result "not running" :is-error true})))

  (state {:id :running}
    (service/handle :repl/eval real-eval-fn)))
```

SCXML transition precedence: the substate handler wins when present;
otherwise the request hits no handler and the worker times out. Authors
who want explicit "not ready" replies write them at the appropriate state,
as in `:idle` above.

### 4. Slow async work — author pattern (not framework)

A handler that needs to do slow work transitions the region into a "busy"
substate and posts the reply later. Concurrent requests landing in the
busy substate get an immediate "busy" reply. The framework doesn't queue;
the chart's state machine expresses the policy:

```clojure
(state {:id :running :initial :ready}
  (state {:id :ready}
    (service/handle :repl/eval
      (fn [env req]
        (kick-off-async-eval! env req)
        ;; The handler returns nil → service helper interprets
        ;; nil as "reply will come later"; the kicked-off worker
        ;; calls service/post-reply when done.
        (transition-to! env :evaluating))))
  (state {:id :evaluating}
    (service/handle :repl/eval
      (constantly {:result "busy, retry shortly" :is-error true}))))
```

A small `service/post-reply` helper lets deferred work post the reply
explicitly (carrying the saved reply-id from the original request). For
authors who want true cross-conversation queueing-on-busy, that's a pattern
they implement in their chart's data model — not framework code.

### 5. Conversation worker changes (`invocation/llm_conversation.clj`)

#### 5a. Palette snapshot at conversation start

In `start-invocation!`, after `resolve-real-tools` and `event-tool-defs`,
read the chart-tools spec from invocation params:

```clojure
;; :chart-tools is a vector of {:owner <state-id> :as <kw-prefix>?}.
;; Default (missing or nil) is no region tools.
(let [registry @(::service/registry env)
      decls    (or (:chart-tools params) [])
      pulled   (for [{:keys [owner as]} decls
                     [tool-kw entry] registry
                     :when (= owner (:owner entry))]
                 (cond-> entry
                   true     (assoc :event-kw tool-kw)
                   true     (assoc :tool-kw  (if as
                                               (keyword (name as) (name tool-kw))
                                               tool-kw))
                   true     (assoc :owner owner)))]
  ;; Collision detection: two entries with the same :tool-kw is an author
  ;; error (e.g. two owners aliased to the same :as prefix). Throw, naming
  ;; both owners.
  ...)
```

The worker's `name->region-tool` map keys on the LLM-facing tool name
(derived from `:tool-kw`):

```clojure
{"py_eval"  {:event-kw :repl/eval :owner :repl-A :timeout-default 30000 :input-schema ...}
 "clj_eval" {:event-kw :repl/eval :owner :repl-B :timeout-default 30000 :input-schema ...}}
```

Merged into the assembled Anthropic `:tools` array alongside real-tools
and event-tools, schemas passed through `malli->json-schema`. An implicit
optional `:timeout-ms` field is merged into each schema (the merge requires
schemas to be open; closed schemas error at snapshot with a clear message).

#### 5b. Worker entry additions

```clojure
{:name->region-tool  {}                          ;; built at snapshot
 :tool-reply-queue   (ArrayBlockingQueue. 64)}   ;; new inbound queue
```

#### 5c. `handle-tool-use-block` — new branch (synchronous dispatch)

After the event-tool branch, before `:else`:

```clojure
(contains? name->region-tool name)
(let [{:keys [event-kw owner input-schema timeout-default]}
        (get name->region-tool name)
      schema     (assoc-implicit-timeout input-schema)]
  (if-let [errors (m/explain schema (or input {}))]
    (... validation-failure-path: same retry-once semantics as event-tools ...)
    (let [reply-id    (str "tr_" (random-uuid))
          timeout-ms  (or (get input :timeout-ms) timeout-default)
          payload     (-> (dissoc input :timeout-ms)
                          (assoc :escapement.tool/reply-id   reply-id
                                 :escapement.tool/reply-to   (:invokeid worker-ctx)
                                 :escapement.tool/owner      owner
                                 :escapement.tool/timeout-ms timeout-ms))]
      (post-event-to-parent! parent-ctx event-kw payload)
      (let [deadline (+ (now-ms) timeout-ms)
            reply    (poll-reply-queue tool-reply-queue reply-id deadline)]
        (if reply
          {:result-block {:type "tool_result"
                          :tool_use_id id
                          :is_error (boolean (:is-error reply))
                          :content [{:type "text" :text (str (:result reply))}]}}
          {:result-block {:type "tool_result"
                          :tool_use_id id
                          :is_error true
                          :content [{:type "text"
                                     :text (str name " timed out after " timeout-ms "ms")}]}})))))
```

`poll-reply-queue` reads from `tool-reply-queue` with a `min(remaining, max-step)`
poll, discarding any reply whose `:reply-id` doesn't match this call (carries
forward into a small stash for any concurrent in-flight call — see below).

If a turn includes multiple region-tool blocks, they dispatch sequentially:
total wait = sum of per-call waits, not max. This is the explicit tradeoff
for keeping the worker loop unchanged; LLMs rarely emit many tool calls per
turn, and parallel dispatch can be revisited later.

**Stashing late/mismatched replies**: while polling for `reply-id` A, the
queue may yield a reply for B (e.g. a previous slow call that finally
arrived after its caller already timed out). Drop it with a transcript log
("late reply for <id>"). No stash needed because at any given moment the
worker has at most one outstanding region call.

#### 5d. Reply routing into the worker

`forward-event!` gains one case:

```clojure
:escapement.tool/reply
(let [{:keys [escapement.tool/reply-to]} (:data event)
      entry (get @workers reply-to)]
  (when entry
    (.offer (:tool-reply-queue entry) (:data event))))
```

Look up worker by `reply-to`; deliver directly. No broadcast, no per-worker
filtering. Missing worker → transcript-log and drop.

#### 5e. Stop semantics

`stop-worker-entry!` doesn't need to do anything for region tools:
synchronous dispatch means there's no `pending-region-calls` map to drain.
If the worker is stopped mid-poll, the polling thread sees `:dying` and
exits; any in-flight reply that arrives after stop hits a missing entry
in `forward-event!` and drops silently.

### 6. Concrete helper: `escapement.chart.repl-service`

A drop-in service region implementing `:repl/start`, `:repl/status`,
`:repl/eval`, `:repl/stop` against an nREPL, with a `:ready`/`:evaluating`
substate split for the slow `:repl/eval` case. Reuses the discovery logic
already in `demos/unit_test/chart.clj`.

This helper is the author-side example of the slow-work pattern from §4.
It is NOT framework — it's a chart author would write following the
framework's conventions, packaged for convenience.

## Timing and executable-content order

SCXML executes each microstep in fixed phases:

1. exit-states (on-exit in reverse document order)
2. transition executable content
3. enter-states (on-entry in document order)
4. start invocations (for every state newly entered this microstep)
5. process internal events, loop

Implications:

* **Same-microstep register-then-invoke is safe.** An on-entry that calls
  `(service/register-tool! ...)` runs in phase 3; a sibling or same-state
  `(h/llm-conversation ...)` invocation starts in phase 4 and snapshots a
  registry that already contains the registered entries.
* **A conversation that starts BEFORE a service region's on-entry runs
  will NOT see those tools.** Fix in chart authoring: barrier the consuming
  conversation behind a readiness signal (e.g. the existing `:await-repl`
  pattern, `demos/unit_test/chart.clj:346`).
* **Snapshot freezes the palette.** If the service region exits mid-
  conversation, subsequent tool calls fire events with no matching handler
  → worker times out → LLM gets `is-error`. Authors aware of this risk
  scope their consumer conversations to a lifetime that brackets the
  service.

The phase-ordering guarantee is verified by a focused test in
`service_test.clj` rather than left as a doc claim — Escapement assembles
its own env (CLAUDE.md), so we don't take SCXML compliance for granted.

## Files to add / modify

### New

* `src/escapement/chart/service.clj` — `register-tool!`, `unregister-tool!`,
  `handle`, `post-reply`, env-scoped registry helpers.
* `src/escapement/chart/repl_service.clj` — concrete drop-in.
* `test/escapement/chart/service_test.clj` — request/reply correlation,
  timeout (with LLM-supplied override), late-reply drop, owner-tag
  routing across two sibling service regions, registration collision
  error, missing-handler timeout, phase-ordering verification.
* `test/escapement/chart/repl_service_test.clj` — segment composition test
  (shim chart); mock LLM issues `:repl/start` then `:repl/eval`; assert
  tool_result content; skip cleanly if nREPL discovery deps unavailable
  under bb.

### Modified

* `src/escapement/invocation/llm_conversation.clj`
    - New worker-context keys (`name->region-tool`, `tool-reply-queue`).
    - New region-tool branch in `handle-tool-use-block` (line 277).
    - `start-invocation!` / palette assembly: derive region-tool palette
      from `::service/registry` filtered by `:chart-tools` declarations;
      apply `:as` aliasing; check for collisions.
    - `forward-event!`: add the `:escapement.tool/reply` case (look up
      worker by `:escapement.tool/reply-to`, `.offer` to its
      `tool-reply-queue`).
* `src/escapement/engine/env.clj`
    - Initialize `::service/registry` atom on the env (around line 36).
    - State-exit hook auto-prunes stale registry entries.
* `src/escapement/chart/helpers.clj`
    - Re-export `service/handle` and `service/post-reply` as `h/handle-tool`
      and `h/post-reply` (simple `def` aliases) for ergonomic access.

### Untouched

* `src/escapement/tools/protocol.clj` — region tools live in the chart-scoped
  registry, NOT in the tool protocol registry. They aren't synchronous from
  the engine's perspective, they aren't process-global, they aren't reusable
  across charts. Different abstraction.

## Verification

1. **Unit suite**: `bb test` runs everything new and existing under
   Babashka.
2. **Correlation** (`service_test.clj`): mock backend returns a `tool_use`
   for a registered region-tool; fixture chart fires a matching
   `:escapement.tool/reply`; assert worker emits the correct `tool_result`
   block on the next assistant turn.
3. **Timeout — default & LLM-overridden**: never reply, advance clock past
   deadline; assert error tool_result. Re-run with the LLM tool input
   `{:timeout-ms 1000}` and a 500ms reply; assert success. Late replies
   (arriving after timeout) are dropped silently with a transcript event.
4. **Owner-tag scoping**: two sibling service regions both register
   `:repl/eval`; consumer declares `:chart-tools` for only one; assert the
   other region's handler is never invoked.
5. **Registration collision**: two states both call
   `(service/register-tool! {:tool :repl/eval ...})`; assert exception at
   registration time naming both owners.
6. **Snapshot semantics**: register tools AFTER conversation start; assert
   the new tools are NOT in the conversation's palette. Unregister tools
   the LLM previously had: assert subsequent `tool_use` for them times out.
7. **Service substate routing**: shim chart with a region in `:idle`; LLM
   fires `:repl/eval`; assert "not running" reply from the `:idle` handler.
   Drive region into `:running` then fire again; assert real eval result.
8. **Phase-ordering**: focused test that asserts an on-entry
   `register-tool!` is visible to a sibling-state invocation's palette
   snapshot started in the same microstep.
9. **Manual smoke**: optionally migrate `demos/unit_test/chart.clj`'s
   `:repl-mgr` to use `service/handle` and the new helper, asserting the
   existing pipeline still works end-to-end.

## What this does NOT introduce

* **No framework queue, busy-flag, or internal dispatch events.** SCXML
  serializes external events; synchronous handlers compose naturally.
  Async/slow work is an author chart-design pattern.
* **No new threads.** Worker polls its existing reply queue inside
  `handle-tool-use-block`.
* **No new dependencies.** Pure Clojure data + the existing event bus.
* **No global mutable state.** The registry lives on the engine env, scoped
  to one chart run.
* **No two-phase result assembly.** Region tools dispatch synchronously
  within `handle-tool-use-block`; the existing `doseq` over tool_use
  blocks works unchanged.
* **No broadcast.** Replies are hard-routed to the addressed worker by
  invokeid lookup.
* **No JVM-only paths.** Reuses primitives already proven under SCI.

## Tradeoffs accepted

1. **Multiple region tools in one turn dispatch sequentially.** Total wait
   is the sum of per-call timeouts, not the max. In practice LLMs emit
   few tool calls per turn; parallel dispatch can be added later behind
   an opt-in flag without changing the surface.
2. **No automatic queueing on busy.** Concurrent calls to a slow region
   while it's busy get a "busy, retry" reply (author-defined). LLMs are
   good at retrying; this keeps the framework small and honest.
3. **Stale palette after registration changes.** Conversations snapshot
   the palette at start. If owners exit mid-conversation, subsequent
   calls time out. Authors aware of this scope conversations accordingly.
