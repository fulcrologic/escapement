(ns escapement.ui.rerun-from-integration-test
  "Task 005 — `escapement.ui.debug-control/rerun-from!` end-to-end against a LIVE
   in-process engine (mock backend). This is the path the OpenTUI sidecar actually
   triggers (wire §9 `rerun-from`): fork a branch from the node-entry checkpoint,
   apply `:debug/overrides` + the replay policy, resume → re-invoke → continue, and
   emit the `branch-running` debug frame + branch event stream.

   Covers:
     1. single-session   — root chart, override on the re-issued turn, downstream state.
     2. multiplex-child  — the conversation lived in a CHILD sub-chart session; the
        branch resumes the SUB-chart (via the seed's `::sc/statechart-src`) and
        produces the child's output. (Known limit: it does NOT re-feed the
        tournament aggregate — documented, not asserted.)
     3. missing-registry — an env with no `::sc/statechart-registry` returns NO
        `:future` (no silent success).

   `debug-control` is an `escapement.ui.*` add-on; test code may require it
   directly (the architecture-boundary scanner exempts test/). Production reaches
   it only via `requiring-resolve`."
  (:require
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry send state transition]]
    [com.fulcrologic.statecharts.promise :as p]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.engine.store :as store]
    [escapement.llm.protocol :as llm]
    [escapement.runner :as runner]
    [escapement.test-support :as ts]
    [escapement.tools.protocol :as tp]
    [escapement.ui.debug-control :as dc]
    [fulcro-spec.core :refer [=> assertions specification component]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (str (Files/createTempDirectory "rerun-int" (into-array FileAttribute []))))

;; Mock backend that records every assembled request so a test can assert the
;; worker re-ran AND that an override was reflected in the re-issued turn.
(defrecord RecordingBackend [responses calls]
  llm/LLMBackend
  (send-turn [_ request]
    (swap! calls conj request)
    (p/do! (or (ts/pop-first! responses)
             {:stop-reason :end_turn
              :content     [{:type :text :text "done"}]
              :usage       {:input-tokens 1 :output-tokens 1}
              :model       "mock"}))))

;; `:warmup` self-sends a delayed `:go`, so `:talk` (an llm-conversation node) is
;; entered by a real EVENT → a node-entry checkpoint exists before it runs. `:talk`
;; pins :model :base + :system "BASE SYSTEM" so an override has a known baseline to
;; diff against.
(def ^:private talk-chart
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id "writer" :message "hi" :model :base :system "BASE SYSTEM"})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

(def ^:private aliases {:base [{:provider :prov-base :model "base-model"}]})

(defn- transcript-rows [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)] (doall (line-seq r)))))

;; Stand up a LIVE in-process engine for `talk-chart`, capturing the live env via
;; `on-env-ready` (exactly the seam cli.clj uses to fill the control-handle). The
;; parent run completes; the captured env carries `::sc/statechart-registry`
;; (chart registered) + `:escapement/artifact-store` + `::sc/working-memory-store`
;; — everything `rerun-from!` reads off `(:env live)`.
(defn- start-live!
  "Returns {:env :session-id :calls :transcript-path :tap-events}. `tap-events`
   is an atom collecting every transcript event (the live ws-push tap stand-in)."
  [{:keys [dir sid extra-charts chart-env-ready]}]
  (let [sdir  (str dir "/" sid)
        chk   (str sdir "/checkpoints")
        _     (.mkdirs (io/file chk))
        calls (atom [])
        env*  (atom nil)
        taps  (atom [])]
    (runner/run! {:chart              talk-chart
                  :session-id         sid
                  :transcript-path    (str sdir "/transcript.jsonl")
                  :checkpoint-dir     chk
                  :session-dir        sdir
                  :backend            (->RecordingBackend (ts/queue []) calls)
                  :tool-registry      (tp/new-registry)
                  :llm-aliases        aliases
                  :chart-env-ready    chart-env-ready
                  :quiescent-sleep-ms 5
                  :transcript-tap     (fn [ev] (swap! taps conj ev))
                  :on-env-ready       (fn [env]
                                        ;; Register any extra (sub-)charts in the
                                        ;; LIVE registry so the branch can resolve
                                        ;; them via the seed's statechart-src.
                                        (doseq [[cid c] extra-charts]
                                          (sp/register-statechart!
                                            (::sc/statechart-registry env) cid c))
                                        (reset! env* env))})
    {:env             @env*
     :session-id      sid
     :calls           calls
     :transcript-path (str sdir "/transcript.jsonl")
     :tap-events      taps}))

;; -------------------------------------------------------------------------------------------------

