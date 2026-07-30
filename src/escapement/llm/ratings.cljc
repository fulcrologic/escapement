(ns escapement.llm.ratings
  "Subjective opinion about models — the part that is *ours*, not a fact
   the API exposes. Kept deliberately separate from the objective catalog
   (`escapement.llm.catalog-source`) and out of the invocation path.

   As of the mandatory-aliases model, this subjective overlay is keyed by
   ALIAS KEYWORD, not by model-id string. Aliases were built to eliminate
   the cross-provider naming divergence (`kimi-k2.6` vs
   `moonshotai/Kimi-K2.6` vs `accounts/fireworks/models/kimi-k2p6`); keying
   ratings on the alias keyword resolves that divergence for opinions too.
   The OBJECTIVE catalog lookup (`escapement.llm.catalog`) is unchanged —
   it is still per provider+model; only this subjective overlay moves to
   alias keys.

   There is **no built-in opinion**. Ratings are entirely user-defined:
   the table comes only from `:llm/ratings` (or nested `[:llm :ratings]`)
   in the merged `.escapement.edn`. With nothing configured, `rating-for`
   is nil and a `:min`/`:max`/`:require` policy over a rating key matches
   no alias — the project that wants subjective gating supplies the
   numbers, the library ships none.

   An entry is a free-form map keyed by an ALIAS KEYWORD (which MUST be a
   key in `:llm/aliases` — referential integrity, enforced at load time by
   `escapement.config`). The value map is intentionally open:
   `:intelligence` is just a conventional general-purpose key, with no
   privileged status — per-dimension keys like `:clojure`/`:tool-calling`,
   or anything else (`:tier`, `:avoid?`, …), work identically. Only aliases
   that carry a key can satisfy a `:min`/`:max` over it."
  (:require
    #?(:clj [escapement.config :as config])))

(comment
  ;; There are deliberately no built-in ratings. To gate model selection on
  ;; opinion, define your own table in `.escapement.edn`, KEYED BY ALIAS
  ;; KEYWORD (each key must also exist in `:llm/aliases`), e.g.:
  ;;
  ;;   {:llm/aliases {:opus  [{:provider :anthropic :model "claude-opus-5"}]
  ;;                  :gpt   [{:provider :openai    :model "gpt-5"}]}
  ;;    :llm/ratings {:opus {:intelligence 10 :clojure 10 :tool-calling 9}
  ;;                  :gpt  {:intelligence 10 :clojure 6}}}
  ;;
  ;; Keys are free-form; only aliases carrying a key can satisfy a policy
  ;; `:min`/`:max` over it.
  )

(defn from-config
  "Extract the raw `:llm/ratings` table from a loaded config map. Accepts
   the flat key or the nested `[:llm :ratings]`. nil when neither set."
  [cfg]
  (or (:llm/ratings cfg)
    (get-in cfg [:llm :ratings])))

(defn ratings
  "Effective alias-keyword → opinion map, taken solely from the config
   overlay. Empty when nothing is configured — there is no built-in opinion.

   Zero-arg loads+merges `.escapement.edn`; one-arg takes an already
   loaded config map (no disk read — handy for tests)."
  ([] #?(:clj  (ratings (config/load-config))
         :cljs (throw (ex-info "(ratings) zero-arg requires CLJ disk config loading; pass a config map"
                        {:reason :cljs-no-disk-config}))))
  ([cfg] (or (from-config cfg) {})))

(defn dangling-references
  "Return the subset of rating keys NOT present as `:llm/aliases` keys
   (referential-integrity violations). `ratings-table` is the alias-keyed
   overlay; `aliases` the `:llm/aliases` map. Empty when all keys resolve."
  [ratings-table aliases]
  (vec (remove (set (keys aliases)) (keys ratings-table))))

(defn string-keyed?
  "True when `ratings-table` carries any non-keyword (e.g. legacy model-id
   STRING) key — the OLD ratings shape, now rejected."
  [ratings-table]
  (boolean (some (complement keyword?) (keys ratings-table))))

(defn rating-for
  "Opinion map for `alias` (a keyword) against config `cfg`, or nil when
   nothing is known. Exact alias-keyword lookup only — no string-prefix
   resolution, since aliases already collapse the per-provider naming
   divergence dated-id prefix matching used to paper over."
  [cfg alias]
  (when (keyword? alias)
    (get (ratings cfg) alias)))
