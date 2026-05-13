(ns escapement.tools.builtin
  "Built-in tools usable from both bb and JVM:
  `:fs/read`, `:fs/write`, `:fs/edit`, `:fs/multi-edit`, `:fs/glob`, `:fs/grep`,
  `:shell/run`, `:repl/eval`.

  Each tool is a `defrecord` implementing `escapement.tools.protocol/Tool`. Inputs are
  validated by `dispatch` before `invoke` is called; the bodies here can therefore assume
  shape correctness and only need to defend against runtime conditions (missing files,
  ambiguous edits, non-zero exit codes, eval errors)."
  (:require
   [babashka.process :as bp]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [escapement.tools.protocol :as tp])
  (:import
   (java.nio.charset StandardCharsets)
   (java.nio.file CopyOption Files FileSystems FileVisitOption LinkOption Path StandardCopyOption)
   (java.util.concurrent TimeoutException TimeUnit)))

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

(>defn ^:private atomic-write!
       "Writes `content` (UTF-8) atomically to `path`: writes to `<path>.tmp`, then renames
       over `path` with `ATOMIC_MOVE` + `REPLACE_EXISTING`. Creates parent dirs as needed.
       Returns the number of bytes written."
       [path content]
       [:string :string => :int]
       (let [f      (io/file path)
             parent (.getParentFile f)
             _      (when parent (.mkdirs parent))
             tmp    (io/file (str path ".tmp"))
             bytes  (.getBytes ^String content StandardCharsets/UTF_8)]
         (with-open [out (io/output-stream tmp)]
           (.write out bytes))
         (Files/move ^Path (as-path tmp) ^Path (as-path f)
                     (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                             StandardCopyOption/REPLACE_EXISTING]))
         (alength bytes)))

(>defn ^:private read-utf8
       "Reads the file at `path` as a UTF-8 string."
       [path]
       [:string => :string]
       (slurp (io/file path) :encoding "UTF-8"))

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
   [:limit  {:optional true} pos-int?]])

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
  (tool-name    [_] :fs/read)
  (description  [_]
    (str "Read a UTF-8 text file. Output is `cat -n` style: 1-based line "
         "numbers, tab, content. Optional `offset` (1-based start line) and "
         "`limit` (max lines). Default reads up to "
         default-read-lines " lines from the top. A trailing notice "
         "indicates if more lines remain past the window. Files larger than "
         max-read-bytes " bytes are byte-truncated up front."))
  (input-schema [_] fs-read-schema)
  (invoke [_ {:keys [path offset limit]}]
    (let [f (io/file path)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true}

        (.isDirectory f)
        {:result (str "Path is a directory: " path) :is-error true}

        :else
        (let [size       (.length f)
              raw        (if (> size max-read-bytes)
                           (let [head (with-open [r (io/reader f :encoding "UTF-8")]
                                        (let [buf (char-array max-read-bytes)
                                              n   (.read r buf 0 max-read-bytes)]
                                          (String. buf 0 (max 0 n))))]
                             (str head
                                  "\n... [byte-truncated: file is " size " bytes; first "
                                  max-read-bytes " bytes only]"))
                           (read-utf8 path))
              all-lines  (str/split-lines raw)
              total      (count all-lines)
              start      (max 1 (long (or offset 1)))
              start-idx  (dec start)
              n          (long (or limit default-read-lines))
              slice      (when (< start-idx total)
                           (->> all-lines (drop start-idx) (take n)))
              shown      (count slice)
              end        (+ start (max 0 (dec shown)))
              remaining  (max 0 (- total end))
              body       (format-numbered-lines slice start)
              footer     (cond
                           (zero? total) "[empty file]"
                           (zero? shown) (str "[offset " start " is past the last line ("
                                              total ")]")
                           (pos? remaining) (str "\n... [" remaining " more line"
                                                 (when (> remaining 1) "s")
                                                 "; read with :offset " (inc end) "]")
                           :else "")]
          {:result (str body footer) :is-error false})))))

