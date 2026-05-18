(ns escapement.config
  "Loads `.escapement.edn` configuration. Two facilities live here:

   * `load-config` — user-global (`~/.escapement.edn`) merged under the
     cwd's `.escapement.edn`. Used by debug/viewers.
   * `find-project-config` / `load-project-config` — git-style walk-up
     discovery starting at cwd, returning the parsed+validated config and
     the resolved config root. Used by `escapement run` to drive
     `:source-paths`, `:deps`, `:tools-ns`, `:work-dir`, `:default-chart`.

   Example file:
   ```
   {:debug   {:auto-pause? true}
    :viewers {\"md\"      \"open -a 'Visual Studio Code' {{path}}\"
              \"svg\"     \"open -a 'Google Chrome' {{path}}\"
              \"default\" :internal}
    :d2      {:layout \"elk\" :command \"d2\"}}
   ```

   Key shapes:

   * `:debug :auto-pause?` — boolean. When `--debug` is on, halt before
     processing the very first event so the user can step from the start.
   * `:viewers` — map of lowercase file extension (string) to either a
     command template (string) containing `{{path}}` or the keyword
     `:internal` to use the TUI's built-in scrollable pager. The key
     `\"default\"` is the fallback when no extension matches.
   * `:d2` — d2/ELK chart visualizer settings.

   Babashka-compatible: only `clojure.edn` and `java.io.File`."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]))

(def ^:const filename ".escapement.edn")

(defn- read-edn-or-empty
  "Reads `f` as EDN. Returns `{}` if the file is missing, empty, or unreadable."
  [^java.io.File f]
  (if (and f (.exists f) (.isFile f))
    (try
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (or (edn/read {:eof nil} r) {}))
      (catch Throwable _ {}))
    {}))

(defn deep-merge
  "Recursively merges maps. Non-map values from later args replace earlier ones.
   Used to layer project config over user-global config."
  [& maps]
  (let [maps (remove nil? maps)]
    (cond
      (empty? maps) nil
      (= 1 (count maps)) (first maps)
      (every? map? maps) (apply merge-with deep-merge maps)
      :else (last maps))))

(defn user-config-file
  "Returns the `~/.escapement.edn` file (may not exist)."
  []
  (io/file (System/getProperty "user.home") filename))

(defn project-config-file
  "Returns the `./.escapement.edn` file in the current working directory
   (may not exist)."
  []
  (io/file (System/getProperty "user.dir") filename))

(defn load-config
  "Returns the merged configuration map. User-global is read first, then
   project-local is deep-merged on top so project entries win."
  []
  (deep-merge (read-edn-or-empty (user-config-file))
              (read-edn-or-empty (project-config-file))))

