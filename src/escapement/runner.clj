(ns escapement.runner
  "The driver loop. Pumps the event queue through the chart processor, checkpointing
  working memory after each pass. Terminates when the queue is quiescent AND no
  invocation processor has any live workers.

  Public entry: `run!`. Public helper: `load-chart`."
  (:require
    [clojure.set :as set]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.controller :as dbg]
    [escapement.engine.env :as engine-env]
    [escapement.engine.queue :as engine-queue]
    [escapement.invocation.human-input :as human-input]
    [escapement.invocation.llm-conversation :as llm-conv]
    [escapement.transcript :as transcript]))

(defn- now-ms [] (System/currentTimeMillis))

(defn load-chart
  "Given a fully-qualified symbol such as `'escapement.examples.hello/agent`, `require` the
   namespace and return the resolved var's value. Throws if it can't be resolved."
  [chart-sym]
  (assert (qualified-symbol? chart-sym) "chart-sym must be qualified, e.g. my.ns/agent")
  (let [ns-sym (symbol (namespace chart-sym))]
    (require ns-sym)
    (let [v (resolve chart-sym)]
      (when-not v
        (throw (ex-info (str "Could not resolve chart: " chart-sym) {:chart-sym chart-sym})))
      (deref v))))

(defn load-chart-with-meta
  "Like `load-chart` but returns `[chart-value var-meta]` so callers (e.g. the
   CLI) can read `^{:interactive? true}` and similar markers without redoing
   the resolve."
  [chart-sym]
  (assert (qualified-symbol? chart-sym) "chart-sym must be qualified")
  (let [ns-sym (symbol (namespace chart-sym))]
    (require ns-sym)
    (let [v (resolve chart-sym)]
      (when-not v
        (throw (ex-info (str "Could not resolve chart: " chart-sym) {:chart-sym chart-sym})))
      [(deref v) (or (meta v) {})])))

(defn- count-live-invocations
  "Sum live worker counts across all `InvocationProcessor`s in the env that support it."
  [env]
  (reduce
    (fn [n proc]
      (cond
        ;; Our LLM conversation processor — uses the public accessor.
        (instance? escapement.invocation.llm_conversation.LlmConversationProcessor proc)
        (+ n (llm-conv/active-worker-count proc))

        (instance? escapement.invocation.human_input.HumanInputProcessor proc)
        (+ n (human-input/active-worker-count proc))

        ;; Generic fallback: any processor that exposes a `:workers` atom of map entries
        ;; whose vals have a `:worker-state` atom (e.g. test stand-ins) is honored.
        (and (record? proc) (some-> proc :workers deref map?))
        (+ n (reduce-kv
               (fn [m _ entry]
                 (let [s (some-> (:worker-state entry) deref)]
                   (if (or (nil? s) (= :dying s)) m (inc m))))
               0
               @(:workers proc)))

        :else n))
    0
    (::sc/invocation-processors env)))

(defn- external-event?
  "An event lacks `::sc/source-session-id` only when it was injected from
   outside the chart (CLI, TUI, fixtures). Internal raises always carry it
   (set in `engine.queue/send!`)."
  [event]
  (nil? (::sc/source-session-id event)))

(defn- maybe-pause!
  "Honors the debug `controller` (if any) before processing `event`. Two
   gates apply:

   * `pause-on-next-external?` — flips the controller to `:paused` if `event`
     came from outside the chart.
   * `:paused` mode — emits `:debug/awaiting-step` and parks the runner
     thread until the TUI calls `step!` or `continue!`.

   Skips entirely when `controller` is nil or when a `human-input-active?`
   thunk returns true (so the chart can answer prompts without the debugger
   stealing focus)."
  [{:keys [controller transcript-fn human-input-active?]} event]
  (when (and controller
          (not (and human-input-active? (human-input-active?))))
    (dbg/maybe-arm-from-external! controller (when (external-event? event)
                                               {:external? true}))
    (when (dbg/paused? controller)
      (transcript-fn {:event :debug/awaiting-step
                      :data  {:event-name (:name event)
                              :external?  (external-event? event)}})
      (dbg/await-release! controller))
    (when (pos? (:step-budget @controller))
      (dbg/consume-step-budget! controller))))

