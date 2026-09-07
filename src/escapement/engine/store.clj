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
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as queue])
  (:import
    (java.nio.file CopyOption Files Path StandardCopyOption)))

(defn- session-slug
  "Filesystem-safe name for `session-id` (a keyword drops its leading colon; everything else is
   stringified). `/` in a namespaced keyword stays (nested dirs) as it did before this refactor."
  [session-id]
  (if (keyword? session-id) (subs (str session-id) 1) (str session-id)))

(>defn ^:private session-file
  "Returns the canonical checkpoint file for `session-id` inside `dir`."
  [dir session-id]
  [:string [:or :keyword :string :uuid :symbol] => any?]
  (io/file dir (str (session-slug session-id) ".edn")))

(defn- history-dir
  "The per-session retained-history directory `<dir>/history/<session-slug>/`."
  [dir session-id]
  (io/file dir "history" (session-slug session-id)))

(defn- history-file
  "The retained-history checkpoint file for `session-id` at save-index `seq` (`…/history/<slug>/<seq>.edn`)."
  [dir session-id seq]
  (io/file (history-dir dir session-id) (str seq ".edn")))

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

;; ---------------------------------------------------------------------------
;; ON-DISK CHECKPOINT FORMAT — READ THIS BEFORE CHANGING THE RECORD SHAPE
;; ---------------------------------------------------------------------------
;; A checkpoint on disk is a COMBINED point-in-time record so the working-memory
;; configuration and the pending event queue are captured together in ONE atomic
;; write (never two files that could tear across a crash). `::wmem` is the library
;; working memory `get-working-memory` returns; `::queue-snapshot` is the durable
;; event queue (delayed timers) as `escapement.engine.queue/snapshot` data.
;;
;; THE RULE: **any** change to the on-disk record shape MUST
;;   1. bump `current-version` below, AND
;;   2. add a case to `normalize-record` (the single reader seam) that upgrades
;;      the older shape to the current one.
;; Every reader goes through `normalize-record`; nothing else may sniff the record
;; shape. A record whose `::version` is NEWER than `current-version` is a hard
;; error (we cannot know what it means) — never silently misread it.

(def current-version
  "Schema version of the checkpoint record this build writes.

   History:
     * (pre-version, legacy bare) — the file was the bare working-memory map itself,
       with no `::wmem` marker key and no queue snapshot.
     * (pre-version, combined)    — `{::wmem … ::queue-snapshot …}` with no `::version`.
     * 1                          — the same combined record, now carrying `::version 1`.

   Normalized on read to the current shape by `normalize-record`."
  1)

(defn normalize-record
  "THE single reader seam for on-disk checkpoint records: given whatever `record` was read from
   disk, return it in the CURRENT combined shape (`{::version ::wmem ::queue-snapshot}`), or nil
   for nil input.

   Handles: a versioned combined record (current); an unversioned combined record (has `::wmem`);
   and a legacy bare working-memory map (no `::wmem` marker at all → nil queue snapshot).

   Throws `ex-info` when the record's `::version` is newer than this build understands."
  [record]
  (when (some? record)
    (let [v (when (map? record) (::version record))]
      (cond
        (and v (> v current-version))
        (throw (ex-info (str "Checkpoint was written by a newer version of Escapement "
                          "(checkpoint schema v" v ", this build understands v" current-version
                          "). Upgrade Escapement to read it.")
                 {:checkpoint-version v
                  :supported-version  current-version}))

        ;; versioned or unversioned combined record
        (and (map? record) (contains? record ::wmem))
        (assoc record ::version current-version)

        ;; legacy: the file WAS the bare working-memory map
        :else
        {::version current-version ::wmem record ::queue-snapshot nil}))))

(defn record->wmem
  "Unwrap the working memory from an on-disk checkpoint `record` (any supported schema version).
   A legacy bare-wmem checkpoint is returned as-is."
  [record]
  (::wmem (normalize-record record)))

(defn record->queue-snapshot
  "Return the event-queue snapshot from a checkpoint `record` (nil for a legacy bare-wmem
   checkpoint)."
  [record]
  (::queue-snapshot (normalize-record record)))

