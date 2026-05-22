(ns escapement.engine.store-test
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts.protocols :as sp]
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

  (component "synchronous default writes through immediately (no flush needed)"
    (let [dir (tmp-dir)
          s   (store/new-store dir)]
      (sp/save-working-memory! s {} :sync {:v 1})
      (store/reload-from-disk! s :sync)
      (assertions
        "value is durable without any flush!"
        (sp/get-working-memory s {} :sync) => {:v 1}
        "flush!/close! are no-ops on a sync store (do not throw)"
        (do (store/flush! s) (store/close! s) :ok) => :ok)))

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

(specification "FileBackedStore write-behind mode"
  (component "coalesces: rapid saves do not write inline; flush! writes the latest"
    (let [dir (tmp-dir)
          ;; large flush-ms so the background flusher won't fire during the test
          s   (store/new-store dir {:write-behind? true :flush-ms 100000})]
      (dotimes [i 50] (sp/save-working-memory! s {} :wb {:gen i}))
      (assertions
        "in-process read sees the latest value (cache)"
        (sp/get-working-memory s {} :wb) => {:gen 49}
        "nothing written to disk yet (no inline write, flusher not fired)"
        (.exists (io/file dir "wb.edn")) => false)
      (store/flush! s)
      (store/reload-from-disk! s :wb)
      (assertions
        "after flush! the on-disk value is the LATEST (one coalesced write)"
        (sp/get-working-memory s {} :wb) => {:gen 49})
      (store/close! s)))

  (component "flush! makes the latest value durable across a simulated restart"
    (let [dir (tmp-dir)
          s   (store/new-store dir {:write-behind? true :flush-ms 100000})]
      (sp/save-working-memory! s {} :a {:v 1})
      (sp/save-working-memory! s {} :a {:v 2})
      (sp/save-working-memory! s {} :a {:v 3})
      (store/flush! s)
      (store/reload-from-disk! s :a)
      (assertions
        "latest value visible from disk"
        (sp/get-working-memory s {} :a) => {:v 3})
      (store/close! s)))

  (component "close! drains; a fresh sync store reads the latest from disk"
    (let [dir (tmp-dir)
          s   (store/new-store dir {:write-behind? true :flush-ms 100000})]
      (sp/save-working-memory! s {} :c {:final true})
      (store/close! s)
      (let [s2 (store/new-store dir)]
        (assertions
          "fresh store sees the closed store's last write"
          (sp/get-working-memory s2 {} :c) => {:final true}))))

  (component "background flusher eventually persists without an explicit flush!"
    (let [dir (tmp-dir)
          s   (store/new-store dir {:write-behind? true :flush-ms 25})]
      (sp/save-working-memory! s {} :bg {:auto true})
      ;; give the flusher a couple of ticks
      (Thread/sleep 200)
      (store/reload-from-disk! s :bg)
      (assertions
        "value persisted by the background flusher"
        (sp/get-working-memory s {} :bg) => {:auto true})
      (store/close! s)))

  (component "delete! clears cache, disk, and dirty set"
    (let [dir (tmp-dir)
          s   (store/new-store dir {:write-behind? true :flush-ms 100000})]
      (sp/save-working-memory! s {} :d {:x 1})
      (store/flush! s)
      (assertions "on disk pre-delete" (.exists (io/file dir "d.edn")) => true)
      ;; dirty it again, then delete before the flusher can write
      (sp/save-working-memory! s {} :d {:x 2})
      (sp/delete-working-memory! s {} :d)
      (store/flush! s)                         ; would re-write {:x 2} if still dirty
      (store/reload-from-disk! s :d)
      (assertions
        "file removed and not re-created by a stale dirty entry"
        (.exists (io/file dir "d.edn")) => false
        "cache miss after reload"
        (sp/get-working-memory s {} :d) => nil)
      (store/close! s))))
