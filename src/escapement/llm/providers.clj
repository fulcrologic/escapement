(ns escapement.llm.providers
  "Single source of truth for env→provider→backend.

   `detect-available-credentials` enumerates every LLM provider reachable from
   the environment — one descriptor per present API-key env var, plus the
   ChatGPT-subscription OAuth token if `escapement login codex` was run. Order
   is the preference order used to pick the default backend when assembling a
   multi-dispatch.

   `build-credential-backend` instantiates the concrete sub-backend for one
   descriptor. The CLI's auto-detection and the live e2e suite both consume
   these so the provider matrix never drifts between them.

   Backend constructors are resolved lazily (require + resolve) so this ns
   stays cheap to load and pulls in only the backends actually used."
  (:require
    [clojure.string :as str]))

(defn build-api-backend
  "Anthropic-compatible (Messages API) backend: Anthropic, z.ai,
   opencode-go-anthropic."
  [opts]
  (require 'escapement.llm.api)
  (let [ctor (resolve 'escapement.llm.api/new-backend)]
    (assert ctor "escapement.llm.api/new-backend not found")
    (ctor opts)))

(defn build-openai-backend
  "OpenAI Chat-Completions-compatible backend: OpenAI, OpenRouter, Ollama
   Cloud, opencode-go-openai."
  [opts]
  (require 'escapement.llm.openai)
  (let [ctor (resolve 'escapement.llm.openai/new-backend)]
    (assert ctor "escapement.llm.openai/new-backend not found")
    (ctor opts)))

(defn build-codex-backend
  "ChatGPT-subscription backend via saved OAuth token."
  [opts]
  (require 'escapement.llm.openai-codex)
  (let [ctor (resolve 'escapement.llm.openai-codex/new-backend)]
    (assert ctor "escapement.llm.openai-codex/new-backend not found")
    (ctor opts)))

(defn build-multi-backend
  "Model-prefix dispatcher across several sub-backends."
  [opts]
  (require 'escapement.llm.multi)
  (let [ctor (resolve 'escapement.llm.multi/new-backend)]
    (assert ctor "escapement.llm.multi/new-backend not found")
    (ctor opts)))

(defn nonblank-env
  "Env var value, or nil when unset/blank."
  [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v) v)))

(defn opencode-go-anthropic-model? [model]
  (and model (re-find #"^minimax-" model)))

(defn build-opencode-go-backend [{:keys [model api-key base-url] :as opts}]
  (if (opencode-go-anthropic-model? model)
    (build-api-backend {:api-key       api-key
                        :base-url      (or base-url "https://opencode.ai/zen/go")
                        :default-model model
                        :auth-mode     :x-api-key})
    (build-openai-backend {:api-key       api-key
                           :base-url      (or base-url "https://opencode.ai/zen/go/v1")
                           :default-model (or model (:default-model opts) "glm-5")})))

(defn detect-available-credentials
  "Returns a vector of available credential descriptors (one per env var or
   OAuth token present). Order is the preference order used for the default
   backend when assembling a multi-dispatch."
  []
  (let [anthropic   (nonblank-env "ANTHROPIC_API_KEY")
        zai         (nonblank-env "ZAI_API_KEY")
        openai      (nonblank-env "OPENAI_API_KEY")
        openrouter  (nonblank-env "OPENROUTER_API_KEY")
        ollama      (nonblank-env "OLLAMA_API_KEY")
        opencode-go (nonblank-env "OPENCODE_GO_API_KEY")
        codex-auth  (try
                      (require 'escapement.llm.openai-codex.auth)
                      (when-let [load! (resolve 'escapement.llm.openai-codex.auth/load-auth!)]
                        (load!))
                      (catch Throwable _ nil))]
    (cond-> []
      anthropic
      (conj {:kind          :anthropic :source "ANTHROPIC_API_KEY"
             :api-key       anthropic :base-url "https://api.anthropic.com"
             :default-model "claude-sonnet-4-6" :auth-mode :x-api-key
             :route         #"^claude-"})

      codex-auth
      (conj {:kind          :codex :source "saved OAuth token"
             :default-model "gpt-5.1-codex"
             :route         #"^gpt-5"})

      openai
      (conj {:kind          :openai :source "OPENAI_API_KEY"
             :api-key       openai :base-url "https://api.openai.com/v1"
             :default-model (or (System/getenv "OPENAI_MODEL") "gpt-4o-mini")
             :route         #"^gpt-"})

      openrouter
      (conj {:kind          :openrouter :source "OPENROUTER_API_KEY"
             :api-key       openrouter :base-url "https://openrouter.ai/api/v1"
             :default-model (or (System/getenv "OPENROUTER_MODEL") "openai/gpt-4o-mini")
             :route         #".+/.+"})

      ;; Keep established provider routes before newer hosted gateways so
      ;; adding Ollama/OpenCode credentials does not steal existing glm-* traffic.
      zai
      (conj {:kind          :zai :source "ZAI_API_KEY"
             :api-key       zai :base-url "https://api.z.ai/api/anthropic"
             :default-model "glm-4.6" :auth-mode :bearer
             :route         #"^glm-"})

      opencode-go
      (conj {:kind          :opencode-go-openai :source "OPENCODE_GO_API_KEY"
             :api-key       opencode-go :base-url "https://opencode.ai/zen/go/v1"
             :default-model (or (System/getenv "OPENCODE_GO_MODEL") "glm-5")
             :route         #"^(glm-|kimi-|mimo-)"})

      opencode-go
      (conj {:kind          :opencode-go-anthropic :source "OPENCODE_GO_API_KEY"
             :api-key       opencode-go :base-url "https://opencode.ai/zen/go"
             :default-model "minimax-m2.7" :auth-mode :x-api-key
             :route         #"^minimax-"})

      ollama
      (conj {:kind          :ollama :source "OLLAMA_API_KEY"
             :api-key       ollama :base-url "https://ollama.com/v1"
             :default-model (or (System/getenv "OLLAMA_MODEL") "kimi-k2.5")
             :route         #"^(kimi-|deepseek-|glm-|minimax-|gpt-oss)"}))))

(defn build-credential-backend
  "Instantiate the sub-backend for one credential descriptor."
  [{:keys [kind] :as c}]
  (case kind
    :anthropic (build-api-backend (select-keys c [:api-key :base-url :default-model :auth-mode]))
    :zai (build-api-backend (select-keys c [:api-key :base-url :default-model :auth-mode]))
    :openai (build-openai-backend (select-keys c [:api-key :base-url :default-model]))
    :openrouter (build-openai-backend (select-keys c [:api-key :base-url :default-model]))
    :ollama (build-openai-backend (select-keys c [:api-key :base-url :default-model]))
    :opencode-go-openai (build-openai-backend (select-keys c [:api-key :base-url :default-model]))
    :opencode-go-anthropic (build-api-backend (select-keys c [:api-key :base-url :default-model :auth-mode]))
    :codex (build-codex-backend {:default-model (:default-model c)})))

;; --- Hermetic explicit-credentials assembly -------------------------------
;;
;; The map below is the provider→backend matrix for the *injection* path.
;; It MIRRORS — fact for fact — the descriptor `detect-available-credentials`
;; emits for the same provider (`:kind`, `:base-url`, `:default-model`,
;; `:auth-mode`, `:route`). Both paths feed the same `build-credential-backend`
;; constructor, so the provider matrix cannot drift between CLI auto-detection
;; and explicit injection: changing a provider's wire shape means changing it
;; in BOTH `detect-available-credentials` and here, and the equivalence is
;; covered by tests. This path NEVER reads `System/getenv` and NEVER touches
;; disk — every value is supplied by the caller's descriptor or the static
;; template below.
(def ^:private provider-templates
  "Static, env-free defaults per provider keyword. Keys match the provider
   keyword used in `:llm/preferences` / injected descriptors. Each value is
   the env-independent half of the descriptor `detect-available-credentials`
   builds for that provider (the `:api-key` / overrides come from the caller)."
  {:anthropic             {:kind          :anthropic :base-url "https://api.anthropic.com"
                           :default-model "claude-sonnet-4-6" :auth-mode :x-api-key
                           :route         #"^claude-"}
   :z-ai                  {:kind          :zai :base-url "https://api.z.ai/api/anthropic"
                           :default-model "glm-4.6" :auth-mode :bearer
                           :route         #"^glm-"}
   ;; `:z-ai-plan` is the subscription-billed face of z.ai used in
   ;; `default-preferences`; same wire backend as metered `:z-ai`.
   :z-ai-plan             {:kind          :zai :base-url "https://api.z.ai/api/anthropic"
                           :default-model "glm-4.6" :auth-mode :bearer
                           :route         #"^glm-"}
   :openai                {:kind          :openai :base-url "https://api.openai.com/v1"
                           :default-model "gpt-4o-mini"
                           :route         #"^gpt-"}
   :openrouter            {:kind          :openrouter :base-url "https://openrouter.ai/api/v1"
                           :default-model "openai/gpt-4o-mini"
                           :route         #".+/.+"}
   :ollama                {:kind          :ollama :base-url "https://ollama.com/v1"
                           :default-model "kimi-k2.5"
                           :route         #"^(kimi-|deepseek-|glm-|minimax-|gpt-oss)"}
   :opencode-go           {:kind          :opencode-go-openai :base-url "https://opencode.ai/zen/go/v1"
                           :default-model "glm-5"
                           :route         #"^(glm-|kimi-|mimo-)"}
   :opencode-go-anthropic {:kind          :opencode-go-anthropic :base-url "https://opencode.ai/zen/go"
                           :default-model "minimax-m2.7" :auth-mode :x-api-key
                           :route         #"^minimax-"}
   ;; ChatGPT-subscription: no api-key/base-url; OAuth token is loaded by the
   ;; codex backend itself at send time, not here.
   :codex                 {:kind  :codex :default-model "gpt-5.1-codex"
                           :route #"^gpt-5"}
   :openai-codex          {:kind  :codex :default-model "gpt-5.1-codex"
                           :route #"^gpt-5"}})

(defn- descriptor->credential
  "Resolve one injected credential descriptor into a full
   `build-credential-backend`-shaped map by merging the static provider
   template with the caller-supplied overrides (`:api-key`, `:base-url`,
   `:default-model`, `:model`). Pure: no env, no disk. Returns nil for an
   unknown provider so the caller can drop it cleanly."
  [{:keys [provider] :as desc}]
  (when-let [tmpl (get provider-templates provider)]
    (let [overrides (-> desc
                      (select-keys [:api-key :base-url :default-model :auth-mode])
                      (cond-> (:model desc) (assoc :default-model (:model desc))))]
      (merge tmpl (into {} (remove (comp nil? val)) overrides)))))

(defn- preference-rank
  "Map of provider-keyword → its rank (lower = higher priority), derived from
   the DISTINCT, first-seen order of providers across the flattened preference
   aliases' targets (R8). `pref-targets` is the alias-flattened
   `{:provider :model …}` sequence (`preferences/flatten-targets`); the route
   ordering follows the providers as they first appear there. Providers absent
   from the flattened targets sort last (stable, handled by the caller)."
  [pref-targets]
  (->> (map :provider pref-targets)
    distinct
    (map-indexed (fn [i p] [p i]))
    (into {})))

(defn build-injected-credentials-backend
  "Assemble a `multi` routing backend purely from explicitly injected
   credential descriptors — HERMETIC: never calls
   `detect-available-credentials`, never reads `System/getenv`, never touches
   disk. Every credential value comes from `descriptors`; everything else
   comes from the static `provider-templates` matrix (which mirrors what
   `detect-available-credentials` would emit, so the provider matrix never
   drifts).

   - `descriptors` — ordered vector of
     `[{:provider :z-ai-plan :subscription true}
       {:provider :anthropic :api-key \"sk-...\"}
       {:provider :openai :api-key \"sk-...\" :base-url \"...\"}]`.
     Each is resolved to a concrete sub-backend via
     `build-credential-backend`. Descriptors with an unknown `:provider`
     are dropped.
   - `pref-targets` — the flattened preference-alias targets
     (`preferences/flatten-targets` of `:llm/preferences` over `:llm/aliases`,
     highest priority first; each a `{:provider :model …}` map). Used ONLY to
     ORDER the routing table: the route order derives from the DISTINCT,
     first-seen order of the targets' providers (R8) — routes for providers
     appearing earlier are tried first; providers absent from the preference
     targets keep their descriptor order, after the ranked ones. No new
     selection behavior — pure assembly over `multi/new-backend`.

   The first resolvable descriptor's backend becomes the `:default-backend`
   so a model that matches no route still runs (consistent with
   `escapement.llm.multi` semantics). Returns nil when no descriptor
   resolves."
  [descriptors pref-targets]
  (let [creds (->> descriptors
                (keep (fn [d] (some-> (descriptor->credential d)
                                (assoc ::provider (:provider d)))))
                vec)]
    (when (seq creds)
      (let [rank    (preference-rank pref-targets)
            n       (count creds)
            ;; stable sort by preference rank; unranked keep descriptor order
            sorted  (->> (map-indexed vector creds)
                      (sort-by (fn [[i c]]
                                 [(get rank (::provider c) (+ n i)) i]))
                      (mapv second))
            ;; Tag each route with its provider keyword (3-tuple) so the
            ;; multi-backend can dispatch by an explicit request `:provider`
            ;; (R3) instead of re-matching the model-string regex. Inert when
            ;; no request carries `:provider` — the regex `:route` is still the
            ;; first element and the legacy matcher path is untouched.
            routes  (mapv (fn [c]
                            [(:route c)
                             (build-credential-backend (dissoc c ::provider :route))
                             (::provider c)])
                      sorted)
            default (build-credential-backend
                      (dissoc (first creds) ::provider :route))]
        (build-multi-backend {:routes routes :default-backend default})))))
