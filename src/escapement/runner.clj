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
    [escapement.capture :as capture]
    [escapement.engine.clock :as clock]
    [escapement.engine.env :as engine-env]
    [escapement.engine.instrumented-queue :as iq]
    [escapement.engine.queue :as engine-queue]
    [escapement.engine.session-lock :as session-lock]
    [escapement.engine.store :as engine-store]
    [escapement.invocation.human-input :as human-input]
    [escapement.invocation.llm-conversation :as llm-conv]
    [escapement.storage.disk :as disk]
    [escapement.transcript :as transcript]
    [taoensso.timbre :as log]))

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

(defn- report-event-failure!
  "`event`'s processing threw `t`: record it, and let the chart see it as `error.execution` (as the
   statecharts standard prescribes for errors during processing) instead of the event vanishing. The
   raised error is itself marked, so an error event whose own processing throws is recorded but never
   raises another (no error loop)."
  [{:keys [env transcript-fn]} sid event ^Throwable t]
  (let [message (or (ex-message t) (str t))]
    (log/warn t "[runner] processing event" (:name event) "threw; raising :error.execution")
    (transcript-fn {:event :runner/event-failed
                    :ts    (now-ms)
                    :data  {:event-name (:name event)
                            :session-id (str sid)
                            :message    message}})
    (when-not (contains? (:data event) :escapement/failed-event)
      (sp/send! (::sc/event-queue env) env
        {:event             :error.execution
         :target            sid
         :source-session-id sid
         :data              {:reason                  :event-processing-threw
                             :message                 message
                             :escapement/failed-event (:name event)}}))))