(defn- shell-quote
  "Single-quotes `s` for safe inclusion in a `sh -c` command line. Embedded
   single quotes are escaped as `'\\''`."
  [s]
  (str \' (str/replace (str s) "'" "'\\''") \'))

(defn expand-command
  "Substitutes `{{path}}` in command template `tmpl` with a shell-quoted
   `path`. Returns the resulting command string suitable for `sh -c`.

   If `tmpl` does not contain `{{path}}`, the path is appended (separated by
   a space) so naive templates like `\"open\"` still work."
  [tmpl path]
  (let [quoted (shell-quote path)
        s      (str tmpl)]
    (if (str/includes? s "{{path}}")
      (str/replace s "{{path}}" quoted)
      (str s " " quoted))))

(defn viewer-for
  "Resolves the viewer entry for `path` from `(:viewers cfg)`. Looks up by
   lowercase file extension; falls back to the `\"default\"` entry; falls
   back to `:internal` if nothing matches.

   Returns either `:internal` or a command-template string."
  [cfg path]
  (let [viewers (:viewers cfg)
        ext     (let [n (str path)
                      i (.lastIndexOf n ".")]
                  (when (pos? i) (str/lower-case (subs n (inc i)))))]
    (or (when ext (get viewers ext))
        (get viewers "default")
        :internal)))

(defn viewer-for-url
  "Resolves the viewer entry for a URL (no file extension to key on). Looks
   up `(get-in cfg [:viewers \"url\"])`. Falls back to `:internal` — i.e.
   *don't* auto-open — when no entry is configured, so opt-in is explicit.

   Returns either `:internal` or a command-template string."
  [cfg]
  (or (get-in cfg [:viewers "url"]) :internal))

(def project-schema
  [:map {:closed true}
   [:source-paths  {:optional true} [:vector :string]]
   [:deps          {:optional true} [:map-of :symbol :any]]
   [:tools-ns      {:optional true} [:or :symbol [:vector :symbol]]]
   [:work-dir      {:optional true} :string]
   [:default-chart {:optional true} :symbol]
   ;; Why: existing user-level keys are tolerated in project files too so a
   ;; single .escapement.edn can hold both kinds of settings.
   [:debug         {:optional true} :any]
   [:viewers       {:optional true} :any]
   [:d2            {:optional true} :any]
   ;; LLM model selection overlays. Validated/sanitized downstream by
   ;; `escapement.llm.preferences` / `escapement.llm.ratings` against the
   ;; catalog, so kept `:any` here (same as the user-level keys above).
   ;; Both the flat `:llm/preferences`/`:llm/ratings` keys and the nested
   ;; `:llm` map (`[:llm :preferences]` / `[:llm :ratings]`) are accepted,
   ;; matching those namespaces' `from-config`.
   [:llm/preferences {:optional true} :any]
   [:llm/ratings     {:optional true} :any]
   [:llm             {:optional true} :any]])

(defn find-project-config
  "Walks up from `start-dir` (a File or path string) looking for
   `.escapement.edn`. Returns the File, or nil if none found before the
   filesystem root."
  ([] (find-project-config (System/getProperty "user.dir")))
  ([start-dir]
   (loop [^java.io.File d (io/file start-dir)]
     (when d
       (let [f (io/file d filename)]
         (if (and (.exists f) (.isFile f))
           f
           (recur (.getParentFile d))))))))

(defn- read-edn-strict
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (edn/read {:eof nil} r)))

(defn validate-project-config!
  "Validates `cfg` against `project-schema`. Throws `ex-info` with a
   humanized error map on failure. Returns `cfg` on success."
  [cfg path]
  (if (m/validate project-schema cfg)
    cfg
    (throw (ex-info (str "Invalid .escapement.edn at " path)
                    {:path   (str path)
                     :errors (me/humanize (m/explain project-schema cfg))}))))

(defn- normalize-tools-ns [v]
  (cond
    (nil? v)     []
    (symbol? v)  [v]
    (vector? v)  v
    :else        (throw (ex-info ":tools-ns must be a symbol or vector of symbols"
                                 {:value v}))))

(defn load-project-config
  "Discover and load the project `.escapement.edn` by walking up from
   `start-dir` (default cwd). Returns `nil` if no file is found.

   On success returns `{:config <validated-map> :root <java.io.File> :path <java.io.File>}`.
   `:tools-ns` in the returned map is normalized to a vector."
  ([] (load-project-config (System/getProperty "user.dir")))
  ([start-dir]
   (when-let [^java.io.File f (find-project-config start-dir)]
     (let [raw (or (try (read-edn-strict f)
                        (catch Throwable t
                          (throw (ex-info (str "Failed to parse " (.getPath f) ": " (.getMessage t))
                                          {:path (.getPath f)} t))))
                   {})
           _   (validate-project-config! raw (.getPath f))
           cfg (cond-> raw
                 (contains? raw :tools-ns) (update :tools-ns normalize-tools-ns))]
       {:config cfg
        :root   (.getParentFile f)
        :path   f}))))

(defn resolve-path
  "Resolves a possibly-relative path string against `root` (a File or
   path string). Absolute paths are returned unchanged."
  [root path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file root path))))
