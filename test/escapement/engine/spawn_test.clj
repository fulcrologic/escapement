(ns escapement.engine.spawn-test
  "Integration test for `escapement.engine.spawn/spawn-child!` driven through
   `runner/run!` with `:multi-session? true`. Asserts that a parent chart can
   spawn a sibling session at runtime, that the runner pumps both sessions
   from the same loop, that the child's reply event reaches the parent (so
   it can transition to final), and that the transcript carries
   `:session/spawned` plus per-event `:session-id`."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.spawn :as spawn]
    [escapement.runner :as runner]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [p]
  (str (Files/createTempDirectory p (into-array FileAttribute []))))

(defn- read-rows [path]
  (mapv #(json/parse-string % true) (str/split-lines (slurp path))))

(def child-chart
  (chart/statechart
    {:initial :work}
    (state {:id :work}
      (on-entry {}
        (script
          {:expr
           (fn [env data]
             (let [queue  (get env ::sc/event-queue)
                   my-sid (some-> env ::sc/vwmem deref ::sc/session-id)
                   parent (:reply-to data)]
               (sp/send! queue env
                 {:target            parent
                  :source-session-id my-sid
                  :event             :child/done
                  :data              {:from (str my-sid)}})
               nil))}))
      (transition {:event :_done :target :finished}))
    (final {:id :finished})))

(def parent-chart
  (chart/statechart
    {:initial :spawning}
    (state {:id :spawning}
      (on-entry {}
        (script
          {:expr
           (fn [env _data]
             (spawn/spawn-child!
               env
               {:chart    child-chart
                :chart-id ::spawn-test-child
                :input    {:reply-to (spawn/parent-sid env)}})
             nil)}))
      (transition {:event :child/done :target :done}))
    (final {:id :done})))

(specification "spawn-child! + :multi-session? — runner pumps both sessions, child reply lands"
  (let [dir   (tmp-dir "spawn-multi-")
        tpath (str dir "/t.jsonl")
        cdir  (str dir "/chk")
        result (runner/run!
                 {:chart              parent-chart
                  :session-id         :spawn-test/parent
                  :transcript-path    tpath
                  :checkpoint-dir     cdir
                  :max-iterations     500
                  :quiescent-sleep-ms 5
                  :multi-session?     true})
        rows  (read-rows tpath)
        evs   (map :event rows)]
    (assertions
      "parent run terminated normally"
      (:status result) => :done

      "transcript has a :session/spawned row"
      (boolean (some #{"session/spawned"} evs)) => true

      "spawned row carries parent-sid, child-sid, chart-id"
      (let [r (first (filter #(= "session/spawned" (:event %)) rows))]
        (every? some? [(get-in r [:data :parent-sid])
                       (get-in r [:data :child-sid])
                       (get-in r [:data :chart-id])])) => true

      "the parent processed the child's :child/done reply"
      (boolean
        (some #(and (= "runner/event-processed" (:event %))
                    (= "child/done" (get-in % [:data :event-name])))
          rows)) => true

      "every :runner/event-processed row carries :session-id under multi-session"
      (every? (fn [r] (some? (get-in r [:data :session-id])))
        (filter #(= "runner/event-processed" (:event %)) rows)) => true)))
