(ns escapement.llm.catalog-test
  "Back-compat contract for the catalog after it was rewired onto the
   models.dev dump (objective facts) + ratings overlay (subjective).
   Public accessor signatures and the answers callers depend on must not
   regress."
  (:require
   [escapement.llm.catalog :as catalog]
   [escapement.llm.preferences :as prefs]
   [fulcro-spec.core :refer [specification assertions component =>]]))

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

(specification "catalog — subjective intelligence comes from the overlay"
  (assertions
   "intelligence is surfaced through info (overlaid, not in the dump)"
   (catalog/intelligence "claude-opus-4-7") => 10
   (catalog/intelligence "gpt-5") => 10
   "unknown model has no intelligence"
   (catalog/intelligence "totally-made-up") => nil))

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
   ":min is a numeric floor over the merged info (intelligence overlay)"
   (catalog/satisfies-policy? "claude-opus-4-7" {:min {:intelligence 8}}) => true
   (catalog/satisfies-policy? "claude-haiku-4-5" {:min {:intelligence 8}}) => false
   ":require is exact equality over an info key"
   (catalog/satisfies-policy? "gpt-5" {:require {:vision? true}}) => true
   ":max is a numeric ceiling"
   (catalog/satisfies-policy? "gpt-5" {:max {:intelligence 6}}) => false
   "a non-empty policy rejects an unknown id (no facts to vouch for it)"
   (catalog/satisfies-policy? "totally-made-up" {:min {:intelligence 1}}) => false
   "multiple clauses must all hold"
   (catalog/satisfies-policy? "claude-opus-4-7"
                              {:require {:vision? true} :min {:intelligence 9}})
   => true))

(specification "catalog — default preferences stay reachable"
  (assertions
   "every built-in default preference validates against the catalog"
   (mapv prefs/valid-entry? prefs/default-preferences)
   => (mapv (constantly true) prefs/default-preferences)))
