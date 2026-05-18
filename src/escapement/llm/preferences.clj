(ns escapement.llm.preferences
  "User-configurable, priority-ordered list of which (provider, model)
   pairs to use — and in what order to prefer them.

   The catalog (`escapement.llm.catalog`) says what *exists* and what each
   provider charges. This namespace says what *you* want reached first.
   The same model can be available from several providers — e.g. `glm-5.1`
   metered from `:z-ai` and free under an `:ollama` subscription — so the
   unit of preference is a `{:provider P :model M}` pair, ordered.

   Source of truth: the `:llm/preferences` key of the layered
   `.escapement.edn` config (`escapement.config/load-config` — project
   file wins over `~/.escapement.edn`). Shape:

   ```
   {:llm/preferences [{:provider :ollama    :model \"glm-5.1\"}
                      {:provider :z-ai      :model \"glm-5.1\"}
                      {:provider :anthropic :model \"claude-opus-4-7\"}]}
   ```

   When the key is absent, `default-preferences` is used. Entries that
   don't validate against the catalog (unknown provider, or provider
   doesn't serve that model) are dropped — preferences can only point at
   things the catalog actually knows how to reach.

   NOTE: explicitly pinning one specific provider/model for a single run
   (overriding this priority order from the CLI/a chart) is intentionally
   out of scope here for now — this namespace only expresses the standing
   priority list. That one-off override is a later piece."
  (:require [escapement.config :as config]
            [escapement.llm.catalog :as catalog]))

(def default-preferences
  "Built-in priority order used when the config sets nothing. Cheapest /
   subscription-backed options first, climbing to premium models. Tune via
   `:llm/preferences` in `.escapement.edn` rather than editing this."
  [{:provider :z-ai-plan :model "glm-5.1"}
   {:provider :z-ai      :model "glm-4.7"}
   {:provider :anthropic :model "claude-sonnet-4-7"}
   {:provider :anthropic :model "claude-opus-4-7"}
   {:provider :openai    :model "gpt-5"}])

(defn valid-entry?
  "True when `entry` is a `{:provider :model}` pair the catalog can
   actually reach — known provider that serves that model id."
  [{:keys [provider model]}]
  (boolean (and provider model (catalog/serves? provider model))))

(defn from-config
  "Extract the raw preference vector from a loaded config map. Accepts the
   flat `:llm/preferences` key or a nested `[:llm :preferences]`. Returns
   nil when neither is present (caller falls back to the default)."
  [cfg]
  (or (:llm/preferences cfg)
      (get-in cfg [:llm :preferences])))

(defn sanitize
  "Coerce a raw preference seq to validated `{:provider :model}` entries,
   preserving order and dropping anything the catalog can't reach. Tuple
   `[:provider \"model\"]` entries are accepted alongside maps."
  [raw]
  (->> raw
       (map (fn [e]
              (if (map? e)
                (select-keys e [:provider :model])
                {:provider (first e) :model (second e)})))
       (filterv valid-entry?)))

(defn preferences
  "The effective priority list as validated `{:provider :model}` entries.

   Zero-arg: load+merge `.escapement.edn` and read `:llm/preferences`,
   falling back to `default-preferences` when unset. One-arg: same, but
   against an already-loaded config map (no disk read — handy for tests)."
  ([]
   (preferences (config/load-config)))
  ([cfg]
   (let [raw (from-config cfg)]
     (if (some? raw)
       (sanitize raw)
       (sanitize default-preferences)))))

(defn available
  "Filter `prefs` to entries whose provider is currently usable, keeping
   priority order. `provider-available?` is a predicate of a provider
   keyword (e.g. \"has credentials detected\") — defaults to every entry."
  ([prefs] (available prefs (constantly true)))
  ([prefs provider-available?]
   (filterv (comp provider-available? :provider) prefs)))

(defn model-order
  "Reduce `prefs` to a distinct, priority-ordered vector of model ids —
   the `:default-models` fallback list the llm-conversation processor
   consumes when a chart doesn't pin `:model`."
  [prefs]
  (->> prefs (map :model) distinct vec))
