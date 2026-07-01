(ns escapement.engine.store-test
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.queue :as queue]
    [escapement.engine.store :as store]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "store-test" (into-array FileAttribute []))))

(specification "FileBackedStore"
  (component "round-trips working memory via the canonical file"
    (let [dir (tmp-dir)
          s   (store/new-store dir)
          wm  {:com.fulcrologic.statecharts/configuration #{:a :b}
               :user/data                                 {:n 42}}]
      (sp/save-working-memory! s {} :s1 wm)
      ;; force a disk read by dropping cache
      (store/reload-from-disk! s :s1)
      (assertions
        "canonical file exists"
        (.exists (io/file dir "s1.edn")) => true
        "no .tmp file left behind"
        (.exists (io/file dir "s1.edn.tmp")) => false
        "reload returns the same value"
        (sp/get-working-memory s {} :s1) => wm)))

  (component "delete! removes the on-disk checkpoint"
    (let [dir (tmp-dir)
          s   (store/new-store dir)]
      (sp/save-working-memory! s {} :s2 {:x 1})
      (assertions "exists pre-delete" (.exists (io/file dir "s2.edn")) => true)
      (sp/delete-working-memory! s {} :s2)
      (assertions
        "file is removed" (.exists (io/file dir "s2.edn")) => false
        "cache miss returns nil after reload"
        (do (store/reload-from-disk! s :s2)
            (sp/get-working-memory s {} :s2)) => nil)))

  (component "combined record: persists the event queue atomically with working memory"
    (let [dir   (tmp-dir)
          s     (store/new-store dir)
          queue (queue/new-queue)
          env   {::sc/event-queue queue}
          wm    {::sc/configuration #{:run :polling} :user/data {:n 7}}]
      (sp/send! queue env {:event :poll/tick :target :s5 :delay 60000})
      (sp/save-working-memory! s env :s5 wm)
      (store/reload-from-disk! s :s5)
      (let [snap (store/get-queue-snapshot s :s5)]
        (assertions
          "get-working-memory returns the BARE working memory (no wrapper marker leaks)"
          (sp/get-working-memory s env :s5) => wm
          "the delayed event is recoverable from the checkpoint's queue snapshot"
          (get-in snap [:sessions :s5 0 :event :name]) => :poll/tick
          "the snapshot rehydrates a queue with the pending timer"
          (queue/pending-count (queue/queue-from-snapshot snap)) => 1))))

  (component "get-queue-snapshot is nil for a legacy bare-wmem checkpoint"
    (let [dir (tmp-dir)
          s   (store/new-store dir)]
      ;; Saving with an env that has NO queue writes a combined record whose queue slice is nil.
      (sp/save-working-memory! s {} :s6 {::sc/configuration #{:a}})
      (store/reload-from-disk! s :s6)
      (assertions
        "working memory still round-trips"
        (sp/get-working-memory s {} :s6) => {::sc/configuration #{:a}}
        "no queue snapshot is present when the env carried no queue"
        (store/get-queue-snapshot s :s6) => nil)))

  (component "retained history: save-index-keyed snapshots enable at-or-before lookup"
    (let [dir   (tmp-dir)
          s     (store/new-store dir {:retain-history? true})
          queue (queue/new-queue)
          env   {::sc/event-queue queue}]
      (sp/save-working-memory! s env :h {::sc/configuration #{:a}})
      (sp/send! queue env {:event :timer :target :h :delay 1000})
      (sp/save-working-memory! s env :h {::sc/configuration #{:b}})
      (sp/save-working-memory! s env :h {::sc/configuration #{:c}})
      (assertions
        "every save is retained under an ascending save-index"
        (store/list-history s :h) => [0 1 2]
        "read-checkpoint-at returns the exact retained working memory for a seq"
        (store/record->wmem (store/read-checkpoint-at s :h 1)) => {::sc/configuration #{:b}}
        "the retained snapshot carries the queue that was pending at that save"
        (get-in (store/record->queue-snapshot (store/read-checkpoint-at s :h 1))
          [:sessions :h 0 :event :name]) => :timer
        "an at-or-before lookup past the end resolves to the latest retained state"
        (store/record->wmem (store/read-checkpoint-at s :h 99)) => {::sc/configuration #{:c}}
        "a lookup before the first retained save finds nothing"
        (store/read-checkpoint-at s :h -1) => nil)))

  (component "history retention is off by default (no history dir written)"
    (let [dir (tmp-dir)
          s   (store/new-store dir)]
      (sp/save-working-memory! s {} :h2 {::sc/configuration #{:a}})
      (assertions
        "no retained history without opting in"
        (store/list-history s :h2) => []
        "read-checkpoint-at is nil when nothing was retained"
        (store/read-checkpoint-at s :h2 0) => nil)))

  (component "atomic checkpoint: a partial .tmp does not corrupt the canonical file"
    (let [dir (tmp-dir)
          s   (store/new-store dir)]
      (sp/save-working-memory! s {} :s3 {:gen 1})
      ;; Simulate a crash mid-write: leave a partial .tmp file beside the canonical one.
      (spit (io/file dir "s3.edn.tmp") "{:partial ")
      ;; Drop cache to force a fresh disk read.
      (store/reload-from-disk! s :s3)
      (assertions
        "canonical file still readable"
        (sp/get-working-memory s {} :s3) => {:gen 1})
      ;; A subsequent successful write atomically replaces the canonical file.
      (sp/save-working-memory! s {} :s3 {:gen 2})
      (store/reload-from-disk! s :s3)
      (assertions
        "new value visible after successful write"
        (sp/get-working-memory s {} :s3) => {:gen 2}))))
