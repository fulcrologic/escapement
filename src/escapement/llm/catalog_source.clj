(ns escapement.llm.catalog-source
  "Loads the *objective* half of the LLM catalog from the bundled
   `models.dev` dump (`models-api.json`, on the classpath next to this
   namespace) instead of hand-typing it.

   The dump is keyed by provider, and the same model id appears under many
   providers with that provider's price — which is exactly the two-table
   split the catalog wants. We normalize it into:

     {:models    {\"<id>\" {:context-tokens .. :max-output-tokens ..
                            :vision? .. :tool-call? .. :reasoning? ..
                            :company .. :name .. :family .. :knowledge ..}}
      :providers {<kw>  {:display .. :auth .. :env [..] :api ..
                         :models {\"<id>\" {:pricing {:input N :output M}}}}}}

   Only the curated `allowlist` providers are surfaced, mapped to the
   project's own provider keywords. A provider tagged `:subscription` in
   the allowlist has its per-token pricing zeroed — usage is free at the
   margin under a flat plan, regardless of any list price the dump carries.

   Nothing subjective lives here (no `:intelligence`). Opinion is layered
   on separately by `escapement.llm.ratings`.

   Babashka-compatible: `clojure.java.io` + `cheshire`."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private resource-path "escapement/llm/models-api.json")

(def ^:private allowlist
  "Project provider keyword → {:source <models.dev provider id> :auth ..}.
   Order here is the catalog order for `providers`."
  (array-map
   :anthropic {:source "anthropic"       :auth :metered}
   :openai    {:source "openai"          :auth :metered}
   :z-ai      {:source "zai"             :auth :metered}
   :z-ai-plan {:source "zai-coding-plan" :auth :subscription}
   :ollama    {:source "ollama-cloud"    :auth :subscription}))

(def ^:private family->company
  "Coarse model family → maker, for display. The dump has no per-model
   maker, so we infer it from `family`. Unknown families get nil."
  {"claude"   "Anthropic"
   "gpt"      "OpenAI"
   "o1"       "OpenAI"
   "o3"       "OpenAI"
   "glm"      "Zhipu"
   "qwen"     "Alibaba"
   "gemini"   "Google"
   "llama"    "Meta"
   "mistral"  "Mistral"
   "deepseek" "DeepSeek"
   "grok"     "xAI"
   "kimi"     "Moonshot"
   "minimax"  "MiniMax"})

(defn- company-for
  "Maker for a models.dev `family` string. The dump uses sub-families like
   `claude-opus`/`claude-sonnet`, so match by known prefix."
  [family]
  (when family
    (some (fn [[pfx co]] (when (str/starts-with? family pfx) co))
          family->company)))

(defn- num->double [x]
  (when (number? x) (double x)))

(defn- normalize-model
  "models.dev model entry → intrinsic fact map. Provider-independent;
   pricing is intentionally NOT here (it lives on the provider side)."
  [m]
  (let [family (get m "family")]
    {:context-tokens    (get-in m ["limit" "context"])
     :max-output-tokens (get-in m ["limit" "output"])
     :vision?           (boolean (some #{"image"} (get-in m ["modalities" "input"])))
     :tool-call?        (boolean (get m "tool_call"))
     :reasoning?        (boolean (get m "reasoning"))
     :family            family
     :company           (company-for family)
     :knowledge         (get m "knowledge")
     :name              (get m "name")}))

(defn- provider-pricing
  "Per-token `{:input N :output M}` for a dump model entry. Subscription
   providers are free at the margin, so their pricing is zeroed."
  [m subscription?]
  (if subscription?
    {:input 0 :output 0}
    {:input  (or (num->double (get-in m ["cost" "input"])) 0.0)
     :output (or (num->double (get-in m ["cost" "output"])) 0.0)}))

(defn build
  "Pure: parsed models.dev map → normalized `{:models :providers}`,
   restricted to the curated `allowlist`."
  [dump]
  (reduce
   (fn [acc [kw {:keys [source auth]}]]
     (if-let [prov (get dump source)]
       (let [subscription? (= :subscription auth)
             prov-models   (get prov "models")]
         (-> acc
             (update :models
                     (fn [ms]
                       (reduce (fn [ms [id m]]
                                 (cond-> ms
                                   (not (contains? ms id))
                                   (assoc id (normalize-model m))))
                               ms prov-models)))
             (assoc-in [:providers kw]
                       {:display (get prov "name")
                        :auth    auth
                        :env     (get prov "env")
                        :api     (get prov "api")
                        :models  (into {}
                                       (map (fn [[id m]]
                                              [id {:pricing (provider-pricing m subscription?)}]))
                                       prov-models)})))
       acc))
   {:models {} :providers {}}
   allowlist))

(def ^:private cache
  (delay (build (json/parse-string (slurp (io/resource resource-path))))))

(defn load-catalog
  "Normalized objective catalog `{:models :providers}` from the bundled
   dump. Parsed once and cached."
  []
  @cache)
