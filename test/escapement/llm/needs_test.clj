(ns escapement.llm.needs-test
  "Unit tests for `escapement.llm.needs/needs->policy` — the pure flat
   `:needs` → canonical `{:require/:min/:max}` translation — plus a
   round-trip against `catalog/satisfies-policy?` matching the reference
   resolution table in fixlibfacade.md (objective fact + subjective
   rating mixed)."
  (:require
    [escapement.llm.catalog :as catalog]
    [escapement.llm.needs :as needs]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "needs->policy — the three forms, mixed"
  (assertions
    "bare value ⇒ :require (exact equality)"
    (needs/needs->policy {:vision? true})
    => {:require {:vision? true} :min {} :max {}}
    "bare string value ⇒ :require"
    (needs/needs->policy {:family "claude"})
    => {:require {:family "claude"} :min {} :max {}}
    "[:>= n] ⇒ :min (inclusive floor)"
    (needs/needs->policy {:context-tokens [:>= 200000]})
    => {:require {} :min {:context-tokens 200000} :max {}}
    "[:<= n] ⇒ :max (inclusive ceiling)"
    (needs/needs->policy {:max-output-tokens [:<= 64000]})
    => {:require {} :min {} :max {:max-output-tokens 64000}}
    "all three forms mixed (objective + subjective keyspace is uniform)"
    (needs/needs->policy {:tool-call?        true
                          :clojure           [:>= 7]
                          :context-tokens    [:>= 200000]
                          :max-output-tokens [:<= 64000]})
    => {:require {:tool-call? true}
        :min     {:clojure 7 :context-tokens 200000}
        :max     {:max-output-tokens 64000}}))

(specification "needs->policy — empty / nil ⇒ canonical empty policy (admits everything)"
  (assertions
    "nil ⇒ empty policy"
    (needs/needs->policy nil) => {:require {} :min {} :max {}}
    "empty map ⇒ empty policy"
    (needs/needs->policy {}) => {:require {} :min {} :max {}}
    "empty policy admits everything via satisfies-policy?"
    (catalog/satisfies-policy? "claude-opus-4-7" (needs/needs->policy nil) {})
    => true
    (catalog/satisfies-policy? "totally-made-up" (needs/needs->policy {}) {})
    => true))

(specification "needs->policy — malformed entries throw an ex-info naming the key"
  (component "unknown operator"
    (assertions
      (try (needs/needs->policy {:clojure [:> 8]})
           (catch clojure.lang.ExceptionInfo e
             [(:key (ex-data e)) (boolean (re-find #":clojure" (ex-message e)))]))
      => [:clojure true]))
  (component "the other forbidden operators (:< / :=) are rejected too"
    (assertions
      (try (needs/needs->policy {:ux [:< 3]}) :no-throw
           (catch clojure.lang.ExceptionInfo e (string? (:reason (ex-data e)))))
      => true
      (try (needs/needs->policy {:tier [:= "A"]}) :no-throw
           (catch clojure.lang.ExceptionInfo e (:key (ex-data e))))
      => :tier))
  (component "wrong-arity vector"
    (assertions
      (try (needs/needs->policy {:context-tokens [:>= 1 2]})
           (catch clojure.lang.ExceptionInfo e (:key (ex-data e))))
      => :context-tokens
      (try (needs/needs->policy {:context-tokens [:>=]})
           (catch clojure.lang.ExceptionInfo e (:key (ex-data e))))
      => :context-tokens))
  (component "non-numeric bound"
    (assertions
      (try (needs/needs->policy {:clojure [:>= "8"]})
           (catch clojure.lang.ExceptionInfo e
             [(:key (ex-data e)) (string? (:reason (ex-data e)))]))
      => [:clojure true])))

;; ---------------------------------------------------------------------------
;; Round-trip against the reference resolution table (fixlibfacade.md):
;; node :refactor — :needs {:tool-call? true :clojure [:>= 7]} — over the
;; reference config's ratings. gpt-5 (clj 5) drops; glm-5.1/opus clear.
;; ---------------------------------------------------------------------------

(specification "needs->policy ∘ satisfies-policy? matches the reference table (objective + subjective)"
  (let [ratings  {"glm-5.1"         {:clojure 7 :ux 5}
                  "claude-opus-4-7" {:clojure 9 :ux 4}
                  "gpt-5"           {:clojure 5 :ux 8}}
        refactor (needs/needs->policy {:tool-call? true :clojure [:>= 7]})
        review   (needs/needs->policy {:vision? true :ux [:>= 6]})]
    (component "node :refactor — tool-call? (objective) AND clojure>=7 (subjective)"
      (assertions
        "glm-5.1 clears (clj 7 ✓, tool-call ✓)"
        (catalog/satisfies-policy? "glm-5.1" refactor ratings) => true
        "claude-opus-4-7 clears (clj 9 ✓)"
        (catalog/satisfies-policy? "claude-opus-4-7" refactor ratings) => true
        "gpt-5 dropped (clj 5 < 7)"
        (catalog/satisfies-policy? "gpt-5" refactor ratings) => false))
    (component "node :design-review — vision? (objective) AND ux>=6 (subjective)"
      (assertions
        "only gpt-5 clears (ux 8 ✓, vision ✓)"
        (catalog/satisfies-policy? "gpt-5" review ratings) => true
        "glm-5.1 dropped (ux 5 < 6)"
        (catalog/satisfies-policy? "glm-5.1" review ratings) => false
        "claude-opus-4-7 dropped (ux 4 < 6)"
        (catalog/satisfies-policy? "claude-opus-4-7" review ratings) => false))))
