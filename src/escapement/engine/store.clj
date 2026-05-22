(ns escapement.engine.store
  "WorkingMemoryStore with atomic file-based checkpoint persistence.

  Each session's working memory is serialized as EDN and written via temp-file + atomic rename
  so that a crash mid-write cannot corrupt the canonical checkpoint file. On reload, the canonical
  file is read; if it does not exist the session is considered new.

  An in-memory cache mirrors the on-disk state so that hot reads (every event) don't hit the disk."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.threads :as threads])
  (:import
    (java.nio.file CopyOption Files Path StandardCopyOption)))

(>defn ^:private session-file
  "Returns the canonical checkpoint file for `session-id` inside `dir`."
  [dir session-id]
  [:string [:or :keyword :string :uuid :symbol] => any?]
  (io/file dir (str (cond
                      (keyword? session-id) (subs (str session-id) 1)
                      :else (str session-id))
                 ".edn")))

(defn- ensure-dir! [^String dir]
  (let [d (io/file dir)]
    (when-not (.exists d) (.mkdirs d))))

(defn- ^Path as-path [f] (.toPath (io/file f)))

(defn- atomic-write-edn!
  "Serialize `value` as EDN, write to `<file>.tmp`, then atomically rename over `file`.
   The rename is performed with `ATOMIC_MOVE` and `REPLACE_EXISTING`."
  [file value]
  (ensure-dir! (.getParent (io/file file)))
  (let [tmp (io/file (str (.getPath ^java.io.File file) ".tmp"))]
    (with-open [w (io/writer tmp)]
      (binding [*out* w]
        (pr value)))
    (Files/move (as-path tmp) (as-path file)
      (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                              StandardCopyOption/REPLACE_EXISTING]))
    nil))

(defn- read-edn-file [file]
  (when (.exists (io/file file))
    (edn/read-string {:default tagged-literal} (slurp file))))

(defn- drain-dirty!
  "Atomically grab the current dirty set and write each session's LATEST `wmem`
   (from `cache`) to disk. Latest-wins: if a save lands between the grab and the
   write, we still write the freshest cache value, and any subsequent save will
   re-dirty for the next drain."
  [dir cache dirty]
  (let [ids (first (swap-vals! dirty (constantly #{})))]
    (doseq [sid ids]
      (when-let [wmem (get @cache sid)]
        (atomic-write-edn! (session-file dir sid) wmem)))))

;; `dirty` and `flusher` are nil in synchronous (default) mode. In write-behind
;; mode `dirty` is an atom holding a set of session-ids and `flusher` is a map
;; {:running? atom :thread Thread} so close! can stop the loop.
(defrecord FileBackedStore [dir cache dirty flusher]
  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (if-let [hit (get @cache session-id)]
      hit
      (when-let [v (read-edn-file (session-file dir session-id))]
        (swap! cache assoc session-id v)
        v)))
  (save-working-memory! [_ _env session-id wmem]
    (swap! cache assoc session-id wmem)
    (if dirty
      (swap! dirty conj session-id)                  ; write-behind: defer to flusher
      (atomic-write-edn! (session-file dir session-id) wmem))
    nil)
  (delete-working-memory! [_ _env session-id]
    (swap! cache dissoc session-id)
    (when dirty (swap! dirty disj session-id))
    (let [f (session-file dir session-id)]
      (when (.exists f) (.delete f)))
    nil))

(defn flush!
  "Synchronously drain all pending dirty writes to disk. No-op on a synchronous
   (non-write-behind) store. Idempotent."
  [store]
  (when-let [dirty (:dirty store)]
    (drain-dirty! (:dir store) (:cache store) dirty))
  nil)

(defn close!
  "Stop the background flusher (if any) and perform a final synchronous drain.
   No-op / safe on a synchronous store. Idempotent."
  [store]
  (when-let [{:keys [running? thread]} (:flusher store)]
    (when (compare-and-set! running? true false)
      (.interrupt ^Thread thread)
      (try (.join ^Thread thread 1000) (catch InterruptedException _ nil))))
  (flush! store)
  nil)

(defn- start-flusher!
  [dir cache dirty flush-ms]
  (let [running? (atom true)
        thread   (threads/unstarted-daemon
                   "escapement-ckpt-flusher"
                   (fn []
                     (while @running?
                       (try (Thread/sleep (long flush-ms))
                            (catch InterruptedException _ nil))
                       (when @running?
                         (drain-dirty! dir cache dirty)))))]
    (.start thread)
    {:running? running? :thread thread}))

(>defn new-store
  "Create a new checkpoint store backed by `dir` (creating it if necessary).

   Single-arity (default): synchronous, byte-for-byte equivalent to the original
   FileBackedStore — every save writes through to disk inline.

   With `{:write-behind? true :flush-ms 250}`: saves only update the in-memory
   cache and mark the session dirty; a background daemon flusher coalesces writes
   every `flush-ms` (default 250). Call `flush!`/`close!` to force durability."
  ([dir]
   [:string => any?]
   (new-store dir {}))
  ([dir {:keys [write-behind? flush-ms] :or {flush-ms 250}}]
   [:string [:map
             [:write-behind? {:optional true} [:maybe :boolean]]
             [:flush-ms {:optional true} [:maybe :int]]]
    => any?]
   (ensure-dir! dir)
   (let [cache (atom {})]
     (if write-behind?
       (let [dirty   (atom #{})
             flusher (start-flusher! dir cache dirty flush-ms)]
         (->FileBackedStore dir cache dirty flusher))
       (->FileBackedStore dir cache nil nil)))))

(>defn reload-from-disk!
  "Drop the in-memory cache so the next read for `session-id` comes from disk (simulating a process restart)."
  [store session-id]
  [any? any? => :nil]
  (swap! (:cache store) dissoc session-id)
  nil)
