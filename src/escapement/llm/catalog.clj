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
      `:intelligence` and any other opinion keys, layered from
      `.escapement.edn`. Merged into `info` so callers see one map, but the
      objective tables stay opinion-free.

   Pricing lives ONLY on the provider side. The id-only `pricing` arity is
   backward-compat and answers \"cheapest metered list price\". Unknown
   ids/providers return `nil` rather than throwing.

   There is no `intelligence` accessor: intelligence is just one key in
   the ratings overlay, surfaced through `info` and filtered like any
   other via `satisfies-policy?` — callers never name it directly."
  (:require
   [clojure.string :as str]
   [escapement.config :as config]
   [escapement.llm.catalog-source :as src]
   [escapement.llm.ratings :as ratings]))

;; =============================================================================
;; Layer 2 — small local fact overlay (deep-merged over the dump; local wins)
;; =============================================================================

(def local-models
  "Intrinsic facts for ids the models.dev dump doesn't carry yet but that
   the project still wants reachable. Keep this as small as possible."
  {"claude-sonnet-4-7" {:context-tokens 1000000 :max-output-tokens 64000
                        :vision? true :tool-call? true :reasoning? true
                        :family "claude" :company "Anthropic"
                        :name "Claude Sonnet 4.7"}})

(def local-providers
  "Provider entries / model rows not present in the dump:
   * `claude-sonnet-4-7` priced under `:anthropic` (mirrors Sonnet list price).
   * `:openai-codex` — a flat-fee subscription endpoint that is not a
     models.dev provider; per-token pricing is zeroed."
  {:anthropic
   {:models {"claude-sonnet-4-7" {:pricing {:input 3.0 :output 15.0}}}}
   :openai-codex
   {:display "OpenAI Codex" :auth :subscription
    :models {"gpt-5"      {:pricing {:input 0 :output 0}}
             "gpt-5-mini" {:pricing {:input 0 :output 0}}
             "o3"         {:pricing {:input 0 :output 0}}}}})

;; =============================================================================
;; Assembled tables
;; =============================================================================

(def models
  "Canonical model id → intrinsic fact map (objective only; subjective
   `:intelligence` is overlaid by `info`, not stored here)."
  (let [{:keys [models]} (src/load-catalog)]
    (config/deep-merge models local-models)))

(def providers
  "Provider keyword → `{:display :auth :env :api :models}`. Same id may
   appear under several providers with different pricing."
  (let [{:keys [providers]} (src/load-catalog)]
    (config/deep-merge providers local-providers)))

;; Subjective overlay is config-driven; resolve+cache once per process.
(def ^:private ratings-cache (delay (ratings/ratings (config/load-config))))

(defn- prefix-lookup
  "Exact key match in `table`, else longest-prefix match so dated ids like
   `claude-opus-4-7-20260101` resolve to the family entry."
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
  "Intrinsic fact map for `model`, with the subjective ratings overlay
   merged in (so `:intelligence` and any configured opinion keys appear),
   or nil when the id is unknown to the objective catalog.

   Exact match first; otherwise longest-prefix match."
  [model]
  (when-let [base (prefix-lookup models model)]
    (merge base (prefix-lookup @ratings-cache model))))

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

(defn satisfies-policy?
  "True when `model`'s merged `info` satisfies `policy`. The policy is a
   declarative map over *any* info key — objective (`:vision?`,
   `:tool-call?`, `:context-tokens`, …) or subjective (`:intelligence`
   and anything else a chart configures via `:llm/ratings`). The
   invocation layer never names a specific key, so new opinion keys are
   filterable with zero code change.

   Clauses (all optional, all must hold):
   * `:require {k v …}` — exact equality, `(= (info k) v)`.
   * `:min     {k n …}` — numeric floor, `(>= (info k) n)`.
   * `:max     {k n …}` — numeric ceiling, `(<= (info k) n)`.

   An empty/nil policy admits everything (including ids the catalog
   doesn't know). A non-empty policy rejects an unknown id — there are no
   facts to vouch for it."
  [model policy]
  (let [{:keys [require min max]} policy]
    (if (and (empty? require) (empty? min) (empty? max))
      true
      (if-let [i (info model)]
        (and (every? (fn [[k v]] (= (get i k) v)) require)
             (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (>= x n)))) min)
             (every? (fn [[k n]] (let [x (get i k)] (and (number? x) (<= x n)))) max))
        false))))

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