(specification "rerun-from! single-session: live in-process fork → override → replay → resume → continue"
  (let [dir    (tmp-dir)
        live   (start-live! {:dir dir :sid "rerun-int-parent"})
        env    (:env live)
        ;; A fresh tap atom for the BRANCH so we can isolate branch events from
        ;; the parent's (the parent's transcript-fn is reused via :transcript-fn).
        branch-events (atom [])
        result (dc/rerun-from!
                 {:live          {:env env :session-id (:session-id live) :controller nil}
                  :node-id       "talk"
                  :visit         0
                  :turn          0
                  :overrides     {:system "OVERRIDDEN SYSTEM"}
                  :transcript-fn (fn [ev] (swap! branch-events conj ev))})
        _      (some-> (:future result) (deref 5000 :timeout))
        run-result (deref (:future result) 0 nil)
        rows   (transcript-rows (str dir "/" (:branch-id result) "/transcript.jsonl"))]
    (assertions
      "seeded precisely from the node-entry checkpoint (not the :latest fallback)"
      (:seed-source result) => :node-entry
      "a branch run actually started (future non-nil)"
      (some? (:future result)) => true
      "the branch run reached the chart's final :done state (continued forward)"
      (contains? (set (:final-config run-result)) :done) => true
      "the branch transcript logged at least one :llm/* row (worker re-invoked)"
      (boolean (some #(re-find #"\"llm/" %) rows)) => true
      "branch events flowed through the supplied :transcript-fn"
      (pos? (count @branch-events)) => true
      "the :branch-frame is a branch-running debug frame with parent + branch-point"
      (let [bf (:branch-frame result)]
        [(:kind bf) (:mode bf)
         (-> bf :branch :parent)
         (-> bf :branch :branch-point :node-id)])
      => ["debug" "branch-running" "rerun-int-parent" "talk"])))

(specification "rerun-from! single-session: the override took effect on the re-issued turn"
  ;; Separate run (a branch's seeded resume is one-shot) — assert the override at
  ;; the mock-backend boundary using a backend whose calls we can inspect AFTER
  ;; the branch runs. We swap the live env's backend for the branch via the same
  ;; path rerun-from! uses (it reads :escapement/llm-backend off env).
  (let [dir    (tmp-dir)
        ;; The branch reuses the live env's backend, so the SAME `calls` atom from
        ;; start-live! records the re-issued turn. The parent run already pushed
        ;; one call; the branch adds another whose request reflects the override.
        live   (start-live! {:dir dir :sid "rerun-int-ov"})
        env    (:env live)
        before (count @(:calls live))
        result (dc/rerun-from!
                 {:live      {:env env :session-id (:session-id live)}
                  :node-id   "talk" :visit 0 :turn 0
                  :overrides {:system "OVERRIDDEN SYSTEM"}
                  :transcript-fn (fn [_])})
        _      (some-> (:future result) (deref 5000 :timeout))
        new-calls (subvec @(:calls live) before)]
    (assertions
      "the branch re-issued a turn (a new backend call beyond the parent's)"
      (pos? (count new-calls)) => true
      "the re-issued turn's assembled request carries the overridden :system"
      (:system (first new-calls)) => "OVERRIDDEN SYSTEM")))

(specification "rerun-from! multiplex-child: branch resumes the SUB-chart and produces child output"
  ;; The poet/judge shape: the conversation ran in a CHILD sub-chart session
  ;; (`multiplex.poets.0`), whose node-entry checkpoint is keyed by the child
  ;; session-id and carries the sub-chart's `::sc/statechart-src`. The captured
  ;; tool-results/transcript live under the ROOT session. The re-run must seed
  ;; from the child checkpoint, resume the child's OWN chart (here `:poet`, which
  ;; we reuse `talk-chart` as), and re-invoke it live.
  ;;
  ;; Known limit (documented, NOT asserted): this resumes the sub-chart standalone
  ;; — it does NOT re-feed the tournament aggregate.
  (let [dir       (tmp-dir)
        poet-cid  :escapement.examples/poet
        live      (start-live! {:dir dir :sid "rerun-int-root"
                                :extra-charts {poet-cid talk-chart}})
        env       (:env live)
        wmstore   (::sc/working-memory-store env)
        child-kw  :multiplex.poets.0   ; saved under the KEYWORD child id
        wire-sid  "multiplex.poets.0"  ; sidecar sends the colon-less wire form
        ;; Hand-seed the child node-entry checkpoint exactly as a real multiplex
        ;; child run writes it: node IN config + statechart-src of the sub-chart.
        _         (store/save-node-entry-checkpoint!
                    wmstore child-kw :talk 0
                    {::sc/configuration  #{:run :talk}
                     ::sc/statechart-src poet-cid})
        branch-events (atom [])
        result    (dc/rerun-from!
                    {:live          {:env env :session-id (:session-id live)}
                     :session-id    wire-sid          ; the CHILD session-id (wire form)
                     :node-id       "talk" :visit 0 :turn 0
                     :transcript-fn (fn [ev] (swap! branch-events conj ev))})
        _         (some-> (:future result) (deref 5000 :timeout))
        run-result (deref (:future result) 0 nil)
        rows      (transcript-rows (str dir "/" (:branch-id result) "/transcript.jsonl"))]
    (assertions
      "seeded precisely from the CHILD node-entry checkpoint"
      (:seed-source result) => :node-entry
      "a branch run started (the poet sub-chart resolved via statechart-src)"
      (some? (:future result)) => true
      "the branch resumed the SUB-chart, not the root (replay sources from the ROOT)"
      (:source (:replay result)) => "rerun-int-root"
      "the sub-chart re-invoked and produced child output (an :llm/* row)"
      (boolean (some #(re-find #"\"llm/" %) rows)) => true
      "the sub-chart continued to its own final state"
      (contains? (set (:final-config run-result)) :done) => true
      "the branch-running frame names the CHILD as parent"
      (-> result :branch-frame :branch :parent) => "multiplex.poets.0")))

(specification "rerun-from! surfaces the missing-registry error path (no silent no-op)"
  ;; An env WITHOUT `::sc/statechart-registry` cannot resolve the branch chart, so
  ;; `live-chart` returns nil and no branch must start — `rerun-from!` returns NO
  ;; `:future` rather than silently appearing to succeed.
  (let [dir     (tmp-dir)
        sid     "no-registry"
        sdir    (str dir "/" sid)
        chk     (str sdir "/checkpoints")
        _       (.mkdirs (io/file chk))
        wmstore (store/new-store chk)
        _       (store/save-node-entry-checkpoint!
                  wmstore sid :talk 0
                  {::sc/configuration #{:run :talk}})
        ;; Deliberately omit ::sc/statechart-registry.
        env     {:escapement/session-dir   sdir
                 ::sc/working-memory-store  wmstore}
        result  (dc/rerun-from!
                  {:live    {:env env :session-id sid}
                   :node-id "talk" :visit 0 :turn 0})]
    (assertions
      "no branch run started (no chart resolvable without a registry)"
      (:future result) => nil
      "the fork still seeded a checkpoint (the failure is at resume, not fork)"
      (:seed-source result) => :node-entry)))

(specification "rerun-from! :scope :root — finish the WHOLE chart (multiplex, lifted limitation)"
  ;; The selected node is a CHILD conversation (e.g. the muse turn in a poet
  ;; sub-chart), but :scope :root re-points the FORK to the ROOT's multiplex
  ;; PARENT state (`:root-branch-point`) and resumes the whole chart multi-session,
  ;; with the override still scoped to the child node. We inject :run-fn to assert
  ;; the wiring deterministically.
  (let [dir       (tmp-dir)
        root-sid  "root-scope"
        hook      (fn [_env] nil)
        live      (start-live! {:dir dir :sid root-sid :chart-env-ready hook})
        env       (:env live)
        wmstore   (::sc/working-memory-store env)
        ;; Seed the ROOT's node-entry checkpoint at the multiplex parent state.
        _         (store/save-node-entry-checkpoint!
                    wmstore root-sid :composing 0
                    {::sc/configuration  #{:run :composing}
                     ::sc/statechart-src :escapement.runner/chart})
        captured  (atom nil)
        result    (dc/rerun-from!
                    {:live              {:env env :session-id root-sid}
                     ;; selected CHILD conversation node (the override target)
                     :node-id           "musing" :visit 0 :turn 0
                     :overrides         {:system "SABOTAGE"}
                     :scope             :root
                     :root-branch-point {:node-id "composing" :visit 0 :turn 0}
                     :run-fn            (fn [args] (reset! captured args) {:status :done})})
        _         (some-> (:future result) (deref 5000 :timeout))
        c         @captured]
    (assertions
      "forked from the ROOT multiplex-parent checkpoint (not the child node)"
      (:seed-source result) => :node-entry
      (-> result :branch :branch-point :node-id) => "composing"
      "the branch parent is the ROOT session"
      (-> result :branch :parent) => root-sid
      "resumes multi-session (pumps the multiplex children)"
      (:multi-session? c) => true
      "re-applies the chart's sub-chart registration on the branch run"
      (:chart-env-ready c) => hook
      "the override is still scoped to the CHILD conversation node"
      (-> c :debug-overrides :node-id) => :musing
      (-> c :debug-overrides :system) => "SABOTAGE"
      "resumes (does not cold-start)"
      (:resume? c) => true)))
