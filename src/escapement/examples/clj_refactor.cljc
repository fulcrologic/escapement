(ns escapement.examples.clj-refactor
  "Example: a chart that gates model auto-selection on *per-dimension*
   ratings rather than a single intelligence number.

   The `:needs` below is the ergonomic eligibility-gate surface: a flat
   `fact → constraint` map (translated at the invocation boundary into
   the canonical `catalog/satisfies-policy?` policy). It is applied only
   to the processor's auto-detected `default-models` fallback list — if a
   chart pins `:model`/`:models` that is honored verbatim. Here we demand
   a model that scores well on Clojure *and* has usable tool-calling:

     {:clojure [:>= 8] :tool-calling [:>= 6]}

   (`[:>= n]` is an inclusive numeric floor; a bare value would mean
   exact equality; `[:<= n]` an inclusive ceiling. Those are the only
   forms.) The gate **filters** the preference-ordered list; it never
   reorders — ordering is the sole job of `:llm/preferences`.

   Those keys come from the subjective overlay in
   `escapement.llm.ratings`, resolved from the injected ratings table, so
   the filter is pure data — no invocation code knows the word
   \"clojure\".

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
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
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
          {:id             "clj-refactor"
           :system         system-prompt
           ;; Multi-dimensional eligibility gate over
           ;; the ratings overlay: strong Clojure AND
           ;; usable tool-calling, or this model is
           ;; not eligible for the auto-fallback list.
           :needs          {:clojure [:>= 8] :tool-calling [:>= 6]}
           :real-tools     []
           :allowed-events [{:event       :done
                             :data-schema [:map [:summary :string]]}]
           :message        (str "Rename the function `foo` to `bar` "
                             "across `src/example.clj` and update "
                             "its call sites.")})
        (transition {:event :done :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :summary
                              (get-in data [:_event :data :summary]))])})))
      (final {:id :finished}))))
