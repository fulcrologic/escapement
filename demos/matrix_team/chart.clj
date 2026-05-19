(ns matrix-team.chart
  "Demo chart: two LLM agents collaborating on `com.example.matrix/mult`.

  The chart is a single parallel composite with four regions:

    * `:exp-repl`   — service region. Owns the experimenter's JVM nREPL
                      lifecycle (spawns, parses the port, kills on exit).
                      Exposes `:exp/eval` once ready.
    * `:test-repl`  — same shape, owns the tester's private nREPL.
    * `:experimenter` — LLM conversation. Sits in `:experimenter-waiting`
                      until its REPL is ready, then transitions into the
                      `:experimenter-running` substate that owns the
                      invocation. Pulls `:chart-tools` from `:exp-repl`.
    * `:tester`     — mirror of `:experimenter` against `:test-repl`.

  Cross-agent routing happens at the parallel level via internal
  transitions that call `tell-other-llm`. The chart terminates when the
  experimenter fires `event__experiment_done` — a top-level transition exits
  the entire parallel into the `:finished` final state. The REPL regions'
  on-exit scripts destroy their JVMs.

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
   [clojure.string :as str]
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

;; ---------------------------------------------------------------------------
;; nREPL JVM lifecycle (chart-owned)
;; ---------------------------------------------------------------------------
;;
;; Each service region spawns `clojure -M:nrepl` against demos/tools, watches
;; the merged stdout/stderr stream for the canonical line
;; `nREPL server started on port NNN`, and fires a per-region ready event
;; carrying the port. The Process is stashed in `processes` so on-exit can
;; destroy it. Two regions are tracked under distinct keys.

(defonce ^:private processes (atom {}))

(def ^:private port-line-re
  ;; nREPL.cmdline format. Emitted on stdout once the server is listening.
  #"nREPL server started on port (\d+)")

(defn- send-self!
  "Post an event back into the chart's session from an arbitrary thread.
   Safe to call from a background reader future."
  [queue sid event-kw event-data]
  (when (and queue sid)
    (sp/send! queue {}
              {:target            sid
               :source-session-id sid
               :event             event-kw
               :data              (or event-data {})})))

(defn- watch-for-port!
  "Spawn a future that drains `proc`'s stdout looking for the nREPL port
   line, then fires `ready-event` with `{:port N}`. If the stream closes
   before a port is seen, fires `failed-event` with a reason. Runs in a
   background thread so the chart microstep returns promptly."
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
  "Start `clojure -M:nrepl` in `project-dir`. Merges stderr into stdout via
   `sh -c` so we only need one reader. Stashes the resulting process under
   `region-id` in `processes`."
  [region-id project-dir queue sid ready-event failed-event]
  (let [proc (bp/process ["sh" "-c" "clojure -M:nrepl 2>&1"]
                         {:dir      project-dir
                          :in       nil
                          :shutdown bp/destroy-tree})]
    (swap! processes assoc region-id proc)
    (watch-for-port! proc queue sid ready-event failed-event)
    nil))

(defn- kill-nrepl!
  "Best-effort: destroy the JVM tied to `region-id`, if any."
  [region-id]
  (when-let [proc (get @processes region-id)]
    (try (bp/destroy-tree proc) (catch Throwable _ nil))
    (swap! processes dissoc region-id)
    nil))

(defn- start-region-script
  "Build a script element that spawns the nREPL for `region-id` against the
   chart's `:project-dir` and arranges for `ready-event`/`failed-event` to
   fire back into the session."
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

;; ---------------------------------------------------------------------------
;; REPL eval handler — shells out to clj-nrepl-eval against a private port.
;;
;; The builtin :shell/run tool does not accept stdin, so we write the code
;; under a deterministic tmp path and use bash stdin redirection inside the
;; shell command we hand off. The tmp file is overwritten per call; the
;; chart only ever has one in-flight call per port (one consumer per REPL).
;; ---------------------------------------------------------------------------

(defn- tmp-code-path
  "Per-region temp file the eval handler reads code from. One-per-region
   keeps the two regions' file writes from racing each other."
  [region-id]
  (str "/tmp/escapement/matrix-team/" (name region-id) "-eval.clj"))

(defn- write-code-tempfile!
  "Write `code` to the region's tmp eval file, creating parent dirs."
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
  "Synchronously dispatch one nREPL eval via `:shell/run` against `port`.
   Shared by the `:*-ready` handler (live calls) and the queue-drain (calls
   that landed while the REPL was still starting)."
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
  "Returns the service handler used while the region is in `:*-ready`.
   Synchronous — the nREPL is up and the response is fast (sub-second for
   most evals)."
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
;;
;; The deferring handler at `:*-starting` returns nil (deferred reply) and
;; stashes the request here. On entry to `:*-ready` we drain in a background
;; future so the chart microstep returns immediately; each queued request is
;; replayed against the live port via `run-eval!` and answered with
;; `service/post-reply`.

(defonce ^:private pending-evals (atom {}))

(defn- make-deferring-eval-handler
  "Handler attached to `:*-starting`. Captures everything needed to fulfil
   the reply later and returns nil (deferred)."
  [region-id]
  (fn [env {:keys [reply-id reply-to timeout-ms data]}]
    (swap! pending-evals update region-id (fnil conj [])
           {:reply-id    reply-id
            :reply-to    reply-to
            :timeout-ms  timeout-ms
            :code        (or (:code data) "")
            :env         env
            :enqueued-at (System/currentTimeMillis)})
    ;; nil = "the reply is in the mail" — see service/handle docstring.
    nil))

(defn- post-error-reply [env item reason]
  (service/post-reply env
                      {:reply-id (:reply-id item)
                       :reply-to (:reply-to item)
                       :result   reason
                       :is-error true}))

(defn- drain-queue-async!
  "Run every pending eval for `region-id` against `port`, posting replies
   as each one completes. Runs in a background future so the calling
   microstep returns promptly. Each item's remaining timeout is reduced by
   the time it spent queued; items that already expired get an error reply."
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
  "Abort path: fail every queued request with a uniform reason. Called on
   entry to `:*-aborted` so deferred-reply workers don't park until their
   timeout."
  [region-id reason]
  (let [pending (get @pending-evals region-id [])]
    (swap! pending-evals dissoc region-id)
    (doseq [item pending]
      (post-error-reply (:env item) item reason))))

;; ---------------------------------------------------------------------------
;; Service regions — one per private REPL.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Service regions — own their REPL JVM lifecycle.
;;
;; Each region has three substates:
;;   :*-starting   — JVM is launching; on-entry spawns process + reader future.
;;   :*-ready      — port discovered; tool is REGISTERED (on-entry) so the
;;                   gated consumer LLM's palette snapshot picks it up.
;;   :*-aborted    — final; spawn failed. A top-level transition routes this
;;                   into the chart's :aborted final.
;;
;; The compound region's on-exit destroys the JVM unconditionally.

;; The tool is REGISTERED at the compound level so consumer LLMs see it in
;; their palette snapshot from chart start, regardless of which substate the
;; region is currently in. SCXML transition precedence then selects the
;; appropriate handler:
;;
;;   :*-starting → deferring handler (queues the request, returns nil; reply
;;                                    is posted later by drain-queue-async!)
;;   :*-ready    → synchronous handler (real eval via clj-nrepl-eval)
;;   :*-aborted  → never reached, because drain-queue-with-error! drains the
;;                 queue on entry to :*-aborted; subsequent calls would hit
;;                 the deferring handler (the region is final by then, so
;;                 calls hang until tool timeout — accepted tradeoff for a
;;                 demo).

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
         (on-exit  {} (service/unregister-tool! :exp/eval))
         (on-exit  {} (script {:expr (fn [_ _] (kill-nrepl! :exp-repl))}))

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
         (on-exit  {} (service/unregister-tool! :test/eval))
         (on-exit  {} (script {:expr (fn [_ _] (kill-nrepl! :test-repl))}))

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

;; ---------------------------------------------------------------------------
;; Event declarations — shared between params-fns and routing transitions.
;; ---------------------------------------------------------------------------

(def ^:private experimenter-events
  [{:event       :new-version
    :description "Announce a new candidate implementation of `com.example.matrix/mult`. The tester will receive your summary, run correctness tests, and reply. This tool call BLOCKS until the tester reports back — the tool_result you receive IS the tester's verdict. Do not call other tools while waiting; the framework handles the wait for you."
    :data-schema [:map
                  [:summary :string]
                  [:approach :string]]
    ;; Deferred reply: this tool blocks until the tester fires
    ;; `:tester/passed` or `:tester/failed`. The chart's transitions on
    ;; those events call `h/complete-call` to wire the verdict back into
    ;; THIS tool_result.
    :awaits      {:on           #{:tester/passed :tester/failed}
                  :error-events #{:tester/failed}
                  :timeout-ms   1200000}}    ;; 20 min — the tester writes tests + reloads + runs

   {:event       :experiment/done
    :description "End the experiment. Call this once you are satisfied with the final implementation. Provide a one-sentence summary of the winning approach and the best timing you observed."
    :data-schema [:map
                  [:final-summary :string]
                  [:best-timing :string]]}])

(def ^:private tester-events
  [{:event       :tester/passed
    :description "Report that the latest version of `com.example.matrix/mult` passes all your correctness tests."
    :data-schema [:map [:summary :string]]}
   {:event       :tester/failed
    :description "Report one or more correctness failures in the latest version of `com.example.matrix/mult`."
    :data-schema [:map
                  [:summary :string]
                  [:details :string]]}])

;; ---------------------------------------------------------------------------
;; Per-agent params-fns
;; ---------------------------------------------------------------------------

(defn- experimenter-params [data]
  {:system               (p/render :experimenter data)
   :real-tools           [:fs/read :fs/write :fs/edit :shell/run]
   :allowed-events       experimenter-events
   :chart-tools          [{:owner :exp-repl}]
   :initial-user-message
   (str "Begin work on `com.example.matrix/mult` in `"
        (:project-dir data) "/src/com/example/matrix.clj`. "
        "Follow the instructions exactly.")})

(defn- tester-params [data]
  {:system          (p/render :tester data)
   :real-tools      [:fs/read :fs/write :fs/edit]
   :allowed-events  tester-events
   :chart-tools     [{:owner :test-repl}]
   ;; No :initial-user-message — the tester parks in :awaiting-user until the
   ;; experimenter's first :new-version arrives via tell-other-llm.
   })

;; ---------------------------------------------------------------------------
;; LLM regions
;; ---------------------------------------------------------------------------

;; The LLM regions start immediately at chart entry; they do NOT wait for
;; their REPL to be ready. Because tool registration happens on-entry to the
;; compound :exp-repl / :test-repl states, the palette snapshot taken when
;; each invocation starts (phase 4 of the same microstep) already includes
;; the region tool. If an LLM calls its eval tool before the REPL is up, the
;; deferring handler in :*-starting queues the request and the matching
;; drain-queue-async! in :*-ready resolves it.

(defn- experimenter-region []
  (state {:id :experimenter :initial :experimenter-running}
         (state {:id :experimenter-running}
                (h/llm-conversation
                 {:id        "experimenter"
                  :params-fn (fn [_env data] (experimenter-params data))}))
         (final {:id :experimenter-finished})))

(defn- tester-region []
  (state {:id :tester :initial :tester-running}
         (state {:id :tester-running}
                (h/llm-conversation
                 {:id        "tester"
                  :params-fn (fn [_env data] (tester-params data))}))
         (final {:id :tester-finished})))

;; ---------------------------------------------------------------------------
;; Routing transitions
;; ---------------------------------------------------------------------------
;;
;; Each cross-agent event lands here as a chart-level event. The transition
;; uses :type :internal so the parallel is not exited; the script body is
;; the routing payload.

;; The tester->experimenter direction is now peer-RPC; the verdict arrives
;; as the tool_result of the experimenter's `event__new_version` call. No
;; user-message formatter is needed for that direction — the framework
;; pr-strs the answering event's :data into the tool_result content.

(defn- ->tester-msg [data]
  (let [ev (get-in data [:_event :data])]
    (str "EXPERIMENTER ANNOUNCEMENT — new candidate ready\n\n"
         "Summary: " (:summary ev) "\n"
         "Approach: " (:approach ev) "\n\n"
         "The source file at `src/com/example/matrix.clj` has been updated and reloaded in the\n"
         "experimenter's REPL. Reload it in YOUR REPL, run your correctness tests, add new\n"
         "edge cases if you can think of any, and report results via event__tester_passed\n"
         "or event__tester_failed.")))

;; ---------------------------------------------------------------------------
;; Chart
;; ---------------------------------------------------------------------------

(defn- init-derivations
  "Normalize :project-dir, derive file paths, set defaults. Ports are NOT
   user-supplied — the service regions spawn their own JVMs and populate
   `:experimenter-port` / `:tester-port` when each nREPL reports its port."
  [_env data]
  (let [project (:project-dir data)
        _       (assert (string? project) ":project-dir (string) required — pass via --input")
        abs     (.getAbsolutePath (io/file project))]
    [(ops/assign :project-dir abs)
     (ops/assign :source-path (str abs "/src/com/example/matrix.clj"))
     (ops/assign :test-path   (str abs "/test/com/example/matrix_test.clj"))
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

                    ;; --- routing: experimenter -> tester ---
                    (transition
                     {:event :new-version :type :internal}
                     (h/tell-other-llm
                      {:target "tester"
                       :expr   (fn [_env data] (->tester-msg data))}))

                    ;; --- routing: tester -> experimenter (deferred reply) ---
                    ;; `event__new_version` on the experimenter side declares
                    ;; `:awaits #{:tester/passed :tester/failed}`.
                    ;; `complete-call` here lifts the originating reply-id
                    ;; from the chart's in-flight slot and posts an
                    ;; `:escapement.tool/reply` that the experimenter's
                    ;; worker is polling for, so the tester's verdict
                    ;; arrives as the `tool_result` of the experimenter's
                    ;; announcement call (rather than as an unsolicited
                    ;; user message that would never be consumed while the
                    ;; experimenter chains tool_use blocks).
                    (transition {:event :tester/passed :type :internal}
                                (h/complete-call))
                    (transition {:event :tester/failed :type :internal}
                                (h/complete-call)))

          ;; --- termination: experimenter declares done ---
          (transition {:event :experiment/done :target :finished}
                      (script {:expr (fn [_env data]
                                       [(ops/assign :final-summary
                                                    (get-in data [:_event :data :final-summary]))
                                        (ops/assign :best-timing
                                                    (get-in data [:_event :data :best-timing]))])}))

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
