(ns escapement.debug.rerun-test
  "Tests for the resume-safe `:chart-env-ready` seam and the engine-core
   `escapement.debug.rerun/rerun-from-checkpoint!` function.

   Three layers:
     1. SEAM — `runner/run!` invokes a chart-declared `:chart-env-ready` on EVERY
        run!, including RESUME (the fix that lets a multi-session chart whose
        on-entry registration does not re-fire on resume be re-run correctly).
     2. INTEGRATION (single-session, real engine, mock backend) — the function
        forks a node-entry checkpoint, applies an override, resumes, and
        continues into a downstream state.
     3. CONTRACT (multiplex ROOT resume, injected run-fn) — the function maps its
        inputs onto runner/run! correctly: branch-chart-id from the seed's
        statechart-src, `:multi-session?`, `:chart-env-ready`, `:debug-overrides`,
        `:debug-replay-policy`, `:resume? true`."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script send state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.chart.helpers :as h]
    [escapement.debug.rerun :as rerun]
    [escapement.engine.store :as store]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "rerun" (into-array FileAttribute []))))

(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; ----------------------------------------------------------------------------
;; 1. SEAM — :chart-env-ready runs on resume
;; ----------------------------------------------------------------------------

(defn- trivial-chart []
  (chart/statechart {:initial :a}
    (state {:id :a} (transition {:target :done}))
    (final {:id :done})))

(specification ":chart-env-ready runs on EVERY run! — including resume"
  (let [dir   (tmp-dir)
        sid   "env-ready"
        sdir  (str dir "/" sid)
        chk   (str sdir "/checkpoints")
        _     (.mkdirs (io/file chk))
        hits  (atom 0)
        hook  (fn [_env] (swap! hits inc) nil)
        base  {:chart            (trivial-chart)
               :session-id       sid
               :transcript-path  (str sdir "/transcript.jsonl")
               :checkpoint-dir   chk
               :session-dir      sdir
               :chart-env-ready  hook
               :quiescent-sleep-ms 5}]
    ;; fresh start
    (runner/run! base)
    (let [after-start @hits]
      ;; resume the SAME (now terminal) session
      (runner/run! (assoc base :resume? true))
      (assertions
        "the hook fired on the fresh start"
        after-start => 1
        "the hook fired AGAIN on resume (on-entry would NOT have re-fired)"
        @hits => 2))))

;; ----------------------------------------------------------------------------
;; 2. INTEGRATION — single-session fork → override → resume → continue
;; ----------------------------------------------------------------------------

(defn- talk-chart [wrapup-atom]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi" :system "BASE SYSTEM"})
        (transition {:event :llm.idle :target :wrapup}))
      (state {:id :wrapup}
        (on-entry {} (script {:expr (fn [_ _] (swap! wrapup-atom inc) nil)}))
        (transition {:target :done}))
      (final {:id :done}))))

(specification "rerun-from-checkpoint! (single-session): fork → override → resume → continue"
  (let [dir       (tmp-dir)
        sid       "rerun-parent"
        sdir      (str dir "/" sid)
        chk       (str sdir "/checkpoints")
        _         (.mkdirs (io/file chk))
        pwrap     (atom 0)
        pcalls    (atom [])
        ;; parent run to completion → node-entry checkpoint at :talk v0
        _         (runner/run! {:chart           (talk-chart pwrap)
                                :session-id      sid
                                :transcript-path (str sdir "/transcript.jsonl")
                                :checkpoint-dir  chk
                                :session-dir     sdir
                                :backend         (->RecordingBackend (ts/queue []) pcalls)
                                :tool-registry   (tp/new-registry)
                                :quiescent-sleep-ms 5})
        bwrap     (atom 0)
        bcalls    (atom [])
        result    (rerun/rerun-from-checkpoint!
                    {:chart             (talk-chart bwrap)
                     :parent-session-id sid
                     :branch-point      {:node-id "talk" :visit 0 :turn 0}
                     :work-dir          dir
                     :backend           (->RecordingBackend (ts/queue []) bcalls)
                     :tool-registry     (tp/new-registry)
                     :override          {:node-id :talk :visit 0 :turn 0
                                         :system  "OVERRIDE SYSTEM"}
                     :quiescent-sleep-ms 5})]
    (assertions
      "seeded from the node-entry checkpoint (node in config)"
      (:seed-source result) => :node-entry
      "the branch re-invoked the conversation (backend called on the branch)"
      (pos? (count @bcalls)) => true
      "the override system prompt reached the re-issued turn"
      (:system (first @bcalls)) => "OVERRIDE SYSTEM"
      "the branch continued forward into the downstream :wrapup state"
      @bwrap => 1
      "the branch reached its final :done state"
      (contains? (set (:final-config (:result result))) :done) => true
      "the parent was not advanced by the branch"
      @pwrap => 1)))

