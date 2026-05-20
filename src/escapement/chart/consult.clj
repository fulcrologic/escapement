(ns escapement.chart.consult
  "Consultation tools — a sanctioned way for one LLM region to ask a sibling
  specialist LLM region a question and get a typed answer back.

  From the asker LLM's point of view, a consultation is an ordinary region
  tool: JSON in, JSON out. It has no awareness that the answer comes from
  another LLM, no awareness of chart events, no awareness of region
  structure. The asker calls `region__<consult-name>` with input matching
  `:input-schema`; eventually it receives a `tool_result` whose content is
  the JSON-encoded verdict the specialist produced.

  Under the hood, [[declare-consultation]] returns a chart state element.
  The state:

    * owns an `:llm-conversation` invocation for the specialist
    * registers a region tool (the consultation entry point)
    * routes incoming tool requests to the specialist as
      `:llm.user-message` (targeted)
    * waits for the specialist's `:llm.idle` event carrying `:verdict`
      (produced by the specialist's `:verdict-schema` wrap-up inference)
    * replies to the asker by encoding the verdict as JSON and calling
      `post-reply` with the correlation ids captured from the request

  The specialist invocation **must** declare a `:verdict-schema` in its
  params or the consultation has nothing typed to forward — the
  declaration helper does this for you given the `:verdict-schema` arg.

  Exactly one consultation may be in flight per consult state at a time.
  The chart's region-tool service queue is serial within an asker worker,
  so this matches reality. A second concurrent call (e.g. a fan-out across
  multiple askers) lands on a queue inside the consult state and is
  serviced FIFO."
  (:require
    [cheshire.core :as json]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts.elements :as elt]
    [escapement.chart.helpers :as h]
    [escapement.chart.service :as service]
    [escapement.invocation.llm-conversation :as llmc]))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- verdict->tool-result-content
  "Encode the specialist's typed verdict for the asker LLM's `tool_result`
   content slot. JSON keeps it model-agnostic and shape-stable."
  [verdict]
  (json/generate-string verdict))

