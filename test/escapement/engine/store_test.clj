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

(specification "node-entry checkpoints"
  (component "save/read by {node-id visit} round-trips and does not touch the canonical file"
    (let [dir (tmp-dir)
          s   (store/new-store dir)
          wm  {:com.fulcrologic.statecharts/configuration #{:conv}
               :user/data {:n 1}}]
      (sp/save-working-memory! s {} "sess" {:com.fulcrologic.statecharts/configuration #{:latest}})
      (store/save-node-entry-checkpoint! s "sess" "node-A" 0 wm)
      (assertions
        "node-entry snapshot reads back"
        (store/node-entry-checkpoint s "sess" "node-A" 0) => wm
        "missing visit returns nil"
        (store/node-entry-checkpoint s "sess" "node-A" 1) => nil
        "canonical session checkpoint is untouched"
        (sp/get-working-memory s {} "sess")
        => {:com.fulcrologic.statecharts/configuration #{:latest}})))

  (component "resolve-node-entry-wmem prefers the node-entry snapshot, falls back to latest"
    (let [dir (tmp-dir)
          s   (store/new-store dir)
          ne  {:com.fulcrologic.statecharts/configuration #{:entry}}
          lat {:com.fulcrologic.statecharts/configuration #{:latest}}]
      (sp/save-working-memory! s {} "sess" lat)
      (store/save-node-entry-checkpoint! s "sess" "node-A" 0 ne)
      (assertions
        "node-entry source when snapshot present"
        (store/resolve-node-entry-wmem s {} "sess" "node-A" 0)
        => {:wmem ne :source :node-entry}
        "latest fallback when no snapshot"
        (store/resolve-node-entry-wmem s {} "sess" "node-B" 0)
        => {:wmem lat :source :latest})))

  (component "checkpoints round-trip a non-namespaced digit-leading session keyword"
    ;; Regression: the CLI's old session-id `(keyword \"session\" <uuid>)` produced
    ;; `:session/<uuid>` — a NAMESPACED keyword whose name starts with a digit,
    ;; which `pr-str` writes but EDN cannot read back (\"Invalid token\"). The
    ;; engine stamps `::sc/session-id` into working memory, so the debugger's
    ;; branch fork crashed reading the node-entry checkpoint. Session-ids are now
    ;; non-namespaced (`:session-<uuid>`), which round-trips cleanly even with a
    ;; digit-leading uuid embedded in the persisted wmem.
    (let [dir (tmp-dir)
          s   (store/new-store dir)
          sid (keyword (str "session-" "3d78dd3f-fbee-43dc-81aa-84d762c3ae62"))
          wm  {:com.fulcrologic.statecharts/configuration #{:writer}
               :com.fulcrologic.statecharts/session-id    sid
               :data-model                                {:owner sid}}]
      (sp/save-working-memory! s {} sid wm)
      (store/save-node-entry-checkpoint! s sid "poet-1" 0 wm)
      (store/reload-from-disk! s sid)
      (assertions
        "node-entry checkpoint reads back (no Invalid-token crash)"
        (store/node-entry-checkpoint s sid "poet-1" 0) => wm
        "canonical checkpoint reads back the embedded session keyword"
        (sp/get-working-memory s {} sid) => wm))))
