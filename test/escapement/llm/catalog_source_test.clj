(ns escapement.llm.catalog-source-test
  "The objective half of the catalog is loaded from the bundled
   models.dev dump (`models-api.json`) rather than hand-typed. These tests
   pin the normalized shape and the curated provider allowlist."
  (:require
    [escapement.llm.catalog-source :as src]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "catalog-source/load — normalized objective catalog"
  (let [{:keys [models providers]} (src/load-catalog)]

    (component "models — intrinsic facts keyed by canonical id"
      (let [gpt5 (get models "gpt-5")]
        (assertions
          "well-known id is present"
          (some? gpt5) => true
          "context window comes from the dump's limit.context"
          (:context-tokens gpt5) => 400000
          "per-response cap comes from limit.output"
          (:max-output-tokens gpt5) => 128000
          "vision? is derived from image input modality"
          (:vision? gpt5) => true
          "tool-call? carried through"
          (:tool-call? gpt5) => true
          "company derived from family"
          (:company gpt5) => "OpenAI"
          "human name carried through"
          (string? (:name gpt5)) => true
          "no subjective intelligence on the objective side"
          (contains? gpt5 :intelligence) => false)))

    (component "providers — curated allowlist with our keywords"
      (assertions
        "only curated providers are surfaced (keywordized)"
        (set (keys providers)) => #{:anthropic :openai :z-ai :z-ai-plan :ollama}
        "metered provider keeps real per-token pricing"
        (get-in providers [:openai :models "gpt-5" :pricing])
        => {:input 1.25 :output 10.0}
        "metered auth tagged"
        (get-in providers [:openai :auth]) => :metered
        "credential env carried from the dump"
        (get-in providers [:openai :env]) => ["OPENAI_API_KEY"]
        "subscription provider zeroes per-token pricing (free at margin)"
        (get-in providers [:z-ai-plan :auth]) => :subscription
        (get-in providers [:z-ai-plan :models "glm-4.7" :pricing])
        => {:input 0 :output 0}
        "display label carried from the dump"
        (string? (get-in providers [:anthropic :display])) => true))))
