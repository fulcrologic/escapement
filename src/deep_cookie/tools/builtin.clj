(ns deep-cookie.tools.builtin
  "Built-in tools usable from both bb and JVM:
  `:fs/read`, `:fs/write`, `:fs/edit`, `:shell/run`, `:repl/eval`.

  Each tool is a `defrecord` implementing `deep-cookie.tools.protocol/Tool`. Inputs are
  validated by `dispatch` before `invoke` is called; the bodies here can therefore assume
  shape correctness and only need to defend against runtime conditions (missing files,
  ambiguous edits, non-zero exit codes, eval errors)."
  (:require
   [babashka.process :as bp]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [deep-cookie.tools.protocol :as tp])
  (:import
   (java.nio.charset StandardCharsets)
   (java.nio.file CopyOption Files Path StandardCopyOption)
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

(def ^:private fs-read-schema
  [:map {:closed true}
   [:path :string]])

(defrecord FsReadTool []
  tp/Tool
  (tool-name    [_] :fs/read)
  (description  [_] "Read a UTF-8 text file and return its contents. Large files are truncated.")
  (input-schema [_] fs-read-schema)
  (invoke [_ {:keys [path]}]
    (let [f (io/file path)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true}

        (.isDirectory f)
        {:result (str "Path is a directory: " path) :is-error true}

        :else
        (let [size (.length f)
              text (if (> size max-read-bytes)
                     (let [head (with-open [r (io/reader f :encoding "UTF-8")]
                                  (let [buf (char-array max-read-bytes)
                                        n   (.read r buf 0 max-read-bytes)]
                                    (String. buf 0 (max 0 n))))]
                       (str head
                            "\n... [truncated: file is " size " bytes; first "
                            max-read-bytes " bytes shown]"))
                     (read-utf8 path))]
          {:result text :is-error false})))))

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

(def ^:private fs-edit-schema
  [:map {:closed true}
   [:path :string]
   [:old-string :string]
   [:new-string :string]])

(defrecord FsEditTool []
  tp/Tool
  (tool-name    [_] :fs/edit)
  (description  [_] "Replace the unique occurrence of `old-string` with `new-string` in `path`.")
  (input-schema [_] fs-edit-schema)
  (invoke [_ {:keys [path old-string new-string]}]
    (let [f (io/file path)]
      (cond
        (not (.exists f))
        {:result (str "No such file: " path) :is-error true}

        (str/blank? old-string)
        {:result "old-string must be non-empty" :is-error true}

        :else
        (let [content (read-utf8 path)
              hits    (count-occurrences content old-string)]
          (cond
            (zero? hits)
            {:result (str "No match for old-string in " path) :is-error true}

            (> hits 1)
            {:result (str "Ambiguous edit: old-string occurs " hits " times in " path
                          "; provide a more specific snippet.")
             :is-error true}

            :else
            (let [updated (str/replace-first content old-string new-string)
                  n       (atomic-write! path updated)]
              {:result (str "edited " path " (" n " bytes)") :is-error false})))))))

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
  "Create a fresh namespace `deep-cookie.tools.eval.<n>` with `clojure.core` referred,
   evaluate `code` in it, then remove the namespace. Returns the pr-str of the result.

   Uses thread-local `binding` of `*ns*` instead of `in-ns` so that this works on
   non-main threads (e.g. inside a `future`) where `set!`-ing root vars is forbidden."
  [code]
  (let [sym (symbol (str "deep-cookie.tools.eval." (System/nanoTime)))
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
       "Return a vector of the five built-in tool instances."
       []
       [=> [:sequential any?]]
       [(->FsReadTool)
        (->FsWriteTool)
        (->FsEditTool)
        (->ShellRunTool)
        (->ReplEvalTool)])

(>defn new-builtin-registry
       "Return a fresh registry populated with the five built-in tools."
       []
       [=> any?]
       (tp/new-registry (builtin-tools)))
