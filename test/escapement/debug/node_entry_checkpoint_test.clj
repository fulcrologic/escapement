(ns escapement.debug.node-entry-checkpoint-test
  "C2 wiring test: a REAL `runner/run!` pump over a chart with an llm-conversation
   node must tag a node-entry checkpoint at the conversation node's {node-id,
   visit} — the snapshot `escapement.debug.branch/fork-session!` reseeds a branch
   from. Before this was wired, `save-node-entry-checkpoint!` was only ever called
   from tests, so a live fork always fell back to the (wrong) :latest checkpoint."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry send state transition]]
    [clojure.string]
    [escapement.capture :as capture]
    [escapement.chart.helpers :as h]
    [escapement.engine.store :as store]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]]
    [com.fulcrologic.statecharts.promise :as p])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "node-entry-ck" (into-array FileAttribute []))))

(defrecord MockBackend [responses]
  llm/LLMBackend
  (send-turn [_ _request]
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; `:warmup` fires a delayed self-event so the conversation node `:talk` is
;; entered by a real EVENT (a checkpoint is persisted first) — mirroring real
;; charts that plan/route before conversing. Without the prior checkpoint, a
;; node entered on the initial macrostep has no pre-entry wmem to snapshot.
(def ^:private talk-chart
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi"})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

(specification "runner pump writes a node-entry checkpoint at the conversation node"
  (let [dir        (tmp-dir)
        sid        "neck-1"
        sdir       (str dir "/" sid)
        chk        (str sdir "/checkpoints")
        _          (.mkdirs (io/file chk))]
    (runner/run! {:chart              talk-chart
                  :session-id         sid
                  :transcript-path    (str sdir "/transcript.jsonl")
                  :checkpoint-dir     chk
                  :session-dir        sdir
                  :backend            (->MockBackend (ts/queue []))
                  :tool-registry      (tp/new-registry)
                  :quiescent-sleep-ms 5})
    (let [ne-dir  (io/file chk "node-entries" sid)
          files   (some-> ne-dir .listFiles seq)
          one     (first (filter #(.endsWith (.getName ^java.io.File %) "__0.edn") files))
          pstore  (store/new-store chk)
          ;; node-id encodes as `:talk` (the state owning the invocation); the
          ;; file is `<safe(:talk)>__0.edn`. resolve via the same coordinates.
          resolved (store/resolve-node-entry-wmem pstore {} sid :talk 0)]
      (assertions
        "a node-entry checkpoint file was written for visit 0"
        (boolean one) => true
        "it round-trips to a working memory whose configuration CONTAINS the conversation node :talk (post-entry, re-invokable)"
        (let [wmem (read-string (slurp one))]
          (contains? (::sc/configuration wmem #{}) :talk)) => true
        "resolve-node-entry-wmem finds it via {node-id :talk, visit 0} (source :node-entry)"
        (:source resolved) => :node-entry
        "the resolved snapshot's configuration contains :talk (forkable into the node)"
        (contains? (::sc/configuration (:wmem resolved) #{}) :talk) => true
        "the checkpoint's {node-id, visit} (talk/0) matches the capture layer's nodes/<node-id>/<visit>/ coordinates"
        ;; The capture layer writes turns under `nodes/talk/0/turns/…` (see
        ;; escapement.capture/turn-dir); the node-entry file is `talk__0.edn`.
        ;; Same `talk` segment + same `0` visit ⇒ a UI conversation selection
        ;; maps 1:1 to the restorable snapshot.
        (let [parts (clojure.string/split (capture/turn-dir :talk 0 0) #"/")]
          [(nth parts 1) (nth parts 2)]) => ["talk" "0"]
        "and the node-entry checkpoint file is named with that same segment+visit"
        (.getName ^java.io.File one) => "talk__0.edn"))))
