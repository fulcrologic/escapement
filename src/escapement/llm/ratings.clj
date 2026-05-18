(ns escapement.llm.ratings
  "Subjective opinion about models — the part that is *ours*, not a fact
   the API exposes. Kept deliberately separate from the objective catalog
   (`escapement.llm.catalog-source`) and out of the invocation path.

   An entry is a free-form map keyed by canonical model id. `:intelligence`
   (coarse 1–10) is the general-purpose key, but the value map is
   intentionally open. Beyond it we ship coarse 1–10 *per-dimension*
   scores — `:clojure`, `:typescript`, `:tool-calling`, `:ux` — so a chart
   can demand \"good at Clojure with usable tool-calling\" via the
   declarative model policy (`:min {:clojure 8 :tool-calling 6}`) with no
   invocation code change. Add more keys (`:tier`, `:avoid?`, …) the same
   way; only ids that carry a key can satisfy a `:min`/`:max` over it (see
   `escapement.examples.clj-refactor` for a chart that filters on these).

   Layering mirrors `escapement.llm.preferences`: built-in
   `default-ratings`, with `:llm/ratings` (or nested `[:llm :ratings]`)
   from the merged `.escapement.edn` deep-merged on top so a project can
   override one key without restating the rest. Dated ids like
   `claude-opus-4-7-20260101` resolve to the family entry via
   longest-prefix, same as the catalog."
  (:require
   [clojure.string :as str]
   [escapement.config :as config]))

(def default-ratings
  "Canonical model id → opinion map. Tune via `:llm/ratings` in
   `.escapement.edn` rather than editing this."
  {"claude-opus-4-7"     {:intelligence 10 :clojure 10 :typescript  9 :tool-calling 9 :ux 8}
   "claude-opus-4-6"     {:intelligence 10 :clojure  9 :typescript  9 :tool-calling 9 :ux 8}
   "claude-opus-4"       {:intelligence  9 :clojure  8 :typescript  8 :tool-calling 8 :ux 7}
   "claude-sonnet-4-7"   {:intelligence  8 :clojure  8 :typescript  8 :tool-calling 8 :ux 6}
   "claude-sonnet-4-6"   {:intelligence  8 :clojure  8 :typescript  8 :tool-calling 8 :ux 6}
   "claude-sonnet-4-5"   {:intelligence  8 :clojure  7 :typescript  7 :tool-calling 7 :ux 6}
   "claude-haiku-4-5"    {:intelligence  6 :clojure  5 :typescript  5 :tool-calling 6 :ux 4}
   "claude-3-5-sonnet"   {:intelligence  7}
   "claude-3-5-haiku"    {:intelligence  5}
   "claude-3-opus"       {:intelligence  8}
   "glm-5.1"             {:intelligence  8 :clojure  6 :typescript  7 :tool-calling 6 :ux 5}
   "glm-5-turbo"         {:intelligence  7}
   "glm-5"               {:intelligence  8 :clojure  6 :typescript  7 :tool-calling 6 :ux 5}
   "glm-4.7-flashx"      {:intelligence  6}
   "glm-4.7-flash"       {:intelligence  5}
   "glm-4.7"             {:intelligence  7 :clojure  6 :typescript  6 :tool-calling 5 :ux 4}
   "glm-4.6"             {:intelligence  7}
   "glm-4.5-airx"        {:intelligence  6}
   "glm-4.5-air"         {:intelligence  5}
   "glm-4.5-flash"       {:intelligence  4}
   "glm-4.5-x"           {:intelligence  6}
   "glm-4.5"             {:intelligence  6}
   "glm-4-32b-0414-128k" {:intelligence  5}
   "gpt-5-mini"          {:intelligence  7 :clojure  5 :typescript  7 :tool-calling 6 :ux 7}
   "gpt-5-nano"          {:intelligence  5}
   "gpt-5"               {:intelligence 10 :clojure  6 :typescript  9 :tool-calling 7 :ux 8}
   "gpt-4.1-mini"        {:intelligence  6}
   "gpt-4.1"             {:intelligence  8 :clojure  5 :typescript  8 :tool-calling 7 :ux 7}
   "gpt-4o-mini"         {:intelligence  5}
   "gpt-4o"              {:intelligence  7 :clojure  5 :typescript  7 :tool-calling 6 :ux 7}
   "o3-mini"             {:intelligence  7}
   "o3"                  {:intelligence 10 :clojure  6 :typescript  8 :tool-calling 7 :ux 6}
   "o1"                  {:intelligence  9}})

(defn from-config
  "Extract the raw `:llm/ratings` table from a loaded config map. Accepts
   the flat key or the nested `[:llm :ratings]`. nil when neither set."
  [cfg]
  (or (:llm/ratings cfg)
      (get-in cfg [:llm :ratings])))

(defn ratings
  "Effective id → opinion map: `default-ratings` deep-merged with the
   config overlay (config wins per key, missing keys preserved).

   Zero-arg loads+merges `.escapement.edn`; one-arg takes an already
   loaded config map (no disk read — handy for tests)."
  ([] (ratings (config/load-config)))
  ([cfg]
   (config/deep-merge default-ratings (or (from-config cfg) {}))))

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
