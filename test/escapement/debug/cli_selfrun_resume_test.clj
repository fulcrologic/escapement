(ns escapement.debug.cli-selfrun-resume-test
  "Task 006 — the REAL-WORLD proof the user asked for: run my OWN fresh sessions
   with the local gemma model (via the same `runner/run!` the CLI invokes) into a
   THROWAWAY work-dir, then verify resume works two ways on them:

     1. Plain crash-style `--resume` re-invokes an in-flight conversation and the
        run advances. (Fork the node-entry checkpoint into a fresh session — no
        live worker survives — and `run! :resume? true`; the dead in-flight
        invocation re-starts, calls gemma for real, and the chart reaches :done.)

     2. The debugger `rerun-from` re-runs a chosen node with an override (swap the
        model alias :p-gemma1b → :p-gemma270m) and continues forward — producing
        NEW gemma output distinct from the parent's captured turn.

   This is the LIVE counterpart to the deterministic mock-backend gates
   (`reinvoke_resume_test`, `rerun_from_integration_test`). It is **env-gated**:
   it probes ollama at http://localhost:11434/api/tags and SKIPS CLEANLY (green)
   when ollama/gemma is unreachable, so `bb test` stays green on machines without
   ollama. No cloud creds required.

   Equivalent manual CLI invocation (a human can reproduce the self-run half):

       bb -m escapement.cli run escapement.debug.cli-selfrun-resume-test/agent --no-tui \\
          --work-dir /tmp/selfrun --session selfrun-demo

   (the test drives `runner/run!` directly — that IS what `cli/-main` calls — and
   builds the same ollama backend `cli.clj`'s `--backend ollama` / `.escapement.edn`
   `:ollama` path builds: `escapement.llm.openai/new-backend` @
   http://localhost:11434/v1.)

   Assertions are robust to model nondeterminism: they check shape / role /
   resolved-model / branch-advance and that the re-run output DIFFERS from the
   parent's, NOT exact wording."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final on-entry send state transition]]
    [escapement.chart.helpers :as h]
    [escapement.debug.branch :as branch]
    [escapement.llm.openai :as openai]
    [escapement.runner :as runner]
    [escapement.tools.protocol :as tp]
    [escapement.ui.debug-control :as dc]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; --- env gate ---------------------------------------------------------------

(def ^:private ollama-base "http://localhost:11434")

(defn- ollama-up?
  "True when ollama answers /api/tags AND both gemma models are present. Any
   failure → false (test skips cleanly)."
  []
  (try
    (let [resp (slurp (str ollama-base "/api/tags"))]
      (and (str/includes? resp "gemma3:1b")
        (str/includes? resp "gemma3:270m")))
    (catch Throwable _ false)))

;; --- backend (the real ollama backend cli.clj builds) -----------------------

(defn- ollama-backend
  "The exact backend `cli.clj`'s `--backend ollama` / `.escapement.edn` :ollama
   path constructs: an OpenAI-compatible backend pointed at the local ollama
   server. Model is resolved per-conversation via :llm-aliases, so no
   :default-model is required."
  []
  (openai/new-backend {:base-url (str ollama-base "/v1") :api-key "dummy"}))

(def ^:private aliases
  {:p-gemma1b   [{:provider :ollama :model "gemma3:1b"}]
   :p-gemma270m [{:provider :ollama :model "gemma3:270m"}]})

;; --- tiny chart that reaches an llm-conversation node -----------------------
;;
;; `:warmup` self-sends a delayed :go, so `:talk` (the llm-conversation node) is
;; entered by a real EVENT → a node-entry checkpoint with `:talk` IN config is
;; written before the turn runs (the re-invokable snapshot resume/rerun need).
;; `:talk` pins :model :p-gemma1b and caps tokens tiny so the live turn is fast.

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :warmup}
      (state {:id :warmup}
        (on-entry {} (send {:event :go :delay 1}))
        (transition {:event :go :target :talk}))
      (state {:id :talk}
        (h/llm-conversation {:id         "writer"
                             :message    "Reply with a single short word."
                             :model      :p-gemma1b
                             :system     "You are terse. Answer in one word."
                             :max-tokens 16
                             :max-turns  1})
        (transition {:event :llm.idle :target :done}))
      (final {:id :done}))))

;; --- helpers ----------------------------------------------------------------

(defn- tmp-dir [] (str (Files/createTempDirectory "selfrun" (into-array FileAttribute []))))

(defn- transcript-rows [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)] (doall (line-seq r)))))

