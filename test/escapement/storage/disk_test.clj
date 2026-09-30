(ns escapement.storage.disk-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.capture :as capture]
    [escapement.protocols :as proto]
    [escapement.replay :as replay]
    [escapement.storage.disk :as disk]
    [escapement.storage.memory-test :as mt]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "disk-art" (into-array FileAttribute []))))

(specification "DiskArtifactStore"
  (let [dir   (tmp-dir)
        store (disk/new-artifact-store dir)
        big   (apply str (repeat 50000 "x"))]
    (proto/write-artifact! store "s" "nodes/writer/0/turns/0/request.edn" big
      {:transcript/node-id :writer :transcript/visit 0 :transcript/turn 0 :artifact/class :captured-io})
    (proto/write-artifact! store "s" "artifacts/report.md" "# Report" {:artifact/class :author})

    (component "round-trip + walkable layout"
      (assertions
        "the full content round-trips with no truncation"
        (count (proto/read-artifact store "s" "nodes/writer/0/turns/0/request.edn")) => 50000
        "a path never written returns nil"
        (proto/read-artifact store "s" "nodes/none.edn") => nil
        "the captured blob is a real file at its walkable locator"
        (.isFile (io/file dir "nodes/writer/0/turns/0/request.edn")) => true
        "the author file lands under artifacts/"
        (.isFile (io/file dir "artifacts/report.md")) => true
        "no .tmp scratch file is left behind"
        (some #(str/ends-with? (.getName ^java.io.File %) ".tmp")
          (filter #(.isFile ^java.io.File %) (file-seq (io/file dir))))
        => nil))

    (component "list-artifacts reconstructs coordinates from the path"
      ;; A sibling session file (transcript) shares the session dir but is NOT an artifact this
      ;; store owns — list-artifacts must skip it (only artifacts/ and nodes/ count).
      (spit (io/file dir "transcript.jsonl") "{\"event\":\"x\"}\n")
      (let [items (proto/list-artifacts store "s")
            blob  (first (filter #(= "nodes/writer/0/turns/0/request.edn" (:artifact/path %)) items))
            auth  (first (filter #(= "artifacts/report.md" (:artifact/path %)) items))]
        (assertions
          "lists only owned artifacts, sorted by path — sibling session files are excluded"
          (mapv :artifact/path items) => ["artifacts/report.md" "nodes/writer/0/turns/0/request.edn"]
          "captured-I/O node/visit/turn are derived from the locator"
          (select-keys blob [:artifact/class :transcript/node-id :transcript/visit :transcript/turn])
          => {:artifact/class :captured-io :transcript/node-id :writer :transcript/visit 0 :transcript/turn 0}
          "author files are classed :author"
          (:artifact/class auth) => :author
          "size and content-type are reported"
          [(:artifact/size blob) (:artifact/content-type blob)] => [50000 "application/edn"])))))

(specification "DiskArtifactStore shared artifact contract"
  (mt/run-artifact-behaviors! #(disk/new-artifact-store (tmp-dir))))

(defn- old-artifact-path?
  "The listing rule of Escapement 1.0.5 and earlier, copied verbatim: only paths under `artifacts/`
   or `nodes/` are artifacts."
  [path]
  (or (str/starts-with? path "artifacts/")
    (str/starts-with? path "nodes/")))

(defn- relative-files
  "Every regular file under `dir`, as a sorted vector of paths relative to it."
  [dir]
  (let [root (.toPath (io/file dir))]
    (->> (file-seq (io/file dir))
      (filter #(.isFile ^java.io.File %))
      (mapv #(str (.relativize root (.toPath ^java.io.File %))))
      sort
      vec)))

(specification "DiskArtifactStore metadata sidecars"
  (component "a session written before sidecars existed"
    ;; The on-disk shape of a 1.0.5 session: content files only, no .meta/ tree.
    (let [dir   (tmp-dir)
          store (disk/new-artifact-store dir)]
      (doseq [[path content] {"artifacts/report.md"                 "# Report"
                              "nodes/my_node/0/turns/1/request.edn" "{:model \"m\"}"
                              "nodes/writer/2/seed.edn"             "{:params {}}"
                              "transcript.jsonl"                    "{}\n"}]
        (io/make-parents (io/file dir path))
        (spit (io/file dir path) content))
      (assertions
        "lists from the path alone, exactly as 1.0.5 did"
        (proto/list-artifacts store "s")
        => [{:artifact/path         "artifacts/report.md" :artifact/size  8
             :artifact/content-type "text/markdown"       :artifact/class :author}
            {:artifact/path         "nodes/my_node/0/turns/1/request.edn" :artifact/size    12
             :artifact/content-type "application/edn"                     :artifact/class   :captured-io
             :transcript/node-id    :my/node                              :transcript/visit 0            :transcript/turn 1}
            {:artifact/path         "nodes/writer/2/seed.edn" :artifact/size    12
             :artifact/content-type "application/edn"         :artifact/class   :captured-io
             :transcript/node-id    :writer                   :transcript/visit 2}]
        "reads content exactly as written"
        (proto/read-artifact store "s" "nodes/my_node/0/turns/1/request.edn") => "{:model \"m\"}")))

  (component "a session written by this version"
    (let [dir   (tmp-dir)
          store (disk/new-artifact-store dir)
          cap   {:store store :session-id "s" :node-id :my_node :visit 0}]
      (capture/capture-blob! cap 1 "request" {:model "m"} "hi")
      (capture/capture-seed! cap {:params {:model "m"}})
      (proto/write-artifact! store "s" "artifacts/report.md" "# Report" {:artifact/class :author :x/note "n"})
      (proto/write-artifact-bytes! store "s" "artifacts/shot.png" (byte-array mt/png-header)
        {:artifact/content-type "image/png"})
      (assertions
        "keeps each artifact's meta in .meta/<path>.edn"
        (edn/read-string (slurp (io/file dir ".meta/artifacts/report.md.edn")))
        => {:artifact/class :author :x/note "n"}
        "leaves the captured turn file byte-identical to the pr-str of the payload"
        (slurp (io/file dir "nodes/my_node/0/turns/1/request.edn")) => (pr-str {:model "m"})
        "replay reads the captured request back"
        (replay/load-request store "s" :my_node 0 1) => {:model "m"}
        "replay reads the captured seed back"
        (replay/load-seed store "s" :my_node 0) => {:params {:model "m"}}
        "reports the node-id the capture layer wrote, not the lossy path decoding"
        (:transcript/node-id (first (filter #(= "nodes/my_node/0/turns/1/request.edn" (:artifact/path %))
                                      (proto/list-artifacts store "s"))))
        => :my_node
        "seeds visit counts under the real node-id"
        (capture/seed-visit-counts store "s") => {:my_node 0}
        "the 1.0.5 listing rule sees exactly the content files, and no sidecar"
        (filterv old-artifact-path? (relative-files dir))
        => ["artifacts/report.md" "artifacts/shot.png"
            "nodes/my_node/0/seed.edn" "nodes/my_node/0/turns/1/request.edn"]
        "the current listing reports the same paths"
        (mapv :artifact/path (proto/list-artifacts store "s"))
        => ["artifacts/report.md" "artifacts/shot.png"
            "nodes/my_node/0/seed.edn" "nodes/my_node/0/turns/1/request.edn"]
        "the binary file on disk holds exactly the bytes written"
        (vec (Files/readAllBytes (.toPath (io/file dir "artifacts/shot.png")))) => mt/png-header)))

  (component "rewriting with empty meta"
    (let [dir   (tmp-dir)
          store (disk/new-artifact-store dir)]
      (proto/write-artifact! store "s" "artifacts/a.md" "v1" {:x/note "old"})
      (proto/write-artifact! store "s" "artifacts/a.md" "v2" {})
      (assertions
        "removes the sidecar"
        (.exists (io/file dir ".meta/artifacts/a.md.edn")) => false)))

  (component "an unreadable sidecar"
    (let [dir   (tmp-dir)
          store (disk/new-artifact-store dir)]
      (proto/write-artifact! store "s" "artifacts/a.md" "v1" {:artifact/class :author})
      (spit (io/file dir ".meta/artifacts/a.md.edn") "{:broken")
      (assertions
        "falls back to the path-derived summary"
        (first (proto/list-artifacts store "s"))
        => {:artifact/path         "artifacts/a.md" :artifact/size  2
            :artifact/content-type "text/markdown"  :artifact/class :author}))))
