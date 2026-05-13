(ns escapement.engine.store-test
  (:require
   [clojure.java.io :as io]
   [com.fulcrologic.statecharts.protocols :as sp]
   [escapement.engine.store :as store]
   [fulcro-spec.core :refer [specification assertions component =>]])
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
