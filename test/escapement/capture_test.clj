(ns escapement.capture-test
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [escapement.capture :as capture]
    [escapement.protocols :as proto]
    [escapement.storage.memory :as mem]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(specification "snippet"
  (assertions
    "returns short strings unchanged"
    (capture/snippet "hi") => "hi"
    "coerces nil to the empty string"
    (capture/snippet nil) => ""
    "coerces non-strings to their string form"
    (capture/snippet 12345) => "12345"
    "caps overflow at 80 chars total, ending in an ellipsis"
    (let [s (capture/snippet (apply str (repeat 200 "x")))]
      [(count s) (str/ends-with? s "…")]) => [80 true]))

(specification "turn-dir locator rendering"
  (assertions
    "renders a node-relative path for a simple node-id"
    (capture/turn-dir :writer 0 2) => "nodes/writer/0/turns/2"
    "encodes a namespaced node-id into a single path segment"
    (capture/turn-dir :a/b 1 0) => "nodes/a_b/1/turns/0"
    "renders a nil (top-level) node-id as ROOT"
    (capture/turn-dir nil 0 0) => "nodes/ROOT/0/turns/0"))

(specification "capture-blob! externalizes the full payload and returns a ref + snippet"
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :chat :visit 0}
        data  {:role :assistant :content [{:type :text :text (apply str (repeat 5000 "z"))}]}
        ref   (capture/capture-blob! cap 0 "response" data "zzz human preview")]
    (assertions
      "the ref locates the node-relative blob"
      (:io/ref ref) => "nodes/chat/0/turns/0/response.edn"
      "the snippet is the human text (distinct from the serialized payload)"
      (:io/snippet ref) => "zzz human preview"
      "the FULL payload round-trips from the store with no truncation"
      (edn/read-string (proto/read-artifact store "s" (:io/ref ref))) => data)))

(specification "capture-blob! routes a tool-result under tool-results/<id>"
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :chat :visit 0}
        ref   (capture/capture-blob! cap 3 "tool-results/u1" "big tool output" "big tool output")]
    (assertions
      "the locator nests the tool_use_id under the turn"
      (:io/ref ref) => "nodes/chat/0/turns/3/tool-results/u1.edn"
      "the tool output round-trips"
      (edn/read-string (proto/read-artifact store "s" (:io/ref ref))) => "big tool output")))

(specification "capture-request! is first-write-wins"
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :chat :visit 0}
        r1    (capture/capture-request! cap 0 {:system "base" :model "m1"} "user says hi")
        r2    (capture/capture-request! cap 0 {:system "CONTINUATION-PREFILL" :model "m2"} "user says hi")]
    (assertions
      "both calls return the same deterministic ref"
      (= (:io/ref r1) (:io/ref r2)) => true
      "the first (base) request is preserved; a later in-turn re-issue does not clobber it"
      (edn/read-string (proto/read-artifact store "s" (:io/ref r1)))
      => {:system "base" :model "m1"})))

(specification "capture-seed! stores the replayable seed for the invocation"
  (let [store (mem/new-store)
        cap   {:store store :session-id "s" :node-id :writer :visit 1}
        seed  {:params {:system "x" :model "m"} :initial-messages [{:role :user}]}]
    (capture/capture-seed! cap seed)
    (assertions
      "the seed lands at the invocation-relative seed.edn and round-trips"
      (edn/read-string (proto/read-artifact store "s" "nodes/writer/1/seed.edn")) => seed)))

(specification "seed-visit-counts derives the max prior visit per node"
  (component "with captured-io artifacts across several visits"
    (let [store (mem/new-store)
          cap   (fn [nid v] {:store store :session-id "s" :node-id nid :visit v})]
      (capture/capture-request! (cap :writer 0) 0 {:q 1} "w0")
      (capture/capture-request! (cap :writer 1) 0 {:q 1} "w1")
      (capture/capture-request! (cap :writer 2) 0 {:q 1} "w2")
      (capture/capture-request! (cap :judge 0) 0 {:q 1} "j0")
      (capture/capture-seed! (cap :judge 1) {:seed 1})
      (proto/write-artifact! store "s" "artifacts/notes.md" "hi" {:artifact/class :author})
      (let [counts (capture/seed-visit-counts store "s")]
        (assertions
          "returns the highest visit index seen for each node"
          counts => {:writer 2 :judge 1}
          "seeds the increment so the NEXT entry is max+1 (no collision at 0)"
          (get (swap! (atom counts) update :writer (fnil inc -1)) :writer) => 3
          "ignores author files (only captured-I/O carries a visit)"
          (contains? counts :author) => false))))
  (assertions
    "returns {} for a fresh session with no artifacts"
    (capture/seed-visit-counts (mem/new-store) "s") => {}))

(specification "capture-output! externalizes the idle/verdict output per turn"
  (let [store  (mem/new-store)
        cap    {:store store :session-id "s" :node-id :writer :visit 0}
        output {:text "the full assistant report, possibly many KB" :verdict {:status :ok} :from "writer"}
        ref    (capture/capture-output! cap 2 output)]
    (assertions
      "the locator is the per-turn output.edn blob"
      (:io/ref ref) => "nodes/writer/0/turns/2/output.edn"
      "the snippet is a ≤80-char slice of the assistant text"
      (:io/snippet ref) => "the full assistant report, possibly many KB"
      "the full output map (text + verdict + from) round-trips losslessly"
      (edn/read-string (proto/read-artifact store "s" (:io/ref ref))) => output)))
