(ns escapement.storage.disk
  "Disk-backed `ArtifactStore` (bb/CLJ). Bound to one session's root directory (the runner's
   `:escapement/session-dir`), it writes every artifact at `<session-dir>/<path>` so the captured-I/O
   tree laid out by `escapement.capture` is literally walkable:

     <session-dir>/artifacts/<name>                          author files
     <session-dir>/nodes/<node-id>/<visit>/seed.edn          replayable seed
     <session-dir>/nodes/<node-id>/<visit>/turns/<n>/…        per-turn request/response/tool-results

   Writes are atomic (temp file + rename) so a concurrent reader never sees a partial blob. `path`
   IS the addressing key — there is no separate id table.

   The `session-id` protocol argument is accepted but not used for pathing: a runner process owns one
   session dir, so this store is single-session. (The cross-session `SessionIndex` and a multi-session
   layout arrive with the resolver layer.)"
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.protocols :as proto])
  (:import
    (java.nio.file Files Path Paths StandardCopyOption)
    (java.nio.file.attribute FileAttribute)))

(defn- ^Path as-path [s] (Paths/get (str s) (into-array String [])))

(defn- atomic-write!
  "Write string `content` to `path` durably and atomically, creating parent dirs."
  [^String path ^String content]
  (let [target (as-path path)
        parent (.getParent target)]
    (when parent (Files/createDirectories parent (into-array FileAttribute [])))
    (let [tmp (Files/createTempFile parent ".artifact-" ".tmp" (into-array FileAttribute []))]
      (Files/writeString tmp content (into-array java.nio.file.OpenOption []))
      (Files/move tmp target
        (into-array java.nio.file.CopyOption
          [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))))

(defn- path->content-type [path]
  (cond
    (str/ends-with? path ".json") "application/json"
    (str/ends-with? path ".edn")  "application/edn"
    (str/ends-with? path ".md")   "text/markdown"
    :else                         "text/plain"))

(defn- decode-node-id
  "Best-effort inverse of `escapement.capture/encode-node-id` (the namespace `/` → `_` mapping is not
   perfectly reversible; the authoritative coordinates live on the transcript event)."
  [seg]
  (when (seq seg) (keyword (str/replace seg "_" "/"))))

(defn- path->coords
  "Derive `{:artifact/class … :transcript/node-id … :visit … :turn …}` from a relative artifact
   `path`. Author files (`artifacts/…`) carry only `:author`; captured-I/O paths
   (`nodes/<nid>/<visit>/…`) yield node-id/visit and, under `turns/<n>/`, the turn index."
  [path]
  (let [segs (str/split path #"/")]
    (if (= "nodes" (first segs))
      (let [[_ enc-nid visit & more] segs
            turn (when (= "turns" (first more)) (second more))]
        (cond-> {:artifact/class     :captured-io
                 :transcript/node-id (decode-node-id enc-nid)
                 :transcript/visit   (parse-long (str visit))}
          turn (assoc :transcript/turn (parse-long (str turn)))))
      {:artifact/class :author})))

(defn- relative-path [^Path root ^Path file]
  (str (.relativize root file)))

(defn- artifact-path?
  "True when relative `path` names an artifact this store owns — an author file under `artifacts/` or
   a captured-I/O blob under `nodes/`. Excludes sibling session files (the transcript, checkpoints,
   rendered diagrams) that share the session dir but are not artifacts."
  [path]
  (or (str/starts-with? path "artifacts/")
    (str/starts-with? path "nodes/")))

(defrecord DiskArtifactStore [session-dir]
  proto/ArtifactStore
  (write-artifact! [_ _session-id path content meta]
    (atomic-write! (str session-dir "/" path) content)
    (merge (select-keys meta [:transcript/node-id :transcript/visit :transcript/turn :artifact/class])
      {:artifact/path         path
       :artifact/size         (count content)
       :artifact/content-type (or (:artifact/content-type meta) (path->content-type path))}))
  (read-artifact [_ _session-id path]
    (let [f (io/file session-dir path)]
      (when (.isFile f) (slurp f))))
  (list-artifacts [_ _session-id]
    (let [root (as-path session-dir)]
      (->> (file-seq (io/file session-dir))
        (filter #(.isFile ^java.io.File %))
        (keep (fn [^java.io.File f]
                (let [rel (relative-path root (.toPath f))]
                  (when (artifact-path? rel)
                    (merge (path->coords rel)
                      {:artifact/path         rel
                       :artifact/size         (.length f)
                       :artifact/content-type (path->content-type rel)})))))
        (sort-by :artifact/path)
        vec))))

(defn new-artifact-store
  "Create a `DiskArtifactStore` rooted at `session-dir` (the per-session directory). Artifacts are
   written under it; parent dirs are created on demand."
  [session-dir]
  (->DiskArtifactStore session-dir))
