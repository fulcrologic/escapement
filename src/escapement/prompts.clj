(ns escapement.prompts
  "File-based prompt loading with `{{VAR}}` template substitution.

   Prompts are authored as plain text (typically markdown) on disk. Tokens of the
   form `{{IDENT}}` — where `IDENT` matches `[A-Z][A-Z0-9_]*` — are replaced with
   values from a substitution map.

   Rules:

     * Tokens are case-sensitive and uppercase-only by convention.
     * The `subs` map may be keyed by keyword, symbol, or string; lookup tries
       keyword first, then string. Values are coerced via `str`.
     * Any token in the template that has no matching key in `subs` causes
       `render` to throw, listing every unresolved token. Prompts must never
       ship with literal `{{...}}` placeholders.

   This namespace is intentionally dependency-free so it loads cleanly on
   Babashka as well as the JVM."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private token-pattern
  ;; Matches {{IDENT}} where IDENT is uppercase letters, digits, and underscores,
  ;; starting with a letter. The captured group is the bare identifier.
  #"\{\{([A-Z][A-Z0-9_]*)\}\}")

(defn- lookup
  "Find a value in `subs` for the bare token `ident` (a string). Tries keyword,
   then symbol, then string keys. Returns `::missing` when no key matches."
  [subs ident]
  (let [k (keyword ident)]
    (cond
      (contains? subs k)             (get subs k)
      (contains? subs ident)         (get subs ident)
      (contains? subs (symbol ident)) (get subs (symbol ident))
      :else ::missing)))

(defn load-template
  "Returns the contents of the template at `path` as a string. `path` may be a
   filesystem path or anything `clojure.java.io/reader` accepts (e.g. a
   classpath URL from `io/resource`)."
  [path]
  (slurp path))

(defn render
  "Substitutes `{{VAR}}` tokens in `template` using `subs`. Throws
   `ex-info` if any token in the template has no corresponding entry in `subs`."
  [template subs]
  (let [matches  (re-seq token-pattern template)
        tokens   (distinct (mapv second matches))
        missing  (filterv #(= ::missing (lookup subs %)) tokens)]
    (when (seq missing)
      (throw (ex-info (str "Unresolved prompt tokens: " (str/join ", " (map #(str "{{" % "}}") missing)))
                      {:missing missing
                       :provided (vec (sort (map (fn [k] (cond-> k (keyword? k) name)) (keys subs))))})))
    (str/replace template
                 token-pattern
                 ;; `str/replace` already wraps the fn's return value with
                 ;; `Matcher/quoteReplacement`, so we return a plain string.
                 (fn [[_ ident]] (str (lookup subs ident))))))

(defn render-file
  "Loads the template at `path` and renders it with `subs`. See [[render]]."
  [path subs]
  (render (load-template path) subs))
