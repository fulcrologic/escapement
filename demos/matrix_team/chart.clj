(ns matrix-team.chart
  "Demo chart: two LLM agents collaborating on `com.example.matrix/mult`.

  Chart-driven peer loop on the new orchestrator primitives:

    * `:exp-repl`    — service region. Owns the experimenter's JVM nREPL
                       lifecycle (spawns, parses the port, kills on exit).
                       Exposes `:exp/eval` as a region-tool.
    * `:test-repl`   — same shape, owns the tester's private nREPL.
    * `:experimenter` — LLM conversation with a `:verdict-schema`. When the
                       worker reaches its inference boundary, a forced
                       `submit_verdict` inference produces a typed payload
                       that rides on `:llm.idle`.
    * `:tester`      — mirror of `:experimenter` against `:test-repl`.

  Cross-agent routing is entirely chart-side. `:llm.idle` transitions at
  the parallel level guard on `:from` (which invocation idled) and on the
  verdict's `:status`, then either `tell-other-llm!` the peer (peer-loop)
  or transition out of `:work` to `:finished` / `:aborted`. The LLM never
  fires a chart event — it just submits a verdict at end-of-turn.

  Initial data (from --input EDN):
    * `:project-dir`    (string, required) — absolute path to demos/tools
    * `:max-iterations` (int, optional, default 5) — soft cap, advisory in
                                                     the prompt only

  Ports are not user-supplied — the chart spawns the nREPLs itself and
  discovers each port from stdout.

  Boot the chart with:

    bb -m escapement.cli run matrix-team.chart/agent \\
       --input demos/matrix_team/example-input.edn"
  (:require
    [babashka.process :as bp]
    [clojure.java.io :as io]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final on-entry on-exit parallel script state transition]]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.protocols :as sp]
    [escapement.chart.helpers :as h]
    [escapement.chart.service :as service]
    [escapement.tools.protocol :as tp]
    [matrix-team.prompts :as p]))

;; ===========================================================================
;; nREPL JVM lifecycle (chart-owned) — unchanged from the original demo.
;; Each service region spawns `clojure -M:nrepl` against demos/tools, watches
;; the merged stdout/stderr stream for the canonical port line, and fires a
;; per-region ready event carrying the port.
;; ===========================================================================

(defonce ^:private processes (atom {}))