(defn- llm-response-texts
  "Extract the text content of every `:llm/response` row in a branch transcript
   (best-effort: the row is JSON, the assistant text rides in a :text field)."
  [rows]
  (->> rows
    (filter #(str/includes? % "llm/response"))
    (keep (fn [line]
            ;; pull every \"text\":\"…\" payload; robust enough to diff outputs.
            (let [m (re-seq #"\"text\":\"((?:[^\"\\]|\\.)*)\"" line)]
              (when (seq m) (str/join " " (map second m))))))
    vec))

;; Self-run a fresh session with the LIVE ollama backend, capturing the live env
;; via :on-env-ready (the exact seam cli.clj uses to fill the control-handle).
(defn- self-run!
  [{:keys [dir sid]}]
  (let [sdir (str dir "/" sid)
        chk  (str sdir "/checkpoints")
        _    (.mkdirs (io/file chk))
        env* (atom nil)]
    (runner/run! {:chart              agent
                  :session-id         sid
                  :transcript-path    (str sdir "/transcript.jsonl")
                  :checkpoint-dir     chk
                  :session-dir        sdir
                  :backend            (ollama-backend)
                  :tool-registry      (tp/new-registry)
                  :llm-aliases        aliases
                  :quiescent-sleep-ms 25
                  :on-env-ready       (fn [env] (reset! env* env))})
    {:env             @env*
     :session-id      sid
     :session-dir     sdir
     :checkpoint-dir  chk
     :transcript-path (str sdir "/transcript.jsonl")}))

;; ----------------------------------------------------------------------------

(if-not (ollama-up?)
  (specification "live gemma self-run + resume (SKIPPED — ollama unreachable)"
    (assertions
      "ollama at http://localhost:11434 is unreachable or gemma models missing — skipping cleanly"
      true => true))

  (do
    (specification "self-run a fresh gemma session via the real runner produces a captured turn + node-entry checkpoint"
      (let [dir   (tmp-dir)
            run   (self-run! {:dir dir :sid "selfrun-base"})
            rows  (transcript-rows (:transcript-path run))]
        (assertions
          "the live env was captured (the control-handle seam cli.clj uses)"
          (some? (:env run)) => true
          "the run produced at least one :llm/response row (gemma was actually called)"
          (boolean (some #(str/includes? % "llm/response") rows)) => true
          "a node-entry checkpoint for the conversation node :talk was written (re-invokable snapshot)"
          (.exists (io/file (str (:checkpoint-dir run) "/node-entries/selfrun-base/talk__0.edn"))) => true)))

    (specification "plain --resume re-invokes the in-flight conversation and the run advances (crash-style, live gemma)"
      ;; Self-run a parent (writes the :talk node-entry checkpoint), then FORK
      ;; that checkpoint into a fresh session — no live worker survives, exactly
      ;; like a process crash mid-turn — and `run! :resume? true`. The engine
      ;; re-invoke-on-resume primitive must restart the dead invocation, call
      ;; gemma for real, and drive the chart to :done.
      (let [dir    (tmp-dir)
            parent (self-run! {:dir dir :sid "selfrun-crash-parent"})
            fork   (branch/fork-session!
                     {:parent-session-id    (:session-id parent)
                      :branch-point         {:node-id "talk" :visit 0 :turn 0}
                      :work-dir             dir
                      :parent-checkpoint-dir (:checkpoint-dir parent)
                      :parent-session-dir   (:session-dir parent)
                      :env                  (:env parent)})
            result (runner/run! {:chart           agent
                                 :session-id      (:branch-id fork)
                                 :transcript-path (:transcript-path fork)
                                 :checkpoint-dir  (:checkpoint-dir fork)
                                 :session-dir     (:session-dir fork)
                                 :backend         (ollama-backend)
                                 :tool-registry   (tp/new-registry)
                                 :llm-aliases     aliases
                                 :resume?         true
                                 :quiescent-sleep-ms 25})
            rows   (transcript-rows (:transcript-path fork))]
        (assertions
          "the fork seeded precisely from the node-entry checkpoint (config has :talk)"
          (:seed-source fork) => :node-entry
          "resume logged a :runner/resumed row"
          (boolean (some #(str/includes? % "runner/resumed") rows)) => true
          "resume RE-INVOKED the conversation — a fresh :llm/response row appears (gemma was called)"
          (boolean (some #(str/includes? % "llm/response") rows)) => true
          "the resumed run advanced to the chart's final :done state"
          (contains? (set (:final-config result)) :done) => true)))

    (specification "debugger rerun-from re-runs the node with a model override, produces NEW gemma output, and continues"
      ;; Against the live in-process engine seeded from a self-run, call
      ;; `rerun-from!` for :talk with an override swapping the alias to
      ;; :p-gemma270m. The branch re-issues the turn against gemma3:270m and
      ;; continues to :done. Assert: a NEW :llm/response (distinct from the
      ;; parent's captured text) and the branch advanced.
      (let [dir          (tmp-dir)
            parent       (self-run! {:dir dir :sid "selfrun-rerun-parent"})
            parent-rows  (transcript-rows (:transcript-path parent))
            parent-texts (set (llm-response-texts parent-rows))
            branch-events (atom [])
            result       (dc/rerun-from!
                           {:live          {:env (:env parent)
                                            :session-id (:session-id parent)
                                            :controller nil}
                            :node-id       "talk"
                            :visit         0
                            :turn          0
                            :overrides     {:alias :p-gemma270m}
                            :transcript-fn (fn [ev] (swap! branch-events conj ev))})
            _            (some-> (:future result) (deref 30000 :timeout))
            run-result   (deref (:future result) 0 nil)
            branch-rows  (transcript-rows (str dir "/" (:branch-id result) "/transcript.jsonl"))
            branch-texts (llm-response-texts branch-rows)]
        (assertions
          "seeded precisely from the node-entry checkpoint"
          (:seed-source result) => :node-entry
          "a branch run actually started (future non-nil)"
          (some? (:future result)) => true
          "the branch produced at least one :llm/response row (the override turn re-ran on gemma)"
          (boolean (some #(str/includes? % "llm/response") branch-rows)) => true
          "the re-run resolved the OVERRIDDEN model gemma3:270m (override took effect)"
          (boolean (some #(str/includes? % "gemma3:270m") branch-rows)) => true
          "branch events flowed through the supplied :transcript-fn"
          (pos? (count @branch-events)) => true
          "the branch run reached the chart's final :done state (continued forward)"
          (contains? (set (:final-config run-result)) :done) => true
          "the branch produced NEW output text distinct from the parent's captured turn"
          (boolean (some (fn [t] (and (seq t) (not (contains? parent-texts t)))) branch-texts))
          => true
          "the :branch-frame is a branch-running debug frame naming parent + branch-point"
          (let [bf (:branch-frame result)]
            [(:kind bf) (:mode bf)
             (-> bf :branch :parent)
             (-> bf :branch :branch-point :node-id)])
          => ["debug" "branch-running" "selfrun-rerun-parent" "talk"])))))
