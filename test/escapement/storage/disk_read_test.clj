(ns escapement.storage.disk-read-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as-alias sc]
    [escapement.protocols :as proto]
    [escapement.storage.disk-read :as dr]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "disk-read" (into-array FileAttribute []))))

(defn- write-session!
  "Write a session dir `<root>/<sid>/transcript.jsonl` from `rows` (maps serialized one-per-line)."
  [root sid rows]
  (let [f (io/file root sid "transcript.jsonl")]
    (io/make-parents f)
    (spit f (str (clojure.string/join "\n" (map json/generate-string rows)) "\n"))))

(specification "normalize-event"
  (assertions
    "lifts seq/ts and re-keywordizes the bare :event into :transcript/kind"
    (dissoc (dr/normalize-event {:event "runner/started" :ts 9 :seq 2 :data {:x 1}}) :transcript/data)
    => {:transcript/seq 2 :transcript/ts 9 :transcript/kind :runner/started}
    "keeps the full original :data under :transcript/data"
    (:transcript/data (dr/normalize-event {:event "x" :seq 0 :data {:x 1}})) => {:x 1}
    "surfaces :io/ref to the top level when the event data carries it"
    (:io/ref (dr/normalize-event {:event "llm/request" :seq 0 :data {:io/ref "nodes/w/0/turns/0/request.edn"}}))
    => "nodes/w/0/turns/0/request.edn"
    "surfaces :io/snippet to the top level when present"
    (:io/snippet (dr/normalize-event {:event "llm/response" :seq 0 :data {:io/snippet "hello"}}))
    => "hello"
    "omits :io/ref when the data has none"
    (contains? (dr/normalize-event {:event "x" :seq 0 :data {}}) :io/ref) => false))

(specification "->keyword"
  (assertions
    "drops a leading colon from a str'd keyword"
    (dr/->keyword ":escapement.runner/chart") => :escapement.runner/chart
    "passes through a plain qualified name"
    (dr/->keyword "llm/request") => :llm/request
    "returns nil for blank/nil"
    (dr/->keyword "") => nil
    (dr/->keyword nil) => nil))

(specification "events-xform"
  (let [rows [{:event "a" :seq 0} {:event "b" :seq 1} {:event "c" :seq 2}]]
    (assertions
      "with no query maps every row to a normalized event"
      (mapv :transcript/kind (into [] (dr/events-xform nil) rows)) => [:a :b :c]
      ":types keeps only the named kinds"
      (mapv :transcript/kind (into [] (dr/events-xform {:types #{:a :c}}) rows)) => [:a :c]
      ":from-seq drops events below the inclusive lower bound"
      (mapv :transcript/seq (into [] (dr/events-xform {:from-seq 1}) rows)) => [1 2]
      ":to-seq drops events above the inclusive upper bound"
      (mapv :transcript/seq (into [] (dr/events-xform {:to-seq 1}) rows)) => [0 1]
      ":limit caps the count after filtering"
      (count (into [] (dr/events-xform {:limit 2}) rows)) => 2
      ":node-id keeps only events whose :transcript/node-id matches"
      (into [] (dr/events-xform {:node-id :writer})
        [{:event "x" :seq 0 :data {}}]) => [])))

(specification "read-events* (real files)"
  (let [root (tmp-dir)]
    (write-session! root "s1"
      [{:event "runner/started" :ts 100 :seq 0 :data {:chart-id ":x/y"}}
       {:event "llm/request" :ts 101 :seq 1 :data {:io/ref "nodes/w/0/turns/0/request.edn"}}
       {:event "runner/done" :ts 102 :seq 2 :data {}}])
    (assertions
      "reads all events in :transcript/seq order"
      (mapv :transcript/seq (dr/read-events* root "s1" nil)) => [0 1 2]
      "applies the :types filter"
      (mapv :transcript/kind (dr/read-events* root "s1" {:types #{:llm/request}})) => [:llm/request]
      "surfaces :io/ref from a captured-I/O event"
      (:io/ref (first (dr/read-events* root "s1" {:types #{:llm/request}})))
      => "nodes/w/0/turns/0/request.edn"
      "returns nil for a session with no transcript"
      (dr/read-events* root "missing" nil) => nil
      ;; Regression: a session-id may arrive as a java.util.UUID (e.g. a live/active session), while
      ;; the on-disk directory is its string form. read-events* must coerce, not throw on io/file.
      "tolerates a java.util.UUID session-id (coerced to the string dir name)"
      (let [uid (java.util.UUID/randomUUID)]
        (write-session! root (str uid) [{:event "runner/started" :ts 1 :seq 0 :data {}}])
        (mapv :transcript/seq (dr/read-events* root uid nil))) => [0])))

(specification "list-sessions* (real files)"
  (let [root (tmp-dir)]
    (write-session! root "older"
      [{:event "runner/started" :ts 10 :seq 0 :data {:chart-id ":a/b" :resume? false}}
       {:event "runner/done" :ts 20 :seq 1 :data {}}])
    (write-session! root "newer"
      [{:event "runner/started" :ts 500 :seq 0 :data {:chart-id ":c/d" :resume? true}}])
    (io/make-parents (io/file root "not-a-session" "placeholder")) ;; dir without transcript.jsonl
    (let [sessions (dr/list-sessions* root)
          newer    (first (filter #(= "newer" (::sc/session-id %)) sessions))
          older    (first (filter #(= "older" (::sc/session-id %)) sessions))]
      (assertions
        "lists only dirs that contain a transcript.jsonl"
        (set (map ::sc/session-id sessions)) => #{"older" "newer"}
        "orders most-recently-started first"
        (mapv ::sc/session-id sessions) => ["newer" "older"]
        "derives the chart src as a clean keyword"
        (::sc/statechart-src older) => :a/b
        "marks a session :done once a runner/done row is present"
        (:session/status older) => :done
        "marks a session with no runner/done :incomplete"
        (:session/status newer) => :incomplete
        "captures started-at from the first row's ts"
        (:session/started-at newer) => 500
        "captures the resume? flag from runner/started"
        (:session/resume? newer) => true))))

(specification "MultiSessionDiskStore"
  (let [root  (tmp-dir)
        store (dr/new-store root)]
    (write-session! root "s1" [{:event "runner/started" :ts 1 :seq 0 :data {}}])
    (proto/write-artifact! store "s1" "artifacts/report.md" "# hi" {:artifact/class :author})
    (component "delegates ArtifactStore to a per-session dir under the root"
      (assertions
        "writes under <root>/<sid>/ and reads it back"
        (proto/read-artifact store "s1" "artifacts/report.md") => "# hi"
        "the file physically lands in the session dir"
        (.isFile (io/file root "s1" "artifacts/report.md")) => true
        "list-artifacts reports the author file"
        (mapv :artifact/path (proto/list-artifacts store "s1")) => ["artifacts/report.md"]))
    (component "is read-only with respect to the transcript"
      (assertions
        "append-event! throws"
        (proto/append-event! store "s1" {:event "x"}) =throws=> clojure.lang.ExceptionInfo))
    (component "serves the read protocols over the root"
      (assertions
        "read-events sees the session's rows"
        (mapv :transcript/kind (proto/read-events store "s1" nil)) => [:runner/started]
        "list-sessions enumerates the session"
        (mapv ::sc/session-id (proto/list-sessions store)) => ["s1"]))))