(specification "rerun-from-checkpoint! (single-session): :system-append injects, keeping the node system"
  (let [dir       (tmp-dir)
        sid       "append-parent"
        sdir      (str dir "/" sid)
        chk       (str sdir "/checkpoints")
        _         (.mkdirs (io/file chk))
        _         (runner/run! {:chart           (talk-chart (atom 0))
                                :session-id      sid
                                :transcript-path (str sdir "/transcript.jsonl")
                                :checkpoint-dir  chk
                                :session-dir     sdir
                                :backend         (->RecordingBackend (ts/queue []) (atom []))
                                :tool-registry   (tp/new-registry)
                                :quiescent-sleep-ms 5})
        bcalls    (atom [])
        _         (rerun/rerun-from-checkpoint!
                    {:chart             (talk-chart (atom 0))
                     :parent-session-id sid
                     :branch-point      {:node-id "talk" :visit 0 :turn 0}
                     :work-dir          dir
                     :backend           (->RecordingBackend (ts/queue []) bcalls)
                     :tool-registry     (tp/new-registry)
                     ;; node :talk has :system "BASE SYSTEM"; append, don't replace.
                     :override          {:node-id :talk
                                         :system-append "INJECT: about strawberries"}
                     :quiescent-sleep-ms 5})]
    (assertions
      "the re-issued turn's system is the node's base + the appended injection"
      (:system (first @bcalls)) => "BASE SYSTEM\n\nINJECT: about strawberries")))

;; ----------------------------------------------------------------------------
;; 3. CONTRACT — multiplex ROOT resume maps onto run! correctly (injected run-fn)
;; ----------------------------------------------------------------------------

(specification "rerun-from-checkpoint! (multiplex root): forwards multi-session + scoped override + chart-env-ready"
  (let [dir       (tmp-dir)
        root-sid  "root"
        sdir      (str dir "/" root-sid)
        chk       (str sdir "/checkpoints")
        _         (.mkdirs (io/file chk))
        wmstore   (store/new-store chk)
        ;; Seed the ROOT's node-entry checkpoint at the multiplex parent state
        ;; (:composing), node IN config, carrying the chart it resumes under.
        _         (store/save-node-entry-checkpoint!
                    wmstore root-sid :composing 0
                    {::sc/configuration  #{:run :composing}
                     ::sc/statechart-src :escapement.runner/chart})
        captured  (atom nil)
        hook      (fn [_env] nil)
        ;; Pass a NON-ZERO visit (the parent's global visit) to prove the rerun
        ;; layer reconciles it to the branch-local 0.
        override  {:node-id :musing :visit 7 :turn 0 :system "SABOTAGE"}
        replay    {:source root-sid :mode :replay-then-live}
        result    (rerun/rerun-from-checkpoint!
                    {:chart                 :ROOT-CHART
                     :chart-id              :escapement.runner/chart
                     :chart-env-ready       hook
                     :multi-session?        true
                     :parent-session-id     root-sid
                     :branch-point          {:node-id "composing" :visit 0 :turn 0}
                     :work-dir              dir
                     :parent-checkpoint-dir chk
                     :parent-session-dir    sdir
                     :override              override
                     :replay-policy         replay
                     :run-fn                (fn [args] (reset! captured args) {:status :done :final-config [:run :finished]})})
        c         @captured]
    (assertions
      "forked from the ROOT multiplex-parent node-entry checkpoint"
      (:seed-source result) => :node-entry
      "resumes under the seed's statechart-src (the root chart)"
      (:chart-id c) => :escapement.runner/chart
      "pumps ALL sessions (multiplex) — :multi-session? true"
      (:multi-session? c) => true
      "re-applies the chart-declared env setup on the branch run"
      (:chart-env-ready c) => hook
      "scopes the override to the CHILD conversation node (the muse turn)"
      (-> c :debug-overrides :node-id) => :musing
      (-> c :debug-overrides :system) => "SABOTAGE"
      "reconciles the override visit to the BRANCH-LOCAL 0 (not the parent's global 7)"
      (-> c :debug-overrides :visit) => 0
      "carries the replay policy through the continuation"
      (:debug-replay-policy c) => replay
      "resumes (does not cold-start)"
      (:resume? c) => true
      "returns the run result"
      (:result result) => {:status :done :final-config [:run :finished]})))
