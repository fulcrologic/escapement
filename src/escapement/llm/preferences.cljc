(ns escapement.llm.preferences
  "User-configurable, priority-ordered list of which model *aliases* to
   prefer — and in what order to reach them.

   As of the mandatory-aliases model, `:llm/aliases` is the ONLY way to
   define a concrete `{:provider :model …}` target. `:llm/preferences` is no
   longer a list of `{:provider :model}` pairs; it is an ordered VECTOR OF
   ALIAS KEYWORDS — the default candidate set used when a chart node names no
   model. Each keyword MUST be a key in `:llm/aliases` (referential
   integrity, enforced at load time by `escapement.config`).

   Source of truth: the `:llm/preferences` key of the layered
   `.escapement.edn` config (`escapement.config/load-config` — project file
   wins over `~/.escapement.edn`). Shape:

   ```
   {:llm/aliases     {:fast  [{:provider :ollama    :model \"glm-5.1\"}]
                      :smart [{:provider :anthropic :model \"claude-opus-4-7\"}]}
    :llm/preferences [:fast :smart]}
   ```

   When `:llm/preferences` is absent, `default-preferences` (a vector of the
   built-in `default-aliases` keys) is used. When `:llm/aliases` is absent,
   the built-in `default-aliases` set is used, so a config with NEITHER key
   still resolves a sane default purely through the alias path.

   NOTE: explicitly pinning one specific alias for a single run (overriding
   this priority order from the CLI/a chart) is handled by the chart node's
   `:model` keyword, resolved by `escapement.invocation.llm-conversation`."
  (:require #?(:clj [escapement.config :as config])))

(def default-aliases
  "Built-in DEFAULT ALIAS SET used when the config sets no `:llm/aliases`.
   Each value is an ordered, non-empty vector of `{:provider :model}` targets,
   the same shape `:llm/aliases` accepts. These are the targets the empty-config
   path resolves THROUGH — there is no longer a parallel pair-based fallback.
   Tune via `:llm/aliases` + `:llm/preferences` in `.escapement.edn` rather
   than editing this."
  {:default-glm        [{:provider :z-ai-plan :model "glm-5.1"}
                        {:provider :z-ai :model "glm-4.7"}]
   :default-sonnet     [{:provider :anthropic :model "claude-sonnet-4-7"}]
   :default-opus       [{:provider :anthropic :model "claude-opus-4-7"}]
   :default-gpt        [{:provider :openai :model "gpt-5"}]})

(def default-preferences
  "Built-in priority order (a VECTOR OF ALIAS KEYWORDS over `default-aliases`)
   used when the config sets no `:llm/preferences`. Cheapest /
   subscription-backed options first, climbing to premium models."
  [:default-glm :default-sonnet :default-opus :default-gpt])

(defn from-config
  "Extract the raw preference vector from a loaded config map. Accepts the
   flat `:llm/preferences` key or a nested `[:llm :preferences]`. Returns
   nil when neither is present (caller falls back to the default)."
  [cfg]
  (or (:llm/preferences cfg)
    (get-in cfg [:llm :preferences])))

(defn aliases-from-config
  "Extract the `:llm/aliases` map from a loaded config map, falling back to
   the built-in `default-aliases` when the config defines none. Accepts the
   flat `:llm/aliases` key or a nested `[:llm :aliases]`."
  [cfg]
  (or (:llm/aliases cfg)
    (get-in cfg [:llm :aliases])
    default-aliases))

(defn dangling-references
  "Return the subset of preference keywords in `prefs` that are NOT keys in
   `aliases` (referential-integrity violations). Empty when all resolve."
  [prefs aliases]
  (vec (remove (set (keys aliases)) prefs)))

(defn pair-entries?
  "True when `prefs` contains a legacy `{:provider :model}` pair entry (or any
   non-keyword element) — the OLD preference shape, now rejected."
  [prefs]
  (boolean (some (complement keyword?) prefs)))

(defn preferences
  "The effective priority list as an ORDERED VECTOR OF ALIAS KEYWORDS.

   Zero-arg: load+merge `.escapement.edn` and read `:llm/preferences`,
   falling back to `default-preferences` when unset. One-arg: same, but
   against an already-loaded config map (no disk read — handy for tests).

   The returned keywords are references into `:llm/aliases` (or the built-in
   `default-aliases`); referential integrity is validated at config load time
   by `escapement.config`, so this returns the vector as-is."
  ([]
   #?(:clj  (preferences (config/load-config))
      :cljs (throw (ex-info "(preferences) zero-arg requires CLJ disk config loading; pass a config map"
                     {:reason :cljs-no-disk-config}))))
  ([cfg]
   (let [raw (from-config cfg)]
     (vec (if (some? raw) raw default-preferences)))))

(defn flatten-targets
  "Flatten an ordered vector of alias keywords into the ordered, de-duplicated
   sequence of their `{:provider :model …}` targets, looked up in `aliases`.
   Unknown alias keywords contribute nothing (referential integrity is enforced
   at load time). Author/target order is preserved; this is the canonical
   expansion of the preference candidate set."
  [prefs aliases]
  (->> prefs
    (mapcat (fn [k] (get aliases k)))
    (filter some?)
    distinct
    vec))

(defn model-order
  "Reduce an alias-keyword preference vector to a distinct, priority-ordered
   vector of model ids — the `:default-models` fallback list the
   llm-conversation processor consumes when a chart doesn't pin `:model`.

   One-arg legacy seam: when given an already-flattened seq of `{:provider
   :model}` target maps, behaves as before (maps :model). Two-arg: given a
   vector of alias keywords + the `:llm/aliases` map, flattens the referenced
   aliases' targets first.

   The provider order needed for credential-route ordering (R8) is derived by
   `escapement.llm.providers` from these same flattened targets."
  ([targets]
   (->> targets (map :model) distinct vec))
  ([prefs aliases]
   (model-order (flatten-targets prefs aliases))))

(defn provider-order
  "The distinct, priority-ordered vector of provider keywords obtained by
   flattening `prefs` (alias keywords) over `aliases`. Used to order
   credential-backend routes (R8)."
  [prefs aliases]
  (->> (flatten-targets prefs aliases) (map :provider) distinct vec))
