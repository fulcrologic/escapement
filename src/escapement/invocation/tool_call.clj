(ns escapement.invocation.tool-call
  "InvocationProcessor for `:type :tool-call`: call a `Tool` from the run's tool registry as an
   invocation, off the chart's thread.

     (invoke {:id :fetch :type :tool-call
              :params {:tool :mail/list-new :input {:limit 10} :timeout-ms 5000}})

   The tool (`escapement.tools.protocol/Tool`, looked up in the env's `:escapement/tool-registry`)
   runs on its own thread, so the chart keeps processing other events meanwhile. Its input is
   validated against the tool's schema first. Exactly one event comes back:

     done.invoke.<id>      the tool succeeded; data = the tool's whole result map
     error.call.tool       the tool reported an error (`:is-error true`, or invalid input, or no such
                           tool); data = the result map
     error.call.unknown    the tool reported it can't tell whether its effect happened
                           (`:outcome :unknown`; wins over `:is-error`); data = the result map
     error.call.exception  the tool threw; data = `{:message ..}`
     error.call.timeout    the call ran longer than `:timeout-ms`; data = `{:timeout-ms n}`. A
                           timed-out effect may still have happened.

   Error events' data also carries `:escapement/invokeid` and `:escapement/tool`.

   The call counts as in-flight work (`escapement.engine.liveness/ActiveCount`) until its event is
   queued, so the run never ends while it runs. Leaving the invoking state cancels it; nothing is
   reported for a cancelled call. On resume an in-flight call runs again (it is restarted with its
   state), so effectful tools need the host's own at-most-once guard.

   `escapement.engine.env/new-env` installs this processor whenever the run has a tool registry."
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.events :as evts]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.liveness :as liveness]
    [escapement.threads :as threads]
    [escapement.tools.input-keys :as input-keys]
    [escapement.tools.protocol :as tp]
    [malli.core :as m]
    [malli.error :as me]
    [taoensso.timbre :as log]))

(defn- invokeid-str [iid]
  (cond (keyword? iid) (name iid) (nil? iid) nil :else (str iid)))

(defn- call-tool
  "Run `tool-kw` from `registry` on `input`. Returns `[:result r]` or `[:exception t]`, or
   `[:tool-error r]` for a missing tool / invalid input."
  [registry tool-kw input]
  (if-let [t (and registry (tp/lookup registry tool-kw))]
    (let [schema (tp/input-schema t)
          input  (input-keys/decode-keys schema (or input {}))]
      (if (m/validate schema input)
        (try
          [:result (binding [tp/*base-dir* (:escapement/base-dir (meta registry))]
                     (tp/invoke t input))]
          (catch Throwable ex [:exception ex]))
        [:tool-error {:result   (pr-str (me/humanize (m/explain schema input)))
                      :is-error true}]))
    [:tool-error {:result   (str "No such tool: " tool-kw)
                  :is-error true}]))

(defn- outcome->event
  "Map a call outcome to `[event-name data]`."
  [invokeid tool-kw [kind v]]
  (let [tag (fn [m] (assoc m :escapement/invokeid (invokeid-str invokeid) :escapement/tool tool-kw))]
    (case kind
      :result     (cond
                    (not (map? v))                 [:error.call.tool (tag {:result (pr-str v) :is-error true})]
                    (= :unknown (:outcome v))      [:error.call.unknown (tag v)]
                    (:is-error v)                  [:error.call.tool (tag v)]
                    :else                          [(evts/invoke-done-event invokeid) v])
      :tool-error [:error.call.tool (tag v)]
      :exception  [:error.call.exception (tag {:message (or (ex-message v) (str v))})]
      :timeout    [:error.call.timeout (tag {:timeout-ms v})])))

(defrecord ToolCallProcessor [calls]
  sp/InvocationProcessor
  (supports-invocation-type? [_ typ] (= typ :tool-call))

  (start-invocation! [_ env {:keys [invokeid params]}]
    (let [sid        (env-ns/session-id env)
          queue      (::sc/event-queue env)
          registry   (:escapement/tool-registry env)
          {:keys [tool input timeout-ms]} params
          k          [sid invokeid]
          ;; :running → :done (its event is queued) | :cancelled (state left; report nothing).
          ;; Transitions happen under `locking` on this atom, and the event is sent BEFORE the
          ;; call stops counting as running, so the run never sees "nothing live, nothing queued"
          ;; in between.
          state      (atom :running)
          post!      (fn [outcome]
                       (locking state
                         (when (= :running @state)
                           (let [[event data] (outcome->event invokeid tool outcome)]
                             (sp/send! queue env {:target            sid
                                                  :source-session-id sid
                                                  :invokeid          invokeid
                                                  :event             event
                                                  :data              data}))
                           (reset! state :done))))
          work       (atom nil)
          runnable   (fn []
                       (try
                         (let [f (future (call-tool registry tool input))]
                           (reset! work f)
                           (post! (if (and timeout-ms (pos? timeout-ms))
                                    (let [r (deref f (long timeout-ms) ::timeout)]
                                      (if (= ::timeout r)
                                        (do (future-cancel f) [:timeout timeout-ms])
                                        r))
                                    @f)))
                         (catch Throwable t
                           (post! [:exception t]))
                         (finally
                           (swap! calls (fn [m] (if (identical? (:state (get m k)) state) (dissoc m k) m))))))]
      ;; A restart of the same invocation (same session + id) replaces the old call.
      (when-let [old (get @calls k)]
        (locking (:state old) (reset! (:state old) :cancelled))
        (some-> @(:work old) future-cancel))
      (swap! calls assoc k {:state state :work work})
      (.start ^Thread (threads/unstarted-daemon (str "tool-call-" sid "-" invokeid) runnable))
      true))

  (stop-invocation! [_ env {:keys [invokeid]}]
    (let [k [(env-ns/session-id env) invokeid]]
      (when-let [{:keys [state work]} (get @calls k)]
        (locking state
          (when (= :running @state) (reset! state :cancelled)))
        (some-> @work future-cancel)
        (swap! calls dissoc k))
      true))

  (forward-event! [_ _env _event]
    (log/debug "[tool-call] event forwarding is not supported")
    nil)

  liveness/ActiveCount
  (active-count [_]
    (count (filter (fn [{:keys [state]}] (= :running @state)) (vals @calls)))))

(defn new-processor
  "A fresh `:tool-call` invocation processor."
  []
  (->ToolCallProcessor (atom {})))
