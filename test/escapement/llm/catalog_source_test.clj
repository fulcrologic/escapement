(ns escapement.llm.catalog-source-test
  "The objective half of the catalog is loaded from the bundled
   models.dev dump (`models-api.json`) rather than hand-typed. These tests
   pin the normalized shape and the curated provider allowlist."
  (:require
    [escapement.llm.catalog :as catalog]
    [escapement.llm.catalog-source :as src]
    [escapement.llm.providers]
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
        (set (keys providers)) => #{:anthropic :openai :z-ai :z-ai-plan :zai-coding-plan :ollama :deepseek
            :openrouter :opencode-go :opencode-go-anthropic}
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

(specification "every provider escapement can call is known to the catalog"
  ;; The allowlist is the gate, not the JSON: refreshing the dump does not
  ;; surface a provider that is not listed. Before this, escapement could call
  ;; thirteen providers and the catalog knew six — so for the other seven there
  ;; was no pricing, no context window, no output cap and no vision flag, and
  ;; `:needs` eligibility could not evaluate them at all.
  ;;
  ;; Coverage comes from TWO sources and both count: the models.dev dump via
  ;; the allowlist, and the hand-curated `local-providers` overlay for the
  ;; providers models.dev does not carry (or carries wrongly — see the codex
  ;; note in `catalog-source`).

  (let [templates (set (keys @(resolve 'escapement.llm.providers/provider-templates)))
        known     (set (keys catalog/providers))
        ;; `:claude-cli` is a deliberate behavioural exclusion this round; it is
        ;; still expected to be KNOWN to the catalog, via the overlay's aliases.
        uncovered (vec (sort (remove known templates)))]
    (assertions
      "no provider template is invisible to the catalog"
      uncovered => []

      "and the check is not vacuous — there are thirteen templates"
      (count templates) => 13))

  (component "the providers that were previously invisible now carry real facts"
    (assertions
      "OpenRouter, the largest, is present with a substantial model set"
      (> (count (:models (catalog/provider-info :openrouter))) 100) => true

      "OpenCode Go, both spellings, resolve to the same source"
      (= (set (keys (:models (catalog/provider-info :opencode-go))))
        (set (keys (:models (catalog/provider-info :opencode-go-anthropic))))) => true

      "a metered provider carries non-zero pricing"
      (boolean (some (fn [m] (pos? (:input (catalog/pricing :openrouter m) 0)))
                 (keys (:models (catalog/provider-info :openrouter))))) => true

      "and a subscription provider is zeroed at the margin"
      (catalog/subscription? :opencode-go) => true)))