(defrecord FileBackedStore [dir cache history]
  sp/WorkingMemoryStore
  (get-working-memory [_ _env session-id]
    (if-let [hit (get @cache session-id)]
      hit
      (when-let [record (read-edn-file (session-file dir session-id))]
        (let [wmem (record->wmem record)]
          (swap! cache assoc session-id wmem)
          wmem))))
  (save-working-memory! [_ env session-id wmem]
    ;; Cache the BARE wmem (what `get-working-memory` returns); persist the COMBINED
    ;; record. The queue snapshot is read from the live queue on `env` so wmem + queue
    ;; land in one atomic write — the crash-consistent point-in-time the resume path
    ;; and a Level-3 fork both rehydrate from.
    (swap! cache assoc session-id wmem)
    (let [base {::version       current-version
                ::wmem          wmem
                ::queue-snapshot (some-> (::sc/event-queue env) queue/snapshot)}]
      (if history
        ;; Retain an append-only, save-index-keyed COPY of the same combined record under
        ;; history/<slug>/<n>.edn so a Level-3 fork can re-enter the chart at any past point
        ;; with the pending queue that was live then. Opt-in (retain-history?) — per-event
        ;; full snapshots are heavy and unbounded for an infinite chart.
        (let [n   (get @history session-id 0)
              rec (assoc base ::seq n)]
          (atomic-write-edn! (session-file dir session-id) rec)
          (atomic-write-edn! (history-file dir session-id n) rec)
          (swap! history assoc session-id (inc n)))
        (atomic-write-edn! (session-file dir session-id) base)))
    nil)
  (delete-working-memory! [_ _env session-id]
    (swap! cache dissoc session-id)
    (let [f (session-file dir session-id)]
      (when (.exists f) (.delete f)))
    nil))

(>defn list-history
  "Return the retained history save-indices for `session-id` in `store`, ascending. Empty when history
   retention was off or nothing has been saved yet."
  [store session-id]
  [[:fn (partial instance? FileBackedStore)] [:or :keyword :string :uuid :symbol] => [:vector :int]]
  (let [d (history-dir (:dir store) session-id)]
    (if (.exists d)
      (->> (.listFiles d)
        (keep (fn [^java.io.File f]
                (let [n (.getName f)]
                  (when (.endsWith n ".edn") (parse-long (subs n 0 (- (count n) 4)))))))
        sort
        vec)
      [])))

(>defn read-checkpoint-at
  "Return the retained combined checkpoint record for `session-id` at the greatest save-index ≤
   `target-seq` (an at-or-before lookup, so a fork point between retained saves resolves to the last
   state on or before it). `nil` when no retained snapshot qualifies. Use `record->wmem` /
   `record->queue-snapshot` to project it."
  [store session-id target-seq]
  [[:fn (partial instance? FileBackedStore)] [:or :keyword :string :uuid :symbol] :int => [:maybe :map]]
  (when-let [n (last (filter #(<= % target-seq) (list-history store session-id)))]
    (read-edn-file (history-file (:dir store) session-id n))))

(defn file-backed?
  "True when `store` is a `FileBackedStore` (has an on-disk checkpoint the queue snapshot can be read
   from). Lets the runner guard queue rehydration to the disk-durable store — an in-memory store's
   queue survives in-process, so it needs no rehydration."
  [store]
  (instance? FileBackedStore store))

(>defn get-queue-snapshot
  "Return the persisted event-queue snapshot from `session-id`'s on-disk checkpoint in `store`, or
   `nil` when the checkpoint is absent or predates queue durability. The runner rehydrates the live
   queue from this on `--resume` so delayed/timer events scheduled before the process exited fire when
   their time arrives. Reads from disk (not the wmem cache), so it is valid at process start."
  [store session-id]
  [[:fn (partial instance? FileBackedStore)] [:or :keyword :string :uuid :symbol] => [:maybe :map]]
  (record->queue-snapshot (read-edn-file (session-file (:dir store) session-id))))

(>defn new-store
  "Create a new checkpoint store backed by `dir` (creating it if necessary).

   `opts`:
     * `:retain-history?` — when true, every save ALSO writes an append-only, save-index-keyed copy of
       the combined checkpoint under `history/<session>/<n>.edn`, so a Level-3 replay can fork the chart
       from any past point (with the queue that was pending then). Off by default (per-event full
       snapshots are heavy and unbounded for an infinite chart)."
  ([dir] [:string => any?] (new-store dir {}))
  ([dir {:keys [retain-history?]}]
   [:string :map => any?]
   (ensure-dir! dir)
   (->FileBackedStore dir (atom {}) (when retain-history? (atom {})))))

(>defn reload-from-disk!
  "Drop the in-memory cache so the next read for `session-id` comes from disk (simulating a process restart)."
  [store session-id]
  [any? any? => :nil]
  (swap! (:cache store) dissoc session-id)
  nil)
