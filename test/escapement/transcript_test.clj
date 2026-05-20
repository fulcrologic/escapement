(ns escapement.transcript-test
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [escapement.transcript :as transcript]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-file [name]
  (let [d (Files/createTempDirectory "tx-test" (into-array FileAttribute []))]
    (str d "/" name)))

(defn- read-jsonl [path]
  (with-open [r (io/reader path)]
    (mapv #(json/parse-string % true) (doall (line-seq r)))))

(specification "transcript sink — round trip"
  (let [path (tmp-file "rt.jsonl")
        sink (transcript/open-transcript {:path path})]
    (dotimes [i 100]
      (transcript/write! sink {:event :test/n :data {:i i}}))
    (transcript/close! sink)
    (let [rows (read-jsonl path)
          seqs (mapv :seq rows)
          tss  (mapv :ts rows)]
      (assertions
        "all 100 rows present" (count rows) => 100
        "seq is 0..99 monotonic" seqs => (vec (range 100))
        "ts is monotonic non-decreasing" (= tss (sort tss)) => true))))

(specification "transcript sink — unserializable values"
  (let [path (tmp-file "bad.jsonl")
        sink (transcript/open-transcript {:path path})]
    ;; a bare function is not JSON serializable
    (transcript/write! sink {:event :before})
    (transcript/write! sink {:event :bad :payload (fn [_] :nope)})
    (transcript/write! sink {:event :after})
    (transcript/close! sink)
    (let [rows (read-jsonl path)
          evs  (mapv :event rows)]
      (assertions
        "sink stayed usable across the bad write" (some #{"before"} evs) => "before"
        "an error marker was emitted" (some #{"transcript/serialize-error"} evs) => "transcript/serialize-error"
        "the after event made it" (some #{"after"} evs) => "after"))))

(specification "transcript sink — concurrent writers"
  (let [path     (tmp-file "concurrent.jsonl")
        sink     (transcript/open-transcript {:path path})
        n-thread 4
        per      250
        latch    (java.util.concurrent.CountDownLatch. n-thread)
        threads  (mapv
                   (fn [tid]
                     (Thread.
                       ^Runnable
                       (fn []
                         (dotimes [i per]
                           (transcript/write! sink {:event :hit :tid tid :i i}))
                         (.countDown latch))))
                   (range n-thread))]
    (doseq [^Thread t threads] (.start t))
    (.await latch 10 java.util.concurrent.TimeUnit/SECONDS)
    (transcript/close! sink)
    (let [rows (read-jsonl path)
          seqs (mapv :seq rows)]
      (assertions
        "exactly 1000 events" (count rows) => 1000
        "seq covers 0..999 with no gaps" (sort seqs) => (range 1000)
        "seqs strictly monotonic in file" (= seqs (sort seqs)) => true))))
