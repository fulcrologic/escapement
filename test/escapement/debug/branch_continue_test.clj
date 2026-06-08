(ns escapement.debug.branch-continue-test
  "Integration: fork a branch from a node-entry checkpoint, resume it, and prove
   the chart RE-INVOKES the conversation node AND continues forward into a
   downstream state that did NOT exist in the seed configuration.

   The seed checkpoint (per task 001) contains `:talk` IN the configuration. On
   resume the re-invoke primitive restarts the `:talk` conversation worker; when
   it posts `:llm.idle` the chart transitions `:talk` -> `:wrapup` -> `:done`.
   `:wrapup` is a DISTINCT downstream state (absent from the seed config) whose
   on-entry bumps a side-channel atom — the observable proof of forward
   continuation, not just a single node re-run.

   Also asserts the parent's transcript/checkpoint files are untouched
   (immutability) after the branch runs."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry script send state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.chart.helpers :as h]
    [escapement.debug.branch :as branch]
    [escapement.engine.store :as store]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "branch-continue" (into-array FileAttribute []))))

(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; `:warmup` self-sends a delayed `:go` so `:talk` (an llm-conversation node) is
;; entered by a real EVENT (a node-entry checkpoint is written for it). On the
;; LLM terminal event `:llm.idle`, `:talk` -> `:wrapup` (DISTINCT downstream
;; state, NOT in the seed config) whose on-entry bumps `wrapup-counter`, then
;; `:wrapup` -> `:done`. `wrapup-counter` lives in env via `:user-env` so the
;; on-entry script can observe it.
(defn- talk-chart [wrapup-atom]
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi"})
        (transition {:event :llm.idle :target :wrapup}))
      (state {:id :wrapup}
        (on-entry {}
          (script {:expr (fn [_env _data] (swap! wrapup-atom inc) nil)}))
        (transition {:target :done}))
      (final {:id :done}))))

(defn- run-chart! [{:keys [chart sdir chk session-dir sid responses calls]}]
  (runner/run! {:chart              chart
                :session-id         sid
                :transcript-path    (str sdir "/transcript.jsonl")
                :checkpoint-dir     chk
                :session-dir        session-dir
                :backend            (->RecordingBackend (ts/queue (or responses [])) calls)
                :tool-registry      (tp/new-registry)
                :quiescent-sleep-ms 5}))

(defn- file-bytes [f] (when (.exists (io/file f)) (slurp f)))

(specification "fork -> resume -> re-invoke -> continue into a downstream state"
  (let [dir            (tmp-dir)
        sid            "branch-continue-parent"
        sdir           (str dir "/" sid)
        chk            (str sdir "/checkpoints")
        _              (.mkdirs (io/file chk))
        parent-wrapup  (atom 0)
        parent-chart   (talk-chart parent-wrapup)
        pcalls         (atom [])
        ;; Parent run completes; writes a node-entry checkpoint at :talk v0 (node
        ;; IN config) and runs all the way through :wrapup -> :done.
        presult        (run-chart! {:chart parent-chart :sdir sdir :chk chk
                                    :session-dir sdir :sid sid :calls pcalls})
        ;; Snapshot the parent's on-disk state to prove immutability afterward.
        canon-before   (file-bytes (str chk "/" sid ".edn"))
        node-before    (file-bytes (str chk "/node-entries/" sid "/talk__0.edn"))
        transcript-before (file-bytes (str sdir "/transcript.jsonl"))
        ;; Fork a branch from the :talk node-entry checkpoint.
        branch         (branch/fork-session!
                         {:parent-session-id sid
                          :branch-point      {:node-id "talk" :visit 0 :turn 0}
                          :work-dir          dir})
        bid            (:branch-id branch)
        branch-wrapup  (atom 0)
        branch-chart   (talk-chart branch-wrapup)
        bcalls         (atom [])
        ;; Resume the branch with a FRESH backend queue so the re-invoked turn
        ;; has a response to consume.
        result         (runner/run! {:chart              branch-chart
                                     :session-id         bid
                                     :transcript-path    (:transcript-path branch)
                                     :checkpoint-dir     (:checkpoint-dir branch)
                                     :session-dir        (:session-dir branch)
                                     :resume?            true
                                     :backend            (->RecordingBackend (ts/queue []) bcalls)
                                     :tool-registry      (tp/new-registry)
                                     :quiescent-sleep-ms 5})]
    (assertions
      "parent run reached :done and entered :wrapup exactly once"
      (contains? (set (:final-config presult)) :done) => true
      @parent-wrapup => 1
      "the branch seeded from the :node-entry source (node in config)"
      (:seed-source branch) => :node-entry

      ;; (a) the branch re-invoked :talk
      "(a) resume re-invoked the conversation worker (backend called on the branch)"
      (pos? (count @bcalls)) => true

      ;; (b) the branch continued FORWARD into the downstream :wrapup state
      "(b) the branch entered the downstream :wrapup state (on-entry side effect fired)"
      @branch-wrapup => 1
      "(b) the branch chart advanced to its final :done state"
      (contains? (set (:final-config result)) :done) => true

      ;; (c) parent immutability
      "(c) parent canonical checkpoint unchanged"
      (file-bytes (str chk "/" sid ".edn")) => canon-before
      "(c) parent node-entry checkpoint unchanged"
      (file-bytes (str chk "/node-entries/" sid "/talk__0.edn")) => node-before
      "(c) parent transcript unchanged"
      (file-bytes (str sdir "/transcript.jsonl")) => transcript-before
      "(c) the branch did NOT re-bump the parent's wrapup atom"
      @parent-wrapup => 1)))

(specification "guard: forking from a terminated run (empty config) throws 'nothing to continue'"
  (let [dir       (tmp-dir)
        sid       "terminated-parent"
        sdir      (str dir "/" sid)
        chk       (str sdir "/checkpoints")
        _         (.mkdirs (io/file chk))
        pstore    (store/new-store chk)]
    (com.fulcrologic.statecharts.protocols/save-working-memory!
      pstore {} sid {::sc/configuration #{}})
    (assertions
      "fork-session! throws the clear nothing-to-continue error"
      (try (branch/fork-session!
             {:parent-session-id sid
              :branch-point      {:node-id "n" :visit 0 :turn 0}
              :work-dir          dir
              :env               {}})
           :no-throw
           (catch Exception e (.getMessage e)))
      => "Seed working memory has empty configuration (terminated run?) — nothing to continue")))