(def ^:private port-line-re
  #"nREPL server started on port (\d+)")

(defn- send-self!
  "Post an event back into the chart's session from an arbitrary thread."
  [queue sid event-kw event-data]
  (when (and queue sid)
    (sp/send! queue {}
      {:target            sid
       :source-session-id sid
       :event             event-kw
       :data              (or event-data {})})))

(defn- watch-for-port!
  [proc queue sid ready-event failed-event]
  (future
    (let [seen? (atom false)]
      (try
        (with-open [rdr (io/reader (:out proc))]
          (loop []
            (when-let [line (.readLine rdr)]
              (when (and (not @seen?)
                      (re-find port-line-re line))
                (let [[_ port-s] (re-find port-line-re line)]
                  (reset! seen? true)
                  (send-self! queue sid ready-event
                    {:port (Integer/parseInt port-s)})))
              (recur))))
        (catch Throwable t
          (when-not @seen?
            (send-self! queue sid failed-event
              {:reason (str "stdout reader threw: " (.getMessage t))}))))
      (when-not @seen?
        (send-self! queue sid failed-event
          {:reason "nREPL process ended before reporting a port"})))))

(defn- spawn-nrepl!
  [region-id project-dir queue sid ready-event failed-event]
  (let [proc (bp/process ["sh" "-c" "clojure -M:nrepl 2>&1"]
               {:dir      project-dir
                :in       nil
                :shutdown bp/destroy-tree})]
    (swap! processes assoc region-id proc)
    (watch-for-port! proc queue sid ready-event failed-event)
    nil))

(defn- kill-nrepl!
  [region-id]
  (when-let [proc (get @processes region-id)]
    (try (bp/destroy-tree proc) (catch Throwable _ nil))
    (swap! processes dissoc region-id)
    nil))

(defn- start-region-script
  [region-id ready-event failed-event]
  (script
    {:expr
     (fn [env data]
       (let [queue (::sc/event-queue env)
             sid   (env-ns/session-id env)
             pdir  (:project-dir data)]
         (assert (string? pdir)
           "matrix-team chart needs :project-dir in initial data")
         (spawn-nrepl! region-id pdir queue sid ready-event failed-event)
         nil))}))

;; ===========================================================================
;; REPL eval handler — shells out to clj-nrepl-eval against a private port.
;; Unchanged from original demo.
;; ===========================================================================

(defn- tmp-code-path
  [region-id]
  (str "/tmp/escapement/matrix-team/" (name region-id) "-eval.clj"))

(defn- write-code-tempfile!
  [region-id code]
  (let [p (tmp-code-path region-id)
        f (io/file p)]
    (.mkdirs (.getParentFile f))
    (spit f (or code ""))
    p))

(defn- registry-of [env]
  (or (:escapement/tool-registry env)
    (get-in env [:escapement/engine :tool-registry])))

(defn- run-eval!
  [registry region-id port code timeout-ms]
  (let [path      (write-code-tempfile! region-id code)
        shell-tmo (max 1000 (- (or timeout-ms 30000) 500))
        command   (str "clj-nrepl-eval -p " port " < " path)]
    (try
      (tp/dispatch registry :shell/run
        {:command    command
         :timeout-ms shell-tmo})
      (catch Throwable t
        {:result   (str "shell dispatch threw: " (.getMessage t))
         :is-error true}))))

(defn- make-ready-eval-handler
  [region-id port-key]
  (fn [env {:keys [data timeout-ms]}]
    (let [dm       (::sc/data-model env)
          model    (when dm (sp/current-data dm env))
          port     (get model port-key)
          registry (registry-of env)]
      (cond
        (not (integer? port))
        {:result   (str "Internal error: " port-key " missing from data model in :*-ready.")
         :is-error true}

        (nil? registry)
        {:result "No tool registry on env; cannot dispatch :shell/run." :is-error true}

        :else
        (let [{:keys [result is-error]} (run-eval! registry region-id port
                                          (or (:code data) "")
                                          timeout-ms)]
          {:result   (str result)
           :is-error (boolean is-error)})))))

;; ---------------------------------------------------------------------------
;; Pending-eval queues (one per region) — used while a REPL is still starting.

(defonce ^:private pending-evals (atom {}))

(defn- make-deferring-eval-handler
  [region-id]
  (fn [env {:keys [reply-id reply-to timeout-ms data]}]
    (swap! pending-evals update region-id (fnil conj [])
      {:reply-id    reply-id
       :reply-to    reply-to
       :timeout-ms  timeout-ms
       :code        (or (:code data) "")
       :env         env
       :enqueued-at (System/currentTimeMillis)})
    nil))

(defn- post-error-reply [env item reason]
  (service/post-reply env
    {:reply-id (:reply-id item)
     :reply-to (:reply-to item)
     :result   reason
     :is-error true}))

(defn- drain-queue-async!
  [region-id port]
  (let [pending (get @pending-evals region-id [])]
    (swap! pending-evals dissoc region-id)
    (when (seq pending)
      (future
        (doseq [{:keys [timeout-ms code env enqueued-at] :as item} pending]
          (let [registry  (registry-of env)
                elapsed   (- (System/currentTimeMillis) enqueued-at)
                remaining (- (or timeout-ms 30000) elapsed)]
            (cond
              (nil? registry)
              (post-error-reply env item "No tool registry on env when draining queue.")

              (not (pos? remaining))
              (post-error-reply env item
                (str "Queued eval expired before REPL became ready (waited "
                  elapsed "ms)."))

              :else
              (let [{:keys [result is-error]} (run-eval! registry region-id port code remaining)]
                (service/post-reply env
                  {:reply-id (:reply-id item)
                   :reply-to (:reply-to item)
                   :result   (str result)
                   :is-error (boolean is-error)})))))))))

(defn- drain-queue-with-error!
  [region-id reason]
  (let [pending (get @pending-evals region-id [])]
    (swap! pending-evals dissoc region-id)
    (doseq [item pending]
      (post-error-reply (:env item) item reason))))

;; ===========================================================================
;; Service regions — own their REPL JVM lifecycle.
;; ===========================================================================

(def ^:private exp-eval-decl
  {:tool         :exp/eval
   :description  "Evaluate a Clojure form in the experimenter's private nREPL. Input :code (string). State persists across calls (vars, requires, def). Wrap multiple forms in (do ...). Calls issued while the REPL is still booting are queued and resolved automatically once it's ready."
   :input-schema [:map [:code :string]]})

(def ^:private test-eval-decl
  {:tool         :test/eval
   :description  "Evaluate a Clojure form in the tester's private nREPL. Input :code (string). State persists across calls (vars, requires, def). Wrap multiple forms in (do ...). Calls issued while the REPL is still booting are queued and resolved automatically once it's ready."
   :input-schema [:map [:code :string]]})

(defn- exp-repl-region []
  (state {:id :exp-repl :initial :exp-repl-starting}
    (on-entry {} (service/register-tool! exp-eval-decl))
    (on-exit {} (service/unregister-tool! :exp/eval))
    (on-exit {} (script {:expr (fn [_ _] (kill-nrepl! :exp-repl))}))

    (state {:id :exp-repl-starting}
      (on-entry {} (start-region-script :exp-repl
                     :exp-repl/ready
                     :exp-repl/failed))
      (h/handle-tool :exp/eval (make-deferring-eval-handler :exp-repl))
      (transition {:event :exp-repl/ready :target :exp-repl-ready :type :internal}
        (script {:expr (fn [_ data]
                         [(ops/assign :experimenter-port
                            (get-in data [:_event :data :port]))])}))
      (transition {:event :exp-repl/failed :target :exp-repl-aborted :type :internal}
        (script {:expr (fn [_ data]
                         [(ops/assign :exp-repl-error
                            (get-in data [:_event :data :reason]))])})))

    (state {:id :exp-repl-ready}
      (on-entry {} (script {:expr (fn [_ data]
                                    (drain-queue-async! :exp-repl
                                      (:experimenter-port data))
                                    nil)}))
      (h/handle-tool :exp/eval (make-ready-eval-handler :exp-repl :experimenter-port)))

    (state {:id :exp-repl-aborted}
      (on-entry {} (script {:expr (fn [_ data]
                                    (drain-queue-with-error!
                                      :exp-repl
                                      (str "Experimenter REPL aborted: "
                                        (:exp-repl-error data)))
                                    nil)})))))

(defn- test-repl-region []
  (state {:id :test-repl :initial :test-repl-starting}
    (on-entry {} (service/register-tool! test-eval-decl))
    (on-exit {} (service/unregister-tool! :test/eval))
    (on-exit {} (script {:expr (fn [_ _] (kill-nrepl! :test-repl))}))

    (state {:id :test-repl-starting}
      (on-entry {} (start-region-script :test-repl
                     :test-repl/ready
                     :test-repl/failed))
      (h/handle-tool :test/eval (make-deferring-eval-handler :test-repl))
      (transition {:event :test-repl/ready :target :test-repl-ready :type :internal}
        (script {:expr (fn [_ data]
                         [(ops/assign :tester-port
                            (get-in data [:_event :data :port]))])}))
      (transition {:event :test-repl/failed :target :test-repl-aborted :type :internal}
        (script {:expr (fn [_ data]
                         [(ops/assign :test-repl-error
                            (get-in data [:_event :data :reason]))])})))

    (state {:id :test-repl-ready}
      (on-entry {} (script {:expr (fn [_ data]
                                    (drain-queue-async! :test-repl
                                      (:tester-port data))
                                    nil)}))
      (h/handle-tool :test/eval (make-ready-eval-handler :test-repl :tester-port)))

    (state {:id :test-repl-aborted}
      (on-entry {} (script {:expr (fn [_ data]
                                    (drain-queue-with-error!
                                      :test-repl
                                      (str "Tester REPL aborted: "
                                        (:test-repl-error data)))
                                    nil)})))))

;; ===========================================================================
;; Verdict schemas — the typed payload each region produces at idle.
;; ===========================================================================

(def ^:private experimenter-verdict-schema
  "What the experimenter must submit at the end of EVERY turn.

   `:status` drives chart routing:
     :proposed-new-version — chart routes the summary+approach to the tester
     :done                 — chart terminates into :finished
     :stuck                — chart terminates into :aborted"
  [:map
   [:status [:enum :proposed-new-version :done :stuck]]
   [:summary :string]
   [:approach {:optional true} :string]                     ;; only when :proposed-new-version
   [:best-timing {:optional true} :string]])                ;; only when :done

(def ^:private tester-verdict-schema
  "What the tester must submit at the end of EVERY turn.

   `:status` drives chart routing:
     :pass — chart wakes the experimenter with the summary
     :fail — chart wakes the experimenter with summary + details"
  [:map
   [:status [:enum :pass :fail]]
   [:summary :string]
   [:details {:optional true} :string]])                    ;; only when :fail

;; ===========================================================================
;; LLM regions
;;
;; Conversation params are authored as flat keys: static config is a literal;
;; only the data-dependent slots (`:system`, `:message`) are `(fn [_ data])`.
;; ===========================================================================

(defn- experimenter-region []
  (state {:id :experimenter :initial :experimenter-running}
    (state {:id :experimenter-running}
      (h/llm-conversation
        {:id             "experimenter"
         :system         (fn [_ data] (p/render :experimenter data))
         :real-tools     [:fs/read :fs/write :fs/edit :shell/run]
         :chart-tools    [{:owner :exp-repl}]
         :verdict-schema experimenter-verdict-schema
         :message        (fn [_ data]
                           (str "Begin work on `com.example.matrix/mult` in `"
                             (:project-dir data) "/src/com/example/matrix.clj`. "
                             "Follow the instructions exactly."))}))
    (final {:id :experimenter-finished})))

(defn- tester-region []
  (state {:id :tester :initial :tester-running}
    (state {:id :tester-running}
      (h/llm-conversation
        ;; No :message — the tester parks in :awaiting-user until the
        ;; experimenter's first verdict arrives via tell-other-llm!.
        {:id             "tester"
         :system         (fn [_ data] (p/render :tester data))
         :real-tools     [:fs/read :fs/write :fs/edit]
         :chart-tools    [{:owner :test-repl}]
         :verdict-schema tester-verdict-schema}))
    (final {:id :tester-finished})))

;; ===========================================================================
;; Routing — at idle boundaries, the chart inspects :verdict and either
;; nudges a peer via tell-other-llm! or terminates :work.
;; ===========================================================================

(defn- idle-from?
  "Cond helper. True iff this :llm.idle event came from `who` (invokeid string)."
  [who]
  (fn [_env data]
    (= who (get-in data [:_event :data :from]))))

(defn- verdict-status= [expected]
  (fn [_env data]
    (= expected (get-in data [:_event :data :verdict :status]))))

(defn- and-cond [& preds]
  (fn [env data] (every? (fn [p] (p env data)) preds)))

(defn- ->tester-msg [data]
  (let [v (get-in data [:_event :data :verdict])]
    (str "EXPERIMENTER ANNOUNCEMENT — new candidate ready\n\n"
      "Summary: " (:summary v) "\n"
      "Approach: " (or (:approach v) "(no approach detail)") "\n\n"
      "The source file at `src/com/example/matrix.clj` has been updated and reloaded in the\n"
      "experimenter's REPL. Reload it in YOUR REPL, run your correctness tests, add new\n"
      "edge cases if you can think of any, and end your turn by submitting your verdict.")))

(defn- ->experimenter-pass-msg [data]
  (let [v (get-in data [:_event :data :verdict])]
    (str "TESTER VERDICT — PASSED\n\n"
      (:summary v) "\n\n"
      "You may refine for performance and submit another :proposed-new-version,\n"
      "or — if you're satisfied with the current performance — submit :done.")))

(defn- ->experimenter-fail-msg [data]
  (let [v (get-in data [:_event :data :verdict])]
    (str "TESTER VERDICT — FAILED\n\n"
      "Summary: " (:summary v) "\n\n"
      "Details:\n" (or (:details v) "(no details provided)") "\n\n"
      "Fix the bug and submit a new :proposed-new-version.")))

;; ===========================================================================
;; Chart
;; ===========================================================================

(defn- init-derivations
  "Normalize :project-dir, derive file paths, set defaults."
  [_env data]
  (let [project (:project-dir data)
        _       (assert (string? project) ":project-dir (string) required — pass via --input")
        abs     (.getAbsolutePath (io/file project))]
    [(ops/assign :project-dir abs)
     (ops/assign :source-path (str abs "/src/com/example/matrix.clj"))
     (ops/assign :test-path (str abs "/test/com/example/matrix_test.clj"))
     (ops/assign :max-iterations (or (:max-iterations data) 5))]))

(def agent
  (chart/statechart
    {:initial :run}

    (state {:id :run :initial :init}

      (state {:id :init}
        (on-entry {} (script {:expr init-derivations}))
        (transition {:target :work}))

      (parallel {:id :work}

        (exp-repl-region)
        (test-repl-region)
        (experimenter-region)
        (tester-region)

        ;; --- routing: experimenter idled with :proposed-new-version
        (transition
          {:event :llm.idle
           :type  :internal
           :cond  (and-cond (idle-from? "experimenter")
                    (verdict-status= :proposed-new-version))}
          (script
            {:expr (fn [env data]
                     (h/tell-other-llm! env "tester" (->tester-msg data))
                     nil)}))

        ;; --- routing: tester idled with :pass
        (transition
          {:event :llm.idle
           :type  :internal
           :cond  (and-cond (idle-from? "tester")
                    (verdict-status= :pass))}
          (script
            {:expr (fn [env data]
                     (h/tell-other-llm! env "experimenter" (->experimenter-pass-msg data))
                     nil)}))

        ;; --- routing: tester idled with :fail
        (transition
          {:event :llm.idle
           :type  :internal
           :cond  (and-cond (idle-from? "tester")
                    (verdict-status= :fail))}
          (script
            {:expr (fn [env data]
                     (h/tell-other-llm! env "experimenter" (->experimenter-fail-msg data))
                     nil)})))

      ;; --- termination: experimenter idled with :done ---
      (transition
        {:event  :llm.idle
         :target :finished
         :cond   (and-cond (idle-from? "experimenter")
                   (verdict-status= :done))}
        (script {:expr (fn [_env data]
                         (let [v (get-in data [:_event :data :verdict])]
                           [(ops/assign :final-summary (:summary v))
                            (ops/assign :best-timing (or (:best-timing v) "(not provided)"))]))}))

      ;; --- termination: experimenter idled with :stuck ---
      (transition
        {:event  :llm.idle
         :target :aborted
         :cond   (and-cond (idle-from? "experimenter")
                   (verdict-status= :stuck))}
        (script {:expr (fn [_env data]
                         [(ops/assign :abort-reason
                            (str "experimenter is stuck: "
                              (get-in data [:_event :data :verdict :summary])))])}))

      ;; --- termination: either REPL failed to start ---
      (transition {:event :exp-repl/failed :target :aborted}
        (script {:expr (fn [_ data]
                         [(ops/assign :abort-reason
                            (str "experimenter REPL failed: "
                              (get-in data [:_event :data :reason])))])}))
      (transition {:event :test-repl/failed :target :aborted}
        (script {:expr (fn [_ data]
                         [(ops/assign :abort-reason
                            (str "tester REPL failed: "
                              (get-in data [:_event :data :reason])))])}))

      (final {:id :finished})
      (final {:id :aborted}))))