;; ---------------------------------------------------------------------------
;; :fs/write
;; ---------------------------------------------------------------------------

(def ^:private fs-write-schema
  [:map {:closed true}
   [:path :string]
   [:content :string]])

(defrecord FsWriteTool []
  tp/Tool
  (tool-name    [_] :fs/write)
  (description  [_] "Atomically write UTF-8 `content` to `path`, creating parent directories.")
  (input-schema [_] fs-write-schema)
  (invoke [_ {:keys [path content]}]
    (let [n (atomic-write! path content)]
      {:result (str "wrote " n " bytes to " path) :is-error false})))

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
  (tool-name    [_] :fs/edit)
  (description  [_]
    (str "String-replacement edit. By default `old-string` must occur exactly "
         "once in the file; pass `replace-all` true to replace every occurrence. "
         "Indentation in `old-string` must match the file exactly. Atomic write."))
  (input-schema [_] fs-edit-schema)
  (invoke [_ {:keys [path] :as input}]
    (let [f (io/file path)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true}

        :else
        (let [content (read-utf8 path)
              {:keys [ok content error replacements]} (apply-edit content input)]
          (if ok
            (let [n (atomic-write! path content)]
              {:result (str "edited " path " (" n " bytes, " replacements
                            " replacement" (when (> replacements 1) "s") ")")
               :is-error false})
            {:result (str error " in " path) :is-error true}))))))

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
  (tool-name    [_] :fs/multi-edit)
  (description  [_]
    (str "Apply multiple string-replacement edits to one file atomically. "
         "Edits are applied in order to the in-memory content; the file is "
         "written only if ALL edits succeed. Each edit follows the same rules "
         "as fs_edit (unique-match by default; replace_all opts in)."))
  (input-schema [_] fs-multi-edit-schema)
  (invoke [_ {:keys [path edits]}]
    (let [f (io/file path)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true}

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
            {:result (str (:error result)
                          " (edit #" (inc (:failed-index result))
                          " of " (count edits) " in " path
                          "; no changes written).")
             :is-error true}
            (let [n (atomic-write! path (:content result))]
              {:result (str "applied " (count edits) " edit"
                            (when (> (count edits) 1) "s")
                            " to " path " (" n " bytes)")
               :is-error false})))))))

;; ---------------------------------------------------------------------------
;; :fs/glob
;; ---------------------------------------------------------------------------

(def ^:private fs-glob-schema
  [:map {:closed true}
   [:pattern :string]                       ;; e.g. "**/*.cljc" or "src/**/foo.clj"
   [:cwd {:optional true} :string]          ;; default "."
   [:limit {:optional true} pos-int?]       ;; default 200
   ;; Sort results most-recently-modified first when true (default true).
   [:by-mtime? {:optional true} :boolean]])

(defn- glob-match-fn
  "Build a path predicate from `pattern`. Java's `**/*.foo` only matches files
   at depth >= 1; for ergonomic parity with shell/bash globs (and tools like
   ripgrep, fd, minimatch), we also accept matches under the shorter form
   without the leading `**/` so root-level files are included."
  [pattern]
  (let [fs       (FileSystems/getDefault)
        primary  (.getPathMatcher fs (str "glob:" pattern))
        also     (when (str/starts-with? pattern "**/")
                   (.getPathMatcher fs (str "glob:" (subs pattern 3))))]
    (fn [^Path p]
      (or (.matches primary p)
          (and also (.matches also p))))))

