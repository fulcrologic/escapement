(ns escapement.llm.reasoning-test
  "The normalised `:reasoning` request field and its per-provider dialects.

   Every row of the translation matrix gets a test, plus the property that
   makes the field safe to land: a request WITHOUT `:reasoning` produces
   exactly the wire body it produced before the field existed."
  (:require
    [escapement.config :as cfg]
    [escapement.llm :as llm]
    [malli.core :as m]
    [escapement.llm.api :as api]
    [escapement.llm.claude-cli.translate]
    [escapement.llm.openai :as openai]
    [escapement.llm.openai-codex.translate :as codex]
    [escapement.llm.providers :as providers]
    [escapement.llm.reasoning :as rsn]
    [escapement.llm.types :as types]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def base {:model "m" :messages [{:role :user :content [{:type :text :text "hi"}]}]})

(defn openai-body
  ([dialect] (openai-body dialect nil))
  ([dialect reasoning]
   (-> (openai/request->openai-json
         (cond-> base reasoning (assoc :reasoning reasoning))
         dialect)
     (dissoc "model" "messages"))))

(specification "the normalised :reasoning field"

  (component "the bare-keyword sugar is widened exactly once, at build-request"
    (assertions
      "a bare effort keyword becomes the canonical map"
      (:reasoning (llm/build-request (assoc base :reasoning :high)))
      => {:effort :high}

      "a map is left alone"
      (:reasoning (llm/build-request (assoc base :reasoning {:effort :low :budget-tokens 2048})))
      => {:effort :low :budget-tokens 2048}

      "absent stays absent — no key is invented"
      (contains? (llm/build-request base) :reasoning) => false

      "and the canonical form validates against the Request schema"
      (types/validate-request (llm/build-request (assoc base :reasoning :max))) => nil))

  (component "the Responses passthrough shape still validates"
    ;; Caught live: a CLOSED schema silently made the openai-codex escape hatch
    ;; unreachable — `send-turn` validates the request, so the passthrough map
    ;; failed before it ever got to the translator, and the translator-level
    ;; tests could not see it.
    (assertions
      "a string :effort is the passthrough shape and is accepted"
      (types/validate-request
        (assoc base :reasoning {:effort "xhigh" :summary "auto"})) => nil

      "the normalised keyword shape is accepted too"
      (types/validate-request (assoc base :reasoning {:effort :high})) => nil

      "an unknown keyword effort is still rejected"
      (some? (types/validate-request (assoc base :reasoning {:effort :normal}))) => true))

  (component "the sugar never reaches a backend as a second shape"
    (assertions
      "normalize-reasoning is idempotent"
      (types/normalize-reasoning (types/normalize-reasoning :high)) => {:effort :high}

      "a non-effort value is passed through for the schema to reject"
      (types/normalize-reasoning {:nonsense true}) => {:nonsense true}))

  (component "the effort ordinal is closed"
    (assertions
      "exactly the six documented levels, weakest first"
      types/effort-levels => [:none :minimal :low :medium :high :max]

      "an unknown level is not an effort"
      (rsn/valid-effort? :normal) => false)))

(specification "an alias target may carry :reasoning, and it survives resolution"
  ;; This was unreachable: `alias-target->candidate` selects `:reasoning` out of
  ;; a target, but the CLOSED target schema in `escapement.config` omitted the
  ;; key, so no config could ever supply it. Both halves are asserted here so
  ;; the two layers cannot drift apart again.

  (component "the resolver keeps it as that target's default params"
    (let [cand (#'llm/alias-target->candidate
                 {:provider :openai :model "gpt-5" :reasoning {:effort :high}} :deep)]
      (assertions
        "carried into the candidate's params"
        (:params cand) => {:reasoning {:effort :high}}

        "alongside the target's identity"
        (select-keys cand [:provider :model :alias])
        => {:provider :openai :model "gpt-5" :alias :deep})))

  (component "and the config schema accepts what the resolver reads"
    (assertions
      "the map form"
      (m/validate cfg/alias-target-schema
        {:provider :openai :model "gpt-5" :reasoning {:effort :high}}) => true

      "the bare-keyword sugar"
      (m/validate cfg/alias-target-schema
        {:provider :openai :model "gpt-5" :reasoning :high}) => true

      "but not an effort outside the ordinal"
      (m/validate cfg/alias-target-schema
        {:provider :openai :model "gpt-5" :reasoning {:effort :normal}}) => false)))

(specification "dialect: Anthropic-shaped (thinking + budget)"

  (component "effort derives a thinking budget"
    (assertions
      "a derived budget never takes more than half the output cap"
      (get (api/request->anthropic-json (assoc base :max-tokens 8192 :reasoning {:effort :high})) "thinking")
      => {"type" "enabled" "budget_tokens" 4096}

      "a low effort asks for less"
      (get (api/request->anthropic-json (assoc base :max-tokens 8192 :reasoning {:effort :low})) "thinking")
      => {"type" "enabled" "budget_tokens" 2048}

      "an explicit budget is the caller's own arithmetic and is honored"
      (get (api/request->anthropic-json
             (assoc base :max-tokens 8192 :reasoning {:effort :low :budget-tokens 6000})) "thinking")
      => {"type" "enabled" "budget_tokens" 6000}

      "no room for the 1024 floor → the directive is dropped, not sent to be rejected"
      (contains? (api/request->anthropic-json (assoc base :max-tokens 1024 :reasoning {:effort :high})) "thinking")
      => false

      ":none explicitly disables thinking"
      (get (api/request->anthropic-json (assoc base :max-tokens 8192 :reasoning {:effort :none})) "thinking")
      => {"type" "disabled"}))

  (component "an explicit :thinking still wins — nothing that works today changes"
    (assertions
      "the caller's own thinking map is emitted verbatim"
      (get (api/request->anthropic-json
             (assoc base :max-tokens 8192
               :thinking {:type :enabled :budget-tokens 4096}
               :reasoning {:effort :max})) "thinking")
      => {"type" "enabled" "budget_tokens" 4096}))

  (component "conflicting sampling params are dropped, not sent to a 400"
    ;; Anthropic rejects thinking alongside temperature/top_p/top_k, so enabling
    ;; :reasoning would otherwise turn a working chart into a hard error.
    (let [wire (api/request->anthropic-json
                 (assoc base :max-tokens 8192 :temperature 0.7 :top-p 0.9 :top-k 40
                   :reasoning {:effort :high}))]
      (assertions
        "temperature dropped"
        (contains? wire "temperature") => false

        "top_p dropped"
        (contains? wire "top_p") => false

        "top_k dropped"
        (contains? wire "top_k") => false

        "thinking survives"
        (some? (get wire "thinking")) => true)))

  (component "sampling params are untouched when reasoning is not enabled"
    (let [wire (api/request->anthropic-json (assoc base :max-tokens 8192 :temperature 0.7))]
      (assertions
        "temperature passes through exactly as before"
        (get wire "temperature") => 0.7))))

(specification "dialect: the OpenAI chat-completions family"

  (component ":openai — reasoning_effort"
    (assertions
      "an effort becomes a reasoning_effort string"
      (openai-body :openai {:effort :high}) => {"reasoning_effort" "high"}

      ":max collapses onto high (the top of OpenAI's scale)"
      (openai-body :openai {:effort :max}) => {"reasoning_effort" "high"}

      ":none explicitly disables reasoning (unsupported models must reject, not silently think)"
      (openai-body :openai {:effort :none}) => {"reasoning_effort" "none"}))

  (component ":openrouter — the unified reasoning object"
    (assertions
      "an effort becomes a reasoning object"
      (openai-body :openrouter {:effort :medium}) => {"reasoning" {"effort" "medium"}}

      ":none turns reasoning explicitly off — the one dialect that can say it"
      (openai-body :openrouter {:effort :none}) => {"reasoning" {"enabled" false}}

      ;; VERIFIED live: sending both is a 400, \"Only one of reasoning.effort
      ;; and reasoning.max_tokens can be specified\".
      "a budget WINS over effort, because the two together are a hard 400"
      (openai-body :openrouter {:effort :high :budget-tokens 2048})
      => {"reasoning" {"max_tokens" 2048}}))

  (component ":ollama — the think flag"
    (assertions
      "any effort turns thinking on"
      (openai-body :ollama {:effort :low}) => {"think" true}

      ":none turns it off"
      (openai-body :ollama {:effort :none}) => {"think" false}))

  (component ":deepseek — thinking plus reasoning_effort, together"
    (assertions
      ;; VERIFIED live on deepseek-v4-flash: both fields are accepted together.
      "an effort emits both fields"
      (openai-body :deepseek {:effort :high})
      => {"thinking" {"type" "enabled"} "reasoning_effort" "high"}

      ":max reaches DeepSeek's own top level"
      (openai-body :deepseek {:effort :max})
      => {"thinking" {"type" "enabled"} "reasoning_effort" "max"}

      ":medium maps to high, not max (DeepSeek's documented scale)"
      (openai-body :deepseek {:effort :medium})
      => {"thinking" {"type" "enabled"} "reasoning_effort" "high"}

      ":none disables thinking explicitly"
      (openai-body :deepseek {:effort :none}) => {"thinking" {"type" "disabled"}}))

  (component ":none — a provider with no reasoning control we can address"
    (assertions
      "nothing is emitted, whatever the caller asked for"
      (openai-body :none {:effort :max}) => {}))

  (component "a request without :reasoning is byte-for-byte what it was before"
    (assertions
      "no dialect adds a field to a request that did not ask for one"
      (mapv #(openai-body %) [:openai :openrouter :ollama :deepseek :none])
      => [{} {} {} {} {}]

      "and the default dialect is :openai, not a base-url guess"
      (-> (openai/request->openai-json (assoc base :reasoning {:effort :low}))
        (get "reasoning_effort")) => "low")))

(specification "dialect: the OpenAI Responses wire (codex)"

  (component "the normalised field is translated"
    (assertions
      "an effort becomes a Responses reasoning object"
      (:reasoning (codex/build-request-body (assoc base :reasoning {:effort :low})))
      => {:effort "low" :summary "auto"}

      ;; :max is NOT rendered as \"xhigh\" — that level is model-dependent and
      ;; was not verified.
      ":max is rendered as high, not the unverified xhigh"
      (:reasoning (codex/build-request-body (assoc base :reasoning {:effort :max})))
      => {:effort "high" :summary "auto"}))

  (component "back-compat and default are preserved"
    (assertions
      "a legacy string-effort map is still passed through verbatim"
      (:reasoning (codex/build-request-body (assoc base :reasoning {:effort "xhigh" :summary "auto"})))
      => {:effort "xhigh" :summary "auto"}

      "no :reasoning at all still means the backend's own default"
      (:reasoning (codex/build-request-body base))
      => {:effort "medium" :summary "auto"})))

(specification "the dialect is a static property of the provider"

  (component "every provider template declares how it speaks"
    (let [tmpl @(resolve 'escapement.llm.providers/provider-templates)]
      (assertions
        "the OpenAI-shaped providers each name their own dialect"
        (mapv #(:reasoning-dialect (get tmpl %)) [:openai :openrouter :ollama :deepseek])
        => [:openai :openrouter :ollama :deepseek]

        "every declared dialect is one the library actually speaks"
        (every? rsn/dialects
          (keep :reasoning-dialect (vals tmpl))) => true)))

  (component "it is carried into the constructed backend, not sniffed from the base-url"
    (let [b (-> (providers/build-injected-credentials-backend
                  [{:provider :openrouter :api-key "k" :base-url "https://proxy.internal/v1"}]
                  [{:provider :openrouter :model "openai/gpt-4o-mini"}])
              :default-backend)]
      (assertions
        "a caller pointing a provider at a proxy keeps that provider's dialect"
        (-> b :opts :reasoning-dialect) => :openrouter

        "even though the base-url no longer names the provider"
        (-> b :opts :base-url) => "https://proxy.internal/v1"))))
