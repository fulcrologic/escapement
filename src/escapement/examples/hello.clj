(ns escapement.examples.hello
  "M6 demo: minimal single-region chart that drives one LLM conversation. The
  LLM is asked to call a single event-tool `event__done` with a short greeting
  string; the chart then transitions to a wrapped final state.

  A top-level `final` would empty the configuration set on entry, so the final
  is wrapped in a compound parent `:run`."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [state transition final script on-entry]]
   [escapement.chart.helpers :as h]))

(def system-prompt
  (str "When asked, call the `event__done` tool exactly once with `{\"greeting\":\"<a short hello "
       "in any language>\"}`. Keep the greeting under 30 characters. After the tool call, end your turn. "
       "Do not call any other tools."))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :greeting}
          (state {:id :greeting}
                 (h/llm-conversation
                  {:id        "hello"
                   :params-fn (fn [_env _data]
                                {:system               system-prompt
                                 :real-tools           []
                                 :allowed-events       [{:event       :done
                                                         :data-schema [:map [:greeting :string]]}]
                                 :initial-user-message "Say hello."})})
                 (transition {:event :done :target :finished}
                             (script {:expr (fn [_env data]
                                              [(ops/assign :greeting
                                                           (get-in data [:_event :data :greeting]))])})))
          (final {:id :finished}))))
