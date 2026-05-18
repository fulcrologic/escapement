(ns escapement.examples.clj-refactor
  "Example: a chart that gates model auto-selection on *per-dimension*
   ratings rather than a single intelligence number.

   The `:model-policy` below is a declarative
   `escapement.llm.catalog/satisfies-policy?` map. It is applied only to
   the processor's auto-detected `default-models` fallback list — if a
   chart pins `:model`/`:models` that is honored verbatim. Here we demand
   a model that scores well on Clojure *and* has usable tool-calling:

     {:min {:clojure 8 :tool-calling 6}}

   Those keys come from the subjective overlay in
   `escapement.llm.ratings` (merged into `catalog/info`), so the filter is
   pure data — no invocation code knows the word \"clojure\".

   There is **no built-in opinion**: ratings are entirely user-defined.
   This policy filters nothing until you supply the keys it gates on in
   `.escapement.edn`, e.g.:

     {:llm/ratings {\"claude-opus-4-7\"   {:clojure 10 :tool-calling 9}
                    \"claude-sonnet-4-7\" {:clojure  8 :tool-calling 8}
                    \"gpt-5\"             {:clojure  6 :tool-calling 7}}}

   With that config the policy keeps the Claude family and drops gpt-5
   (clojure 6 < 8). With *no* ratings configured, no id carries
   `:clojure`/`:tool-calling`, the auto-fallback filter empties, the run
   proceeds on the unfiltered default-models list, and a
   `:llm/model-policy-empty` transcript event records the gap.

   Run it:
     escapement run escapement.examples.clj-refactor/agent --debug"
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [state transition final script]]
   [escapement.chart.helpers :as h]))

(def system-prompt
  (str "You are a Clojure refactoring agent. Apply the requested change to "
       "idiomatic, bb-compatible Clojure. When the edit is complete, call "
       "the `event__done` tool exactly once with a one-line `summary` of "
       "what changed. Then end your turn. Do not call any other tools."))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :refactor}
          (state {:id :refactor}
                 (h/llm-conversation
                  {:id        "clj-refactor"
                   :params-fn (fn [_env _data]
                                {:system       system-prompt
                                 ;; Multi-dimensional gate over the ratings
                                 ;; overlay: strong Clojure AND usable
                                 ;; tool-calling, or this model is not
                                 ;; eligible for the auto-fallback list.
                                 :model-policy {:min {:clojure 8 :tool-calling 6}}
                                 :real-tools   []
                                 :allowed-events
                                 [{:event       :done
                                   :data-schema [:map [:summary :string]]}]
                                 :initial-user-message
                                 (str "Rename the function `foo` to `bar` "
                                      "across `src/example.clj` and update "
                                      "its call sites.")})})
                 (transition {:event :done :target :finished}
                             (script {:expr (fn [_env data]
                                              [(ops/assign :summary
                                                           (get-in data [:_event :data :summary]))])})))
          (final {:id :finished}))))
