(ns escapement.llm.ratings
  "Subjective opinion about models — the part that is *ours*, not a fact
   the API exposes. Kept deliberately separate from the objective catalog
   (`escapement.llm.catalog-source`) and out of the invocation path.

   There is **no built-in opinion**. Ratings are entirely user-defined:
   the table comes only from `:llm/ratings` (or nested `[:llm :ratings]`)
   in the merged `.escapement.edn`. With nothing configured, `rating-for`
   is nil and a `:min`/`:max`/`:require` policy over a rating key matches
   no model — the project that wants subjective gating supplies the
   numbers, the library ships none.

   An entry is a free-form map keyed by canonical model id. The value map
   is intentionally open: `:intelligence` is just a conventional
   general-purpose key, with no privileged status — per-dimension keys
   like `:clojure`/`:tool-calling`, or anything else (`:tier`, `:avoid?`,
   …), work identically. Only ids that carry a key can satisfy a
   `:min`/`:max` over it (see `escapement.examples.clj-refactor` for the
   config a chart that filters on these requires).

   Dated ids like `claude-opus-4-7-20260101` resolve to the family entry
   via longest-prefix, same as the catalog."
  (:require
    [clojure.string :as str]
    #?(:clj [escapement.config :as config])))

(comment
  ;; There are deliberately no built-in ratings. To gate model selection
  ;; on opinion, define your own table in `.escapement.edn`, e.g.:
  ;;
  ;;   {:llm/ratings {"claude-opus-4-7" {:intelligence 10 :clojure 10
  ;;                                     :tool-calling 9}
  ;;                  "gpt-5"           {:intelligence 10 :clojure 6}}}
  ;;
  ;; Keys are free-form; only ids carrying a key can satisfy a policy
  ;; `:min`/`:max` over it.
  )

(defn from-config
  "Extract the raw `:llm/ratings` table from a loaded config map. Accepts
   the flat key or the nested `[:llm :ratings]`. nil when neither set."
  [cfg]
  (or (:llm/ratings cfg)
    (get-in cfg [:llm :ratings])))

(defn ratings
  "Effective id → opinion map, taken solely from the config overlay.
   Empty when nothing is configured — there is no built-in opinion.

   Zero-arg loads+merges `.escapement.edn`; one-arg takes an already
   loaded config map (no disk read — handy for tests)."
  ([] #?(:clj  (ratings (config/load-config))
         :cljs (throw (ex-info "(ratings) zero-arg requires CLJ disk config loading; pass a config map"
                        {:reason :cljs-no-disk-config}))))
  ([cfg] (or (from-config cfg) {})))

(defn rating-for
  "Opinion map for `model` against config `cfg`, or nil when nothing is
   known. Exact id first, then longest-prefix so dated ids resolve to the
   family entry."
  [cfg model]
  (when (string? model)
    (let [table (ratings cfg)]
      (or (get table model)
        (->> (keys table)
          (sort-by (comp - count))
          (some (fn [k]
                  (when (str/starts-with? model k)
                    (get table k)))))))))
