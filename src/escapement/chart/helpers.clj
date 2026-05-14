(ns escapement.chart.helpers
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
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :as elt]
   [com.fulcrologic.statecharts.environment :as env-ns]
   [com.fulcrologic.statecharts.protocols :as sp]))

(defn llm-conversation
  "Returns an `invoke` element of type `:llm-conversation`.

   `opts`:
    * `:id` (required) — invoke id (used as the `invokeid` in the processor)
    * `:params-fn` (required) — `(fn [env data] params-map)` returning the params
      consumed by the processor (see `escapement.invocation.llm-conversation`).
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

(defn human-input
  "Returns an `invoke` element of type `:human-input`.

   When the state owning this invocation is entered, the human-input processor
   spawns a worker that prompts the user via the active `HumanRenderer` (TUI
   modal when a TUI is running, stdin fallback otherwise). The worker posts
   `:human.answer {:answer …}` (or `:on-answer-event`) to the chart's session
   when the user responds, then dies.

   `opts`:
    * `:id` (required) — invoke id.
    * `:params-fn` (required) — `(fn [env data] params-map)`. params-map keys:
        :kind             :text | :select | :multi-select | :confirm | :progress | :custom
        :prompt           string shown to the user
        :options          [{:label … :value …}]  ; :select / :multi-select
        :answer-schema    optional Malli schema validating the answer
        :on-answer-event  default :human.answer
        :on-cancel-event  default :human.cancelled
        :on-error-event   default :human.error
        :render           required for :custom; (fn [env data] answer)"
  [{:keys [id params-fn]}]
  (assert id "human-input requires :id")
  (assert (fn? params-fn) "human-input requires :params-fn")
  (elt/invoke {:type   :human-input
               :id     id
               :params params-fn}))

(declare tell-llm)

(defn with-llm-questions
  "Authoring sugar for the \"LLM can ask the human for design decisions or
   clarifications mid-conversation\" pattern.

   Produces a compound STATE (not an invoke) containing:
     1. An `llm-conversation` invocation owned by the parent state, so the
        conversation stays alive across question detours.
     2. A `:converse` child state that hosts the user-supplied exit transitions.
     3. Two ask child states (`:ask-choice`, `:ask-text`) that pop a
        `human-input` modal and `tell-llm` the answer back to the live LLM.

   Two event-tools are injected into the LLM's `:allowed-events`:

     event__ask_choice  {:question :string
                         :options  [{:label :string :value :any} …]}
     event__ask_text    {:question :string}

   When the LLM calls either, the chart visits the corresponding ask state,
   prompts the human, and `tell-llm`s the answer prefixed with `User chose: `
   or `User said: ` so the LLM can react.

   **REQUIRES an interactive chart** (`^{:interactive? true}` on the chart
   var). Without a `HumanRenderer` wired through the CLI (TUI or stdin), the
   ask states will stall on first invocation. Use plain `llm-conversation`
   for non-interactive charts.

   `opts`:
    * `:id` (required) — id of the compound state.
    * `:params-fn` (required) — `(fn [env data] params-map)` for the underlying
      `llm-conversation`. Your params-fn's `:allowed-events` (if any) are
      concatenated with the two question events; the helper rewrites the
      returned map.
    * `:exit-transitions` (optional) — vector of `transition` elements installed
      on the `:converse` child state. Use these to leave the bubble when the
      LLM signals completion (e.g. `(transition {:event :done :target ...})`).
    * `:autoforward?` (optional, default true)."
  [{:keys [id params-fn exit-transitions autoforward?]
    :or   {autoforward? true exit-transitions []}}]
  (assert id "with-llm-questions requires :id")
  (assert (fn? params-fn) "with-llm-questions requires :params-fn")
  (let [base-name     (name id)
        converse-id   (keyword (str base-name ".converse"))
        ask-choice-id (keyword (str base-name ".ask-choice"))
        ask-text-id   (keyword (str base-name ".ask-text"))
        question-events
        [{:event       :question/ask-choice
          :description "Ask the human to pick one option. Use when you need a design decision the human must make."
          :data-schema [:map
                        [:question :string]
                        [:options [:vector [:map
                                            [:label :string]
                                            [:value :any]]]]]}
         {:event       :question/ask-text
          :description "Ask the human a free-text question. Use when you need clarification or a value the human must supply."
          :data-schema [:map [:question :string]]}]
        wrapped-params-fn
        (fn [env data]
          (let [p (params-fn env data)]
            (update p :allowed-events
                    (fn [evs] (vec (concat (or evs []) question-events))))))]
    (apply elt/state {:id id :initial converse-id}
           ;; Conversation owned by the PARENT so child-state transitions
           ;; don't tear it down.
           (llm-conversation
            {:id           (str base-name "/conv")
             :autoforward? autoforward?
             :params-fn    wrapped-params-fn})
           (apply elt/state {:id converse-id}
                  (elt/transition
                   {:event :question/ask-choice :target ask-choice-id}
                   (elt/script
                    {:expr (fn [_ data]
                             [(ops/assign :question/pending-question
                                          (get-in data [:_event :data :question]))
                              (ops/assign :question/pending-options
                                          (get-in data [:_event :data :options]))])}))
                  (elt/transition
                   {:event :question/ask-text :target ask-text-id}
                   (elt/script
                    {:expr (fn [_ data]
                             [(ops/assign :question/pending-question
                                          (get-in data [:_event :data :question]))])}))
                  exit-transitions)
           [(elt/state {:id ask-choice-id}
                       (human-input
                        {:id        (str base-name "/ask-choice")
                         :params-fn (fn [_ data]
                                      {:kind    :select
                                       :prompt  (:question/pending-question data)
                                       :options (:question/pending-options data)})})
                       (elt/transition
                        {:event :human.answer :target converse-id}
                        (tell-llm
                         {:expr (fn [_ data]
                                  (str "User chose: "
                                       (pr-str (get-in data [:_event :data :answer]))))}))
                       (elt/transition
                        {:event :human.cancelled :target converse-id}
                        (tell-llm
                         {:expr (fn [_ _]
                                  "User declined to choose; please proceed with your best judgement.")}))
                       (elt/transition
                        {:event :human.error :target converse-id}
                        (tell-llm
                         {:expr (fn [_ _]
                                  "Question prompt errored; please proceed without that input.")})))

            (elt/state {:id ask-text-id}
                       (human-input
                        {:id        (str base-name "/ask-text")
                         :params-fn (fn [_ data]
                                      {:kind   :text
                                       :prompt (:question/pending-question data)})})
                       (elt/transition
                        {:event :human.answer :target converse-id}
                        (tell-llm
                         {:expr (fn [_ data]
                                  (str "User said: "
                                       (pr-str (get-in data [:_event :data :answer]))))}))
                       (elt/transition
                        {:event :human.cancelled :target converse-id}
                        (tell-llm
                         {:expr (fn [_ _]
                                  "User declined to answer; please proceed without that input.")}))
                       (elt/transition
                        {:event :human.error :target converse-id}
                        (tell-llm
                         {:expr (fn [_ _]
                                  "Question prompt errored; please proceed without that input.")})))])))

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
