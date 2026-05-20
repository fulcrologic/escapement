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
    [com.fulcrologic.statecharts.protocols :as sp])
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

(defrecord FileBackedStore [dir cache]
  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (if-let [hit (get @cache session-id)]
      hit
      (when-let [v (read-edn-file (session-file dir session-id))]
        (swap! cache assoc session-id v)
        v)))
  (save-working-memory! [_ _env session-id wmem]
    (swap! cache assoc session-id wmem)
    (atomic-write-edn! (session-file dir session-id) wmem)
    nil)
  (delete-working-memory! [_ _env session-id]
    (swap! cache dissoc session-id)
    (let [f (session-file dir session-id)]
      (when (.exists f) (.delete f)))
    nil))

(>defn new-store
  "Create a new checkpoint store backed by `dir` (creating it if necessary)."
  [dir]
  [:string => any?]
  (ensure-dir! dir)
  (->FileBackedStore dir (atom {})))

(>defn reload-from-disk!
  "Drop the in-memory cache so the next read for `session-id` comes from disk (simulating a process restart)."
  [store session-id]
  [any? any? => :nil]
  (swap! (:cache store) dissoc session-id)
  nil)
