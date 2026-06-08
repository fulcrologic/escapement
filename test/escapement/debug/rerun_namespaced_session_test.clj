(ns escapement.debug.rerun-namespaced-session-test
  "Regression for the LIVE Ctrl-R failure: the running session-id is the
   namespaced keyword `:session/<uuid>` (so ESCAPEMENT_SESSION_ID = the string
   \"session/<uuid>\"), but the CLI names the session DIR by the uuid ALONE. The
   old `fork-session!` derived the parent dirs as `work-dir/<session-id>` →
   `work-dir/session/<uuid>` (the `/` nests) → it never found the node-entry
   checkpoint and the branch silently never forked. `rerun-from!` now hands
   `fork-session!` the REAL parent dirs off the live env."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.engine.store :as store]
    [escapement.ui.debug-control :as dc]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp [] (str (Files/createTempDirectory "rerun-ns" (into-array FileAttribute []))))

(specification "rerun-from! forks a branch when the session-id is :session/<uuid> and the dir is the uuid alone"
  (let [work-dir   (tmp)
        uuid       "abc12345"
        sid-str    (str "session/" uuid)          ; ESCAPEMENT_SESSION_ID form
        session-dir (str work-dir "/" uuid)        ; CLI names the dir by uuid ONLY
        ck-dir     (str session-dir "/checkpoints")
        _          (.mkdirs (io/file ck-dir))
        wmstore    (store/new-store ck-dir)
        ;; A non-empty node-entry snapshot keyed by the NAMESPACED session-id
        ;; (exactly as the in-session invocation writes it, C2).
        _          (store/save-node-entry-checkpoint!
                     wmstore sid-str :writer 0
                     {::sc/configuration #{:S/root :writer} :data-model {:x 1}})
        registry   (reify sp/StatechartRegistry
                     (register-statechart! [_ _ _] nil)
                     (get-statechart [_ _] {:fake :chart}))
        env        {:escapement/session-dir    session-dir
                    ::sc/working-memory-store   wmstore
                    ::sc/statechart-registry    registry}
        captured   (atom nil)
        result     (dc/rerun-from!
                     {:live       {:env env :session-id sid-str}
                      :session-id sid-str
                      :node-id    ":writer"
                      :visit      0
                      :turn       0
                      :run-fn     (fn [opts] (reset! captured opts) :ran)})]
    (assertions
      "the seed resolved to the PRECISE node-entry checkpoint (not the :latest fallback)"
      (:seed-source result) => :node-entry
      "a branch run actually started (future non-nil)"
      (some? (:future result)) => true
      "the branch id is a single filesystem-safe segment (no nested `session/`)"
      (.contains ^String (str (:branch-id result)) "/") => false
      "the branch dir was created on disk"
      (.exists (io/file (:session-dir result))) => true
      "the branch run! got resume? + the parent-sourced replay policy"
      (do (deref (future (loop [i 0] (when (and (nil? @captured) (< i 200)) (Thread/sleep 5) (recur (inc i))))) 2000 nil)
          [(:resume? @captured) (:source (:debug-replay-policy @captured))])
      => [true sid-str])))

(specification "rerun-from! on a MULTI-session run forks from the child session, resumes its sub-chart, and replays from the ROOT transcript"
  ;; The poet/judge case: the conversation ran in a sub-chart CHILD session
  ;; (`:multiplex.poets.4`), so its node-entry checkpoint is keyed by the child
  ;; id — but the captured tool-results + transcript live under the ROOT session
  ;; dir. The re-run must (1) seed from the child checkpoint, (2) resume the
  ;; child's OWN chart (via the seed's `::sc/statechart-src`), and (3) point the
  ;; replay index at the ROOT session, not the child.
  (let [work-dir   (tmp)
        root-id    "rootuuid"
        root-sid   (str "session/" root-id)        ; live runner session-id form
        root-dir   (str work-dir "/" root-id)
        ck-dir     (str root-dir "/checkpoints")
        _          (.mkdirs (io/file ck-dir))
        wmstore    (store/new-store ck-dir)
        poet-chart-id :escapement.examples.haiku-tournament-dynamic/poet
        ;; Child node-entry checkpoint, saved under the keyword child id; carries
        ;; the sub-chart's statechart-src (engine stamps this on chart start).
        _          (store/save-node-entry-checkpoint!
                     wmstore :multiplex.poets.4 :haiku-1 3
                     {::sc/configuration #{:compose-route}
                      ::sc/statechart-src poet-chart-id})
        registry   (reify sp/StatechartRegistry
                     (register-statechart! [_ _ _] nil)
                     ;; Only the poet sub-chart resolves; the root chart-id would
                     ;; return nil, proving the branch used statechart-src.
                     (get-statechart [_ id] (when (= id poet-chart-id) {:poet :chart})))
        env        {:escapement/session-dir    root-dir
                    ::sc/working-memory-store   wmstore
                    ::sc/statechart-registry    registry}
        captured   (atom nil)
        result     (dc/rerun-from!
                     {:live       {:env env :session-id root-sid}
                      ;; Sidecar sends the SELECTED row's child session-id (wire
                      ;; colon-less form) — NOT the root.
                      :session-id "multiplex.poets.4"
                      :node-id    ":haiku-1"
                      :visit      3
                      :turn       0
                      :run-fn     (fn [opts] (reset! captured opts) :ran)})]
    (assertions
      "seeded precisely from the CHILD node-entry checkpoint"
      (:seed-source result) => :node-entry
      "a branch run started (the poet sub-chart resolved via statechart-src)"
      (some? (:future result)) => true
      (do (deref (future (loop [i 0] (when (and (nil? @captured) (< i 200)) (Thread/sleep 5) (recur (inc i))))) 2000 nil)
          [;; resumed the poet sub-chart, not the root chart
           (:chart-id @captured)
           (:chart @captured)
           ;; replay sources from the ROOT transcript, not the child
           (:source (:debug-replay-policy @captured))])
      => [poet-chart-id {:poet :chart} root-sid])))
