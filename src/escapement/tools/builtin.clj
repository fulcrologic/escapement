(ns escapement.tools.builtin
  "Built-in tools:
  `:fs/read`, `:fs/write`, `:fs/edit`, `:fs/multi-edit`, `:fs/glob`, `:fs/grep`,
  `:shell/run`, `:web/search`, `:web/fetch`.

  Each tool is a `defrecord` implementing `escapement.tools.protocol/Tool`. Inputs are
  validated by `dispatch` before `invoke` is called; the bodies here can therefore assume
  shape correctness and only need to defend against runtime conditions (missing files,
  ambiguous edits, non-zero exit codes, eval errors)."
  (:require
   [babashka.http-client :as http]
   [babashka.process :as bp]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
   [escapement.tools.protocol :as tp])
  (:import
   (java.io InputStream)
   (java.nio.charset StandardCharsets)
   (java.nio.file CopyOption FileSystems FileVisitOption Files LinkOption Path StandardCopyOption)
   (java.util UUID)
   (java.util.concurrent TimeUnit)))

(def ^:const max-read-bytes
  "Soft cap on bytes returned by `:fs/read` before truncation."
  (* 200 1024))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(>defn ^:private as-path
       "Return the `java.nio.file.Path` for `f`."
       [f]
       [any? => any?]
       (.toPath (io/file f)))

