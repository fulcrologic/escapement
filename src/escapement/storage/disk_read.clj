(ns escapement.storage.disk-read
  "Read-side, MULTI-session disk store (bb/CLJ). Rooted at a sessions-root (the runner's
   `--work-dir`, default `.escapement`), it serves every session that has been written under it:

     <work-dir>/<session-id>/transcript.jsonl   ordered event log (bare-key JSON)
     <work-dir>/<session-id>/artifacts/<name>   author files
     <work-dir>/<session-id>/nodes/…            captured-I/O tree

   It implements `TranscriptStore` (read), `SessionIndex`, and `ArtifactStore` (by delegating to a
   per-session `escapement.storage.disk/DiskArtifactStore`). The `session-id` protocol argument
   selects the per-session directory under the root.

   `append-event!` is intentionally unsupported here: the live writer thread
   (`escapement.transcript`) owns the append path. This store is for the read surface (resolvers /
   `--api-server`) and is read-only with respect to the transcript.

   The transcript is BARE-KEY JSON (`{\"event\":… ,\"data\":… ,\"ts\":… ,\"seq\":N}`). `normalize-event`
   maps it to the namespaced vocabulary on read, so the hot daemon writer stays untouched. JSON
   parse-back sidesteps the digit-leading `:session/<uuid>` keyword EDN gotcha entirely."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as-alias sc]
    [escapement.protocols :as proto]
    [escapement.storage.disk :as disk]))

(def ^:const transcript-name "transcript.jsonl")

(defn ->keyword
  "Coerce a transcript-stored keyword-ish `s` back to a keyword, tolerating a leading colon (some
   values are persisted via `str` on a keyword, e.g. `\":escapement.runner/chart\"`). Returns `nil`
   for a blank/`nil` input."
  [s]
  (when (and (string? s) (seq s))
    (keyword (cond-> s (str/starts-with? s ":") (subs 1)))))

(defn normalize-event
  "Map one on-disk bare-key transcript `row` (a JSON-parsed map with keyword keys) to the namespaced
   transcript vocabulary. Surfaces `:io/ref` / `:io/snippet` to the top level when the event's
   `:data` carries them (captured-I/O events), while keeping the full `:data` under
   `:transcript/data`."
  [row]
  (let [data (:data row)]
    (cond-> {:transcript/seq  (:seq row)
             :transcript/ts   (:ts row)
             :transcript/kind (->keyword (:event row))
             :transcript/data data}
      (:io/ref data)     (assoc :io/ref (:io/ref data))
      (:io/snippet data) (assoc :io/snippet (:io/snippet data)))))

