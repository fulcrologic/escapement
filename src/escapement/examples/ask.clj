(ns escapement.examples.ask
  "Minimal interactive chart for smoke-testing the :human-input invocation
   and the TUI. Asks the user's name, confirms it, and ends. An Esc keypress
   in the TUI sends :ui.interrupt and transitions to :cancelled."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]))

(def ^{:interactive? true} agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :ask-name}
      ;; Chart-wide Esc handler.
      (transition {:event :ui.interrupt :target :cancelled})

      (state {:id :ask-name}
        (h/human-input
          {:id        "ask-name"
           :params-fn (fn [_env _data]
                        {:kind          :text
                         :prompt        "What's your name?"
                         :answer-schema [:string {:min 1}]})})
        (transition {:event :human.answer :target :confirm-name}
          (script {:expr (fn [_ data]
                           [(ops/assign :name
                              (get-in data [:_event :data :answer]))])})))

      (state {:id :confirm-name}
        (h/human-input
          {:id        "confirm-name"
           :params-fn (fn [_env data]
                        {:kind    :confirm
                         :prompt  (str "Hello " (:name data) " — is that right?")
                         :default true})})
        (transition {:event :human.answer :target :greeted
                     :cond  (fn [_ data] (get-in data [:_event :data :answer]))})
        (transition {:event :human.answer :target :ask-name}))

      (final {:id :greeted})
      (final {:id :cancelled}))))