(defn resolve-file
  "Resolve `path` to a `java.io.File`. Absolute paths are returned as-is;
   relative paths are resolved against `tp/*base-dir*` (the session work-dir,
   bound by `tp/dispatch`) when set, else against the process working
   directory. This is the single seam through which every path-taking
   builtin tool resolves user-supplied paths."
  ^java.io.File [path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (if-let [base tp/*base-dir*]
        (io/file base path)
        f))))

(defn resolved-abs
  "Absolute path string for `path` after `resolve-file`. Surfaced to callers
   as `:resolved-path` so transcripts/tests can assert where a tool acted."
  [path]
  (.getAbsolutePath (resolve-file path)))

(>defn ^:private atomic-write!
       "Writes `content` (UTF-8) atomically to `path` (resolved via `resolve-file`):
  writes to `<resolved>.tmp`, then renames over the resolved file with
  `ATOMIC_MOVE` + `REPLACE_EXISTING`. Creates parent dirs as needed.
  Returns `{:bytes <n> :resolved <abs path string>}`. Throws if the
  resolved file does not exist after the move (so `:is-error false`
  callers can rely on the file being present)."
       [path content]
       [:string :string => [:map [:bytes :int] [:resolved :string]]]
       (let [f      (resolve-file path)
             parent (.getParentFile f)
             _      (when parent (.mkdirs parent))
             tmp    (io/file (str (.getAbsolutePath f) ".tmp"))
             bytes  (.getBytes ^String content StandardCharsets/UTF_8)]
         (with-open [out (io/output-stream tmp)]
           (.write out bytes))
         (Files/move ^Path (as-path tmp) ^Path (as-path f)
                     (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                             StandardCopyOption/REPLACE_EXISTING]))
         (when-not (.exists f)
           (throw (ex-info "atomic-write! post-check failed: file absent after move"
                           {:path path :resolved (.getAbsolutePath f)})))
         {:bytes (alength bytes) :resolved (.getAbsolutePath f)}))

(>defn ^:private read-utf8
       "Reads the file at `path` (resolved via `resolve-file`) as a UTF-8 string."
       [path]
       [:string => :string]
       (slurp (resolve-file path) :encoding "UTF-8"))

(defn- deref-or-self
  "babashka.process exposes :out / :err as either delays or strings depending on
   the spawn options. With `:out :string` / `:err :string` they are already strings,
   but be resilient either way."
  [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(>defn ^:private count-occurrences
       "Returns the number of non-overlapping occurrences of `needle` in `haystack`."
       [haystack needle]
       [:string :string => :int]
       (if (str/blank? needle)
         0
         (loop [n 0 from 0]
           (let [i (.indexOf ^String haystack ^String needle (int from))]
             (if (neg? i)
               n
               (recur (inc n) (+ i (count needle))))))))

;; ---------------------------------------------------------------------------
;; :fs/read
;; ---------------------------------------------------------------------------

(def ^:const default-read-lines
  "Default number of lines returned by `:fs/read` when `:limit` is absent."
  2000)

(def ^:private fs-read-schema
  [:map {:closed true}
   [:path :string]
   ;; 1-based start line. Default 1 (read from the top).
   [:offset {:optional true} pos-int?]
   ;; Maximum lines to return. Default `default-read-lines`.
   [:limit {:optional true} pos-int?]])

(defn- format-numbered-lines
  "Render `lines` (a seq of strings) in `cat -n` style starting at `start`,
   right-padded to 6-digit line numbers separated by a tab."
  [lines ^long start]
  (let [sb (StringBuilder.)]
    (loop [ls (seq lines) n start]
      (when ls
        (.append sb (format "%6d\t%s%n" n (first ls)))
        (recur (next ls) (inc n))))
    (.toString sb)))

(defrecord FsReadTool []
  tp/Tool
  (tool-name [_] :fs/read)
  (description [_]
    (str "Read a UTF-8 text file. Output is `cat -n` style: 1-based line "
         "numbers, tab, content. Optional `offset` (1-based start line) and "
         "`limit` (max lines). Default reads up to "
         default-read-lines " lines from the top. A trailing notice "
         "indicates if more lines remain past the window. Files larger than "
         max-read-bytes " bytes are byte-truncated up front."))
  (input-schema [_] fs-read-schema)
  (invoke [_ {:keys [path offset limit]}]
    (let [f   (resolve-file path)
          abs (.getAbsolutePath f)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true :resolved-path abs}

        (.isDirectory f)
        {:result (str "Path is a directory: " path) :is-error true :resolved-path abs}

        :else
        (let [size      (.length f)
              raw       (if (> size max-read-bytes)
                          (let [head (with-open [r (io/reader f :encoding "UTF-8")]
                                       (let [buf (char-array max-read-bytes)
                                             n   (.read r buf 0 max-read-bytes)]
                                         (String. buf 0 (max 0 n))))]
                            (str head
                                 "\n... [byte-truncated: file is " size " bytes; first "
                                 max-read-bytes " bytes only]"))
                          (read-utf8 path))
              all-lines (str/split-lines raw)
              total     (count all-lines)
              start     (max 1 (long (or offset 1)))
              start-idx (dec start)
              n         (long (or limit default-read-lines))
              slice     (when (< start-idx total)
                          (->> all-lines (drop start-idx) (take n)))
              shown     (count slice)
              end       (+ start (max 0 (dec shown)))
              remaining (max 0 (- total end))
              body      (format-numbered-lines slice start)
              footer    (cond
                          (zero? total) "[empty file]"
                          (zero? shown) (str "[offset " start " is past the last line ("
                                             total ")]")
                          (pos? remaining) (str "\n... [" remaining " more line"
                                                (when (> remaining 1) "s")
                                                "; read with :offset " (inc end) "]")
                          :else "")]
          {:result (str body footer) :is-error false :resolved-path abs})))))

;; ---------------------------------------------------------------------------
;; :fs/write
;; ---------------------------------------------------------------------------

(def ^:private fs-write-schema
  [:map {:closed true}
   [:path :string]
   [:content :string]])

(defrecord FsWriteTool []
  tp/Tool
  (tool-name [_] :fs/write)
  (description [_] "Atomically write UTF-8 `content` to `path`, creating parent directories.")
  (input-schema [_] fs-write-schema)
  (invoke [_ {:keys [path content]}]
    (let [{:keys [bytes resolved]} (atomic-write! path content)]
      {:result   (str "wrote " bytes " bytes to " resolved)
       :is-error false :resolved-path resolved})))

;; ---------------------------------------------------------------------------
;; :fs/edit
;; ---------------------------------------------------------------------------

(defn- apply-edit
  "Apply a single edit to `content`. Returns `{:ok true :content <new> :replacements <int>}`
   on success, or `{:ok false :error <string>}` on failure (no match, ambiguous,
   blank old-string)."
  [content {:keys [old-string new-string replace-all]}]
  (cond
    (str/blank? old-string)
    {:ok false :error "old-string must be non-empty"}

    (= old-string new-string)
    {:ok false :error "old-string and new-string are identical"}

    :else
    (let [hits (count-occurrences content old-string)]
      (cond
        (zero? hits)
        {:ok false :error (str "No match for old-string")}

        (and (> hits 1) (not replace-all))
        {:ok false :error (str "Ambiguous edit: old-string occurs " hits " times; "
                               "provide a more specific snippet or pass replace-all=true.")}

        :else
        (if replace-all
          {:ok true :content (str/replace content old-string new-string) :replacements hits}
          {:ok true :content (str/replace-first content old-string new-string) :replacements 1})))))

(def ^:private fs-edit-schema
  [:map {:closed true}
   [:path :string]
   [:old-string :string]
   [:new-string :string]
   ;; When true, replace every occurrence of `old-string`. When false/absent,
   ;; require a unique occurrence (current default behavior).
   [:replace-all {:optional true} :boolean]])

(defrecord FsEditTool []
  tp/Tool
  (tool-name [_] :fs/edit)
  (description [_]
    (str "String-replacement edit. By default `old-string` must occur exactly "
         "once in the file; pass `replace-all` true to replace every occurrence. "
         "Indentation in `old-string` must match the file exactly. Atomic write."))
  (input-schema [_] fs-edit-schema)
  (invoke [_ {:keys [path] :as input}]
    (let [f   (resolve-file path)
          abs (.getAbsolutePath f)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true :resolved-path abs}

        :else
        (let [content (read-utf8 path)
              {:keys [ok content error replacements]} (apply-edit content input)]
          (if ok
            (let [{:keys [bytes resolved]} (atomic-write! path content)]
              {:result   (str "edited " resolved " (" bytes " bytes, " replacements
                              " replacement" (when (> replacements 1) "s") ")")
               :is-error false :resolved-path resolved})
            {:result (str error " in " abs) :is-error true :resolved-path abs}))))))

;; ---------------------------------------------------------------------------
;; :fs/multi-edit
;; ---------------------------------------------------------------------------

(def ^:private fs-multi-edit-schema
  [:map {:closed true}
   [:path :string]
   [:edits [:vector {:min 1}
            [:map {:closed true}
             [:old-string :string]
             [:new-string :string]
             [:replace-all {:optional true} :boolean]]]]])

(defrecord FsMultiEditTool []
  tp/Tool
  (tool-name [_] :fs/multi-edit)
  (description [_]
    (str "Apply multiple string-replacement edits to one file atomically. "
         "Edits are applied in order to the in-memory content; the file is "
         "written only if ALL edits succeed. Each edit follows the same rules "
         "as fs_edit (unique-match by default; replace_all opts in)."))
  (input-schema [_] fs-multi-edit-schema)
  (invoke [_ {:keys [path edits]}]
    (let [f   (resolve-file path)
          abs (.getAbsolutePath f)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true :resolved-path abs}

        :else
        (let [start  (read-utf8 path)
              result (reduce
                      (fn [{:keys [content idx]} edit]
                        (let [r (apply-edit content edit)]
                          (if (:ok r)
                            {:content (:content r) :idx (inc idx)}
                            (reduced {:failed?      true
                                      :error        (:error r)
                                      :failed-index idx}))))
                      {:content start :idx 0}
                      edits)]
          (if (:failed? result)
            {:result   (str (:error result)
                            " (edit #" (inc (:failed-index result))
                            " of " (count edits) " in " abs
                            "; no changes written).")
             :is-error true :resolved-path abs}
            (let [{:keys [bytes resolved]} (atomic-write! path (:content result))]
              {:result   (str "applied " (count edits) " edit"
                              (when (> (count edits) 1) "s")
                              " to " resolved " (" bytes " bytes)")
               :is-error false :resolved-path resolved})))))))

;; ---------------------------------------------------------------------------
;; :fs/glob
;; ---------------------------------------------------------------------------

(def ^:private fs-glob-schema
  [:map {:closed true}
   [:pattern :string]                                       ;; e.g. "**/*.cljc" or "src/**/foo.clj"
   [:cwd {:optional true} :string]                          ;; default "."
   [:limit {:optional true} pos-int?]                       ;; default 200
   ;; Sort results most-recently-modified first when true (default true).
   ;; NOTE: no `?` suffix — Anthropic tool input_schema property keys must match
   ;; ^[a-zA-Z0-9_.-]{1,64}$, which forbids `?`.
   [:by-mtime {:optional true} :boolean]])

(defn- glob-match-fn
  "Build a path predicate from `pattern`. Java's `**/*.foo` only matches files
   at depth >= 1; for ergonomic parity with shell/bash globs (and tools like
   ripgrep, fd, minimatch), we also accept matches under the shorter form
   without the leading `**/` so root-level files are included."
  [pattern]
  (let [fs      (FileSystems/getDefault)
        primary (.getPathMatcher fs (str "glob:" pattern))
        also    (when (str/starts-with? pattern "**/")
                  (.getPathMatcher fs (str "glob:" (subs pattern 3))))]
    (fn [^Path p]
      (or (.matches primary p)
          (and also (.matches also p))))))

(defrecord FsGlobTool []
  tp/Tool
  (tool-name [_] :fs/glob)
  (description [_]
    (str "Find files matching a glob pattern (e.g. `**/*.cljc`, `src/**/foo.clj`). "
         "Walks `cwd` (default `.`) recursively and returns matching paths. "
         "Sorted most-recently-modified first by default; pass by-mtime false "
         "for lexicographic order. Capped at `limit` (default 200)."))
  (input-schema [_] fs-glob-schema)
  (invoke [_ {:keys [pattern cwd limit by-mtime]
              :or   {by-mtime true}}]
    (let [root      (resolve-file (or cwd "."))
          _         (when-not (.exists root)
                      (throw (ex-info "glob root does not exist"
                                      {:cwd cwd})))
          root-path (.toPath root)
          pat-fn    (glob-match-fn pattern)
          cap       (long (or limit 200))]
      (try
        (let [hits    (with-open [stream (Files/walk root-path
                                                     (into-array FileVisitOption []))]
                        (->> stream
                             .iterator
                             iterator-seq
                             (filter (fn [^Path p]
                                       (and (Files/isRegularFile p (into-array LinkOption []))
                                            (pat-fn (.relativize root-path p)))))
                             (mapv (fn [^Path p]
                                     {:path  (str (.toAbsolutePath p))
                                      :mtime (.toMillis (Files/getLastModifiedTime
                                                         p (into-array LinkOption [])))}))))
              ordered (if by-mtime
                        (sort-by (comp - :mtime) hits)
                        (sort-by :path hits))
              kept    (vec (take cap ordered))
              over?   (> (count hits) cap)]
          {:result   (cond
                       (empty? hits) "[no matches]"
                       :else
                       (str (str/join "\n" (mapv :path kept))
                            (when over?
                              (str "\n... [" (- (count hits) cap)
                                   " more matches; raise :limit to see them]"))))
           :is-error false})
        (catch Throwable t
          {:result (str "glob failed: " (.getMessage t)) :is-error true})))))

;; ---------------------------------------------------------------------------
;; :fs/grep
;; ---------------------------------------------------------------------------

(def ^:private fs-grep-schema
  [:map {:closed true}
   [:pattern :string]                                       ;; regex
   [:path {:optional true} :string]                         ;; default "."
   [:glob {:optional true} :string]                         ;; file-name glob filter
   [:output-mode {:optional true}
    [:enum "content" "files-with-matches" "count"]]
   [:ignore-case {:optional true} :boolean]
   [:context {:optional true} [:int {:min 0 :max 10}]]
   [:limit {:optional true} pos-int?]])

(defn- rg-available? []
  (try
    (let [p (bp/process ["bash" "-lc" "command -v rg"]
                        {:out :string :err :string})]
      (.waitFor ^Process (:proc p) 2 TimeUnit/SECONDS)
      (zero? (.exitValue ^Process (:proc p))))
    (catch Throwable _ false)))

(defn- run-grep [{:keys [pattern path glob output-mode ignore-case context limit]}]
  (let [path  (.getAbsolutePath (resolve-file (or path ".")))
        mode  (or output-mode "files-with-matches")
        rg?   (rg-available?)
        cmd
        (if rg?
          (cond-> ["rg" "--no-config" "--color=never"]
            ignore-case (conj "-i")
            (= mode "files-with-matches") (conj "-l")
            (= mode "count") (conj "-c")
            (= mode "content") (conj "-n")
            (and (= mode "content") context) (into ["-C" (str context)])
            glob (into ["-g" glob])
            true (into ["-e" pattern path]))
                ;; Fallback: POSIX `grep -E -r`. Glob filtering via --include only on GNU grep;
                ;; we approximate using --include for our usage; suppress directory recursion errors.
          (cond-> ["grep" "-rE"]
            ignore-case (conj "-i")
            (= mode "files-with-matches") (conj "-l")
            (= mode "count") (conj "-c")
            (= mode "content") (conj "-n")
            (and (= mode "content") context) (into [(str "-C" context)])
            glob (into [(str "--include=" glob)])
            true (into [pattern path])))
        proc  (bp/process cmd {:out :string :err :string :shutdown bp/destroy-tree})
        done? (.waitFor ^Process (:proc proc) 20 TimeUnit/SECONDS)]
    (if-not done?
      (do (try (.destroyForcibly ^Process (:proc proc)) (catch Throwable _ nil))
          {:result "grep timed out after 20s" :is-error true})
      (let [exit      (.exitValue ^Process (:proc proc))
            out       (or (deref-or-self (:out proc)) "")
            err       (or (deref-or-self (:err proc)) "")
            cap       (long (or limit 200))
            ;; exit 1 = no matches; exit 0 = matches; exit 2 = error
            no-match? (= exit 1)
            err?      (= exit 2)
            lines     (when (= mode "content") (str/split-lines out))
            kept      (when (= mode "content") (take cap lines))
            over?     (when (= mode "content") (> (count lines) cap))]
        (cond
          err?
          {:result (str "grep error (exit 2): " (str/trim err)) :is-error true}

          no-match?
          {:result "[no matches]" :is-error false}

          (= mode "content")
          {:result   (str (str/join "\n" kept)
                          (when over?
                            (str "\n... [" (- (count lines) cap)
                                 " more lines; raise :limit]")))
           :is-error false}

          :else                                             ;; files-with-matches | count
          {:result (str/trim out) :is-error false})))))

(defrecord FsGrepTool []
  tp/Tool
  (tool-name [_] :fs/grep)
  (description [_]
    (str "Regex search across files. Uses `rg` (ripgrep) if available, falls "
         "back to `grep -rE`. `output-mode`: `files-with-matches` (default), "
         "`content` (with -n line numbers and optional `context`), or `count`. "
         "`glob` filters file names (e.g. `*.clj`). 20s timeout. "
         "Capped at `limit` (default 200) result lines in content mode."))
  (input-schema [_] fs-grep-schema)
  (invoke [_ input]
    (try (run-grep input)
         (catch Throwable t
           {:result (str "grep failed: " (.getMessage t)) :is-error true}))))

;; ---------------------------------------------------------------------------
;; :shell/run
;; ---------------------------------------------------------------------------

(def ^:private shell-run-schema
  [:map {:closed true}
   [:command :string]
   [:timeout-ms {:optional true} :int]])

(def ^:const default-shell-timeout-ms 30000)

(defrecord ShellRunTool []
  tp/Tool
  (tool-name [_] :shell/run)
  (description [_] "Run a shell command via `bash -lc`. Returns combined stdout+stderr and exit code.")
  (input-schema [_] shell-run-schema)
  (invoke [_ {:keys [command timeout-ms]}]
    (let [timeout  (or timeout-ms default-shell-timeout-ms)
          ;; Run in the tool base-dir (bound by `tp/dispatch` from the registry's
          ;; `:escapement/base-dir` meta) so `:shell/run` agrees with the `:fs/*`
          ;; tools on the working directory. Falls back to the process cwd when no
          ;; base-dir is set or it does not (yet) exist.
          base-dir (when-let [b tp/*base-dir*]
                     (let [f (io/file b)] (when (.isDirectory f) f)))
          ;; Redirect child stdio to FILES, never `:out :string`. A `:string`
          ;; capture spawns a reader thread that reads the child's stdout pipe
          ;; until EOF — and a backgrounded daemon (a REPL, a dev server) that
          ;; inherits that pipe holds the write-end open FOREVER. The `bash -lc`
          ;; parent exits promptly (so `.waitFor` returns "done"), but the deref
          ;; of the `:string` output would then block on the orphaned grandchild's
          ;; pipe with no way to kill it — hanging the tool indefinitely past the
          ;; timeout. File redirection has no reader thread and cannot block: we
          ;; `.waitFor` the parent within the timeout, then read whatever bytes
          ;; were written; a lingering daemon writing to the file is harmless.
          out-file (java.io.File/createTempFile "esc-shell-out" ".log")
          err-file (java.io.File/createTempFile "esc-shell-err" ".log")]
      (try
        (let [proc  (bp/process ["bash" "-lc" command]
                                (cond-> {:in       nil
                                         :out      :write :out-file out-file
                                         :err      :write :err-file err-file
                                         :shutdown bp/destroy-tree}
                                  base-dir (assoc :dir base-dir)))
              done? (.waitFor ^Process (:proc proc) timeout TimeUnit/MILLISECONDS)]
          (if-not done?
            (do
              (try (.destroyForcibly ^Process (:proc proc)) (catch Throwable _ nil))
              (let [out (slurp out-file)
                    err (slurp err-file)]
                {:result   (str out
                             (when-not (str/blank? err) (str "\n[stderr]\n" err))
                             "\n[timed out after " timeout "ms; process killed]"
                             "\nNote: :shell/run is for commands that COMPLETE. Do not start"
                             " long-running daemons (REPLs, dev servers) with it. If you must,"
                             " redirect ALL stdio to a file and `disown` (e.g."
                             " `cmd </dev/null >/tmp/x.log 2>&1 & disown`), or use the managed stack.")
                 :is-error true}))
            (let [exit (.exitValue ^Process (:proc proc))
                  out  (slurp out-file)
                  err  (slurp err-file)
                  body (str out (when-not (str/blank? err) (str "\n[stderr]\n" err)))]
              {:result   (str body "\n[exit " exit "]")
               :is-error (not (zero? exit))})))
        (finally
          (try (.delete out-file) (catch Throwable _ nil))
          (try (.delete err-file) (catch Throwable _ nil)))))))

;; ---------------------------------------------------------------------------
;; :web/search  (Gemini google_search grounding)
;; ---------------------------------------------------------------------------

(def ^:private web-search-schema
  [:map {:closed true}
   [:query :string]
   [:max-results {:optional true} [:int {:min 1 :max 10}]]])

(def ^:const default-web-search-max-results 5)
(def ^:const default-gemini-search-model "gemini-2.5-flash")

(defn- gemini-search-url [model api-key]
  (str "https://generativelanguage.googleapis.com/v1beta/models/"
       model ":generateContent?key=" api-key))

(defn- parse-gemini-search-response
  "Parse a Gemini generateContent response with google_search grounding into
   a vector of `{:title :url :snippet}` maps. Pairs each grounding-support
   (which carries the snippet) with the first grounding-chunk it references."
  [resp]
  (let [candidate   (-> resp (get "candidates") first)
        gm          (get candidate "groundingMetadata")
        chunks      (vec (get gm "groundingChunks"))
        supports    (vec (get gm "groundingSupports"))
        chunk->info (fn [i]
                      (when-let [c (get chunks i)]
                        (let [w (get c "web")]
                          {:title (get w "title")
                           :url   (get w "uri")})))
        ;; Build snippet per chunk by joining all supports whose
        ;; groundingChunkIndices include that chunk.
        chunk->snip (reduce
                     (fn [m support]
                       (let [snip (get-in support ["segment" "text"])
                             idxs (get support "groundingChunkIndices")]
                         (reduce (fn [m i]
                                   (update m i (fn [existing]
                                                 (cond
                                                   (nil? existing) snip
                                                   (str/includes? existing snip) existing
                                                   :else (str existing " " snip)))))
                                 m idxs)))
                     {}
                     supports)]
    (vec
     (keep-indexed
      (fn [i _]
        (when-let [info (chunk->info i)]
          (assoc info :snippet (or (get chunk->snip i) ""))))
      chunks))))

(defn- http-post-json
  "Indirection so tests can `with-redefs` a fake response."
  [url body-string timeout-ms]
  (http/post url
             {:headers {"Content-Type" "application/json"}
              :body    body-string
              :timeout timeout-ms
              :throw   false}))

(defn- env
  "Env-var lookup wrapped so tests can stub. Returns nil for blank/missing."
  [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v) v)))

(defrecord WebSearchTool []
  tp/Tool
  (tool-name [_] :web/search)
  (description [_]
    (str "Web search via Google Search grounding (Gemini API). Returns up to "
         "`max-results` (default " default-web-search-max-results ", max 10) "
         "results as EDN: a vector of `{:title :url :snippet}`. Requires "
         "GEMINI_API_KEY in the environment. The model can be overridden via "
         "GEMINI_SEARCH_MODEL (default `" default-gemini-search-model "`)."))
  (input-schema [_] web-search-schema)
  (invoke [_ {:keys [query max-results]}]
    (let [api-key (env "GEMINI_API_KEY")
          model   (or (env "GEMINI_SEARCH_MODEL") default-gemini-search-model)
          cap     (long (or max-results default-web-search-max-results))]
      (cond
        (str/blank? api-key)
        {:result "GEMINI_API_KEY not set" :is-error true}

        :else
        (try
          (let [req-body {"contents" [{"role"  "user"
                                       "parts" [{"text" query}]}]
                          "tools"    [{"google_search" {}}]}
                {:keys [status body]} (http-post-json
                                       (gemini-search-url model api-key)
                                       (json/generate-string req-body)
                                       30000)]
            (if-not (and (>= status 200) (< status 300))
              {:result   (str "Gemini search HTTP " status ": "
                              (if (string? body) body (pr-str body)))
               :is-error true}
              (let [parsed (json/parse-string body)
                    hits   (parse-gemini-search-response parsed)
                    kept   (vec (take cap hits))]
                {:result   (with-out-str (pprint/pprint kept))
                 :is-error false})))
          (catch Throwable t
            {:result (str "web/search failed: " (.getMessage t)) :is-error true}))))))

;; ---------------------------------------------------------------------------
;; :web/fetch
;; ---------------------------------------------------------------------------

(def ^:private web-fetch-schema
  [:map {:closed true}
   [:url :string]
   [:max-bytes {:optional true} [:int {:min 1024 :max 50000000}]]
   [:timeout-ms {:optional true} [:int {:min 100 :max 120000}]]])

(def ^:const default-fetch-max-bytes 5000000)
(def ^:const default-fetch-timeout-ms 30000)

(defn- guess-extension
  "Best-effort file extension from Content-Type (without the leading dot,
   so returned value already starts with a `.` or is the empty string)."
  [content-type]
  (let [ct (some-> content-type (str/split #";") first str/trim str/lower-case)]
    (case ct
      "text/html" ".html"
      "application/xhtml+xml" ".html"
      "text/plain" ".txt"
      "text/markdown" ".md"
      "application/json" ".json"
      "application/xml" ".xml"
      "text/xml" ".xml"
      "application/pdf" ".pdf"
      "text/css" ".css"
      "text/csv" ".csv"
      "application/javascript" ".js"
      "text/javascript" ".js"
      "")))

(defn- fetch-temp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir") "escapement-fetch")]
    (.mkdirs d)
    d))

(defn- copy-stream-capped!
  "Copy `in` to `out` up to `max-bytes`. Returns `{:bytes <n> :truncated <bool>}`.
   When the cap is reached, the remaining input is discarded."
  [^InputStream in out ^long max-bytes]
  (let [buf (byte-array 8192)]
    (loop [total 0]
      (let [remaining (- max-bytes total)]
        (if (<= remaining 0)
          ;; Drain to see if more was available — flags truncation.
          (let [extra (.read in buf 0 (alength buf))]
            {:bytes total :truncated (pos? extra)})
          (let [to-read (min (alength buf) remaining)
                n       (.read in buf 0 to-read)]
            (if (neg? n)
              {:bytes total :truncated false}
              (do (.write out buf 0 n)
                  (recur (+ total n))))))))))

(defn- title-from-html [^String s]
  (when s
    (when-let [m (re-find #"(?is)<title[^>]*>(.*?)</title>" s)]
      (some-> (second m) str/trim
              (str/replace #"\s+" " ")
              (#(when-not (str/blank? %) %))))))

(defn- http-get-stream
  "Indirection so tests can stub. Returns the babashka.http-client response
   with `:body` as an InputStream."
  [url timeout-ms]
  (http/get url
            {:timeout          timeout-ms
             :as               :stream
             :throw            false
             :follow-redirects :always}))

(defrecord WebFetchTool []
  tp/Tool
  (tool-name [_] :web/fetch)
  (description [_]
    (str "HTTP GET a URL, streaming the body to a temp file under "
         "${java.io.tmpdir}/escapement-fetch/. Returns EDN metadata "
         "(`:url :final-url :status :content-type :bytes :saved-to :title "
         ":preview :truncated`). Truncates at `max-bytes` (default "
         default-fetch-max-bytes ") without erroring; non-2xx and "
         "network/timeout failures are errors. Use this with `:fs/grep` "
         "and `:fs/read` (or `:shell/run` for pandoc/html2text) to read "
         "slices off disk rather than dumping the whole body into context."))
  (input-schema [_] web-fetch-schema)
  (invoke [_ {:keys [url max-bytes timeout-ms]}]
    (let [cap (long (or max-bytes default-fetch-max-bytes))
          to  (long (or timeout-ms default-fetch-timeout-ms))]
      (try
        (let [resp      (http-get-stream url to)
              status    (:status resp)
              headers   (:headers resp)
              body      (:body resp)
              hget      (fn [k]
                          (or (get headers k)
                              (get headers (str/lower-case k))))
              ct        (or (hget "content-type") (hget "Content-Type"))
              final-url (or (some-> resp :uri str)
                            (hget "x-final-url")
                            url)]
          (cond
            (nil? status)
            {:result (str "web/fetch: no response status for " url) :is-error true}

            (not (and (>= status 200) (< status 300)))
            (do
              (when (instance? InputStream body) (try (.close ^InputStream body) (catch Throwable _ nil)))
              {:result (str "web/fetch HTTP " status " for " url) :is-error true})

            :else
            (let [tdir      (fetch-temp-dir)
                  ext       (guess-extension ct)
                  fname     (str (UUID/randomUUID) ext)
                  out-f     (io/file tdir fname)
                  {:keys [bytes truncated]}
                  (with-open [in  (if (instance? InputStream body)
                                    ^InputStream body
                                    (io/input-stream body))
                              out (io/output-stream out-f)]
                    (copy-stream-capped! in out cap))
                  ;; preview: up to 500 chars of the saved file, decoded as UTF-8
                  preview   (try
                              (with-open [r (io/reader out-f :encoding "UTF-8")]
                                (let [buf (char-array 500)
                                      n   (.read r buf 0 500)]
                                  (when (pos? n) (String. buf 0 n))))
                              (catch Throwable _ nil))
                  html?     (and ct (str/starts-with? (str/lower-case ct) "text/html"))
                  ;; For title detection, scan a slightly larger window if needed
                  title-src (when html?
                              (try
                                (with-open [r (io/reader out-f :encoding "UTF-8")]
                                  (let [buf (char-array 8192)
                                        n   (.read r buf 0 8192)]
                                    (when (pos? n) (String. buf 0 n))))
                                (catch Throwable _ nil)))
                  title     (when html? (title-from-html title-src))
                  out-map   {:url          url
                             :final-url    final-url
                             :status       status
                             :content-type ct
                             :bytes        bytes
                             :saved-to     (.getCanonicalPath out-f)
                             :title        title
                             :preview      preview
                             :truncated    truncated}]
              {:result   (with-out-str (pprint/pprint out-map))
               :is-error false})))
        (catch Throwable t
          {:result (str "web/fetch failed: " (.getMessage t)) :is-error true})))))

;; ---------------------------------------------------------------------------
;; Public assembly
;; ---------------------------------------------------------------------------

(>defn builtin-tools
       "Return a vector of the built-in tool instances.

  `:web/search` is conditionally included only when `GEMINI_API_KEY` is set
  in the environment, so the LLM doesn't see (and waste a turn calling) a
  search tool that can't reach a backend. `:web/fetch` is always included —
  it has no credential requirement."
       []
       [=> [:sequential any?]]
       (cond-> [(->FsReadTool)
                (->FsWriteTool)
                (->FsEditTool)
                (->FsMultiEditTool)
                (->FsGlobTool)
                (->FsGrepTool)
                (->ShellRunTool)
                (->WebFetchTool)]
         (env "GEMINI_API_KEY") (conj (->WebSearchTool))))

(>defn new-builtin-registry
       "Return a FRESH registry populated with the built-in tools (eight or nine,
   depending on whether `GEMINI_API_KEY` is set — see `builtin-tools`).
   Use this when you want isolation — most commonly in tests, or when a
   host process drives multiple chart runs that need disjoint tool sets."
       []
       [=> any?]
       (tp/new-registry (builtin-tools)))

(defonce ^{:doc "The default tool registry used by `escapement.cli` and any
  host that calls `(default-registry)`. Process-global: top-level
  `(tp/register! escapement.tools.builtin/default-registry ...)` calls in
  any namespace that is loaded before `runner/run!` will be visible to the
  chart. Seeded with the built-ins on namespace load (`:web/search` is
  included only when `GEMINI_API_KEY` is set in the environment at load time).

  This is the Clojure-idiomatic registration pattern: require your tool
  namespace from the chart and it self-registers — no CLI flag needed.

  If you need isolation, prefer `new-builtin-registry` (returns a fresh
  registry) or build your own via `escapement.tools.protocol/new-registry`
  and thread it through `runner/run!` as `:tool-registry`."}
  default-registry
  (tp/new-registry (builtin-tools)))
