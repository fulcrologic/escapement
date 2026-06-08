(ns escapement.debug.branch-test
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.debug.branch :as branch]
    [escapement.engine.store :as store]
    [fulcro-spec.core :refer [=> assertions component specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "branch-test" (into-array FileAttribute []))))

(defn- file-bytes [f]
  (when (.exists (io/file f)) (slurp f)))

(specification "fork-session!"
  (component "forks a new branch seeded from the parent's node-entry checkpoint, parent untouched"
    (let [work-dir   (tmp-dir)
          parent-id  "parent-1"
          parent-sd  (str work-dir "/" parent-id)
          parent-ck  (str parent-sd "/checkpoints")
          _          (.mkdirs (io/file parent-ck))
          pstore     (store/new-store parent-ck)
          latest     {::sc/configuration #{:later-state} :user/data {:n 99}}
          entry-wmem {::sc/configuration #{:conv-node}    :user/data {:n 1}}
          bp         {:node-id "node-A" :visit 0 :turn 2}]
      ;; Parent has a latest checkpoint AND an explicit node-entry checkpoint.
      (sp/save-working-memory! pstore {} parent-id latest)
      (store/save-node-entry-checkpoint! pstore parent-id "node-A" 0 entry-wmem)
      ;; Snapshot parent files so we can assert immutability.
      (let [parent-canon-before (file-bytes (str parent-ck "/" parent-id ".edn"))
            parent-node-before  (file-bytes (str parent-ck "/node-entries/" parent-id "/node-A__0.edn"))
            result (branch/fork-session!
                     {:parent-session-id parent-id
                      :branch-point      bp
                      :work-dir          work-dir})]
        (assertions
          "seed came from the node-entry snapshot"
          (:seed-source result) => :node-entry
          "branch dir created"
          (.exists (io/file (:session-dir result))) => true
          "branch transcript path is under the branch dir"
          (.startsWith ^String (:transcript-path result) ^String (:session-dir result)) => true
          "branch parentage recorded"
          (:parent result) => parent-id
          "branch checkpoint seeded with the parent's node-entry wmem, REKEYED to the branch session-id (so re-invoked events route to the branch, not the parent)"
          (let [bstore (store/new-store (:checkpoint-dir result))]
            (sp/get-working-memory bstore {} (:branch-id result)))
          => (assoc entry-wmem ::sc/session-id (:branch-id result))
          "branch.edn parentage metadata persisted"
          (let [p (branch/read-parentage (:session-dir result))]
            [(:parent p) (:branch-point p) (:seed-source p) (number? (:created-at p))])
          => [parent-id bp :node-entry true]
          "PARENT canonical checkpoint unchanged"
          (file-bytes (str parent-ck "/" parent-id ".edn")) => parent-canon-before
          "PARENT node-entry checkpoint unchanged"
          (file-bytes (str parent-ck "/node-entries/" parent-id "/node-A__0.edn")) => parent-node-before
          "parent's latest checkpoint value still intact"
          (sp/get-working-memory (store/new-store parent-ck) {} parent-id) => latest))))

  (component "falls back to the parent's latest checkpoint when no node-entry snapshot exists"
    (let [work-dir  (tmp-dir)
          parent-id "parent-2"
          parent-ck (str work-dir "/" parent-id "/checkpoints")
          _         (.mkdirs (io/file parent-ck))
          pstore    (store/new-store parent-ck)
          latest    {::sc/configuration #{:running} :user/data {}}]
      (sp/save-working-memory! pstore {} parent-id latest)
      (let [result (branch/fork-session!
                     {:parent-session-id parent-id
                      :branch-point      {:node-id "node-Z" :visit 0 :turn 0}
                      :work-dir          work-dir
                      :env               {}})]
        (assertions
          "fell back to latest checkpoint"
          (:seed-source result) => :latest
          "branch seeded with latest wmem, rekeyed to the branch session-id"
          (sp/get-working-memory (store/new-store (:checkpoint-dir result)) {} (:branch-id result))
          => (assoc latest ::sc/session-id (:branch-id result))))))

  (component "multi-session: seeds from a CHILD sub-chart session-id looked up via the colon-less wire form, surfacing its statechart-src"
    ;; Regression for the OpenTUI \"No checkpoint to seed branch from\" failure
    ;; on a poet/judge re-run. The conversation lived in a sub-chart CHILD session
    ;; whose node-entry checkpoint was saved under the keyword id
    ;; `:multiplex.poets.4` (str `:multiplex.poets.4`), but the sidecar re-run
    ;; sends the colon-LESS wire form `multiplex.poets.4`. `safe-segment` now
    ;; strips the leading colon so both collapse to the same node-entry path.
    (let [work-dir   (tmp-dir)
          ;; The parent dirs are the ROOT session's (the child has no own dir);
          ;; the node-entry checkpoint is keyed INSIDE them by the child id.
          root-id    "6207c655"
          root-ck    (str work-dir "/" root-id "/checkpoints")
          _          (.mkdirs (io/file root-ck))
          pstore     (store/new-store root-ck)
          child-kw   :multiplex.poets.4                 ; saved under the KEYWORD form
          wire-sid   "multiplex.poets.4"                ; re-run sends the colon-less form
          entry-wmem {::sc/configuration #{:compose-route}
                      ::sc/statechart-src :escapement.examples.haiku-tournament-dynamic/poet
                      :user/data {:idx 4}}
          bp         {:node-id ":haiku-1" :visit 3 :turn 0}]
      (store/save-node-entry-checkpoint! pstore child-kw "haiku-1" 3 entry-wmem)
      (let [result (branch/fork-session!
                     {:parent-session-id     wire-sid
                      :branch-point          bp
                      :work-dir              work-dir
                      :parent-checkpoint-dir root-ck
                      :env                   {}})]
        (assertions
          "resolved the precise CHILD node-entry checkpoint despite the colon mismatch"
          (:seed-source result) => :node-entry
          "surfaced the seed's statechart-src so the branch can resume the poet sub-chart"
          (:statechart-src result) => :escapement.examples.haiku-tournament-dynamic/poet
          "branch checkpoint seeded with the child's node-entry wmem, rekeyed to the branch session-id"
          (sp/get-working-memory (store/new-store (:checkpoint-dir result)) {} (:branch-id result))
          => (assoc entry-wmem ::sc/session-id (:branch-id result))))))

  (component "refuses to fork from a terminated run (empty configuration)"
    (let [work-dir  (tmp-dir)
          parent-id "parent-3"
          parent-ck (str work-dir "/" parent-id "/checkpoints")
          _         (.mkdirs (io/file parent-ck))
          pstore    (store/new-store parent-ck)]
      (sp/save-working-memory! pstore {} parent-id {::sc/configuration #{}})
      (assertions
        "throws because there is nothing to continue"
        (try (branch/fork-session!
               {:parent-session-id parent-id
                :branch-point      {:node-id "n" :visit 0 :turn 0}
                :work-dir          work-dir
                :env               {}})
             :no-throw
             (catch Exception _ :threw))
        => :threw))))
