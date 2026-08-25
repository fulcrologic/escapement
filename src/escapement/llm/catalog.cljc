(ns escapement.llm.catalog
  "LLM model catalog, assembled from three layers:

   1. Objective facts — loaded from the bundled models.dev dump by
      `escapement.llm.catalog-source` (context window, output cap, vision,
      tool-call, pricing, credential env, …). Never hand-typed; regenerate
      the dump to update.

   2. A tiny local *fact* overlay (`local-models` / `local-providers`) for
      ids/providers the dump doesn't carry but the project still needs
      reachable (e.g. a model not yet in models.dev, or a subscription
      endpoint that isn't a models.dev provider). Deep-merged over layer 1;
      local wins.

   3. The subjective overlay — `escapement.llm.ratings` — supplies
      `:intelligence` and any other opinion keys. It is **not** a process
      global and is **not** merged into `info`; it flows as an explicit
      ratings value passed to `satisfies-policy?` (the only fn that needs
      opinion). `info` and the objective accessors stay opinion-free.

   Pricing lives ONLY on the provider side. The id-only `pricing` arity is
   backward-compat and answers \"cheapest metered list price\". Unknown
   ids/providers return `nil` rather than throwing.

   There is no `intelligence` accessor: intelligence is just one key in
   the ratings overlay, filtered like any other via `satisfies-policy?`
   (which takes ratings explicitly) — callers never name it directly."
  (:require
    [clojure.string :as str]
    #?(:clj [escapement.config :as config])
    [escapement.llm.catalog-source :as src]
    [escapement.llm.ratings :as ratings]))

(defn- deep-merge
  "Inlined copy of `escapement.config/deep-merge` so catalog stays CLJC.
   Recursively merges maps; non-map values from later args replace earlier ones."
  [& maps]
  (let [maps (remove nil? maps)]
    (cond
      (empty? maps) nil
      (= 1 (count maps)) (first maps)
      (every? map? maps) (apply merge-with deep-merge maps)
      :else (last maps))))

;; =============================================================================
;; Layer 2 — small local fact overlay (deep-merged over the dump; local wins)
;; =============================================================================

(def local-models
  "Intrinsic facts for ids the models.dev dump doesn't carry yet but that
   the project still wants reachable. Keep this as small as possible."
  ;; Anthropic's current generation. Absent from the models.dev dump, which
  ;; stops at `claude-opus-4-8` / `claude-sonnet-4-6`. Verified 2026-07-29
  ;; against the Claude Code CLI's live registry (`fable`→`claude-fable-5`,
  ;; `opus`→`claude-opus-5`, `sonnet`→`claude-sonnet-5`) and the published
  ;; model reference.
  ;;
  ;; NOTE: these REPLACE a `claude-sonnet-4-7` row that named a model which
  ;; does not exist — the CLI rejects that id outright and it appears in no
  ;; Anthropic model list. It was reachable through `preferences/default-aliases`
  ;; as `:default-sonnet`, so the built-in Sonnet default pointed at nothing.
  {"claude-fable-5"    {:context-tokens 1000000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Fable 5"}
   "claude-opus-5"     {:context-tokens 1000000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Opus 5"}
   "claude-sonnet-5"   {:context-tokens 1000000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Sonnet 5"}
   ;; OpenAI's GPT-5.6 generation — the Luna/Terra/Sol family (least → most
   ;; capable, released 2026-07-09). Absent from the models.dev dump, which
   ;; stops at 5.5.
   ;;
   ;; Pricing is published; the LIMITS are not, so `:context-tokens` /
   ;; `:max-output-tokens` are INHERITED from the 5.4/5.5 generation
   ;; (context 1050000, output 128000 — identical across both) as a documented
   ;; assumption, not a published spec. Refresh when the dump carries 5.6.
   "gpt-5.6-sol"       {:context-tokens 1050000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "gpt-5.6" :company "OpenAI"
                        :name           "GPT-5.6 Sol"}
   "gpt-5.6-terra"     {:context-tokens 1050000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "gpt-5.6" :company "OpenAI"
                        :name           "GPT-5.6 Terra"}
   "gpt-5.6-luna"      {:context-tokens 1050000 :max-output-tokens 128000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "gpt-5.6" :company "OpenAI"
                        :name           "GPT-5.6 Luna"}
   ;; The Claude Code CLI's own model ALIASES (`:claude-cli` provider). The CLI
   ;; resolves these against its current registry, so the facts here are the
   ;; conservative floor for the family rather than a pinned model's spec — the
   ;; Response reports whichever dated model actually ran.
   "fable"             {:context-tokens 200000 :max-output-tokens 64000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Fable (CLI alias)"}
   "opus"              {:context-tokens 200000 :max-output-tokens 64000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Opus (CLI alias)"}
   "sonnet"            {:context-tokens 200000 :max-output-tokens 64000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Sonnet (CLI alias)"}
   "haiku"             {:context-tokens 200000 :max-output-tokens 32000
                        :vision?        true :tool-call? true :reasoning? true
                        :family         "claude" :company "Anthropic"
                        :name           "Claude Haiku (CLI alias)"}
   "deepseek-v4-flash" {:max-output-tokens 65536}
   ;; ollama-cloud advertises `limit.output: 1048576` for this id, but the
   ;; underlying DeepSeek API rejects max_tokens > 393216 ("Invalid
   ;; max_tokens value, the valid range of max_tokens is [1, 393216]").
   ;; Clamp at 16384 — comfortably under every observed wire cap and more
   ;; than enough for any single LLM turn this project asks for.
   "deepseek-v4-pro"   {:max-output-tokens 16384}
   ;; z.ai's GLM flagship as of 2026-08. The dump's zai / zai-coding-plan
   ;; providers stop at glm-5.1 (z.ai server-side auto-routes glm-5.1/5.2
   ;; requests to glm-5.3, per the coding-plan docs). Limits mirror the
   ;; published glm-5.1 row; context confirmed by the coding-plan docs
   ;; ("other models 200000").
   "glm-5.3"           {:context-tokens  200000 :max-output-tokens 131072
                        :vision?         false :tool-call? true :reasoning? true
                        :family          "glm" :company "Zhipu"
                        :name            "GLM-5.3"}})

(def ^:private codex-subscription-models
  "The ChatGPT-account Codex model set, zero-priced (flat-fee subscription).
   Shared by BOTH provider spellings — see `local-providers`."
  {"gpt-5.6-sol"   {:pricing {:input 0 :output 0}}
   "gpt-5.6-terra" {:pricing {:input 0 :output 0}}
   "gpt-5.6-luna"  {:pricing {:input 0 :output 0}}
   "gpt-5.5"       {:pricing {:input 0 :output 0}}
   "gpt-5.4"       {:pricing {:input 0 :output 0}}
   "gpt-5.4-mini"  {:pricing {:input 0 :output 0}}})

(def local-providers
  "Provider entries / model rows not present in the dump:
   * Anthropic's current 5-series priced under `:anthropic` (the dump stops at
     `claude-opus-4-8` / `claude-sonnet-4-6`). List prices as published
     2026-07-29; Sonnet 5 carries an introductory rate through 2026-08-31
     ($2.00/$10.00) that is NOT encoded here — the standard rate is.
   * `:openai-codex` — a flat-fee subscription endpoint that is not a
     models.dev provider; per-token pricing is zeroed.
   * `:claude-cli` — likewise flat-fee (a Claude Max/Pro subscription driven
     through `claude -p`), so pricing is zeroed. Its model keys are the CLI's
     own ALIASES rather than Anthropic ids, because the CLI resolves `--model`
     against its own registry and rejects ids it does not know."
  {:anthropic
   {:models {"claude-fable-5"  {:pricing {:input 10.0 :output 50.0}}
             "claude-opus-5"   {:pricing {:input 5.0 :output 25.0}}
             "claude-sonnet-5" {:pricing {:input 3.0 :output 15.0}}}}
   ;; Only the ids the ChatGPT-ACCOUNT Codex endpoint actually accepts, which is
   ;; a much smaller set than the OpenAI catalog implies: every `-codex`,
   ;; `-pro`, and `-nano` variant is rejected with "The '<id>' model is not
   ;; supported when using Codex with a ChatGPT account", as is the previous
   ;; generation of general models. Verified live 2026-07-29 — the previous
   ;; entries here (`gpt-5`, `gpt-5-mini`, `o3`) were all stale.
   ;; `escapement.llm.openai-codex.translate/supported-models` is the source of
   ;; truth; `bb_test/codex_models_probe.clj` re-verifies both against a token.
   :openai-codex
   {:display "OpenAI Codex" :auth :subscription
    :models  codex-subscription-models}
   ;; `:codex` is the OTHER accepted spelling of the same provider —
   ;; `providers/provider-templates` takes both, `detect-available-credentials`
   ;; emits `:kind :codex`, and `preferences/default-aliases` names `:codex`.
   ;; Without this entry `subscription? :codex` was false and
   ;; `pricing :codex <model>` nil, so a codex target was accounted as METERED.
   :codex
   {:display "OpenAI Codex" :auth :subscription
    :models  codex-subscription-models}
   ;; Metered OpenAI list prices for the GPT-5.6 generation, which the
   ;; models.dev dump does not carry yet (it stops at 5.5). Sol's $5/$30 is
   ;; exactly `gpt-5.5`'s tier and Terra's $2.50/$15 exactly `gpt-5.4`'s, so the
   ;; family slots into the existing ladder; Luna is a new cheaper tier.
   :openai
   {:models {"gpt-5.6-sol"   {:pricing {:input 5.0 :output 30.0}}
             "gpt-5.6-terra" {:pricing {:input 2.5 :output 15.0}}
             "gpt-5.6-luna"  {:pricing {:input 1.0 :output 6.0}}}}
   :claude-cli
   {:display "Claude Code CLI" :auth :subscription
    :models  {"fable"  {:pricing {:input 0 :output 0}}
              "opus"   {:pricing {:input 0 :output 0}}
              "sonnet" {:pricing {:input 0 :output 0}}
              "haiku"  {:pricing {:input 0 :output 0}}}}
   ;; z.ai coding plan, v1 (legacy) generation — flat-fee subscription on the
   ;; OpenAI Responses wire (`https://api.z.ai/api/v1`). Same plan/billing as
   ;; the dump's `:z-ai-plan` (Anthropic face); this is the provider keyword
   ;; `detect-available-credentials` emits for ZAI_API_KEY. glm-5.3 is absent
   ;; from the dump's zai-coding-plan model list, so it is spelled out here;
   ;; the other ids mirror the dump's rows (zeroed: subscription).
   :zai-coding-plan
   {:display "Z.AI Coding Plan" :auth :subscription
    :models  {"glm-5.3"     {:pricing {:input 0 :output 0}}
              "glm-5.1"     {:pricing {:input 0 :output 0}}
              "glm-5-turbo" {:pricing {:input 0 :output 0}}
              "glm-4.7"     {:pricing {:input 0 :output 0}}}}
   ;; glm-5.3 under the Anthropic-face subscription provider too (deep-merged
   ;; over the dump's zai-coding-plan rows), so a `{:provider :z-ai-plan
   ;; :model "glm-5.3"}` target resolves pricing/subscription.
   :z-ai-plan
   {:models {"glm-5.3" {:pricing {:input 0 :output 0}}}}})

;; =============================================================================
;; Assembled tables
;; =============================================================================

(def models
  "Canonical model id → intrinsic fact map (objective only; subjective
   `:intelligence` is overlaid by `info`, not stored here). Loaded from
   the models.dev dump and deep-merged with the local overlay on every
   host; on CLJS the dump is baked in at compile time via
   `escapement.llm.catalog-macros/embedded-catalog`."
  (let [{:keys [models]} (src/load-catalog)]
    (deep-merge models local-models)))

(def providers
  "Provider keyword → `{:display :auth :env :api :models}`. Same id may
   appear under several providers with different pricing. Loaded from the
   models.dev dump on every host."
  (let [{:keys [providers]} (src/load-catalog)]
    (deep-merge providers local-providers)))

(defn- prefix-lookup
  "Exact key match in `table`, else longest-prefix match so dated ids like
   `claude-haiku-4-5-20251001` resolve to the family entry."
  [table k]
  (when (string? k)
    (or (get table k)
      (->> (keys table)
        (sort-by (comp - count))
        (some (fn [kk] (when (str/starts-with? k kk) (get table kk))))))))

;; =============================================================================
;; Model accessors (id-keyed, provider-independent)
;; =============================================================================

(defn info
  "Intrinsic *objective* fact map for `model` (context window, output cap,
   vision, tool-call, company, …) drawn solely from the models.dev-backed
   catalog tables — no ratings, no config, no disk. nil when the id is
   unknown to the objective catalog.

   The subjective ratings overlay is intentionally NOT merged here: it
   flows as an explicit value through `satisfies-policy?`, not a process
   global. Callers that need an opinion key pass ratings to the policy fn.

   Exact match first; otherwise longest-prefix match."
  [model]
  (prefix-lookup models model))

(defn context-window
  "Known INPUT context window (tokens) for `model`, or nil. Everything the
   model sees on a request: system + tools + accumulated history."
  [model]
  (:context-tokens (info model)))

(defn max-output-tokens
  "Per-response output cap (tokens) for `model`, or nil."
  [model]
  (:max-output-tokens (info model)))

(defn vision?
  "True when `model` accepts image input. False for unknown models."
  [model]
  (true? (:vision? (info model))))

(defn company
  "Vendor/maker string for `model`, or nil when unknown."
  [model]
  (:company (info model)))

(defn model-name
  "Short human display label for `model`, or nil when unknown."
  [model]
  (:name (info model)))

;; =============================================================================
;; Declarative model policy
;; =============================================================================

(def eligibility-facts
  "The stable, published vocabulary of **objective** facts a chart node's
   eligibility gate may reference. These come from the models.dev-backed
   catalog (never ratings, never config). A gate clause may also reference
   any *subjective* rating key supplied via `:llm/ratings` — those are
   host-defined and free-form, so they are deliberately NOT enumerated
   here; this constant lists only the objective half.

   This is data + docs, not enforcement: nothing rejects an unknown key,
   but this is the contract embedders should treat as supported.

     | key                  | meaning                                  |
     |----------------------|------------------------------------------|
     | `:vision?`           | accepts image input (boolean)            |
     | `:tool-call?`        | supports tool/function calling (boolean) |
     | `:reasoning?`        | reasoning / extended-thinking (boolean)  |
     | `:context-tokens`    | input context window, in tokens          |
     | `:max-output-tokens` | per-response output cap, in tokens       |
     | `:company`           | vendor / maker string                    |
     | `:family`            | model family string                      |
     | `:knowledge`         | knowledge-cutoff string                  |"
  {:vision?           "accepts image input (boolean)"
   :tool-call?        "supports tool/function calling (boolean)"
   :reasoning?        "reasoning / extended-thinking (boolean)"
   :context-tokens    "input context window, in tokens"
   :max-output-tokens "per-response output cap, in tokens"
   :company           "vendor / maker string"
   :family            "model family string"
   :knowledge         "knowledge-cutoff string"})

(defn- merged-info
  "Objective `info` for `model` with the subjective `ratings` overlay
   merged on top (prefix-resolved against `ratings` so dated ids reach the
   family entry). nil when the id is unknown to the objective catalog."
  [model ratings]
  (when-let [base (info model)]
    (merge base (prefix-lookup (or ratings {}) model))))

(defn satisfies-policy?
  "True when `model` satisfies `policy`, where facts are resolved from the
   objective catalog plus an explicit subjective `ratings` table merged on
   top. The policy is a declarative map over *any* fact key — objective
   (`:vision?`, `:tool-call?`, `:context-tokens`, … see
   `eligibility-facts`) or subjective (anything the host configures via
   `:llm/ratings`). The invocation layer never names a specific key, so
   new opinion keys are filterable with zero code change.

   `ratings` is a plain id → opinion-map value (the shape
   `escapement.llm.ratings/ratings` returns). It is resolved locally with
   the same longest-prefix logic the catalog uses for dated ids; an empty
   `{}` means a subjective clause matches nothing.

   Clauses (all optional, all must hold):
   * `:require {k v …}` — exact equality, `(= (fact k) v)`.
   * `:min     {k n …}` — numeric floor, `(>= (fact k) n)`.
   * `:max     {k n …}` — numeric ceiling, `(<= (fact k) n)`.

   An empty/nil policy admits everything (including ids the catalog
   doesn't know). A non-empty policy rejects an unknown id — there are no
   facts to vouch for it.

   The 2-arg arity is a backward-compatible CLI seam: it resolves ratings
   from the merged `.escapement.edn` on each call (no process global, no
   `def`-of-`delay`). New callers thread ratings explicitly via 3-arg."
  ([model policy]
   (satisfies-policy? model policy
     #?(:clj  (ratings/ratings (config/load-config))
        :cljs {})))
  ([model policy ratings]
   (let [{:keys [require min max]} policy]
     (if (and (empty? require) (empty? min) (empty? max))
       true
       (if-let [i (merged-info model ratings)]
         (and (every? (fn [[k v]] (= (get i k) v)) require)
           (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (>= x n)))) min)
           (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (<= x n)))) max))
         false)))))

(defn target-satisfies-policy?
  "True when a single TARGET satisfies `policy`, where objective facts are
   resolved from the catalog by the target's `model` STRING and the subjective
   overlay is the alias's rating map `overlay` supplied DIRECTLY (already looked
   up by alias keyword — no model-string prefix resolution).

   This is the mandatory-aliases target-granularity gate: objective catalog
   facts stay per provider+model (`model` string), while the subjective rating
   is read by the originating ALIAS keyword upstream and handed in here as a
   plain opinion map. An empty/nil `policy` admits everything; a non-empty
   policy over an unknown model id (no objective facts) is rejected unless the
   overlay alone satisfies every clause."
  [model policy overlay]
  (let [{:keys [require min max]} policy]
    (if (and (empty? require) (empty? min) (empty? max))
      true
      (let [i (merge (or (info model) {}) (or overlay {}))]
        (and (seq i)
          (every? (fn [[k v]] (= (get i k) v)) require)
          (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (>= x n)))) min)
          (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (<= x n)))) max))))))

;; =============================================================================
;; Provider accessors
;; =============================================================================

(defn provider-info
  "Return the provider entry for `provider`, or nil when unknown."
  [provider]
  (get providers provider))

(defn provider-display
  "Human label for `provider`. Falls back to the keyword name, then
   \"Unknown\"."
  [provider]
  (or (:display (provider-info provider))
    (some-> provider name)
    "Unknown"))

(defn subscription?
  "True when `provider` bills a flat subscription (per-token pricing is
   zeroed). False for metered or unknown providers."
  [provider]
  (= :subscription (:auth (provider-info provider))))

(defn serves?
  "True when `provider` serves model `id`."
  [provider id]
  (contains? (:models (provider-info provider)) id))

(defn providers-for
  "Provider keywords that serve model `id`, in catalog order."
  [id]
  (->> providers
    (filter (fn [[_ entry]] (contains? (:models entry) id)))
    (mapv key)))

(defn pricing
  "USD per 1M tokens `{:input N :output M}`.

   * `[provider id]` — the price `provider` charges for `id`
     (`{:input 0 :output 0}` for subscription providers); nil when that
     provider doesn't serve the model.
   * `[id]` — backward-compat: cheapest list price across *metered*
     providers serving `id` (by `:input`), or nil."
  ([id]
   (->> (providers-for id)
     (remove subscription?)
     (keep #(get-in providers [% :models id :pricing]))
     (sort-by :input)
     first))
  ([provider id]
   (get-in providers [provider :models id :pricing])))