(defn- process-targeted-event!
  "Advance session `sid` by `event` against its current `wmem`, checkpoint,
   and emit the `:runner/event-processed` transcript row (with the
   `:entered`/`:exited` state-membership delta and an unconditional
   `:session-id` join key).

   The checkpoint written here is also the event's acknowledgement: the queue already
   no longer holds `event` (it is taken out just before its handler runs), so the one
   atomic write records the new state together with every OTHER pending event. If
   processing throws, nothing is saved and the chart gets `error.execution`
   (`report-event-failure!`); the event stays in the last saved checkpoint until the
   error event's own checkpoint acknowledges it."
  [{:keys [store env processor transcript-fn] :as session} sid wmem event ts]
  (let [before-set    (set (::sc/configuration wmem #{}))
        config-before (vec before-set)
        wmem'         (try (sp/process-event! processor env wmem event)
                           (catch Throwable t
                             (report-event-failure! session sid event t)
                             ::failed))]
    (when-not (= ::failed wmem')
      (let [after-set    (set (::sc/configuration wmem' #{}))
            config-after (vec after-set)
            entered      (vec (set/difference after-set before-set))
            exited       (vec (set/difference before-set after-set))]
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
                        :data  {:session-id (str sid)}})))))

(defn- event-handler
  "The queue handler that processes one delivered event for `session`.

   When `:multi-session?` is true, the target sid for each event is read from the event
   itself, so the parent, the multiplex aggregator, and every child are pumped from the
   one loop (charts that spawn child sessions via the multiplex invocation processor,
   `com.fulcrologic.statecharts.invocation.multiplex`). `on-event` (optional) is called
   with every delivered event."
  [{:keys [env session-id transcript-fn multi-session?] :as session} on-event]
  (let [store (::sc/working-memory-store env)]
    (fn [_ event]
      (when on-event (on-event event))
      (let [ts   (now-ms)
            sid  (if multi-session?
                   (or (:target event) session-id)
                   session-id)
            wmem (sp/get-working-memory store env sid)]
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
          (process-targeted-event! session sid wmem event ts))))))

(defn- drain-once!
  "Drain currently-deliverable events for the session through the processor exactly once,
   checkpointing after each event. Returns true if at least one event was processed.

   The debug pause/step gate is NOT applied here: when a `:debug-controller`
   is supplied to `run!`, the event queue itself is an
   `escapement.engine.instrumented-queue` that applies the gate per event
   inside `receive-events!`, BEFORE this handler runs. That keeps the gate a
   single primitive (the queue) with no double-gating.

   When `:multi-session?` is true, drain ALL session queues in this env."
  [{:keys [env session-id multi-session?] :as session}]
  (let [queue       (::sc/event-queue env)
        progressed? (atom false)
        handler     (event-handler session (fn [_] (reset! progressed? true)))]
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

(defn- open-session!
  "Build everything a session needs to run (transcript sink, queue, env, registered chart) and emit
   the start-of-run transcript rows. Starts NOTHING: `start-or-resume!` does. The caller holds the
   session lock and must `close-session!` the result. Takes `run!`'s options; returns the session map
   the rest of this namespace passes around."
  [{:keys [chart chart-id session-id transcript-path checkpoint-dir
           session-dir backend backend-default-models
           catalog-ratings eligibility-strict? llm-aliases llm-preferences llm-resilience
           tool-registry resume? human-renderer
           on-env-ready transcript-tap prelude-events store run-id
           debug-controller human-input-active? multi-session? retain-history?
           invocation-processors clock]
    :or   {chart-id ::chart
           resume?  false}
    :as   opts}]
  (assert chart "chart is required")
  (assert session-id "session-id is required")
  (assert transcript-path "transcript-path is required")
  (assert checkpoint-dir "checkpoint-dir is required")
  (let [;; Append on resume so the transcript stays ONE navigable timeline across the
        ;; whole life of the chart (seq continues past the prior run); truncate on a
        ;; fresh start so a reused session id begins cleanly.
        sink          (transcript/open-transcript {:path transcript-path :append? (boolean resume?)})
        jsonl-fn      (transcript/make-transcript-fn sink)
        ;; A host callback must never take down the runner, so its throws are
        ;; swallowed — but the swallow used to be TOTAL: a tap whose sink was
        ;; misconfigured or whose database was down received nothing and was
        ;; told nothing, for the whole run, while the transcript file itself
        ;; stayed healthy so nothing else looked wrong. Warn ONCE (every event
        ;; carries the same broken tap; a line per event would bury the run) and
        ;; keep going.
        tap-warned    (atom false)
        transcript-fn (if transcript-tap
                        (fn [ev]
                          (jsonl-fn ev)
                          (try (transcript-tap ev)
                               (catch Throwable t
                                 (when (compare-and-set! tap-warned false true)
                                   (log/warn t "[runner] :transcript-tap threw; its events are being"
                                     "dropped for the rest of this run. The transcript FILE is"
                                     "unaffected. This is logged once per run."))
                                 nil)))
                        jsonl-fn)
        ;; When a debug controller is supplied, the event queue becomes an
        ;; instrumented queue that applies the pause/step gate per event (and
        ;; exposes pending/last-delivered instrumentation to the api-server).
        ;; With no controller the default in-process queue is used, so normal
        ;; runs carry zero stepping overhead. The gate's human-input yield is
        ;; threaded in here so a live human-input modal keeps focus.
        queue         (when debug-controller
                        (iq/new-instrumented-queue
                          {:inner        (engine-queue/new-queue {:clock clock})
                           :controller   debug-controller
                           :human-input? human-input-active?}))
        env           (engine-env/new-env {:checkpoint-dir          checkpoint-dir
                                           :session-dir             session-dir
                                           :artifact-store          (when session-dir
                                                                      (disk/new-artifact-store session-dir))
                                           :queue                   queue
                                           :retain-history?         retain-history?
                                           :store                   store
                                           :llm-backend             backend
                                           :llm-default-models      backend-default-models
                                           :llm-catalog-ratings     catalog-ratings
                                           :llm-eligibility-strict? eligibility-strict?
                                           :llm-aliases             llm-aliases
                                           :llm-preferences         llm-preferences
                                           :llm-resilience          llm-resilience
                                           :tool-registry           tool-registry
                                           :clock                   clock
                                           :human-renderer          human-renderer
                                           :extra-invocation-processors (vec invocation-processors)
                                           :transcript-fn           transcript-fn})
        registry      (::sc/statechart-registry env)]
    (try
      (sp/register-statechart! registry chart-id chart)
      ;; Seed the per-(session,node) visit counter from artifacts already on disk so
      ;; a restart/resume of a looping chart keeps numbering re-entries `max+1` instead
      ;; of colliding at 0 and overwriting the prior run's captured-I/O. No-op on a
      ;; fresh session (empty store ⇒ `{}`), so first-run numbering is unchanged.
      (when-let [artifact-store (:escapement/artifact-store env)]
        (reset! (:escapement/visit-counts env)
          (capture/seed-visit-counts artifact-store session-id)))
      ;; Hook for callers (e.g. CLI's TUI) that need the queue/env at the
      ;; moment env is built but the chart hasn't started yet. Errors in
      ;; the callback are caught so they don't take down the runner.
      (when on-env-ready
        (try (on-env-ready env)
             (catch Throwable t
               ;; Same shape as the tap: the callback must not take down the
               ;; runner, but a host whose setup hook failed should not have to
               ;; infer it from later symptoms.
               (log/warn t "[runner] :on-env-ready threw; the run continues without whatever"
                 "it was supposed to set up."))))
      (doseq [ev prelude-events]
        (transcript-fn ev))
      (transcript-fn {:event :runner/started
                      :data  (cond-> {:session-id (str session-id)
                                      :chart-id   (str chart-id)
                                      :resume?    (boolean resume?)}
                               run-id (assoc :run-id (str run-id)))})
      (catch Throwable t
        (transcript/close! sink)
        (throw t)))
    {:opts           opts
     :chart          chart
     :chart-id       chart-id
     :session-id     session-id
     :multi-session? multi-session?
     :env            env
     :store          (::sc/working-memory-store env)
     :processor      (::sc/processor env)
     :sink           sink
     :transcript-fn  transcript-fn
     ;; The run's time source: the queue's clock (the wall clock unless `:clock` was given).
     :clock          (engine-queue/queue-clock (::sc/event-queue env))}))

(defn- start-or-resume!
  "Start `session`'s chart fresh, or (with `:resume?` and a checkpoint that has an active
   configuration) resume it from that checkpoint. Returns `session`."
  [{:keys [env store processor transcript-fn session-id chart-id opts] :as session}]
  (let [{:keys [resume? resume-events initial-data]} opts
        existing (sp/get-working-memory store env session-id)]
    (if (and resume? existing (seq (::sc/configuration existing #{})))
      ;; Rehydrate the durable event queue BEFORE the pump loop so delayed/timer
      ;; events scheduled before the exit (poll ticks, safety stops) are restored;
      ;; any whose delivery-time has since passed fire immediately, in
      ;; delivery-time order. Must restore INTO the live queue (the execution model
      ;; already captured its reference), not swap a new one in.
      (let [snap     (when (engine-store/file-backed? store)
                       (engine-store/get-queue-snapshot store session-id))
            restored (engine-queue/restore-into! (::sc/event-queue env) snap)
            config   (vec (::sc/configuration existing))
            queue    (::sc/event-queue env)]
        (transcript-fn {:event :runner/resumed
                        :data  {:config          config
                                :restored-events (if restored
                                                   (engine-queue/pending-count restored)
                                                   0)}})
        ;; Emit a chart-observable resume signal so a chart can idempotently
        ;; re-establish anything a restored TIMER cannot (service registries,
        ;; acquired resources, a timer already consumed pre-exit). This is the
        ;; opt-in self-heal seam: a chart adds a transition on `:escapement/resumed`
        ;; where it needs one; a chart that ignores it simply drains the event as a
        ;; no-op. We do NOT blanket-replay on-entry (many on-entry actions acquire
        ;; resources and would double-fire). Host-supplied `:resume-events` follow,
        ;; letting a CLI/embedder inject its own post-restore events (something
        ;; `:on-env-ready` — which fires before restore — and transcript-only
        ;; `:prelude-events` cannot do).
        (sp/send! queue env {:event :escapement/resumed :target session-id
                             :data  {:config config}})
        (doseq [ev resume-events]
          (sp/send! queue env (cond-> ev (not (:target ev)) (assoc :target session-id)))))
      ;; Seed the chart's data model with `:initial-data` by passing it as
      ;; `::sc/invocation-data` in the start params (per the library's
      ;; v20150901_impl/initialize!).
      (let [w0 (sp/start! processor env chart-id
                 (cond-> {::sc/session-id session-id}
                   initial-data (assoc ::sc/invocation-data initial-data)))]
        (sp/save-working-memory! store env session-id w0)
        (transcript-fn {:event :runner/start-config
                        :data  {:config (vec (::sc/configuration w0))}})))
    session))

(defn- pump!
  "Run `session` until it is quiet, cancelled or wedged. Returns the terminal status keyword:
     :done          — quiescent with no live invocations (normal)
     :aborted       — host cancel signal fired
     :frozen-config — config wedged: no progress with live work for
                      `:max-frozen-cycles` consecutive cycles"
  [{:keys [env transcript-fn opts] run-clock :clock :as session}]
  (let [{:keys [trace? cancel max-frozen-cycles quiescent-sleep-ms]
         :or   {trace?             false
                max-frozen-cycles  4000
                quiescent-sleep-ms 50}} opts]
    ;; A SINGLE counter, `no-progress`, bounds *consecutive*
    ;; no-progress quiescent cycles while live work (or stranded
    ;; deliverable events) exist — the frozen-config wedge guard.
    ;; It RESETS to 0 on any progress, so an infinite *event-driven*
    ;; chart (one that keeps draining events) never trips it and runs
    ;; forever — the chart owns its own termination. The old
    ;; lifetime `:max-iterations` cap is gone: it counted total pump
    ;; iterations without resetting, so it mis-aborted legitimately
    ;; infinite event charts. The real *transitionless* (eventless)
    ;; loop guard lives in the statecharts library (bounded per
    ;; macrostep, fresh each event) and is untouched.
    (loop [no-progress 0]
      (when trace?
        (transcript-fn {:event :runner/tick :data {:no-progress no-progress}}))
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

        :else
        (if (drain-once! session)
          ;; Made progress this pass — the config is moving, so
          ;; the frozen-config counter resets.
          (recur 0)
          (let [live        (count-live-invocations env)
                pending     (engine-queue/pending-count
                              (::sc/event-queue env))
                deliverable (engine-queue/deliverable-now-count
                              (::sc/event-queue env))]
            (cond
              ;; A delayed `send` (e.g. a safety-stop timer) sits
              ;; in the queue with a *future* delivery-time. Until
              ;; that time arrives `drain-once!` returns no events
              ;; AND `count-live-invocations` is zero, so without
              ;; this check the loop would declare :done and lose
              ;; the timer. Sleep without bumping `no-progress` —
              ;; this is *planned* idle, not a wedge, and a chart
              ;; waiting on its own timer is a RUNNING chart, so it
              ;; is intentionally UNBOUNDED (the chart owns when it
              ;; ends). Gated on `(zero? deliverable)`: if events
              ;; are deliverable NOW yet drain made no progress,
              ;; they are stranded on sessions this run does not
              ;; pump (e.g. a multiplex chart run without
              ;; `:multi-session?`) — that is a wedge, not a timer,
              ;; so it falls through to the frozen path below.
              (and (zero? live) (pos? pending) (zero? deliverable))
              (do (clock/idle! run-clock (engine-queue/next-delivery-time (::sc/event-queue env))
                    quiescent-sleep-ms)
                  (recur 0))

              (and (zero? live) (zero? deliverable))
              :done                                         ;; done — no live work, nothing deliverable stuck

              ;; A LIVE invocation is in-flight (e.g. an LLM turn
              ;; mid-generation) — the run is WORKING, not wedged.
              ;; Wait UNBOUNDEDLY without bumping the frozen counter:
              ;; a slow model (or a long turn over a big context)
              ;; must not be aborted as "frozen" — the agent should
              ;; STOP itself (idle/done/need-human), never BE stopped
              ;; mid-thought. A genuinely hung HTTP call is caught by
              ;; the backend's own request timeout, which posts an
              ;; error event → progress → this loop advances. The
              ;; frozen-config guard below therefore targets only the
              ;; real wedge: events STRANDED on un-drained sessions
              ;; (live = 0, deliverable > 0).
              (pos? live)
              (do (clock/idle! run-clock nil quiescent-sleep-ms)
                  (recur 0))

              ;; No progress, no live work, but deliverable events
              ;; remain (stranded on un-pumped sessions — almost
              ;; always a multiplex chart missing `^:multi-session?`).
              ;; This is the genuine wedge: bump; at the threshold
              ;; emit :runner/error and return a terminal status
              ;; (clean exit, no throw), mirroring :aborted.
              (>= (inc no-progress) max-frozen-cycles)
              (do (transcript-fn {:event :runner/error
                                  :data  {:reason           :frozen-config
                                          :live-invocations live
                                          :pending          pending
                                          :deliverable-now  deliverable
                                          :cycles           (inc no-progress)
                                          ;; `deliverable-now > 0` with no live work means events
                                          ;; are stranded on un-pumped sessions — almost always a
                                          ;; multiplex chart missing `^:multi-session?`.
                                          :hint             (when (and (zero? live) (pos? deliverable))
                                                              "deliverable events stranded on un-drained sessions — does this chart need ^:multi-session?")}})
                  :frozen-config)

              :else
              (do (clock/idle! run-clock nil quiescent-sleep-ms)
                  (recur (inc no-progress))))))))))

(defn- close-session!
  "Close `session`'s transcript sink. Safe to call twice."
  [{:keys [sink]}]
  (transcript/close! sink))

(defn- run-session!
  "The body of `run!`, run while the caller holds the session lock (see `run!`)."
  [{:keys [transcript-path checkpoint-dir] :as opts}]
  (let [{:keys [env store session-id transcript-fn] :as session} (open-session! opts)]
    (try
      (start-or-resume! session)
      (let [status     (pump! session)
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
        (close-session! session)))))

;; ---------------------------------------------------------------------------
;; Event-driven stepping (public; `escapement.engine.step` is the host-facing API)
;; ---------------------------------------------------------------------------

(defn session-state
  "Where `session` stands now, as plain data:
     * `:configuration`    — the active state ids
     * `:final?`           — true once the chart reached a top-level final state
     * `:live-invocations` — invocations in flight
     * `:next-wake-at`     — earliest due time of a pending event (epoch ms on the run's clock), or nil
     * `:pending-events`   — the session's queued events, in delivery order (`engine.queue/pending-summary`)
     * `:status`           — `:final`; `:running` (an event is due now, or an invocation is in flight);
                             `:sleeping` (only delayed events not yet due); or `:waiting` (nothing
                             pending at all: only an outside event can move it)."
  [{:keys [env store session-id multi-session?] run-clock :clock}]
  (let [queue       (::sc/event-queue env)
        qsid        (when-not multi-session? session-id)
        wmem        (sp/get-working-memory store env session-id)
        cfg         (vec (::sc/configuration wmem #{}))
        final?      (empty? cfg)
        live        (count-live-invocations env)
        next-wake   (engine-queue/next-delivery-time queue qsid)
        due-now?    (and next-wake (<= next-wake (clock/now-ms run-clock)))]
    {:configuration    cfg
     :final?           final?
     :live-invocations live
     :next-wake-at     next-wake
     :pending-events   (engine-queue/pending-summary queue qsid)
     :status           (cond final?                    :final
                             (or due-now? (pos? live)) :running
                             next-wake                 :sleeping
                             :else                     :waiting)}))

(defn open-stepping-session!
  "Open (start, or with `:resume?` resume) a session for event-by-event driving, taking `run!`'s
   options. Takes the session lock; `close-stepping-session!` releases it. Returns the session."
  [{:keys [checkpoint-dir session-id lock?] :as opts}]
  (assert session-id "session-id is required")
  (assert checkpoint-dir "checkpoint-dir is required")
  (let [lock (session-lock/acquire! checkpoint-dir session-id {:lock? (if (some? lock?) lock? true)})]
    (try
      (let [session (open-session! opts)]
        (try
          (start-or-resume! session)
          (assoc session ::lock lock)
          (catch Throwable t
            ((:transcript-fn session) {:event :runner/error
                                       :data  {:message (.getMessage t)}})
            (close-session! session)
            (throw t))))
      (catch Throwable t
        (session-lock/release! lock)
        (throw t)))))

(defn step-session!
  "Process AT MOST ONE due event of `session` (the earliest by delivery time, then arrival), run its
   whole macrostep, checkpoint, and acknowledge it. Returns `session-state` plus `:processed?` and
   `:event-name` (nil when nothing was due)."
  [{:keys [env session-id multi-session?] :as session}]
  (let [delivered (atom nil)
        handler   (event-handler session (fn [ev] (reset! delivered ev)))]
    (engine-queue/receive-one! (::sc/event-queue env) env handler
      (when-not multi-session? {:session-id session-id}))
    (let [ev @delivered]
      (assoc (session-state session)
        :processed? (some? ev)
        :event-name (:name ev)))))

(defn close-stepping-session!
  "Close a session opened with `open-stepping-session!`: closes its transcript and releases the
   session lock. Returns `session-state` as of closing."
  [session]
  (try
    (let [state (session-state session)]
      ((:transcript-fn session) {:event :runner/closed :data (select-keys state [:status :configuration])})
      state)
    (finally
      (close-session! session)
      (session-lock/release! (::lock session)))))

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
    * `:max-iterations` — **DEPRECATED / no-op.** Formerly a lifetime cap on
                          total pump iterations; removed because it counted
                          without resetting and so mis-aborted legitimately
                          infinite *event-driven* charts. The chart owns its own
                          termination; the only runner backstop is the
                          `:max-frozen-cycles` wedge guard (which resets on
                          progress). Still accepted for call-site compatibility,
                          but ignored.
    * `:quiescent-sleep-ms` (default 50) — how long to sleep when queue is empty
                                           but live invocations exist
    * `:max-frozen-cycles` (default 4000) — number of *consecutive*
                                           quiescent cycles (queue empty,
                                           no progress) tolerated while
                                           live invocations exist before
                                           the pump declares the config
                                           wedged and terminates with
                                           `:runner/error {:reason
                                           :frozen-config}`. Defaults to
                                           4000 which, at the default
                                           50ms `:quiescent-sleep-ms`, is
                                           a ~200s wall-clock budget. It is
                                           deliberately larger than any
                                           chart-level `:safety/stop` timer
                                           the bundled examples arm (≤150s),
                                           so a genuinely-stuck chart fires
                                           its OWN graceful safety stop
                                           before this runner backstop trips
                                           a `:frozen-config` error. This
                                           must comfortably exceed the
                                           longest *single* no-progress
                                           span a healthy run produces —
                                           which, for an `:llm-conversation`,
                                           is one whole LLM turn: the model
                                           may think, stream, and make
                                           several tool round-trips (each
                                           `fs_read`/`fs_edit`, etc.) with
                                           NO chart-visible event until the
                                           turn ends by firing an
                                           event-tool. On slower backends a
                                           single such turn routinely
                                           exceeds 10s, so the historical
                                           ~200/10s default tripped the
                                           guard spuriously mid-turn. The
                                           counter resets to 0 whenever the
                                           pump makes progress, so the
                                           budget only needs to cover the
                                           longest quiet stretch (one
                                           turn), not the whole run. A
                                           genuine wedge (a chart stuck in
                                           an invoking state, or events
                                           stranded on un-pumped sessions)
                                           still self-terminates once the
                                           budget elapses.
    * `:retain-history?` (optional) — when true, the default file-backed
                                      store retains an append-only,
                                      save-index-keyed copy of every
                                      checkpoint so a Level-3 replay can fork
                                      the chart from any past point. Off by
                                      default. Ignored when `:store` is
                                      supplied.
    * `:resume-events` (optional) — a vector of send-request maps
                                    (`{:event … :data …}`) injected into the
                                    chart AFTER a successful `:resume?` restore
                                    and after the built-in `:escapement/resumed`
                                    signal. Each is defaulted to `:target
                                    session-id`. Lets a host drive post-resume
                                    behavior a chart can't self-heal. Ignored on
                                    a fresh (non-resume) start.
    * `:invocation-processors` (optional) — a vector of extra statecharts
                                `InvocationProcessor`s (e.g. the library's
                                `:future` processor, or a host's own type),
                                installed alongside the built-in LLM,
                                human-input, child-chart and multiplex
                                processors. The built-ins are always present
                                and keep handling their own types.
    * `:clock` (optional) — an `escapement.engine.clock/Clock` the run reads
                             time from: delayed events' due times, and the
                             waits for them. A `manual-clock` makes timers due
                             when the test advances it, or (`:auto-advance?`)
                             straight away. Defaults to the wall clock.
    * `:lock?` (default true) — take the single-writer session lock (see
                                `escapement.engine.session-lock`) for the whole
                                run. Held from before the transcript is opened
                                until the run ends, so a SECOND process (or a
                                second `run!` in this process) pointed at the
                                same `:checkpoint-dir` + `:session-id` fails
                                immediately with a clear error instead of
                                silently clobbering checkpoints and colliding
                                transcript `:seq` numbers. It is an OS advisory
                                lock: the kernel releases it if this process is
                                killed, so a crash never leaves a stale lock.
                                Set false ONLY when the host already guarantees
                                single-writer access by other means.
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
  [{:keys [checkpoint-dir session-id lock?] :as opts}]
  [:map => :map]
  (assert session-id "session-id is required")
  (assert checkpoint-dir "checkpoint-dir is required")
  ;; Single-writer guard (`escapement.engine.session-lock`): taken BEFORE the transcript is opened
  ;; (opening truncates/appends, already a write to the session) and before any checkpoint can be
  ;; saved; released after the transcript is closed. It is an OS advisory lock, so the kernel drops
  ;; it if this process dies and it can never go stale.
  (let [lock (session-lock/acquire! checkpoint-dir session-id {:lock? (if (some? lock?) lock? true)})]
    (try
      (run-session! opts)
      (finally
        (session-lock/release! lock)))))
