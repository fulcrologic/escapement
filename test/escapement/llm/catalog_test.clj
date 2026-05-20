(ns escapement.llm.catalog-test
  "Back-compat contract for the catalog after it was rewired onto the
   models.dev dump (objective facts) + ratings overlay (subjective).
   Public accessor signatures and the answers callers depend on must not
   regress."
  (:require
    [escapement.llm.catalog :as catalog]
    [escapement.llm.preferences :as prefs]
    [fulcro-spec.core :refer [=> assertions specification]]))

(specification "catalog — objective facts from the dump"
  (assertions
    "context window resolves for a well-known id"
    (catalog/context-window "gpt-5") => 400000
    "max output cap resolves"
    (catalog/max-output-tokens "gpt-5") => 128000
    "vision? true for a multimodal model, false for unknown"
    (catalog/vision? "gpt-5") => true
    (catalog/vision? "totally-made-up") => false
    "company derived"
    (catalog/company "claude-opus-4-7") => "Anthropic"
    "dated id resolves via longest-prefix (value from the dump)"
    (catalog/context-window "claude-opus-4-7-20260101") => 1000000
    "unknown id returns nil, not a throw"
    (catalog/info "totally-made-up") => nil))

(specification "catalog — info carries objective facts; opinion is config-only"
  (assertions
    "objective facts flow from the dump"
    (:context-tokens (catalog/info "claude-opus-4-7")) => 1000000
    (:vision? (catalog/info "gpt-5")) => true
    "unknown model has no info at all"
    (catalog/info "totally-made-up") => nil))
;; The subjective overlay has no built-in opinion and is config-only; its
;; merge into `info` is exercised hermetically (explicit cfg) in
;; escapement.llm.ratings-test, not here where the process picks up
;; whatever `.escapement.edn` is ambient.

(specification "catalog — provider pricing & policy"
  (assertions
    "metered provider precise pricing"
    (catalog/pricing :openai "gpt-5") => {:input 1.25 :output 10.0}
    "subscription provider is free at the margin"
    (catalog/subscription? :z-ai-plan) => true
    (catalog/pricing :z-ai-plan "glm-4.7") => {:input 0 :output 0}
    "id-only arity = cheapest metered list price"
    (map? (catalog/pricing "gpt-5")) => true
    "serves? reflects the dump"
    (catalog/serves? :openai "gpt-5") => true
    (catalog/serves? :openai "totally-made-up") => false
    "providers-for lists provider keywords for an id"
    (contains? (set (catalog/providers-for "glm-4.7")) :z-ai) => true))

(specification "catalog — declarative model policy"
  (assertions
    "empty policy admits everything (even unknown ids)"
    (catalog/satisfies-policy? "totally-made-up" nil) => true
    (catalog/satisfies-policy? "totally-made-up" {}) => true
    ":min is a numeric floor over a (here objective) info key"
    (catalog/satisfies-policy? "claude-opus-4-7" {:min {:context-tokens 300000}}) => true
    (catalog/satisfies-policy? "claude-haiku-4-5" {:min {:context-tokens 300000}}) => false
    ":require is exact equality over an info key"
    (catalog/satisfies-policy? "gpt-5" {:require {:vision? true}}) => true
    ":max is a numeric ceiling"
    (catalog/satisfies-policy? "gpt-5" {:max {:context-tokens 150000}}) => false
    "a non-empty policy rejects an unknown id (no facts to vouch for it)"
    (catalog/satisfies-policy? "totally-made-up" {:min {:context-tokens 1}}) => false
    "multiple clauses must all hold"
    (catalog/satisfies-policy? "claude-opus-4-7"
      {:require {:vision? true} :min {:context-tokens 500000}})
    => true))

(specification "catalog — satisfies-policy? resolves subjective facts from the PASSED ratings"
  (assertions
    "empty ratings ⇒ a non-empty subjective :min clause matches nothing"
    (catalog/satisfies-policy? "claude-opus-4-7" {:min {:clojure 7}} {}) => false
    (catalog/satisfies-policy? "claude-opus-4-7" {:require {:tier "A"}} {}) => false
    "populated ratings ⇒ :require / :min / :max behave as before"
    (catalog/satisfies-policy? "claude-opus-4-7" {:require {:tier "A"}}
      {"claude-opus-4-7" {:tier "A" :clojure 9}}) => true
    (catalog/satisfies-policy? "claude-opus-4-7" {:min {:clojure 7}}
      {"claude-opus-4-7" {:clojure 9}}) => true
    (catalog/satisfies-policy? "claude-opus-4-7" {:min {:clojure 7}}
      {"claude-opus-4-7" {:clojure 5}}) => false
    (catalog/satisfies-policy? "claude-opus-4-7" {:max {:clojure 7}}
      {"claude-opus-4-7" {:clojure 5}}) => true
    "dated ids resolve subjective ratings via longest-prefix"
    (catalog/satisfies-policy? "claude-opus-4-7-20260101" {:min {:clojure 7}}
      {"claude-opus-4-7" {:clojure 9}}) => true
    "longest-prefix picks the more specific rating entry"
    (catalog/satisfies-policy? "claude-opus-4-7-20260101" {:require {:clojure 1}}
      {"claude"          {:clojure 0}
       "claude-opus-4-7" {:clojure 1}}) => true
    "objective clauses still work with an EMPTY ratings map (facts from the catalog)"
    (catalog/satisfies-policy? "gpt-5" {:require {:vision? true}} {}) => true
    (catalog/satisfies-policy? "claude-opus-4-7" {:min {:context-tokens 200000}} {}) => true
    (catalog/satisfies-policy? "claude-haiku-4-5" {:min {:context-tokens 300000}} {}) => false
    "subjective + objective in one policy, both must hold"
    (catalog/satisfies-policy? "gpt-5"
      {:require {:vision? true} :min {:clojure 7}}
      {"gpt-5" {:clojure 8}}) => true
    (catalog/satisfies-policy? "gpt-5"
      {:require {:vision? true} :min {:clojure 7}}
      {"gpt-5" {:clojure 3}}) => false))

(specification "catalog — no process global: passed ratings decide, per call"
  (assertions
    "two calls in one process with different ratings give different results"
    (catalog/satisfies-policy? "gpt-5" {:min {:clojure 7}} {"gpt-5" {:clojure 9}}) => true
    (catalog/satisfies-policy? "gpt-5" {:min {:clojure 7}} {"gpt-5" {:clojure 1}}) => false
    "no catalog var holds a Delay (no load-time config-bound global)"
    (->> (ns-interns 'escapement.llm.catalog)
      vals
      (filter (fn [v] (instance? clojure.lang.Delay (deref v))))
      seq)
    => nil
    "info is objective-only — ratings never leak into it"
    (contains? (catalog/info "gpt-5") :clojure) => false))

(specification "catalog — eligibility-facts is the documented objective vocabulary"
  (assertions
    "enumerates exactly the documented keys"
    (set (keys catalog/eligibility-facts))
    => #{:vision? :tool-call? :reasoning? :context-tokens
         :max-output-tokens :company :family :knowledge}
    "each key carries a one-line meaning string"
    (every? string? (vals catalog/eligibility-facts)) => true))

(specification "catalog — default preferences stay reachable"
  (assertions
    "every built-in default preference validates against the catalog"
    (mapv prefs/valid-entry? prefs/default-preferences)
    => (mapv (constantly true) prefs/default-preferences)))
