(ns escapement.ui.resolvers
  "Pathom 2 resolvers for the Escapement read surface (the `--api-server` EQL API).

   Resolver bodies call ONLY `escapement.protocols` methods, so they are host-agnostic — the same
   resolvers run over the disk read store (`escapement.storage.disk-read`) on the server and over any
   future browser store. The store and the active session id ride on the Pathom env under
   `:escapement/store` and `:escapement/active-session-id`; `process` injects them per call.

   Read-only: there are no mutations. Idents:
     * `[:com.fulcrologic.statecharts/session-id <sid>]`  — a session
     * `[:transcript/id [<sid> <seq>]]`                   — one transcript event
     * `[:artifact/id [<sid> <path>]]`                    — one artifact (content lazy)"
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [escapement.protocols :as proto]
    #?@(:clj [[com.fulcrologic.statecharts.runtime :as runtime]
              [escapement.debug.control-handle :as ch]
              [escapement.debug.controller :as dbg]
              [escapement.engine.instrumented-queue :as iq]])))

(defn- store [env] (:escapement/store env))
(defn- params [env] (get-in env [:ast :params]))

;; ---------------------------------------------------------------------------
;; Pure: invocation assembly (the §5b fold over captured-I/O artifacts)
;; ---------------------------------------------------------------------------

(defn captured-kind
  "Classify a captured-I/O artifact `path` into `:seed`, `:output`, `:request`, `:response`,
   `:tool-result`, or `:other` by its locator tail."
  [path]
  (cond
    (str/ends-with? path "/seed.edn")     :seed
    (str/ends-with? path "/output.edn")   :output
    (str/ends-with? path "/request.edn")  :request
    (str/ends-with? path "/response.edn") :response
    (str/includes? path "/tool-results/") :tool-result
    :else                                 :other))

