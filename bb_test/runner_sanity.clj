(ns runner-sanity
  "Babashka sanity script for the runner + transcript + CLI plumbing.

  Defines a trivial self-firing chart inline, runs it through `escapement.runner/run!`,
  and asserts:
    - a transcript JSONL file was produced
    - it has at least 3 lines
    - it includes :runner/started and :runner/done events"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.runner :as runner]))

(def chart-def
  (chart/statechart
   {:initial :work}
   (state {:id :work :initial :idle}
          (state {:id :idle}
                 (transition {:target :done}))
          (final {:id :done}))))

(defn -main [& _]
  (let [tmp        (str (System/getProperty "java.io.tmpdir") "/dcch-runner-" (System/currentTimeMillis))
        transcript (str tmp "/run.jsonl")
        chk        (str tmp "/chk")
        summary    (runner/run! {:chart              chart-def
                                 :session-id         :runner-sanity/s1
                                 :transcript-path    transcript
                                 :checkpoint-dir     chk
                                 :max-iterations     500
                                 :quiescent-sleep-ms 10})
        exists?    (.exists (io/file transcript))
        lines      (when exists? (str/split-lines (slurp transcript)))]
    (cond
      (not exists?)
      (do (println "FAIL: transcript file not produced at" transcript)
          (System/exit 1))

      (< (count lines) 3)
      (do (println "FAIL: transcript has fewer than 3 lines:" (count lines))
          (System/exit 1))

      (not (some #(str/includes? % "runner/started") lines))
      (do (println "FAIL: no runner/started event")
          (System/exit 1))

      (not (some #(str/includes? % "runner/done") lines))
      (do (println "FAIL: no runner/done event")
          (System/exit 1))

      :else
      (do (println "PASS: bb runner sanity —" (count lines) "transcript lines, final-config="
                   (:final-config summary))
          (System/exit 0)))))

(-main)
