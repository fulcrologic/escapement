(ns escapement.storage.disk
  "Disk-backed `ArtifactStore` (bb/CLJ). Bound to one session's root directory (the runner's
   `:escapement/session-dir`), it writes every artifact at `<session-dir>/<path>` so the captured-I/O
   tree laid out by `escapement.capture` is literally walkable:

     <session-dir>/artifacts/<name>                          author files
     <session-dir>/nodes/<node-id>/<visit>/seed.edn          replayable seed
     <session-dir>/nodes/<node-id>/<visit>/turns/<n>/…        per-turn request/response/tool-results
     <session-dir>/.meta/<path>.edn                          the meta written with <path> (sidecar)

   Writes are atomic (temp file + rename) so a concurrent reader never sees a partial blob. `path`
   IS the addressing key — there is no separate id table.

   **Meta sidecars.** The `meta` passed to `write-artifact!` / `write-artifact-bytes!` is kept as EDN
   at `.meta/<path>.edn`, written before the content so a crash leaves at worst an orphan sidecar
   (ignored, since listing walks content files). The sidecar holds the caller's meta verbatim —
   coordinates, `:artifact/class`, `:artifact/content-type` when given (a byte write with none
   records `application/octet-stream`), and any other keys — minus `:artifact/path` and
   `:artifact/size`, which always come from the content file itself. An empty `meta` removes the
   sidecar. `list-artifacts` merges the sidecar over the coordinates derived from the path, so the
   exact node-id the capture layer wrote beats the lossy path decoding. A missing or unreadable
   sidecar — every artifact of a session written before sidecars existed — lists from the path
   alone, exactly as before. The `.meta/` tree sits outside `artifacts/` and `nodes/`, so older
   Escapement versions, which list only those two prefixes, never see it.

   The `session-id` protocol argument is accepted but not used for pathing: a runner process owns one
   session dir, so this store is single-session. (The cross-session `SessionIndex` and a multi-session
   layout arrive with the resolver layer.)"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.protocols :as proto]
    [escapement.storage.common :as common])
  (:import
    (java.nio.file Files Path Paths StandardCopyOption)
    (java.nio.file.attribute FileAttribute)))

(def meta-dir
  "The session-relative directory holding meta sidecars."
  ".meta")

(defn- ^Path as-path [s] (Paths/get (str s) (into-array String [])))

(defn- atomic-write!
  "Write the `content` bytes to `path` durably and atomically, creating parent dirs."
  [^String path ^bytes content]
  (let [target (as-path path)
        parent (.getParent target)]
    (when parent (Files/createDirectories parent (into-array FileAttribute [])))
    (let [tmp (Files/createTempFile parent ".artifact-" ".tmp" (into-array FileAttribute []))]
      (Files/write tmp content (into-array java.nio.file.OpenOption []))
      (Files/move tmp target
        (into-array java.nio.file.CopyOption
          [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))))

(defn- decode-node-id
  "Best-effort inverse of `escapement.capture/encode-node-id` (the namespace `/` → `_` mapping is not
   perfectly reversible; the authoritative coordinates live in the meta sidecar and on the transcript
   event)."
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
   rendered diagrams, meta sidecars) that share the session dir but are not artifacts."
  [path]
  (or (str/starts-with? path "artifacts/")
    (str/starts-with? path "nodes/")))

(defn- sidecar-file
  "The meta sidecar file for artifact `path` in `session-dir`."
  ^java.io.File [session-dir path]
  (io/file session-dir meta-dir (str path ".edn")))

(defn- persisted-meta
  "The part of `meta` a sidecar keeps: everything but the content-derived path and size."
  [meta]
  (dissoc meta :artifact/path :artifact/size))

(defn- write-sidecar!
  "Persist `meta` as the sidecar for `path`, or remove the sidecar when `meta` is empty."
  [session-dir path meta]
  (let [f (sidecar-file session-dir path)]
    (if (seq meta)
      (atomic-write! (str f) (common/utf8-bytes (pr-str meta)))
      (Files/deleteIfExists (.toPath f)))))

(defn- read-sidecar
  "The meta map persisted for `path`, or `nil` when there is no sidecar or it cannot be read as an
   EDN map."
  [session-dir path]
  (let [f (sidecar-file session-dir path)]
    (when (.isFile f)
      (let [m (try (edn/read-string {:default tagged-literal} (slurp f))
                   (catch Exception _ nil))]
        (when (map? m) m)))))

(defn- summary
  "The artifact summary for `path` whose content is `size` bytes, given its persisted `meta`."
  [path size meta]
  (common/artifact-summary path size (path->coords path) (persisted-meta meta)))

(defn- write-content!
  "Write the sidecar and then the `content` bytes of `path`, returning its summary."
  [session-dir path ^bytes content meta]
  (let [meta (persisted-meta meta)]
    (write-sidecar! session-dir path meta)
    (atomic-write! (str session-dir "/" path) content)
    (summary path (alength content) meta)))

(defrecord DiskArtifactStore [session-dir]
  proto/ArtifactStore
  (write-artifact! [_ _session-id path content meta]
    (write-content! session-dir path (common/utf8-bytes content) meta))
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
                    (summary rel (.length f) (read-sidecar session-dir rel))))))
        (sort-by :artifact/path)
        vec)))
  (write-artifact-bytes! [_ _session-id path content meta]
    (write-content! session-dir path content (common/binary-meta meta)))
  (read-artifact-bytes [_ _session-id path]
    (let [f (io/file session-dir path)]
      (when (.isFile f) (Files/readAllBytes (.toPath f))))))

(defn new-artifact-store
  "Create a `DiskArtifactStore` rooted at `session-dir` (the per-session directory). Artifacts are
   written under it; parent dirs are created on demand."
  [session-dir]
  (->DiskArtifactStore session-dir))