(defn invocations-from-artifacts
  "Fold captured-I/O artifact summaries into per-node invocation structure:
   a vector of `{:transcript/node-id … :node/visits [{:transcript/visit … :invocation/seed-ref …
   :invocation/turns [{:transcript/turn … :turn/request-ref … :turn/response-ref …
   :turn/tool-result-refs [...]}]}]}`. Author files are ignored; ordering is by node-id then visit
   then turn. Pure — drives both the UI drill-in and the replay primitives."
  [artifacts]
  (let [captured (filter #(= :captured-io (:artifact/class %)) artifacts)]
    (->> (group-by :transcript/node-id captured)
      (sort-by (comp str key))
      (mapv
        (fn [[node-id items]]
          {:transcript/node-id node-id
           :node/visits
           (->> (group-by :transcript/visit items)
             (sort-by key)
             (mapv
               (fn [[visit vitems]]
                 (let [seed  (first (filter #(= :seed (captured-kind (:artifact/path %))) vitems))
                       turns (->> (group-by :transcript/turn (filter :transcript/turn vitems))
                               (sort-by key)
                               (mapv
                                 (fn [[turn titems]]
                                   (let [by-kind (group-by #(captured-kind (:artifact/path %)) titems)]
                                     {:transcript/turn       turn
                                      :turn/request-ref      (:artifact/path (first (:request by-kind)))
                                      :turn/response-ref     (:artifact/path (first (:response by-kind)))
                                      :turn/tool-result-refs (mapv :artifact/path (:tool-result by-kind))
                                      :turn/output-ref       (:artifact/path (first (:output by-kind)))}))))]
                   {:transcript/visit         visit
                    :invocation/seed-ref      (:artifact/path seed)
                    ;; The invocation's final output is the last turn that captured one.
                    :invocation/output-ref    (some :turn/output-ref (rseq turns))
                    :invocation/turns         turns}))))})))))

(defn reconstruct-invocation
  "Pure: reconstruct one llm-conversation node INVOCATION (entry→exit) at (`node-id`, `visit`) of
   `session-id` into a UI-ready, ordered `:invocation/timeline`.

   `artifacts` is the session's `list-artifacts` seq; `events` is its (normalized) `read-events` seq.
   Joins the captured-I/O turn blobs (request/response/tool-results/output — keyed by
   node-id/visit/turn) with the `:llm/event-posted` transcript rows (the statechart events the
   conversation fired — also keyed by node-id/visit/turn) so the timeline reads like a transcript:
   for each turn, a `:turn` entry (its request/response/tool-result/output refs) followed by that
   turn's `:fired-event` entries (ordered by `:transcript/seq`). Returns `nil` when no captured
   artifacts exist for the invocation."
  [{:keys [session-id node-id visit artifacts events]}]
  (let [node (first (filter #(= node-id (:transcript/node-id %))
                      (invocations-from-artifacts artifacts)))
        vmap (first (filter #(= visit (:transcript/visit %)) (:node/visits node)))]
    (when vmap
      (let [evs      (filter #(and (= node-id (:transcript/node-id %))
                                (= visit (:transcript/visit %))) events)
            start    (first (filter #(= :llm/start (:transcript/kind %)) evs))
            posted   (->> evs
                       (filter #(= :llm/event-posted (:transcript/kind %)))
                       (group-by :transcript/turn))
            fired-of (fn [turn]
                       (->> (get posted turn)
                         (sort-by :transcript/seq)
                         (mapv (fn [e]
                                 {:timeline/kind   :fired-event
                                  :transcript/turn turn
                                  :transcript/seq  (:transcript/seq e)
                                  :transcript/ts   (:transcript/ts e)
                                  :event/name      (get-in e [:transcript/data :event-name])
                                  :event/data      (get-in e [:transcript/data :event-data])}))))
            timeline (into []
                       (mapcat (fn [t]
                                 (cons (assoc t :timeline/kind :turn)
                                   (fired-of (:transcript/turn t)))))
                       (:invocation/turns vmap))]
        {:llm.conversation/invocation-id [session-id node-id visit]
         ::sc/session-id                 session-id
         :transcript/node-id             node-id
         :transcript/visit               visit
         :invocation/seed-ref            (:invocation/seed-ref vmap)
         :invocation/started-at          (:transcript/ts start)
         :invocation/turn-count          (count (:invocation/turns vmap))
         :llm.conversation/output-ref    (:invocation/output-ref vmap)
         :invocation/timeline            timeline}))))

(defn remove-functions
  "Recursively replace functions in `data` with the `:fn` placeholder so a chart definition (which
   holds `:expr`/`:cond` fns) serializes to transit. Ported from the statecharts visualization
   server resolvers."
  [data]
  (walk/postwalk (fn [x] (if (fn? x) :fn x)) data))

;; ---------------------------------------------------------------------------
;; Resolvers
;; ---------------------------------------------------------------------------

(pc/defresolver active-session-resolver
  "Global: the currently-running session (the run that started this `--api-server`), if any. Lets a
   client discover the live session without knowing its id."
  [env _]
  {::pc/output [:escapement/active-session-id
                {:escapement/active-session [::sc/session-id]}]}
  (when-let [sid (:escapement/active-session-id env)]
    {:escapement/active-session-id sid
     :escapement/active-session    {::sc/session-id sid}}))

(pc/defresolver all-sessions-resolver
  "Global: every session under the sessions-root, most-recently-started first (past-session browsing)."
  [env _]
  {::pc/output [{:escapement/all-sessions
                 [::sc/session-id ::sc/statechart-src :session/started-at :session/ended-at
                  :session/status :session/event-count :session/resume?]}]}
  {:escapement/all-sessions (vec (proto/list-sessions (store env)))})

(pc/defresolver session-resolver
  "Detail for one session, found within the session index (host-agnostic — uses only `list-sessions`)."
  [env {sid ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [::sc/statechart-src :session/started-at :session/ended-at :session/status
                :session/event-count :session/resume?]}
  (first (filter #(= sid (::sc/session-id %)) (proto/list-sessions (store env)))))

(pc/defresolver transcript-resolver
  "A session's transcript events, server-paged/filtered via params `{:limit :from-seq :to-seq
   :types}`. Each event is normalized and carries its `[:transcript/id [sid seq]]` ident; heavy
   `:transcript/data` rides along (the §0 `:io/ref` keeps it small)."
  [env {sid ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [{:session/events [:transcript/id ::sc/session-id :transcript/seq :transcript/ts
                                  :transcript/kind :transcript/data :io/ref :io/snippet]}]}
  (let [{:keys [limit from-seq to-seq types]} (params env)
        query (cond-> {}
                limit    (assoc :limit limit)
                from-seq (assoc :from-seq from-seq)
                to-seq   (assoc :to-seq to-seq)
                types    (assoc :types (set types)))]
    {:session/events
     (mapv #(assoc % ::sc/session-id sid :transcript/id [sid (:transcript/seq %)])
       (proto/read-events (store env) sid query))}))

(pc/defresolver artifacts-resolver
  "A session's artifact summaries (author files + captured-I/O blobs). Content is NOT included here
   — it loads lazily via `artifact-content-resolver`."
  [env {sid ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [{:session/artifacts [:artifact/id ::sc/session-id :artifact/path :artifact/size
                                     :artifact/content-type :artifact/class :transcript/node-id
                                     :transcript/visit :transcript/turn]}]}
  {:session/artifacts
   (mapv #(assoc % ::sc/session-id sid :artifact/id [sid (:artifact/path %)])
     (proto/list-artifacts (store env) sid))})

(pc/defresolver session-events-report-resolver
  "Global report source for the RAD EventsReport. Reads the session id from query params
   (`::sc/session-id`, supplied by the report's session-id control) plus the usual paging/filter
   params `{:limit :from-seq :to-seq :types}`, and returns the same rows as `transcript-resolver`.

   RAD reports load their `source-attribute` as a ROOT key with control values as params, so an
   ident-rooted resolver (which needs `::sc/session-id` as entity input) cannot serve them — this
   global variant pulls the session id out of params instead."
  [env _]
  {::pc/output [{:escapement/session-events
                 [:transcript/id ::sc/session-id :transcript/seq :transcript/ts
                  :transcript/kind :transcript/data :io/ref :io/snippet]}]}
  (let [{:keys [limit from-seq to-seq types] sid ::sc/session-id} (params env)
        query (cond-> {}
                limit    (assoc :limit limit)
                from-seq (assoc :from-seq from-seq)
                to-seq   (assoc :to-seq to-seq)
                types    (assoc :types (set types)))]
    {:escapement/session-events
     (if sid
       (mapv #(assoc % ::sc/session-id sid :transcript/id [sid (:transcript/seq %)])
         (proto/read-events (store env) sid query))
       [])}))

(pc/defresolver session-artifacts-report-resolver
  "Global report source for the RAD ArtifactsReport. Reads the session id from query params
   (`::sc/session-id`, supplied by the report's session-id control) and returns the same rows as
   `artifacts-resolver`. Global (rather than ident-rooted) so a RAD report can load it as a root key."
  [env _]
  {::pc/output [{:escapement/session-artifacts
                 [:artifact/id ::sc/session-id :artifact/path :artifact/size
                  :artifact/content-type :artifact/class :transcript/node-id
                  :transcript/visit :transcript/turn]}]}
  (let [{sid ::sc/session-id} (params env)]
    {:escapement/session-artifacts
     (if sid
       (mapv #(assoc % ::sc/session-id sid :artifact/id [sid (:artifact/path %)])
         (proto/list-artifacts (store env) sid))
       [])}))

(pc/defresolver node-invocations-resolver
  "The §5b invocation drill-in for a session: per-node, per-visit, per-turn request/response/
   tool-result/output refs, folded from the captured-I/O artifact tree."
  [env {sid ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [{:session/node-invocations
                 [:transcript/node-id
                  {:node/visits [:transcript/visit :invocation/seed-ref :invocation/output-ref
                                 {:invocation/turns [:transcript/turn :turn/request-ref
                                                     :turn/response-ref :turn/tool-result-refs
                                                     :turn/output-ref]}]}]}]}
  {:session/node-invocations (invocations-from-artifacts (proto/list-artifacts (store env) sid))})

(pc/defresolver node-invocation-ids-resolver
  "Enumerate a session's invocation idents so a UI can list them without already knowing
   `(node-id, visit)`. Each entry carries the `[:llm.conversation/invocation-id [sid node-id visit]]`
   ident the `invocation-transcript-resolver` reconstructs from."
  [env {sid ::sc/session-id}]
  {::pc/input  #{::sc/session-id}
   ::pc/output [{:session/invocations [:llm.conversation/invocation-id ::sc/session-id
                                       :transcript/node-id :transcript/visit]}]}
  {:session/invocations
   (vec (for [n (invocations-from-artifacts (proto/list-artifacts (store env) sid))
              v (:node/visits n)]
          {:llm.conversation/invocation-id [sid (:transcript/node-id n) (:transcript/visit v)]
           ::sc/session-id                 sid
           :transcript/node-id             (:transcript/node-id n)
           :transcript/visit               (:transcript/visit v)}))})

(pc/defresolver invocation-transcript-resolver
  "Reconstruct one llm-conversation node invocation (entry→exit) — keyed by its
   `[:llm.conversation/invocation-id [sid node-id visit]]` ident — into an ordered
   `:invocation/timeline` of turns interleaved with the statechart events it fired. See
   `reconstruct-invocation`."
  [env {id :llm.conversation/invocation-id}]
  {::pc/input  #{:llm.conversation/invocation-id}
   ::pc/output [:llm.conversation/invocation-id ::sc/session-id :transcript/node-id :transcript/visit
                :invocation/seed-ref :invocation/started-at :invocation/turn-count
                :llm.conversation/output-ref
                {:invocation/timeline [:timeline/kind :transcript/turn :transcript/seq :transcript/ts
                                       :turn/request-ref :turn/response-ref :turn/tool-result-refs
                                       :turn/output-ref :event/name :event/data]}]}
  (let [[sid node-id visit] id
        st                  (store env)]
    (reconstruct-invocation {:session-id sid :node-id node-id :visit visit
                             :artifacts  (proto/list-artifacts st sid)
                             :events     (proto/read-events st sid {})})))

(pc/defresolver output-resolver
  "Lazy full value of an invocation's captured OUTPUT: derefs the `:llm.conversation/output-ref`
   handle (an `output.edn` locator) via `read-artifact` and parses the EDN to `{:text :verdict
   :from}`. Mirrors `artifact-content-resolver` — the handle rides the read; the value is computed
   only when `:llm.conversation/output` is actually queried. Needs `::sc/session-id` alongside the
   ref (both ride the invocation entity) so the store can resolve the blob."
  [env input]
  {::pc/input  #{:llm.conversation/output-ref ::sc/session-id}
   ::pc/output [:llm.conversation/output]}
  (let [ref (:llm.conversation/output-ref input)
        sid (::sc/session-id input)]
    (when ref
      {:llm.conversation/output
       (some-> (proto/read-artifact (store env) sid ref)
         (->> (edn/read-string {:default tagged-literal})))})))

(pc/defresolver artifact-content-resolver
  "Lazy full content of one artifact, keyed by its `[:artifact/id [sid path]]` ident."
  [env input]
  {::pc/input  #{:artifact/id}
   ::pc/output [:artifact/content]}
  (let [[sid path] (:artifact/id input)]
    {:artifact/content (proto/read-artifact (store env) sid path)}))

(pc/defresolver chart-definition-resolver
  "The active session's chart definition, with functions stripped for transit. Returns nothing when
   no chart was injected on the env (`:escapement/chart`)."
  [env _]
  {::pc/output [{:escapement/chart [:chart/definition]}]}
  (when-let [chart (:escapement/chart env)]
    {:escapement/chart {:chart/definition (remove-functions chart)}}))

;; ---------------------------------------------------------------------------
;; Live control plane (server-only: pause/step/continue + live introspection).
;;
;; These read/mutate the LIVE run via the `:escapement/live` control handle (an
;; atom filled by `run!`'s on-env-ready) and `:escapement/controller`. They are
;; nil-tolerant: before the chart starts (handle empty) or on a read-only past
;; session (no controller), they return nil/empty so the read surface keeps
;; working. Gated `:clj` because the controller/handle/runtime live under bb+JVM
;; only; the browser SPA never runs them.
;; ---------------------------------------------------------------------------

#?(:clj
   (do
     (defn- controller [env] (:escapement/controller env))

     (defn- controller-state
       "Plain snapshot of `controller`'s pause status for mutation returns and
        live resolvers; nil when no controller is present."
       [controller]
       (when controller
         {:debug/paused?     (dbg/paused? controller)
          :debug/step-budget (:step-budget @controller)}))

     (pc/defresolver session-paused-resolver
       "Live: is the running session currently paused at the debug gate? nil when
        no controller is attached (read-only/past session)."
       [env _]
       {::pc/output [:session/paused?]}
       (when-let [c (controller env)]
         {:session/paused? (dbg/paused? c)}))

     (pc/defresolver session-step-budget-resolver
       "Live: the controller's current step budget (events the gate will pass
        before re-pausing). nil when no controller is attached."
       [env _]
       {::pc/output [:session/step-budget]}
       (when-let [c (controller env)]
         {:session/step-budget (:step-budget @c)}))

     (pc/defresolver session-live-configuration-resolver
       "Live: the set of active state ids of the running session, read from the
        live working-memory store via `runtime/current-configuration`. nil when
        the live env is not yet available."
       [env _]
       {::pc/output [:session/live-configuration]}
       (when-let [{:keys [env session-id]} (ch/live (:escapement/live env))]
         (when (and env session-id)
           {:session/live-configuration (vec (runtime/current-configuration env session-id))})))

     (pc/defresolver session-pending-events-resolver
       "Live: transit-safe summaries of events queued-but-not-yet-delivered on the
        live instrumented queue. Empty when no instrumented queue is in play."
       [env _]
       {::pc/output [{:session/pending-events [:event/name :event/data :event/target
                                               :event/external? :event/delivery-time]}]}
       (let [{:keys [queue]} (ch/live (:escapement/live env))]
         {:session/pending-events
          (if (instance? escapement.engine.instrumented_queue.InstrumentedQueue queue)
            (iq/pending-events queue)
            [])}))

     ;; NOTE: pc/defmutation does NOT take a docstring (arglist is
     ;; [sym arglist config & body]); descriptions live in these comments.

     ;; Pause the live run at the debug gate. Returns the new pause status.
     (pc/defmutation pause-mutation [env _]
       {::pc/sym    'escapement.control/pause
        ::pc/output [:debug/paused? :debug/step-budget]}
       (when-let [c (controller env)] (dbg/pause! c))
       (controller-state (controller env)))

     ;; Advance the live run by exactly one event, then re-pause.
     (pc/defmutation step-mutation [env _]
       {::pc/sym    'escapement.control/step
        ::pc/output [:debug/paused? :debug/step-budget]}
       (when-let [c (controller env)] (dbg/step! c))
       (controller-state (controller env)))

     ;; Resume the live run (clear pause + budget, release the gate).
     (pc/defmutation continue-mutation [env _]
       {::pc/sym    'escapement.control/continue
        ::pc/output [:debug/paused? :debug/step-budget]}
       (when-let [c (controller env)] (dbg/continue! c))
       (controller-state (controller env)))

     ;; Arm the controller so the next external event pauses the run.
     (pc/defmutation arm-pause-on-next-external-mutation [env _]
       {::pc/sym    'escapement.control/arm-pause-on-next-external
        ::pc/output [:debug/paused? :debug/step-budget]}
       (when-let [c (controller env)] (dbg/arm-pause-on-next-external! c))
       (controller-state (controller env)))

     ;; Deliver a human-input answer to a parked RemoteUiRenderer prompt (the
     ;; secondary/fallback transport for the WS `answer` frame; see
     ;; docs/opentui-wire.md §5.2). Params: {:prompt-id <s> :value <v>} or
     ;; {:prompt-id <s> :cancelled true}. Resolved via `requiring-resolve` so
     ;; this cljc ns keeps no static dep on the renderer add-on. Returns whether
     ;; a pending prompt was matched.
     (pc/defmutation human-answer-mutation [env _]
       {::pc/sym    'escapement.human/answer
        ::pc/output [:human/delivered?]}
       (let [{:keys [prompt-id value cancelled]} (params env)
             ns-sym 'escapement.ui.remote-renderer
             ok?    (when prompt-id
                      (if cancelled
                        ((requiring-resolve (symbol (name ns-sym) "cancel-answer!")) prompt-id)
                        ((requiring-resolve (symbol (name ns-sym) "deliver-answer!")) prompt-id value)))]
         {:human/delivered? (boolean ok?)}))))

(def read-resolvers
  [active-session-resolver all-sessions-resolver session-resolver transcript-resolver
   artifacts-resolver session-events-report-resolver session-artifacts-report-resolver
   node-invocations-resolver node-invocation-ids-resolver invocation-transcript-resolver
   output-resolver artifact-content-resolver chart-definition-resolver])

(def live-resolvers
  "Server-only live resolvers + control mutations (empty under cljs)."
  #?(:clj  [session-paused-resolver session-step-budget-resolver
            session-live-configuration-resolver session-pending-events-resolver
            pause-mutation step-mutation continue-mutation
            arm-pause-on-next-external-mutation human-answer-mutation]
     :cljs []))

(def all-resolvers
  (into read-resolvers live-resolvers))

;; ---------------------------------------------------------------------------
;; Parser
;; ---------------------------------------------------------------------------

(def parser
  "A Pathom 2 parser wired with all read resolvers AND the live control mutations (lazily constructed
   on first use). `::p/mutate pc/mutate` is set so a POST of an EQL mutation (e.g.
   `[(escapement.control/step {})]`) dispatches to the registered `pc/defmutation`s — the read parser
   alone could not mutate. Call via `process` (which injects the store, active-session id, and the
   live control handle), or deref and call directly with an env carrying `:escapement/store`."
  (delay
    (p/parser
      {::p/mutate  pc/mutate
       ::p/env     {::p/reader [p/map-reader pc/reader2 pc/ident-reader pc/index-reader]}
       ::p/plugins [(pc/connect-plugin {::pc/register all-resolvers})
                    ;; Strip Pathom's `::p/not-found` sentinel from results: a queried-but-unresolved
                    ;; attribute (e.g. `:io/snippet` on an event that has none) otherwise leaks the
                    ;; keyword `:com.wsscode.pathom.core/not-found` to the client, where a RAD report
                    ;; cell renders it as a raw React child and throws. Eliding returns it as absent/nil.
                    (p/post-process-parser-plugin p/elide-not-found)
                    p/error-handler-plugin]})))

(defn process
  "Run EQL `query` (reads or control mutations) against the resolvers with `ctx` injected on the env.
   `ctx` carries `:escapement/store` (required), and optionally `:escapement/active-session-id`,
   `:escapement/chart`, `:escapement/controller` (the live debug controller), and `:escapement/live`
   (the control handle filled by `run!`'s on-env-ready). Returns the EQL result map."
  [ctx query]
  (@parser ctx query))