(defn- payload->user-message-text
  "Format the asker's tool input as a user message for the specialist.

   The input is a Clojure map (already validated against the consult's
   `:input-schema`). We serialise it as compact JSON prefixed with a short
   header so the specialist sees a stable, parseable request. Chart-authors
   may override this via `:render-request` on `declare-consultation`."
  [payload]
  (str "Consultation request:\n" (json/generate-string payload)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(>defn declare-consultation
  "Returns a chart state element implementing a consultation tool. Add it
   as a child of any state that should host the consultation, and
   reference its `:state-id` from the asker conversation's
   `:chart-tools`.

   Opts:
     * `:state-id`            (required, keyword) — id of the state this
       helper produces. Use it on the consumer side as
       `:chart-tools [{:owner <state-id>}]`.
     * `:tool-name`           (required, keyword) — the region-tool
       keyword the asker LLM sees. Becomes `region__<name>` on the wire.
     * `:description`         (required, string) — exposed to the asker LLM.
     * `:specialist-invokeid` (required, keyword or string) — invokeid of
       the specialist `:llm-conversation` invocation this helper creates.
       Used as `:target` on `:llm.user-message` posts so the message
       reaches only this specialist.
     * `:input-schema`        (required, Malli) — validates the asker's
       tool input.
     * `:verdict-schema`      (required, Malli) — the specialist's
       `:verdict-schema`. The specialist's idle-time wrap-up inference
       forces a `submit_verdict` call against this schema; the resulting
       payload is what becomes the asker's tool_result content.
     * `:system`              (optional, string) — system prompt for the
       specialist conversation.
     * `:specialist-params`   (optional, map) — extra params merged into
       the specialist's `llm-conversation` params (e.g. `:model`,
       `:real-tools`). `:verdict-schema` from `opts` always wins.
     * `:render-request`      (optional, fn) — `(fn [payload] text)` that
       formats the asker's input as the user message text for the
       specialist. Defaults to a JSON-prefixed body.

   Notes:

     * The specialist's `:llm-conversation` is owned by the consult
       state, so it lives only while the consult state is active. Stop
       the consult state to stop the specialist.
     * Pending correlation ids are stashed on a per-state atom captured
       by closure — no data-model writes. Concurrent calls land in a
       FIFO queue inside the state.
     * The asker LLM has no awareness that the tool's implementation
       runs another LLM. It sees JSON in, JSON out.
     * Late or unmatched specialist idle events are ignored."
  [{:keys [state-id tool-name description specialist-invokeid
           input-schema verdict-schema
           system specialist-params render-request]
    :as   _opts}]
  [[:map
    [:state-id :keyword]
    [:tool-name :keyword]
    [:description :string]
    [:specialist-invokeid [:or :keyword :string]]
    [:input-schema :any]
    [:verdict-schema :any]
    [:system {:optional true} :string]
    [:specialist-params {:optional true} :map]
    [:render-request {:optional true} fn?]]
   => any?]
  (let [render             (or render-request payload->user-message-text)
        specialist-sid     (llmc/->id-str specialist-invokeid)
        ;; Per-instance pending queue: vector of {:reply-id :reply-to}
        ;; maps, head is the in-flight request. Closed over by the
        ;; element lambdas, so it lives as long as the chart does.
        ;; Specialists serialise turns by construction (one
        ;; `:awaiting-user` at a time), so we use a queue rather than
        ;; a slot and don't try to interleave verdicts.
        pending            (atom [])
        specialist-params' (cond-> (or specialist-params {})
                             true (assoc :verdict-schema verdict-schema)
                             system (assoc :system system))]
    (elt/state
      {:id state-id}

      ;; Register the consultation as a region tool on entry; unregister
      ;; on exit. Owner is this state, so consumers reference it via
      ;; :chart-tools [{:owner state-id}].
      (elt/on-entry
        {}
        (service/register-tool!
          {:tool         tool-name
           :description  description
           :input-schema input-schema}))
      (elt/on-exit
        {}
        (service/unregister-tool! tool-name))

      ;; The specialist conversation — owned by this state, so its
      ;; lifecycle is the consult state's lifecycle. The verdict-schema
      ;; on the specialist's params drives the submit_verdict wrap-up
      ;; inference that produces the typed answer this helper forwards
      ;; back to the asker as tool_result.
      (h/llm-conversation
        {:id        specialist-invokeid
         :params-fn (fn [_ _] specialist-params')})

      ;; Asker called the tool → enqueue correlation, send the request
      ;; to the specialist as a targeted user-message, then defer the
      ;; reply (handler returns nil; region-tool transport keeps the
      ;; asker parked until we post-reply).
      (h/handle-tool
        tool-name
        (fn [env request]
          (let [{:keys [data reply-id reply-to]} request
                text (render data)]
            (swap! pending conj {:reply-id reply-id
                                 :reply-to reply-to})
            (h/tell-other-llm! env specialist-sid text)
            nil)))

      ;; Specialist idled → if a consult is pending and the :from
      ;; matches our specialist, satisfy the oldest pending asker
      ;; with the JSON-encoded verdict.
      (elt/transition
        {:event :llm.idle :type :internal
         :cond  (fn [_env data]
                  (let [from (get-in data [:_event :data :from])]
                    (and (seq @pending)
                      (= specialist-sid (llmc/->id-str from)))))}
        (elt/script
          {:expr
           (fn [env data]
             (let [head    (first @pending)
                   verdict (get-in data [:_event :data :verdict])
                   content (if (some? verdict)
                             (verdict->tool-result-content verdict)
                             (json/generate-string
                               {:error "specialist produced no :verdict"}))]
               (swap! pending #(vec (rest %)))
               (service/post-reply
                 env
                 {:reply-id (:reply-id head)
                  :reply-to (:reply-to head)
                  :result   content
                  :is-error (nil? verdict)})
               nil))}))

      ;; Specialist failed mid-consult → reply with an error
      ;; tool_result so the asker LLM isn't left waiting until
      ;; timeout. Matches any :error.llm.* (verdict-validation,
      ;; backend, max-turns, …).
      (elt/transition
        {:event :error.llm :type :internal
         :cond  (fn [_env _data] (seq @pending))}
        (elt/script
          {:expr
           (fn [env data]
             (let [head    (first @pending)
                   err     (or (get-in data [:_event :data]) {})
                   content (json/generate-string
                             {:error  "specialist failed"
                              :reason (:reason err)
                              :detail (dissoc err :stack)})]
               (swap! pending #(vec (rest %)))
               (service/post-reply
                 env
                 {:reply-id (:reply-id head)
                  :reply-to (:reply-to head)
                  :result   content
                  :is-error true})
               nil))})))))
