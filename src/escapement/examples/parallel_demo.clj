(ns escapement.examples.parallel-demo
  "M6 demo: two parallel regions, each running its own `:llm-conversation`.

  Region A is a translator: asks the LLM to call `event__translated`.
  Region B is a summarizer: asks the LLM to call `event__summarized`.

  Each event-tool name encodes the event keyword (`event__<name>`), so the two
  regions cannot collide on tool dispatch even when both LLMs are running
  concurrently. After both regions reach their per-region final substate, the
  library raises `done.state.work` for the parent parallel state; we transition
  the top-level run to a wrapped final."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [state parallel transition final script]]
   [escapement.chart.helpers :as h]))

(def translator-system
  (str "Reply by calling the `event__translated` tool exactly once with "
       "`{\"text\":\"<the French translation>\"}`. Keep it short. Then end your turn."))

(def summarizer-system
  (str "Reply by calling the `event__summarized` tool exactly once with "
       "`{\"text\":\"<a one-sentence summary>\"}`. Keep it under 20 words. Then end your turn."))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :work}
          (parallel {:id :work}

                    (state {:id :translator :initial :translating}
                           (state {:id :translating}
                                  (h/llm-conversation
                                   {:id        "translator"
                                    :params-fn (fn [_env data]
                                                 {:system               translator-system
                                                  :allowed-events       [{:event       :translated
                                                                          :data-schema [:map [:text :string]]}]
                                                  :initial-user-message (str "Translate to French: "
                                                                             (pr-str (:phrase data "Hello, world.")))})})
                                  (transition {:event :translated :target :t-done}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign :translation
                                                                            (get-in data [:_event :data :text]))])})))
                           (final {:id :t-done}))

                    (state {:id :summarizer :initial :summarizing}
                           (state {:id :summarizing}
                                  (h/llm-conversation
                                   {:id        "summarizer"
                                    :params-fn (fn [_env data]
                                                 {:system               summarizer-system
                                                  :allowed-events       [{:event       :summarized
                                                                          :data-schema [:map [:text :string]]}]
                                                  :initial-user-message (str "Summarize this passage: "
                                                                             (pr-str (:passage data "Once upon a time there was a cat.")))})})
                                  (transition {:event :summarized :target :s-done}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign :summary
                                                                            (get-in data [:_event :data :text]))])})))
                           (final {:id :s-done})))
          ;; When both regions finish, parallel raises done.state.work.
          (transition {:event :done.state.work :target :finished})
          (final {:id :finished}))))
