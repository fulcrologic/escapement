(ns escapement.storage.disk-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [escapement.protocols :as proto]
    [escapement.storage.disk :as disk]
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
      (let [items (proto/list-artifacts store "s")
            blob  (first (filter #(= "nodes/writer/0/turns/0/request.edn" (:artifact/path %)) items))
            auth  (first (filter #(= "artifacts/report.md" (:artifact/path %)) items))]
        (assertions
          "lists both artifacts, sorted by path"
          (mapv :artifact/path items) => ["artifacts/report.md" "nodes/writer/0/turns/0/request.edn"]
          "captured-I/O node/visit/turn are derived from the locator"
          (select-keys blob [:artifact/class :transcript/node-id :transcript/visit :transcript/turn])
          => {:artifact/class :captured-io :transcript/node-id :writer :transcript/visit 0 :transcript/turn 0}
          "author files are classed :author"
          (:artifact/class auth) => :author
          "size and content-type are reported"
          [(:artifact/size blob) (:artifact/content-type blob)] => [50000 "application/edn"])))))
