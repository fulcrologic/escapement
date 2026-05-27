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
        {:transcript/node-id :chat :transcript/visit 0 :transcript/turn 0
         :artifact/class :captured-io})
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

(specification "MemoryStore (in-memory backend)"
  (run-store-behaviors! mem/new-store mem/merge-session-summary!))
