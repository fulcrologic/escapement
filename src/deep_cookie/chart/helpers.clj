(ns deep-cookie.chart.helpers
  "Authoring sugar for chart elements that talk to the `:llm-conversation`
  InvocationProcessor.

  * `llm-conversation` — wraps `(invoke {:type :llm-conversation ...})`. Forces
    `:autoforward? true` so the chart can push events back into the live LLM via
    `tell-llm`.
  * `tell-llm` — emits a `:script` element that posts a `:llm.user-message` event
    onto the chart's own session. With `:autoforward?` set on the invoke, the
    library will hand the event to the processor's `forward-event!`, which routes
    the message into the live conversation.

  IMPORTANT: `tell-llm` must execute inside the state that owns the binding (or a
  descendant thereof); the invocation only exists while that state is active."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.elements :as elt]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]))

(defn llm-conversation
  "Returns an `invoke` element of type `:llm-conversation`.

   `opts`:
    * `:id` (required) — invoke id (used as the `invokeid` in the processor)
    * `:params-fn` (required) — `(fn [env data] params-map)` returning the params
      consumed by the processor (see `deep-cookie.invocation.llm-conversation`).
    * `:autoforward?` (optional, default true) — whether to autoforward chart events
      to the invocation so `tell-llm` works."
  [{:keys [id params-fn autoforward?] :or {autoforward? true}}]
  (assert id "llm-conversation requires :id")
  (assert (fn? params-fn) "llm-conversation requires :params-fn")
  (elt/invoke {:type          :llm-conversation
               :id            id
               :autoforward   autoforward?
               :auto-forward? autoforward?
               :params        params-fn}))

(defn tell-llm
  "Returns a `script` element that, when executed, posts a `:llm.user-message`
   event into the current session (the chart's session). When the chart has a
   live `:llm-conversation` invocation with `:autoforward? true`, this message
   is forwarded into the LLM as a new user turn.

   `opts`:
    * `:expr` (required) — `(fn [env data] text-string)` returning the user text."
  [{:keys [expr]}]
  (assert (fn? expr) "tell-llm requires :expr")
  (elt/script
   {:expr (fn [env data]
            (let [text  (expr env data)
                  queue (::sc/event-queue env)
                  sid   (env-ns/session-id env)]
              (sp/send! queue env
                        {:target            sid
                         :source-session-id sid
                         :event             :llm.user-message
                         :data              {:text text}})
              nil))}))
