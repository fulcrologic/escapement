(ns escapement.chart.deferred-reply
  "Deferred-reply primitive for `:llm-conversation` event-tools.

  An event-tool entry in `:allowed-events` may carry an `:awaits` map; the
  worker then DEFERS its `tool_result` until the chart fires a matching
  reply event via [[complete-call]]. The mechanism is essentially: model
  emits `tool_use`; chart event fires; some other chart transition picks
  up a reply event and fires `complete-call`; the worker's `tool_result`
  is populated with the reply event's `:data` and the assistant turn
  proceeds.

  The trigger of the reply event can be ANYTHING in the chart — a peer
  LLM emitting its own event-tool (the `matrix-team` demo's pattern), a
  timer, a human-input invocation, a service-region handler, a script
  in a sibling parallel region. The name \"deferred reply\" reflects
  that generality: the call IS the request, the chart-side `complete-call`
  IS the reply, and what sits between them is up to the chart author.

  ## In-flight correlation

  The chart-scoped `::in-flight` atom (initialized on `env` by
  `escapement.engine.env/new-env`) maps each *answering* event-keyword
  declared in `:awaits :on` to a vector of slot maps:

      {:reply-id            \"tr_…\"
       :reply-to            \"experimenter\"        ;; requesting invokeid
       :requesting-event-kw :new-version
       :on                  #{:tester/passed :tester/failed}
       :error-events        #{:tester/failed}       ;; optional
       :issued-at           1700000000000}

  The `llm-conversation` worker writes entries when it issues a
  `:awaits`-tagged event-tool call (one entry per kw in `:on`, sharing
  the same `:reply-id`) and removes them all when a reply arrives or the
  call times out.

  [[complete-call]] pops the first matching slot for the firing event
  and posts a `:escapement.tool/reply` carrying the answering event's
  payload. With no matching slot it is a silent no-op — the answering
  event still fires for ordinary chart-side state machinery, but no
  spurious reply is generated.

  ## Wire shape

  The reply event sent back to the worker looks like:

      {:event :escapement.tool/reply
       :data  {:escapement.tool/reply-id        \"tr_…\"
               :escapement.tool/reply-to        \"experimenter\"
               :escapement.tool/answering-event :tester/passed
               :data                            {:summary \"...\"}
               :is-error                        false
               ;; Legacy back-compat keys for region-tool wire compatibility:
               :result                          \"{:summary \\\"...\\\"}\"}}

  See `region-tools.md` and the `escapement.chart.service` namespace for
  the analogous service-region pattern this mirrors."
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn >defn- =>]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.elements :as elt]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]))

(>defn in-flight
       "Returns the chart-scoped `::in-flight` atom on `env`, or nil when
   the env wasn't built by `escapement.engine.env/new-env`. Public for
   test inspection only; chart-author code should never mutate this
   directly."
       [env]
       [map? => [:maybe [:fn #(instance? clojure.lang.IAtom %)]]]
       (::in-flight env))

(>defn- pop-slot!
        "Pop the FIRST in-flight slot for answering `event-kw`. Returns the
   popped slot map (with `:reply-id`, `:reply-to`, …) or nil if no slot
   matches. The popped slot is also removed from every other `:on` key
   that shared the same `:reply-id`, so a single answer doesn't leave
   stale entries that could mis-route a later reply.

   The wildcard key `:escapement.tool/any` is consulted as a fallback
   when the worker's `:on` set was empty (\"any reply with the right
   reply-id wins\").

   PRIVATE: only callable from [[complete-call]] within this namespace.
   Tests that need to drive the cleanup-invariant directly should use
   `#'pop-slot!` to bypass the privacy guard — they are exercising an
   implementation detail, not a stable API."
        [env event-kw]
        [map? :keyword => [:maybe :map]]
        (when-let [atm (in-flight env)]
          (let [[popped reply-id]
                (loop [keys-to-try [event-kw :escapement.tool/any]]
                  (if-let [k (first keys-to-try)]
                    (let [entries (get @atm k)]
                      (if (seq entries)
                        [(first entries) (:reply-id (first entries))]
                        (recur (rest keys-to-try))))
                    [nil nil]))]
            (when popped
              (swap! atm
                     (fn [m]
                       (reduce-kv
                        (fn [acc k entries]
                          (let [filtered (filterv #(not= (:reply-id %) reply-id)
                                                  entries)]
                            (if (seq filtered) (assoc acc k filtered) acc)))
                        {} m)))
              popped))))

(defn complete-call
  "Returns a `script` element. Place it inside a `transition` on a chart
   event that should answer a deferred event-tool call — i.e. a call
   the LLM made via an `event__*` tool whose `:allowed-events` entry
   declared `:awaits`.

   On execution, `complete-call` pops the first matching in-flight slot
   from the chart-scoped `::in-flight` atom (the worker registered it
   when the originating `tool_use` was processed; see
   `escapement.invocation.llm-conversation`'s `:awaits` branch) and
   fires `:escapement.tool/reply` with the originating `:reply-id` and
   `:reply-to`. The reply event's `:data` is the firing event's `:data`
   verbatim, plus `:escapement.tool/answering-event` carrying the
   firing event's keyword so the worker's `:awaits` predicate can
   constrain to the declared `:on` set.

   No options. The transition's `:event` keyword and the firing event's
   `:data` are all the information needed.

   If there is no in-flight slot (e.g. the requester already timed out,
   or this event isn't actually a deferred-reply trigger),
   `complete-call` is a SILENT no-op — the chart event still fires for
   ordinary state-machine purposes, but no `:escapement.tool/reply` is
   generated. This is deliberate: it lets chart authors use the same
   event for both \"answer the in-flight call\" and \"advance some
   other state\" without coupling the two."
  []
  (elt/script
   {:expr
    (fn [env data]
      (let [ev-name (get-in data [:_event :name])
            ev-data (or (get-in data [:_event :data]) {})
            slot    (when ev-name (pop-slot! env ev-name))
            queue   (::sc/event-queue env)
            sid     (env-ns/session-id env)]
        (when (and slot queue sid)
          (let [reply
                {:target            sid
                 :source-session-id sid
                 :event             :escapement.tool/reply
                 :data              (cond-> {:escapement.tool/reply-id        (:reply-id slot)
                                             :escapement.tool/reply-to        (:reply-to slot)
                                             :escapement.tool/answering-event ev-name
                                             :data                            ev-data
                                             :is-error                        (boolean
                                                                               (and (seq (:error-events slot))
                                                                                    (contains? (:error-events slot) ev-name)))}
                                      ;; Back-compat legacy keys for any
                                      ;; observer that watches region-tool replies.
                                      true (assoc :result (pr-str ev-data)))}]
            (sp/send! queue env reply)))
        nil))}))
