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
    [clojure.string :as str]
    [taoensso.timbre :as log]))

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
  "Responses backend: host auth, bearer API key, or CLI saved OAuth."
  [opts]
  (require 'escapement.llm.openai-codex)
  (let [ctor (resolve 'escapement.llm.openai-codex/new-backend)]
    (assert ctor "escapement.llm.openai-codex/new-backend not found")
    (ctor opts)))

(defn build-claude-cli-backend
  "Claude Max/Pro-subscription backend via the `claude -p` CLI."
  [opts]
  (require 'escapement.llm.claude-cli)
  (let [ctor (resolve 'escapement.llm.claude-cli/new-backend)]
    (assert ctor "escapement.llm.claude-cli/new-backend not found")
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

(def opencode-go-anthropic-route
  "The single definition of which models opencode.ai's Zen gateway serves on
   its Anthropic-shaped `/messages` route. Referenced by the predicate AND by
   both credential descriptors below (env-detected and template), so the three
   cannot drift apart — they used to be three separate literals."
  #"(?i)^(minimax-|qwen)")

(defn opencode-go-anthropic-model? [model]
  (boolean (and model (re-find opencode-go-anthropic-route model))))

(defn opencode-session-headers
  "opencode.ai's Zen gateway REJECTS any request without an
   `x-opencode-session` header — `MissingSessionID`, HTTP 400, on both its
   OpenAI-shaped and its Anthropic-shaped endpoints (verified 2026-09-07;
   before this, EVERY opencode-go request through this library failed). The
   value only has to be a stable id the gateway can route on, so each backend
   instance gets its own."
  []
  {"x-opencode-session" (str "escapement-" (random-uuid))})

(defn opencode-go-quirks
  "Per-model request-key constraints for the opencode.ai Zen gateway. Resolved
   lazily so this ns stays cheap to load."
  []
  (require 'escapement.llm.model-quirks)
  @(resolve 'escapement.llm.model-quirks/opencode-go-quirks))

(defn build-opencode-go-backend
  "Route per request, including requests explicitly tagged :provider :opencode-go.
   :base-url is the gateway root (an optional trailing /v1 is normalized).

   ACCEPTED DEBT — this returns a `multi` that the injected-credentials
   assembly then nests inside its own `multi`, so anything walking the route
   table sees one provider whose backend is itself a route table. The
   alternative (two flat outer routes) cannot work: the outer table dispatches
   an explicit `:provider :opencode-go` request through a provider index that
   holds ONE backend per provider keyword, so two same-tagged routes would send
   every MiniMax/Qwen request to whichever was indexed first. Nesting keeps the
   provider tag intact and defers the model decision to the inner table, which
   is the behaviour the gateway requires. Revisit if the provider index ever
   grows per-request matching of its own."
  [{:keys [model base-url reasoning-dialect] :as opts}]
  (let [root     (str/replace (or base-url "https://opencode.ai/zen/go") #"(/v1)?/?$" "")
        model    (or model (:default-model opts) "glm-5")
        headers  (merge (opencode-session-headers) (:extra-headers opts))
        opts     (assoc opts :default-model model :extra-headers headers)
        messages (build-api-backend (assoc opts :base-url root :auth-mode :x-api-key))
        chat     (build-openai-backend
                   (assoc opts
                     :base-url (str root "/v1")
                     ;; Honour a descriptor-supplied dialect; the gateway's own
                     ;; default is :none. Hardcoding it here silently discarded
                     ;; a host's `:reasoning-dialect` and made this route
                     ;; disagree with the `:opencode-go-openai` credential kind
                     ;; for the very same endpoint.
                     :reasoning-dialect (or reasoning-dialect :none)
                     :model-quirks (opencode-go-quirks)))]
    (build-multi-backend
      {:routes          [[(fn [m] (opencode-go-anthropic-model? (if (seq m) m model))) messages]]
       :default-backend chat})))

(defn detect-available-credentials
  "Returns a vector of available credential descriptors (one per env var or
   OAuth token present). Order is the preference order used for the default
   backend when assembling a multi-dispatch."
  []
  (let [anthropic   (nonblank-env "ANTHROPIC_API_KEY")
        zai         (nonblank-env "ZAI_API_KEY")
        openai      (nonblank-env "OPENAI_API_KEY")
        openrouter  (nonblank-env "OPENROUTER_API_KEY")
        deepseek    (nonblank-env "DEEPSEEK_API_KEY")
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
             :default-model "claude-sonnet-5" :auth-mode :x-api-key
             :route         #"^claude-"})

      codex-auth
      (conj {:kind          :codex :source "saved OAuth token"
             ;; NOT a `-codex` id: ChatGPT-account auth rejects every one of
             ;; them (see openai-codex.translate/supported-models). Sol is the
             ;; GPT-5.6 flagship and OpenAI's best coding model.
             :default-model "gpt-5.6-sol"
             :route         #"^gpt-5"})

      openai
      (conj {:kind          :openai :source "OPENAI_API_KEY"
             :api-key       openai :base-url "https://api.openai.com/v1"
             :default-model (or (System/getenv "OPENAI_MODEL") "gpt-4o-mini")
             :reasoning-dialect :openai
             :route         #"^gpt-"})

      openrouter
      (conj {:kind          :openrouter :source "OPENROUTER_API_KEY"
             :api-key       openrouter :base-url "https://openrouter.ai/api/v1"
             :default-model (or (System/getenv "OPENROUTER_MODEL") "openai/gpt-4o-mini")
             :reasoning-dialect :openrouter
             :route         #".+/.+"})

      ;; DeepSeek's own metered endpoint — OpenAI chat-completions wire at
      ;; `https://api.deepseek.com/v1`. Listed BEFORE Ollama on purpose: the
      ;; Ollama descriptor's route also matches `deepseek-*`, so a key for the
      ;; model's own vendor must win over a gateway that merely resells it.
      deepseek
      (conj {:kind          :deepseek :source "DEEPSEEK_API_KEY"
             :api-key       deepseek :base-url "https://api.deepseek.com/v1"
             :default-model (or (System/getenv "DEEPSEEK_MODEL") "deepseek-v4-flash")
             :reasoning-dialect :deepseek
             :route         #"^deepseek-"})

      ;; Keep established provider routes before newer hosted gateways so
      ;; adding Ollama/OpenCode credentials does not steal existing glm-* traffic.
      ;;
      ;; z.ai coding plan, v1 (legacy) generation — OpenAI **Responses** wire
      ;; at `https://api.z.ai/api/v1` (append `/responses`). Endpoint
      ;; generations, all reachable with a coding-plan key (verified
      ;; 2026-08-25, glm-5.3):
      ;;   v1 legacy  https://api.z.ai/api/v1                 Responses  — full tool support; THIS entry's default
      ;;   v1–v3 chat https://api.z.ai/api/coding/{v1,v2,v3}  chat-comp  — text turns OK; tool calls 500 upstream (dead)
      ;;   v4         https://api.z.ai/api/coding/paas/v4     chat-comp  — full tool support; reach via an
      ;;                                                                {:provider :openai :base-url …} override
      ;;   Anthropic  https://api.z.ai/api/anthropic          Messages   — the `:z-ai` / `:z-ai-plan` templates below
      zai
      (conj {:kind          :zai-coding-plan :source "ZAI_API_KEY"
             :api-key       zai :base-url "https://api.z.ai/api/v1"
             :default-model "glm-5.3"
             ;; z.ai (esp. glm-5.x) buffers non-streaming turns and runs ~2x slower
             ;; than Anthropic; a large planning/implement turn routinely exceeds the
             ;; 60s default and 3x-retries to a model-down dead-end. Give it headroom.
             :http-timeout-ms 300000
             :route         #"^glm-"})

      opencode-go
      (conj {:kind          :opencode-go-openai :source "OPENCODE_GO_API_KEY"
             :api-key       opencode-go :base-url "https://opencode.ai/zen/go/v1"
             :default-model (or (System/getenv "OPENCODE_GO_MODEL") "glm-5")
             :reasoning-dialect :none
             :route         #"^(glm-|kimi-|mimo-)"})

      ;; Live-verified 2026-09-08: `qwen3.5-plus` answers on the Zen gateway's
      ;; Anthropic-shaped `/go/v1/messages` route, and `glm-5` on
      ;; `/go/v1/chat/completions` — the per-request split below is what the
      ;; gateway actually requires, not an inference from model naming.
      opencode-go
      (conj {:kind          :opencode-go-anthropic :source "OPENCODE_GO_API_KEY"
             :api-key       opencode-go :base-url "https://opencode.ai/zen/go"
             :default-model "minimax-m2.7" :auth-mode :x-api-key
             :route         opencode-go-anthropic-route})

      ollama
      (conj {:kind          :ollama :source "OLLAMA_API_KEY"
             :api-key       ollama :base-url "https://ollama.com/v1"
             ;; NOT `kimi-k2.5`: retired upstream 2026-07-31, and the cloud
             ;; API answers every request for it with an error, so it was a
             ;; guaranteed failure for anyone taking the default. `glm-5.3-flash`
             ;; is general-purpose (deliberately not a `-code` model), current,
             ;; and the cheapest tier here. Confirmed answering 2026-09-07.
             :default-model (or (System/getenv "OLLAMA_MODEL") "glm-5.3-flash")
             :reasoning-dialect :ollama
             :route         #"^(kimi-|deepseek-|glm-|minimax-|gpt-oss)"}))))

;; Per-wire ALLOWLISTS of descriptor keys a backend constructor may see.
;;
;; These are deliberate, not incidental. A descriptor also carries
;; credential-DETECTION fields (`:kind`, `:source`, `:route`, `:subscription`)
;; that describe how the credential was found, not how the wire behaves;
;; forwarding the whole descriptor makes "which key reaches the wire"
;; unauditable and lets a future `(:route opts)` read inside a backend silently
;; pick one up. Adding a wire-relevant key means adding it HERE, next to the
;; wire it belongs to.
(def ^:private http-credential-keys
  "Keys every HTTP-wire backend understands, host auth included."
  [:api-key :base-url :default-model :http-timeout-ms :http-transport
   :auth-fn :extra-headers])

(def ^:private messages-credential-keys
  (into http-credential-keys [:auth-mode :anthropic-version]))

(def ^:private chat-credential-keys
  ;; `:reasoning-dialect` is a STATIC property of the provider, carried on the
  ;; descriptor/template — never sniffed from the base-url, so pointing a
  ;; provider at a proxy or a self-hosted gateway cannot silently change the
  ;; wire format of its reasoning field.
  (into http-credential-keys [:reasoning-dialect :prefill-support :model-quirks]))

(def ^:private responses-credential-keys
  (into http-credential-keys [:endpoint-profile :allow-stored-auth? :max-sse-event-chars]))

(defn- opencode-go-opts
  "opencode.ai's Zen gateway REJECTS any request without `x-opencode-session`
   (HTTP 400 `MissingSessionID`) on both its wire shapes, so the header is
   added for every opencode route. A caller's own `:extra-headers` wins."
  [c keys]
  (assoc (select-keys c keys)
    :extra-headers (merge (opencode-session-headers) (:extra-headers c))))

(defn build-credential-backend
  "Instantiate the sub-backend for one credential descriptor.

   Only the keys allowlisted per wire above reach a constructor."
  [{:keys [kind] :as c}]
  (case kind
    :anthropic (build-api-backend (select-keys c messages-credential-keys))
    :zai (build-api-backend (select-keys c messages-credential-keys))
    :zai-coding-plan (build-codex-backend (select-keys c responses-credential-keys))
    :openai (build-openai-backend (select-keys c chat-credential-keys))
    :openrouter (build-openai-backend (select-keys c chat-credential-keys))
    :ollama (build-openai-backend (assoc (select-keys c chat-credential-keys)
                                    :prefill-support :unsupported))
    :deepseek (build-openai-backend (select-keys c chat-credential-keys))
    ;; `:auth-mode` too: this kind builds BOTH wires, and the Messages half
    ;; needs it. (`:model` is deliberately absent — `descriptor->credential`
    ;; folds a descriptor's `:model` into `:default-model` before it gets here.)
    :opencode-go (build-opencode-go-backend
                   (select-keys c (conj chat-credential-keys :auth-mode)))
    :opencode-go-openai (build-openai-backend
                          (assoc (opencode-go-opts c chat-credential-keys)
                            :model-quirks (opencode-go-quirks)))
    :opencode-go-anthropic (build-api-backend (opencode-go-opts c messages-credential-keys))
    ;; A Responses credential with NO `:base-url` is the ChatGPT subscription,
    ;; whose endpoint takes an OAuth token — an `:api-key` is meaningless there
    ;; and the constructor rejects one. Drop it rather than fail assembly: a
    ;; `.escapement.edn` `{:provider :codex :key-from …}` that happens to
    ;; resolve a key has always meant "use my ChatGPT login", and it must keep
    ;; meaning that. With a `:base-url` the key IS the credential and is kept.
    :codex (let [opts (select-keys c responses-credential-keys)]
             (build-codex-backend
               (cond-> opts
                 (str/blank? (str (:base-url opts))) (dissoc :api-key))))
    :claude-cli (build-claude-cli-backend
                  (select-keys c [:default-model :binary :timeout-ms :max-concurrency
                                  :effort :max-budget-usd]))))

;; --- Hermetic explicit-credentials assembly -------------------------------
;;
;; The map below is the provider→backend matrix for the *injection* path.
;; It MIRRORS — fact for fact — the descriptor `detect-available-credentials`
;; emits for the same provider (`:kind`, `:base-url`, `:default-model`,
;; `:auth-mode`, `:route`). Both paths feed the same `build-credential-backend`
;; constructor, so the provider matrix cannot drift between CLI auto-detection
;; and explicit injection: changing a provider's wire shape means changing it
;; in BOTH `detect-available-credentials` and here, and the equivalence is
;; covered by tests — see the "the two assembly paths do not drift" spec in
;; `test/escapement/llm/providers_test.clj`, which compares EVERY provider both
;; paths describe, not a sample. This path NEVER reads `System/getenv` and NEVER touches
;; disk — every value is supplied by the caller's descriptor or the static
;; template below.
(def ^:private provider-templates
  "Static, env-free defaults per provider keyword. Keys match the provider
   keyword used in `:llm/preferences` / injected descriptors. Each value is
   the env-independent half of the descriptor `detect-available-credentials`
   builds for that provider (the `:api-key` / overrides come from the caller)."
  {:anthropic             {:kind          :anthropic :base-url "https://api.anthropic.com"
                           :default-model "claude-sonnet-5" :auth-mode :x-api-key
                           :route         #"^claude-"}
   ;; `glm-5.3-flash`, NOT `glm-4.6`: that id is retired and the endpoint
   ;; silently answers it with `glm-5.3-flash` anyway (verified 2026-09-07), so
   ;; the old default named a model that never ran. This names the model the
   ;; endpoint was already delivering — same model, same tier, now stated
   ;; truthfully. Confirmed directly requestable and self-reporting.
   :z-ai                  {:kind          :zai :base-url "https://api.z.ai/api/anthropic"
                           :default-model "glm-5.3-flash" :auth-mode :bearer
                           :http-timeout-ms 300000
                           :route         #"^glm-"}
   ;; `:z-ai-plan` is the subscription-billed face of z.ai used in
   ;; `default-preferences`; same wire backend as metered `:z-ai`.
   :z-ai-plan             {:kind          :zai :base-url "https://api.z.ai/api/anthropic"
                           ;; See the `:z-ai` note: `glm-4.6` is retired and was
                           ;; silently served as `glm-5.3-flash`.
                           :default-model "glm-5.3-flash" :auth-mode :bearer
                           :http-timeout-ms 300000
                           :route         #"^glm-"}
   ;; z.ai coding plan, v1 (legacy) generation — OpenAI Responses wire. Mirrors
   ;; the descriptor `detect-available-credentials` emits for ZAI_API_KEY.
   ;; Newer generations stay reachable: override `:base-url` (any Responses
   ;; root), or use an `{:provider :openai :base-url …}` descriptor for the
   ;; chat-completions generations (v1–v3 text-only, v4 full) — see the
   ;; generation table in `detect-available-credentials`.
   :zai-coding-plan       {:kind          :zai-coding-plan :base-url "https://api.z.ai/api/v1"
                           :default-model "glm-5.3"
                           :http-timeout-ms 300000
                           :route         #"^glm-"}
   :openai                {:kind          :openai :base-url "https://api.openai.com/v1"
                           :default-model "gpt-4o-mini" :reasoning-dialect :openai
                           :route         #"^gpt-"}
   :openrouter            {:kind          :openrouter :base-url "https://openrouter.ai/api/v1"
                           :default-model "openai/gpt-4o-mini" :reasoning-dialect :openrouter
                           :route         #".+/.+"}
   ;; DeepSeek, metered, on its own endpoint. Mirrors the descriptor
   ;; `detect-available-credentials` emits for DEEPSEEK_API_KEY.
   :deepseek              {:kind          :deepseek :base-url "https://api.deepseek.com/v1"
                           :default-model "deepseek-v4-flash" :reasoning-dialect :deepseek
                           :route         #"^deepseek-"}
   ;; `:prefill-support :unsupported` — evidence, not caution: this endpoint
   ;; answers a trailing assistant message with a hard 400,
   ;; "Expected last role User or Tool (or Assistant with prefix True) for
   ;; serving but got assistant" (verified 2026-09-07, mistral-large-3). Every
   ;; other provider keeps today's behaviour and relies on the restart guard in
   ;; `llm-conversation/continuation-restarted?`; in particular Anthropic proper
   ;; is deliberately NOT downgraded — its prefill support is documented and was
   ;; simply not verifiable here, and declaring it unsupported on a guess would
   ;; remove working behaviour from existing embedders.
   :ollama                {:kind          :ollama :base-url "https://ollama.com/v1"
                           ;; See the note in `detect-available-credentials`:
                           ;; `kimi-k2.5` was retired upstream 2026-07-31.
                           :default-model "glm-5.3-flash" :reasoning-dialect :ollama
                           :route         #"^(kimi-|deepseek-|glm-|minimax-|gpt-oss)"}
   :opencode-go           {:kind          :opencode-go-openai :base-url "https://opencode.ai/zen/go/v1"
                           :default-model "glm-5" :reasoning-dialect :none
                           :route         #"^(glm-|kimi-|mimo-)"}
   :opencode-go-anthropic {:kind          :opencode-go-anthropic :base-url "https://opencode.ai/zen/go"
                           :default-model "minimax-m2.7" :auth-mode :x-api-key
                           :route         opencode-go-anthropic-route}
   ;; ChatGPT-subscription: no api-key/base-url; OAuth token is loaded by the
   ;; codex backend itself at send time, not here.
   :codex                 {:kind  :codex :default-model "gpt-5.6-sol"
                           :route #"^gpt-5"}
   :openai-codex          {:kind  :codex :default-model "gpt-5.6-sol"
                           :route #"^gpt-5"}
   ;; Claude Max/Pro subscription via the `claude -p` CLI. Deliberately has NO
   ;; `:route`: a `#"^claude-"` matcher would collide with `:anthropic`'s and
   ;; silently reroute metered traffic onto the subscription (or vice versa),
   ;; which is a billing change nobody asked for. This provider is reachable
   ;; only by being named explicitly — `--backend claude-cli`, a
   ;; `{:provider :claude-cli}` credential entry, or a preference alias — and
   ;; `detect-available-credentials` never emits it, so there is no
   ;; env-detected twin for the two paths to drift apart on.
   ;; `:default-model` is a CLI ALIAS, not an Anthropic id: the CLI resolves
   ;; `--model` against its own registry and exits 1 on ids it does not know
   ;; (`claude-sonnet-4-7` was one such — an id that exists nowhere).
   :claude-cli            {:kind :claude-cli :default-model "sonnet"}})

(defn- descriptor->credential
  "Resolve one injected credential descriptor into a full
   `build-credential-backend`-shaped map by merging the static provider
   template with the caller-supplied overrides (`:api-key`, `:base-url`,
   `:default-model`, `:model`). Pure: no env, no disk. Returns nil for an
   unknown provider so the caller can drop it cleanly."
  [{:keys [provider] :as desc}]
  (when-not (contains? provider-templates provider)
    ;; Dropped, not fatal: tolerating an unknown keyword is real
    ;; forward-compatibility for a host passing a superset of descriptors across
    ;; library versions. The DEFECT was the silence — a one-character typo
    ;; (`:anthropc`) used to remove a provider from the run with no signal
    ;; anywhere, so the run proceeded on a different provider, with the real key
    ;; unused. Name the known set the way a good 400 does, so the intended
    ;; keyword is visible on the same line as the mistake.
    (log/warn "[llm/credentials] unknown :provider" (pr-str provider)
      "— descriptor ignored. Known providers:"
      (pr-str (vec (sort (keys provider-templates))))))
  (when-let [tmpl (get provider-templates provider)]
    ;; `:http-timeout-ms` is honoured here on purpose: a host-supplied timeout
    ;; used to be dropped for EVERY provider, so only a template's own value
    ;; survived — which is why the z.ai entries (which set one) worked and
    ;; nothing else did. A caller whose generations legitimately run past the
    ;; 60s default had no way to say so.
    (let [overrides (-> desc
                      (select-keys [:api-key :base-url :default-model :auth-mode :reasoning-dialect
                                    :http-timeout-ms :http-transport :auth-fn :extra-headers
                                    :endpoint-profile :allow-stored-auth? :max-sse-event-chars])
                      (cond-> (:model desc) (assoc :default-model (:model desc))))]
      (cond-> (merge tmpl {:allow-stored-auth? false}
                (into {} (remove (comp nil? val)) overrides))
        ;; Union of the two opencode routes: the merged credential claims
        ;; every model the gateway serves on either wire, and
        ;; `build-opencode-go-backend` picks the wire per request.
        (= :opencode-go provider) (assoc :kind :opencode-go
                                    :route #"(?i)^(glm-|kimi-|mimo-|minimax-|qwen)")))))

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
    - HTTP descriptors accept `:auth-fn` (see `escapement.llm.auth/transport`),
      `:http-transport`, `:http-timeout-ms`, `:extra-headers`; Responses also
      accepts `:endpoint-profile`. No host auth is invoked at construction.
      Stored OAuth/browser login is disabled unless `:allow-stored-auth? true`
      is explicitly supplied (the CLI opts in). A callback always wins over it.
    - `:opencode-go` routes MiniMax and Qwen to Messages per request, including
      explicit provider requests; other models use Chat Completions.
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
