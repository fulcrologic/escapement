(ns escapement.storage.memory-test
  "Behavioral coverage for the in-memory backend, expressed through a backend-agnostic
   `run-store-behaviors!` runner. The disk and browser backends reuse the same runner (the
   `io-layer-testing` pattern) so all backends are held to identical protocol semantics."
  (:require
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.protocols :as proto]
    [escapement.storage.memory :as mem]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defn run-store-behaviors!
  "Exercise every protocol behavior against a backend produced by the 0-arg `new-store` factory.
   `summarize!` is a 3-arg fn `(store session-id summary-map)` registering session summary fields
   (the in-memory store's `merge-session-summary!`; disk derives these from the session dir)."
  [new-store summarize!]
  (component "TranscriptStore/append-event!"
    (let [s (new-store)
          e0 (proto/append-event! s "s1" {:transcript/kind "runner/started"})
          e1 (proto/append-event! s "s1" {:transcript/kind "llm/request"})
          o0 (proto/append-event! s "s2" {:transcript/kind "runner/started"})]
      (assertions
        "assigns a gapless per-session seq starting at 0"
        [(:transcript/seq e0) (:transcript/seq e1)] => [0 1]
        "returns the stored event carrying its assigned seq"
        (:transcript/kind e1) => "llm/request"
        "numbers each session independently"
        (:transcript/seq o0) => 0)))

  (component "TranscriptStore/read-events"
    (let [s (new-store)]
      (proto/append-event! s "s1" {:transcript/kind "runner/started" :transcript/node-id :ROOT})
      (proto/append-event! s "s1" {:transcript/kind "llm/request" :transcript/node-id :chat})
      (proto/append-event! s "s1" {:transcript/kind "llm/response" :transcript/node-id :chat})
      (assertions
        "returns all events in seq order when query is nil"
        (mapv :transcript/seq (proto/read-events s "s1" nil)) => [0 1 2]
        "returns all events when query is empty"
        (count (proto/read-events s "s1" {})) => 3
        "filters by :types"
        (mapv :transcript/kind (proto/read-events s "s1" {:types #{"llm/request"}})) => ["llm/request"]
        "filters by :node-id"
        (mapv :transcript/seq (proto/read-events s "s1" {:node-id :chat})) => [1 2]
        "filters by an inclusive :from-seq lower bound"
        (mapv :transcript/seq (proto/read-events s "s1" {:from-seq 1})) => [1 2]
        "filters by an inclusive :to-seq upper bound"
        (mapv :transcript/seq (proto/read-events s "s1" {:to-seq 1})) => [0 1]
        "caps the result at :limit"
        (count (proto/read-events s "s1" {:limit 2})) => 2)))

  (component "ArtifactStore round-trip (the capture contract)"
    (let [s       (new-store)
          big     (apply str (repeat 50000 "x"))     ; far past the old 8192 truncation cap
          locator "nodes/chat/0/turns/0/request.json"]
      (proto/write-artifact! s "s1" locator big
        {:transcript/node-id :chat        :transcript/visit 0 :transcript/turn 0
         :artifact/class     :captured-io})
      (proto/write-artifact! s "s1" "artifacts/report.md" "# Report"
        {:artifact/class :author})
      (assertions
        "read-artifact returns the FULL content with no truncation"
        (count (proto/read-artifact s "s1" locator)) => 50000
        "read-artifact returns nil for a path never written"
        (proto/read-artifact s "s1" "nodes/none") => nil)
      (let [items (proto/list-artifacts s "s1")
            blob  (first (filter #(= locator (:artifact/path %)) items))]
        (assertions
          "list-artifacts returns one summary per stored artifact, sorted by path"
          (mapv :artifact/path items) => ["artifacts/report.md" locator]
          "a captured-I/O summary carries its node/visit/turn coordinates"
          (select-keys blob [:transcript/node-id :transcript/visit :transcript/turn])
          => {:transcript/node-id :chat :transcript/visit 0 :transcript/turn 0}
          "a summary carries class and byte size"
          [(:artifact/class blob) (:artifact/size blob)] => [:captured-io 50000]
          "a summary infers content-type from the path suffix"
          (:artifact/content-type blob) => "application/json"
          "a summary omits the heavy content body"
          (contains? blob :artifact/content) => false))))

  (component "SessionIndex/list-sessions"
    (let [s (new-store)]
      (proto/append-event! s "s1" {:transcript/kind "runner/started"})
      (proto/append-event! s "s2" {:transcript/kind "runner/started"})
      (summarize! s "s1" {::sc/statechart-src :my/chart :session/status :running})
      (let [by-id (into {} (map (juxt ::sc/session-id identity)) (proto/list-sessions s))]
        (assertions
          "reports one summary per session that has activity"
          (set (keys by-id)) => #{"s1" "s2"}
          "carries registered summary fields under the library's session-id"
          (select-keys (by-id "s1") [::sc/statechart-src :session/status])
          => {::sc/statechart-src :my/chart :session/status :running}))))

  (component "WorkingMemoryStore"
    (let [s  (new-store)
          wm {::sc/configuration #{:a :b} :user/data {:n 42}}]
      (sp/save-working-memory! s {} "s1" wm)
      (assertions
        "get returns the saved working memory"
        (sp/get-working-memory s {} "s1") => wm)
      (sp/delete-working-memory! s {} "s1")
      (assertions
        "get returns nil after delete"
        (sp/get-working-memory s {} "s1") => nil))))

(def png-header
  "The 8-byte PNG signature: non-UTF-8 bytes (0x89, 0x1A) that a string round-trip would corrupt."
  [-119 80 78 71 13 10 26 10])

(defn run-artifact-behaviors!
  "Exercise the meta and binary contract of `ArtifactStore` against a store produced by the 0-arg
   `new-store` factory. Shared by every backend so they persist meta and bytes identically."
  [new-store]
  (component "ArtifactStore meta round-trip"
    (let [s       (new-store)
          path    "nodes/my_node/0/turns/1/request.edn"
          meta    {:transcript/node-id    :my_node
                   :transcript/visit      0
                   :transcript/turn       1
                   :artifact/class        :captured-io
                   :artifact/content-type "text/x-custom"
                   :x/note                "kept"}
          written (proto/write-artifact! s "s1" path "{:a 1}" meta)
          listed  (first (proto/list-artifacts s "s1"))]
      (assertions
        "list-artifacts reports every meta key the caller wrote"
        (select-keys listed (keys meta)) => meta
        "write-artifact! returns the same summary list-artifacts reports"
        written => listed
        "the summary carries the stored path and its size"
        [(:artifact/path listed) (:artifact/size listed)] => [path 6])))

  (component "meta that names a path or size"
    (let [s (new-store)]
      (proto/write-artifact! s "s1" "artifacts/a.md" "abc" {:artifact/path "elsewhere" :artifact/size 999})
      (assertions
        "is overridden by the stored path and actual size"
        (select-keys (first (proto/list-artifacts s "s1")) [:artifact/path :artifact/size])
        => {:artifact/path "artifacts/a.md" :artifact/size 3})))

  (component "rewriting an artifact"
    (let [s (new-store)]
      (proto/write-artifact! s "s1" "artifacts/a.md" "v1" {:artifact/class :author :x/note "old"})
      (proto/write-artifact! s "s1" "artifacts/a.md" "v2" {:artifact/class :author})
      (assertions
        "replaces the prior meta wholesale"
        (:x/note (first (proto/list-artifacts s "s1"))) => nil
        "replaces the content"
        (proto/read-artifact s "s1" "artifacts/a.md") => "v2")))

  (component "string size"
    (let [s (new-store)]
      (proto/write-artifact! s "s1" "artifacts/u.txt" "héllo" {})
      (assertions
        "is the UTF-8 byte count, not the character count"
        (:artifact/size (first (proto/list-artifacts s "s1"))) => 6)))

  (component "binary content"
    (let [s       (new-store)
          png     (byte-array png-header)
          written (proto/write-artifact-bytes! s "s1" "artifacts/shot.png" png {:artifact/class :author})
          _       (proto/write-artifact-bytes! s "s1" "artifacts/typed.png" png
                    {:artifact/content-type "image/png"})
          _       (proto/write-artifact! s "s1" "artifacts/text.md" "héllo" {})
          _       (proto/write-artifact-bytes! s "s1" "artifacts/ascii.txt" (.getBytes "plain" "UTF-8") {})
          by-path (into {} (map (juxt :artifact/path identity)) (proto/list-artifacts s "s1"))]
      (assertions
        "read-artifact-bytes returns exactly the bytes written"
        (vec (proto/read-artifact-bytes s "s1" "artifacts/shot.png")) => png-header
        "the size is the byte count"
        (:artifact/size (by-path "artifacts/shot.png")) => 8
        "write-artifact-bytes! returns the listed summary"
        written => (by-path "artifacts/shot.png")
        "an untyped binary artifact lists as application/octet-stream"
        (:artifact/content-type (by-path "artifacts/shot.png")) => "application/octet-stream"
        "a caller-supplied content-type is kept"
        (:artifact/content-type (by-path "artifacts/typed.png")) => "image/png"
        "read-artifact-bytes on a string artifact returns its UTF-8 encoding"
        (vec (proto/read-artifact-bytes s "s1" "artifacts/text.md")) => (vec (.getBytes "héllo" "UTF-8"))
        "read-artifact on a byte artifact decodes it as UTF-8"
        (proto/read-artifact s "s1" "artifacts/ascii.txt") => "plain"
        "read-artifact-bytes returns nil for a path never written"
        (proto/read-artifact-bytes s "s1" "artifacts/none") => nil))))

(specification "MemoryStore (in-memory backend)"
  (run-store-behaviors! mem/new-store mem/merge-session-summary!)
  (run-artifact-behaviors! mem/new-store))