(defn events-xform
  "Transducer mapping raw JSON-parsed transcript rows to normalized events, applying the optional
   `query` predicates. `query` keys (all optional): `:types` (set of `:transcript/kind`), `:node-id`,
   `:from-seq`/`:to-seq` (inclusive `:transcript/seq` bounds), `:limit`. `take` is applied LAST so
   the limit counts post-filter; placed in the transducer it short-circuits reading."
  [{:keys [types node-id from-seq to-seq limit]}]
  (apply comp
    (cond-> [(map normalize-event)]
      types    (conj (filter #(contains? types (:transcript/kind %))))
      node-id  (conj (filter #(= node-id (:transcript/node-id %))))
      from-seq (conj (filter #(<= from-seq (:transcript/seq %))))
      to-seq   (conj (filter #(<= (:transcript/seq %) to-seq)))
      limit    (conj (take limit)))))

(defn read-events*
  "Stream `<work-dir>/<session-id>/transcript.jsonl`, parse each line, normalize, and apply `query`
   (see `events-xform`). Returns a vector in `:transcript/seq` order, or `nil` if the session has no
   transcript. Realized inside `with-open`; `:limit` short-circuits so large logs are not fully read."
  [work-dir session-id query]
  ;; `session-id` may arrive as a String or a java.util.UUID (the on-disk session directory is the
  ;; UUID's string form); `io/file` rejects a UUID, so normalize to the string name.
  (let [session-id (str session-id)
        f          (io/file work-dir session-id transcript-name)]
    (when (.isFile f)
      (with-open [r (io/reader f)]
        (into []
          (comp (remove str/blank?)
            (map #(json/parse-string % true))
            (events-xform query))
          (line-seq r))))))

(defn- scan-transcript
  "One pass over a session transcript `f`, returning the summary facts needed for a listing:
   `{:started-at :ended-at :chart-id :resume? :status :count}`. `:status` is `:done` once a
   `runner/done` row is seen, else `:incomplete` (still running or crashed). Constant memory."
  [^java.io.File f]
  (with-open [r (io/reader f)]
    (reduce
      (fn [acc line]
        (if (str/blank? line)
          acc
          (let [row (json/parse-string line true)
                ev  (:event row)]
            (cond-> (assoc acc :ended-at (:ts row))
              (nil? (:started-at acc)) (assoc :started-at (:ts row))
              true                     (update :count inc)
              (= ev "runner/started")  (assoc :chart-id (get-in row [:data :chart-id])
                                         :resume? (get-in row [:data :resume?]))
              (= ev "runner/done")     (assoc :status :done)))))
      {:started-at nil :ended-at nil :chart-id nil :resume? nil :status :incomplete :count 0}
      (line-seq r))))

(defn session-summary
  "Build a session summary map for `session-id` (a directory name under `work-dir`) by scanning its
   transcript. Parent/child correlation is not derived in v1 (`:session/parent-id` nil,
   `:session/child-ids` empty)."
  [work-dir session-id]
  (let [session-id (str session-id)                          ; tolerate a UUID session-id (see read-events*)
        f          (io/file work-dir session-id transcript-name)
        {:keys [started-at ended-at chart-id resume? status count]} (scan-transcript f)]
    {::sc/session-id     session-id
     ::sc/statechart-src (->keyword chart-id)
     :session/started-at started-at
     :session/ended-at   ended-at
     :session/status     status
     :session/resume?    resume?
     :session/event-count count
     :session/parent-id  nil
     :session/child-ids  []}))

(defn list-sessions*
  "Enumerate session summaries for every immediate sub-directory of `work-dir` that contains a
   `transcript.jsonl`, most-recently-started first."
  [work-dir]
  (let [root (io/file work-dir)]
    (if (.isDirectory root)
      (->> (.listFiles root)
        (filter (fn [^java.io.File d]
                  (and (.isDirectory d) (.isFile (io/file d transcript-name)))))
        (mapv (fn [^java.io.File d] (session-summary work-dir (.getName d))))
        (sort-by :session/started-at #(compare %2 %1))
        vec)
      [])))

(defn- session-artifact-store
  "A per-session `DiskArtifactStore` rooted at `<work-dir>/<session-id>` — the same on-disk layout
   the live runner writes to. Constructed per call (cheap record)."
  [work-dir session-id]
  (disk/new-artifact-store (str work-dir "/" session-id)))

(defrecord MultiSessionDiskStore [work-dir]
  proto/TranscriptStore
  (append-event! [_ _session-id _event]
    (throw (ex-info "MultiSessionDiskStore is read-only; the live writer owns appends."
             {:work-dir work-dir})))
  (read-events [_ session-id query]
    (read-events* work-dir session-id query))

  proto/SessionIndex
  (list-sessions [_]
    (list-sessions* work-dir))

  proto/ArtifactStore
  (write-artifact! [_ session-id path content meta]
    (proto/write-artifact! (session-artifact-store work-dir session-id) session-id path content meta))
  (read-artifact [_ session-id path]
    (proto/read-artifact (session-artifact-store work-dir session-id) session-id path))
  (list-artifacts [_ session-id]
    (proto/list-artifacts (session-artifact-store work-dir session-id) session-id)))

(defn new-store
  "Create a `MultiSessionDiskStore` rooted at the sessions-root `work-dir` (the runner's
   `--work-dir`). Implements `TranscriptStore` (read), `SessionIndex`, and `ArtifactStore`."
  [work-dir]
  (->MultiSessionDiskStore work-dir))
