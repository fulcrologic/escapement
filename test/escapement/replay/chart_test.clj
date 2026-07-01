(ns escapement.replay.chart-test
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as queue]
    [escapement.engine.store :as store]
    [escapement.replay.chart :as fork]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "fork-test" (into-array FileAttribute []))))

(def dm-key :com.fulcrologic.statecharts.data-model.working-memory-data-model/data-model)

(def two-step
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :a}
      (state {:id :a} (transition {:event :go :target :b}))
      (state {:id :b} (transition {:event :go :target :done}))
      (final {:id :done}))))

(defn- source-run!
  "Run the two-step chart to quiescence at :a (no events), retaining history, and return its store."
  [dir]
  (let [store (store/new-store (str dir "/chk") {:retain-history? true})]
    (runner/run! {:chart two-step :session-id :src/session
                  :transcript-path (str dir "/t.jsonl") :checkpoint-dir (str dir "/chk")
                  :session-dir dir :store store :quiescent-sleep-ms 5})
    store))

(specification "fork! re-enters a chart from history and runs forward in an isolated session"
  (component "forks at a retained checkpoint, injects an event, and substitutes a datum"
    (let [dir      (tmp-dir)
          src      (source-run! dir)
          fork-dir (tmp-dir)
          res      (fork/fork! {:source-store src :source-session-id :src/session :at-seq 0
                                :chart two-step :fork-session-id :fork/session
                                :fork-checkpoint-dir (str fork-dir "/chk")
                                :fork-session-dir fork-dir
                                :fork-transcript-path (str fork-dir "/t.jsonl")
                                :resume-events [{:event :go}]
                                :transform-wmem #(assoc-in % [dm-key :experiment] :variant-B)
                                :quiescent-sleep-ms 5})
          src-wmem  (sp/get-working-memory src {} :src/session)
          fork-wmem (sp/get-working-memory (store/new-store (str fork-dir "/chk")) {} :fork/session)]
      (assertions
        "the fork ran forward: the injected :go advanced :a → :b"
        (::sc/configuration fork-wmem) => #{:run :b}
        "the SOURCE session is untouched — still parked at :a"
        (::sc/configuration src-wmem) => #{:run :a}
        "the fork owns a distinct session identity (embedded session-id rewritten)"
        (::sc/session-id fork-wmem) => :fork/session
        "the data-model's :_sessionid is rewritten to the fork's too"
        (:_sessionid (get fork-wmem dm-key)) => :fork/session
        "the boundary transform substituted a data-model value into the fork only"
        (get-in fork-wmem [dm-key :experiment]) => :variant-B
        "the source data-model carries no such experiment value"
        (get-in src-wmem [dm-key :experiment]) => nil
        "the summary reports the fork identity + point"
        (select-keys res [:fork-session-id :at-seq]) => {:fork-session-id :fork/session :at-seq 0})))

  (component "a restored timer's session back-references are all retargeted to the fork"
    ;; Seed a source checkpoint whose pending timer carries the source session id on
    ;; :target, :origin, AND ::sc/source-session-id (as a real chart-armed send does).
    ;; Fork with :cancel already true so the runner aborts right after restore, leaving
    ;; the future-dated timer pending — then assert every back-reference points at the fork.
    (let [dir      (tmp-dir)
          src      (store/new-store (str dir "/chk") {:retain-history? true})
          q        (queue/new-queue)]
      (sp/send! q {} {:event :poll/tick :target :src/session :source-session-id :src/session :delay 60000})
      (sp/save-working-memory! src {::sc/event-queue q} :src/session
        {::sc/configuration #{:run :a}})
      (let [fork-dir (tmp-dir)
            _        (fork/fork! {:source-store src :source-session-id :src/session :at-seq 0
                                  :chart two-step :fork-session-id :fork/session
                                  :fork-checkpoint-dir (str fork-dir "/chk")
                                  :fork-session-dir fork-dir
                                  :fork-transcript-path (str fork-dir "/t.jsonl")
                                  :cancel (atom true) :quiescent-sleep-ms 5})
            ev       (get-in (store/get-queue-snapshot (store/new-store (str fork-dir "/chk")) :fork/session)
                       [:sessions :fork/session 0 :event])]
        (assertions
          "the restored timer's :target is the fork session (delivers to the fork)"
          (:target ev) => :fork/session
          "its :origin is the fork session (no source back-reference)"
          (:origin ev) => :fork/session
          "its ::sc/source-session-id is the fork session"
          (::sc/source-session-id ev) => :fork/session))))

  (component "throws when the fork point is unreachable (no retained checkpoint at-or-before)"
    (let [dir (tmp-dir)
          src (source-run! dir)]
      (assertions
        "an unreachable fork point is a clear error, not a silent empty run"
        (fork/fork! {:source-store src :source-session-id :src/session :at-seq -1
                     :chart two-step :fork-session-id :fork/session
                     :fork-checkpoint-dir (str (tmp-dir) "/chk")
                     :fork-session-dir (tmp-dir)
                     :fork-transcript-path (str (tmp-dir) "/t.jsonl")})
        =throws=> clojure.lang.ExceptionInfo))))