(defn- process-targeted-event!
  "Advance session `sid` by `event` against its current `wmem`, checkpoint,
   and emit the `:runner/event-processed` transcript row (with the
   `:entered`/`:exited` state-membership delta and an unconditional
   `:session-id` join key)."
  [{:keys [store env processor transcript-fn sid wmem event ts]}]
  (let [before-set    (set (::sc/configuration wmem #{}))
        config-before (vec before-set)
        wmem'         (sp/process-event! processor env wmem event)
        after-set     (set (::sc/configuration wmem' #{}))
        config-after  (vec after-set)
        entered       (vec (set/difference after-set before-set))
        exited        (vec (set/difference before-set after-set))]
    (sp/save-working-memory! store env sid wmem')
    ;; `:session-id` is now unconditional so a timeline UI has a
    ;; uniform join key across single- and multi-session runs.
    ;; `:entered`/`:exited` make the per-event state-membership
    ;; change first-class data — derivable from before/after, but
    ;; pre-computed here so the UI doesn't have to diff config
    ;; vectors itself.
    (transcript-fn {:event :runner/event-processed
                    :ts    ts
                    :data  {:event-name    (:name event)
                            :config-before config-before
                            :config-after  config-after
                            :entered       entered
                            :exited        exited
                            :event-data    (:data event)
                            :session-id    (str sid)}})
    (transcript-fn {:event :checkpoint/written
                    :data  {:session-id (str sid)}})))

(defn- drain-once!
  "Drain currently-deliverable events for `session-id` through the processor exactly once,
   checkpointing after each event. Returns true if at least one event was processed.

   When `:controller` is supplied, each event is gated through the debug
   pause/step controller (see `maybe-pause!`).

   When `:multi-session?` is true, drain ALL session queues in this env (used
   by charts that spawn child sessions via the multiplex invocation
   processor, `com.fulcrologic.statecharts.invocation.multiplex`). The
   target sid for each event is read from the event itself, so the parent,
   the multiplex aggregator, and every child are pumped from the one loop."
  [{:keys [env session-id transcript-fn controller human-input-active?
           multi-session?]}]
  (let [queue       (::sc/event-queue env)
        store       (::sc/working-memory-store env)
        processor   (::sc/processor env)
        progressed? (atom false)
        handler
        (fn [_ event]
          (maybe-pause! {:controller          controller
                         :transcript-fn       transcript-fn
                         :human-input-active? human-input-active?}
            event)
          (reset! progressed? true)
          (let [ts            (System/currentTimeMillis)
                sid           (if multi-session?
                                (or (:target event) session-id)
                                session-id)
                wmem          (sp/get-working-memory store env sid)]
            ;; A child session that has already reached its final state is
            ;; torn down, so a late event still queued for it (e.g. a
            ;; trailing `:done.invoke.*`) has no working memory to advance —
            ;; delivering it would make the processor throw "Statechart not
            ;; found". In the multi-session drain that is benign teardown
            ;; noise, so skip it. (Single-session always targets the live
            ;; root, so `wmem` is present and this never triggers.)
            (if (nil? wmem)
              (transcript-fn {:event :runner/event-dropped
                              :ts    ts
                              :data  {:event-name (:name event)
                                      :session-id (str sid)
                                      :reason     :session-finished}})
              (process-targeted-event! {:store store :env env :processor processor
                                        :transcript-fn transcript-fn
                                        :sid sid :wmem wmem :event event :ts ts}))))]
    (if multi-session?
      (sp/receive-events! queue env handler)
      (sp/receive-events! queue env handler {:session-id session-id}))
    @progressed?))

(defn- cancel-requested?
  "True when the optional host-supplied cancel `signal` is requesting abort.

   Additive, defensive, and safe to call at any pump-loop boundary:

   * `nil` signal  → never cancels (omitting `:cancel` preserves prior behavior).
   * promise / future / delay (anything `realized?` accepts) → cancels only
     once it is *delivered* and its delivered value is truthy. An undelivered
     promise never blocks here (we check `realized?` first).
   * IDeref (atom / volatile / ref / agent) → cancels when its current value
     is truthy.
   * any other truthy value → treated as cancel (caller passed a raw flag).

   Never throws: any failure to inspect the signal is treated as \"not
   cancelled\" so a malformed handle cannot wedge the runner."
  [signal]
  (when (some? signal)
    (try
      (cond
        ;; Promise/future/delay: only consult the value once realized so an
        ;; un-delivered promise does not block the pump loop.
        (instance? clojure.lang.IPending signal)
        (and (realized? signal) (boolean (deref signal)))

        (instance? clojure.lang.IDeref signal)
        (boolean (deref signal))

        :else (boolean signal))
      (catch Throwable _ false))))

(defn- final-config? [env session-id]
  ;; Heuristic: if the configuration is empty, the chart has terminated via top-level final.
  (let [store (::sc/working-memory-store env)
        wmem  (sp/get-working-memory store env session-id)]
    (empty? (::sc/configuration wmem #{}))))

(>defn run!
  "Run a chart to quiescence. Returns a summary map.

   `opts`:
    * `:chart` (required) — a statechart value (e.g. from `chart/statechart`)
    * `:chart-id` (default `::chart`) — the id under which to register it
    * `:session-id` (required) — the session id for this run
    * `:transcript-path` (required) — JSONL output file path
    * `:checkpoint-dir` (required) — directory for atomic checkpoint files
    * `:catalog-ratings` (optional) — subjective ratings table
                             (`id → opinion-map`) threaded to the
                             llm-conversation eligibility gate.
                             Resolved ONCE by the caller (CLI: from
                             disk config at startup). Defaults to `{}`.
    * `:eligibility-strict?` (optional) — fail-closed flag for the
                             eligibility gate; default fail-open.
    * `:backend` (optional) — an `LLMBackend`; required only if chart uses `:llm-conversation`
    * `:tool-registry` (optional) — tool registry atom
    * `:store` (optional) — working-memory store override; threaded to
                            `engine.env/new-env`. When omitted (`nil`) the
                            default file-backed store is used (CLI behavior
                            unchanged).
    * `:run-id` (optional) — opaque correlation id. When supplied it is
                             echoed on the `:runner/started` event so
                             callers can correlate every transcript row.
    * `:cancel` (optional) — a host-supplied cancellation handle: an atom
                             (or any `IDeref`) whose truthy value, or a
                             promise/future/delay whose delivered value is
                             truthy, requests a prompt abort. Checked at a
                             safe pump-loop boundary (between events, never
                             mid-write); on cancel the run emits a
                             `:runner/aborted` event with
                             `{:reason :cancelled}`, stops pumping cleanly,
                             and the summary map carries
                             `:status :aborted`. Omitting it (`nil`)
                             preserves prior behavior exactly.
    * `:initial-data` (optional) — initial chart data (passed as data-model seed)
    * `:resume?` (default false) — if true and a checkpoint exists, do not call `start!`
    * `:trace?` (default false) — write `:runner/tick` events on every loop turn
    * `:max-iterations` (default 100000) — safety bound on the pump loop
    * `:quiescent-sleep-ms` (default 50) — how long to sleep when queue is empty
                                           but live invocations exist
    * `:max-frozen-cycles` (default 200) — number of *consecutive*
                                           quiescent cycles (queue empty,
                                           no progress) tolerated while
                                           live invocations exist before
                                           the pump declares the config
                                           wedged and terminates with
                                           `:runner/error {:reason
                                           :frozen-config}`. Defaults to
                                           ~200 which, at the default
                                           50ms `:quiescent-sleep-ms`, is
                                           a ~10s wall-clock budget —
                                           generous enough to survive
                                           legitimately slow invocations
                                           (network turns, tool calls)
                                           while still bounding the
                                           statecharts eventless-transition
                                           -loop frozen-config
                                           wedge. The counter resets to 0
                                           whenever the pump makes
                                           progress OR no live
                                           invocations remain, so only an
                                           unbroken run of no-progress
                                           cycles with live work can trip
                                           it.
    * `:debug-controller` (optional) — `escapement.debug.controller`
                                       atom; when supplied, every event
                                       is gated through pause/step
                                       before being processed.
    * `:human-input-active?` (optional) — zero-arg predicate; when it
                                          returns true the debug gate
                                          yields so the chart can
                                          answer a human-input prompt
                                          without the debugger stealing
                                          focus."
  [{:keys [chart chart-id session-id transcript-path checkpoint-dir
           session-dir backend backend-default-models
           catalog-ratings eligibility-strict? llm-aliases llm-preferences
           tool-registry initial-data resume? trace?
           max-iterations max-frozen-cycles quiescent-sleep-ms human-renderer
           on-env-ready transcript-tap prelude-events store run-id cancel
           debug-controller human-input-active? multi-session?]
    :or   {chart-id           ::chart
           resume?            false
           trace?             false
           max-iterations     100000
           max-frozen-cycles  200
           quiescent-sleep-ms 50}}]
  [:map => :map]
  (assert chart "chart is required")
  (assert session-id "session-id is required")
  (assert transcript-path "transcript-path is required")
  (assert checkpoint-dir "checkpoint-dir is required")
  (let [sink          (transcript/open-transcript {:path transcript-path :append? false})
        jsonl-fn      (transcript/make-transcript-fn sink)
        transcript-fn (if transcript-tap
                        (fn [ev]
                          (jsonl-fn ev)
                          (try (transcript-tap ev) (catch Throwable _ nil)))
                        jsonl-fn)
        env           (engine-env/new-env {:checkpoint-dir          checkpoint-dir
                                           :session-dir             session-dir
                                           :store                   store
                                           :llm-backend             backend
                                           :llm-default-models      backend-default-models
                                           :llm-catalog-ratings     catalog-ratings
                                           :llm-eligibility-strict? eligibility-strict?
                                           :llm-aliases             llm-aliases
                                           :llm-preferences         llm-preferences
                                           :tool-registry           tool-registry
                                           :human-renderer          human-renderer
                                           :transcript-fn           transcript-fn})
        registry      (::sc/statechart-registry env)
        store         (::sc/working-memory-store env)
        processor     (::sc/processor env)]
    (sp/register-statechart! registry chart-id chart)
    ;; Hook for callers (e.g. CLI's TUI) that need the queue/env at the
    ;; moment env is built but the chart hasn't started yet. Errors in
    ;; the callback are caught so they don't take down the runner.
    (when on-env-ready
      (try (on-env-ready env)
           (catch Throwable _ nil)))
    (doseq [ev prelude-events]
      (transcript-fn ev))
    (transcript-fn {:event :runner/started
                    :data  (cond-> {:session-id (str session-id)
                                    :chart-id   (str chart-id)
                                    :resume?    (boolean resume?)}
                             run-id (assoc :run-id (str run-id)))})
    (try
      ;; Start or resume
      (let [existing (sp/get-working-memory store env session-id)]
        (if (and resume? existing (seq (::sc/configuration existing #{})))
          (transcript-fn {:event :runner/resumed
                          :data  {:config (vec (::sc/configuration existing))}})
          ;; Seed the chart's data model with `:initial-data` by passing it as
          ;; `::sc/invocation-data` in the start params (per the library's
          ;; v20150901_impl/initialize!).
          (let [w0 (sp/start! processor env chart-id
                     (cond-> {::sc/session-id session-id}
                       initial-data (assoc ::sc/invocation-data initial-data)))]
            (sp/save-working-memory! store env session-id w0)
            (transcript-fn {:event :runner/start-config
                            :data  {:config (vec (::sc/configuration w0))}}))))
      ;; Pump. The loop returns a terminal status keyword:
      ;;   :done          — quiescent with no live invocations (normal)
      ;;   :aborted        — host cancel signal fired, or max-iterations hit
      ;;   :frozen-config  — config wedged: no progress with live work for
      ;;                     `:max-frozen-cycles` consecutive cycles
      (let [status
                       (loop [i             max-iterations
                              frozen-cycles 0]
                         (when trace?
                           (transcript-fn {:event :runner/tick :data {:i (- max-iterations i)}}))
                         (cond
                           ;; Cancellation is checked FIRST, at the top of the
                           ;; iteration — a safe boundary between events, never
                           ;; mid-transcript-write or mid-checkpoint-write — so an
                           ;; abort can never tear a partial transcript/checkpoint
                           ;; row. "Promptly" = by the next pump iteration.
                           (cancel-requested? cancel)
                           (do (transcript-fn {:event :runner/aborted
                                               :data  {:reason :cancelled}})
                               :aborted)

                           (zero? i)
                           (do (transcript-fn {:event :runner/aborted
                                               :data  {:reason :max-iterations}})
                               :aborted)

                           :else
                           (let [progressed? (drain-once! {:env                 env
                                                           :session-id          session-id
                                                           :transcript-fn       transcript-fn
                                                           :controller          debug-controller
                                                           :human-input-active? human-input-active?
                                                           :multi-session?      multi-session?})]
                             (if progressed?
                               ;; Made progress this pass — the config is moving, so
                               ;; the frozen-config counter resets.
                               (recur (dec i) 0)
                               (let [live        (count-live-invocations env)
                                     pending     (engine-queue/pending-count
                                                   (::sc/event-queue env))
                                     deliverable (engine-queue/deliverable-now-count
                                                   (::sc/event-queue env))]
                                 (cond
                                   ;; A delayed `send` (e.g. a safety-stop timer) sits
                                   ;; in the queue with a *future* delivery-time. Until
                                   ;; that time arrives `drain-once!` returns no events
                                   ;; AND `count-live-invocations` may be zero, so
                                   ;; without this check the loop would declare :done
                                   ;; and lose the timer. Sleep without bumping the
                                   ;; frozen counter — this is *planned* idle, not a
                                   ;; wedge. Gated on `(zero? deliverable)`: if events
                                   ;; are deliverable NOW yet drain made no progress,
                                   ;; they are stranded on sessions this run does not
                                   ;; pump (e.g. a multiplex chart run without
                                   ;; `:multi-session?`) — that is a wedge, not a
                                   ;; timer, so fall through to the frozen path rather
                                   ;; than spinning here forever.
                                   (and (zero? live) (pos? pending) (zero? deliverable))
                                   (do (Thread/sleep ^long quiescent-sleep-ms)
                                       (recur (dec i) 0))

                                   (and (zero? live) (zero? deliverable))
                                   :done                    ;; done — no live work, nothing deliverable stuck

                                   ;; No progress AND live invocations exist: this
                                   ;; quiescent cycle may be the frozen-config wedge.
                                   ;; Bump the counter; if it reaches the threshold
                                   ;; the configuration is wedged (the statecharts
                                   ;; eventless transition loop never advances) —
                                   ;; emit :runner/error and return a terminal status
                                   ;; (clean exit, no throw), mirroring :aborted.
                                   (>= (inc frozen-cycles) max-frozen-cycles)
                                   (do (transcript-fn {:event :runner/error
                                                       :data  {:reason           :frozen-config
                                                               :live-invocations live
                                                               :pending          pending
                                                               :deliverable-now  deliverable
                                                               :cycles           (inc frozen-cycles)
                                                               ;; `deliverable-now > 0` with no live work means events
                                                               ;; are stranded on un-pumped sessions — almost always a
                                                               ;; multiplex chart missing `^:multi-session?`.
                                                               :hint             (when (and (zero? live) (pos? deliverable))
                                                                                   "deliverable events stranded on un-drained sessions — does this chart need ^:multi-session?")}})
                                       :frozen-config)

                                   :else
                                   (do (Thread/sleep ^long quiescent-sleep-ms)
                                       (recur (dec i) (inc frozen-cycles)))))))))
            final-wmem (sp/get-working-memory store env session-id)
            cfg        (vec (::sc/configuration final-wmem #{}))]
        (transcript-fn {:event :runner/done
                        :data  {:final-config cfg
                                :status       status}})
        {:final-config   cfg
         :status         status
         :session-id     session-id
         :transcript     transcript-path
         :checkpoint-dir checkpoint-dir
         :env            env})
      (catch Throwable t
        (transcript-fn {:event :runner/error
                        :data  {:message (.getMessage t)}})
        (throw t))
      (finally
        (transcript/close! sink)))))