(defrecord FsGlobTool []
  tp/Tool
  (tool-name    [_] :fs/glob)
  (description  [_]
    (str "Find files matching a glob pattern (e.g. `**/*.cljc`, `src/**/foo.clj`). "
         "Walks `cwd` (default `.`) recursively and returns matching paths. "
         "Sorted most-recently-modified first by default; pass by_mtime? false "
         "for lexicographic order. Capped at `limit` (default 200)."))
  (input-schema [_] fs-glob-schema)
  (invoke [_ {:keys [pattern cwd limit by-mtime?]
              :or   {by-mtime? true}}]
    (let [root      (io/file (or cwd "."))
          _         (when-not (.exists root)
                      (throw (ex-info "glob root does not exist"
                                      {:cwd cwd})))
          root-path (.toPath root)
          pat-fn    (glob-match-fn pattern)
          cap       (long (or limit 200))]
      (try
        (let [hits (with-open [stream (Files/walk root-path
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
              ordered (if by-mtime?
                        (sort-by (comp - :mtime) hits)
                        (sort-by :path hits))
              kept    (vec (take cap ordered))
              over?   (> (count hits) cap)]
          {:result (cond
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
   [:pattern :string]                         ;; regex
   [:path {:optional true} :string]           ;; default "."
   [:glob {:optional true} :string]           ;; file-name glob filter
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
  (let [path       (or path ".")
        mode       (or output-mode "files-with-matches")
        rg?        (rg-available?)
        cmd
        (if rg?
          (cond-> ["rg" "--no-config" "--color=never"]
            ignore-case (conj "-i")
            (= mode "files-with-matches") (conj "-l")
            (= mode "count")              (conj "-c")
            (= mode "content")            (conj "-n")
            (and (= mode "content") context) (into ["-C" (str context)])
            glob (into ["-g" glob])
            true (into ["-e" pattern path]))
          ;; Fallback: POSIX `grep -E -r`. Glob filtering via --include only on GNU grep;
          ;; we approximate using --include for our usage; suppress directory recursion errors.
          (cond-> ["grep" "-rE"]
            ignore-case (conj "-i")
            (= mode "files-with-matches") (conj "-l")
            (= mode "count")              (conj "-c")
            (= mode "content")            (conj "-n")
            (and (= mode "content") context) (into [(str "-C" context)])
            glob (into [(str "--include=" glob)])
            true (into [pattern path])))
        proc  (bp/process cmd {:out :string :err :string :shutdown bp/destroy-tree})
        done? (.waitFor ^Process (:proc proc) 20 TimeUnit/SECONDS)]
    (if-not done?
      (do (try (.destroyForcibly ^Process (:proc proc)) (catch Throwable _ nil))
          {:result "grep timed out after 20s" :is-error true})
      (let [exit (.exitValue ^Process (:proc proc))
            out  (or (deref-or-self (:out proc)) "")
            err  (or (deref-or-self (:err proc)) "")
            cap  (long (or limit 200))
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
          {:result (str (str/join "\n" kept)
                        (when over?
                          (str "\n... [" (- (count lines) cap)
                               " more lines; raise :limit]")))
           :is-error false}

          :else ;; files-with-matches | count
          {:result (str/trim out) :is-error false})))))

(defrecord FsGrepTool []
  tp/Tool
  (tool-name    [_] :fs/grep)
  (description  [_]
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
  (tool-name    [_] :shell/run)
  (description  [_] "Run a shell command via `bash -lc`. Returns combined stdout+stderr and exit code.")
  (input-schema [_] shell-run-schema)
  (invoke [_ {:keys [command timeout-ms]}]
    (let [timeout (or timeout-ms default-shell-timeout-ms)
          ;; Build the process without invoking ":wait true" so we can enforce a timeout.
          proc    (bp/process ["bash" "-lc" command]
                              {:in       nil
                               :out      :string
                               :err      :string
                               :shutdown bp/destroy-tree})
          done?   (.waitFor ^Process (:proc proc) timeout TimeUnit/MILLISECONDS)]
      (if-not done?
        (do
          (try (.destroyForcibly ^Process (:proc proc)) (catch Throwable _ nil))
          {:result   (str "Command timed out after " timeout "ms: " command)
           :is-error true})
        (let [exit (.exitValue ^Process (:proc proc))
              out  (or (some-> proc :out deref-or-self) "")
              err  (or (some-> proc :err deref-or-self) "")
              body (str out (when-not (str/blank? err) (str "\n[stderr]\n" err)))]
          {:result   (str body "\n[exit " exit "]")
           :is-error (not (zero? exit))})))))

;; ---------------------------------------------------------------------------
;; :repl/eval
;; ---------------------------------------------------------------------------

(def ^:private repl-eval-schema
  [:map {:closed true}
   [:code :string]
   [:timeout-ms {:optional true} :int]])

(def ^:const default-eval-timeout-ms 5000)

(defn- fresh-eval-ns
  "Create a fresh namespace `escapement.tools.eval.<n>` with `clojure.core` referred,
   evaluate `code` in it, then remove the namespace. Returns the pr-str of the result.

   Uses thread-local `binding` of `*ns*` instead of `in-ns` so that this works on
   non-main threads (e.g. inside a `future`) where `set!`-ing root vars is forbidden."
  [code]
  (let [sym (symbol (str "escapement.tools.eval." (System/nanoTime)))
        ns  (create-ns sym)]
    (try
      (binding [*ns* ns]
        (refer 'clojure.core)
        (pr-str (load-string code)))
      (finally
        (try (remove-ns sym) (catch Throwable _ nil))))))

(defrecord ReplEvalTool []
  tp/Tool
  (tool-name    [_] :repl/eval)
  (description  [_] "Evaluate Clojure `code` in a fresh sandboxed namespace. Returns pr-str of the value.")
  (input-schema [_] repl-eval-schema)
  (invoke [_ {:keys [code timeout-ms]}]
    (let [timeout (or timeout-ms default-eval-timeout-ms)
          fut     (future
                    (try
                      {:ok true :value (fresh-eval-ns code)}
                      (catch Throwable ex
                        {:ok false :error (str (.getClass ex) ": " (.getMessage ex))})))]
      (try
        (let [res (.get ^java.util.concurrent.Future fut timeout TimeUnit/MILLISECONDS)]
          (if (:ok res)
            {:result (:value res) :is-error false}
            {:result (:error res) :is-error true}))
        (catch TimeoutException _
          (future-cancel fut)
          {:result   (str "Eval timed out after " timeout "ms")
           :is-error true})))))

;; ---------------------------------------------------------------------------
;; Public assembly
;; ---------------------------------------------------------------------------

(>defn builtin-tools
       "Return a vector of the built-in tool instances."
       []
       [=> [:sequential any?]]
       [(->FsReadTool)
        (->FsWriteTool)
        (->FsEditTool)
        (->FsMultiEditTool)
        (->FsGlobTool)
        (->FsGrepTool)
        (->ShellRunTool)
        (->ReplEvalTool)])

(>defn new-builtin-registry
       "Return a FRESH registry populated with the eight built-in tools. Use
        this when you want isolation — most commonly in tests, or when a host
        process drives multiple chart runs that need disjoint tool sets."
       []
       [=> any?]
       (tp/new-registry (builtin-tools)))

(defonce ^{:doc "The default tool registry used by `escapement.cli` and any
  host that calls `(default-registry)`. Process-global: top-level
  `(tp/register! escapement.tools.builtin/default-registry ...)` calls in
  any namespace that is loaded before `runner/run!` will be visible to the
  chart. Seeded with the eight built-ins on namespace load.

  This is the Clojure-idiomatic registration pattern: require your tool
  namespace from the chart and it self-registers — no CLI flag needed.

  If you need isolation, prefer `new-builtin-registry` (returns a fresh
  registry) or build your own via `escapement.tools.protocol/new-registry`
  and thread it through `runner/run!` as `:tool-registry`."}
  default-registry
  (tp/new-registry (builtin-tools)))
