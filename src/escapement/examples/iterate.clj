(ns escapement.examples.iterate
  "M7 demo: a non-trivial coding agent that, given a spec and a target source file,
  iterates `read-spec → propose-patch → run-tests → reflect` until tests pass or
  it exhausts `:max-iterations`.

  ## Design

  One long `:llm-conversation` is bound at the top of `:work`. The chart authors
  the LLM's vocabulary by exposing four event-tools:

    * `:spec-ready`       — fired from `:read-spec` after the LLM has read the spec
    * `:patch-applied`    — fired from `:propose-patch` after `:fs/edit` has run
    * `:retry`            — fired from `:reflect` to loop back to `:propose-patch`
    * `:give-up`          — fired from `:reflect` to terminate as failure

  Test execution is NOT an LLM tool — it is a chart-controlled `script` action on
  `:run-tests` entry that calls `tools/dispatch` for `:shell/run`. The result is
  fed back to the LLM via `h/tell-llm` only on failure (success transitions to
  `:finished` immediately).

  All transitions inside `:work` are `:type :internal` so that the binding (and the
  LLM's accumulated context) is not torn down between iterations.

  ## Data model

    * `:spec-path`        — required, string path to the spec file
    * `:target-path`      — required, string path to the source file under edit
    * `:test-cmd`         — required, string shell command run via `:shell/run`
    * `:iterations`       — int, count of patches applied so far (defaults to 0)
    * `:max-iterations`   — int, cap (defaults to 3)
    * `:last-test-output` — string, populated after a test run
    * `:final-status`     — `:passed`, `:gave-up`, or `:exhausted`"
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [state transition final script on-entry]]
   [escapement.chart.helpers :as h]
   [escapement.tools.protocol :as tp]))

(def system-prompt
  (str
   "You are a focused coding agent driving a small statechart. Be brief; do not "
   "explain unless asked. The chart progresses ONLY when you call event tools.\n\n"
   "Workflow:\n"
   "1. When asked to read the spec, call `fs_read` on the spec path, then "
   "call `event__spec_ready` with a one-sentence `summary`.\n"
   "2. When asked to propose a patch, read the target file if needed via `fs_read`, "
   "then make a minimal change with `fs_edit` (prefer `fs_edit`; use `fs_write` only "
   "if you need to create a missing file). Then call `event__patch_applied` with a "
   "one-sentence `rationale`.\n"
   "3. When tests fail, the chart will tell you so. Decide: call `event__retry` "
   "with `reasoning` to attempt another patch, or `event__give_up` with `reason` if "
   "you cannot progress.\n"
   "Do not call any event tool more than once per turn. End your turn after the event tool."))

(defn- user-message-for-read-spec [data]
  (str "Step 1: read the spec at " (pr-str (:spec-path data))
       " using fs_read, then call event__spec_ready with a one-sentence summary."))

(defn- user-message-for-propose [data]
  (str "Step 2: propose a patch. Edit " (pr-str (:target-path data))
       " (use fs_read first if you need to see its current contents) so the spec is satisfied. "
       "Then call event__patch_applied. Iteration "
       (inc (or (:iterations data) 0)) " of " (or (:max-iterations data) 3) "."))

(defn- failure-message [data]
  (str "Tests failed. Output:\n" (or (:last-test-output data) "<no output>")
       "\nChoose: call event__retry with a different approach, or event__give_up if blocked."))

(defn- run-tests-action
  "Chart-side script: runs the configured test command via `:shell/run` and posts
   either `:tests-pass` or `:tests-fail` back to ourselves. Reads the registry via
   the `env`'s engine map."
  []
  (script
   {:expr
    (fn [env data]
      (let [registry (or (:escapement/tool-registry env)
                         (get-in env [:escapement/engine :tool-registry]))
            cmd      (:test-cmd data)
            {:keys [result is-error]} (if registry
                                        (tp/dispatch registry :shell/run {:command cmd})
                                        {:result "no tool-registry in env" :is-error true})
            output   (or result "")]
        ;; Emit the result-event to ourselves; assign the output into data via ops below.
        ;; We need to send via the chart's queue. Use the helper pattern: return ops, then
        ;; a second script step sends the event. Combine into a single op list + side-effect.
        (let [queue (get env :com.fulcrologic.statecharts/event-queue)
              sid   (some-> env :com.fulcrologic.statecharts/vwmem deref :com.fulcrologic.statecharts/session-id)]
          (when queue
            (com.fulcrologic.statecharts.protocols/send!
             queue env
             {:target            sid
              :source-session-id sid
              :event             (if is-error :tests-fail :tests-pass)
              :data              {:output output}})))
        [(ops/assign :last-test-output output)]))}))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :work}

          (state {:id :work :initial :read-spec}

                 (h/llm-conversation
                  {:id        "coder"
                   :params-fn
                   (fn [_env data]
                     {:system               system-prompt
                      :real-tools           [:fs/read :fs/edit :fs/write]
                      :allowed-events       [{:event       :spec-ready
                                              :data-schema [:map [:summary :string]]}
                                             {:event       :patch-applied
                                              :data-schema [:map [:rationale :string]]}
                                             {:event       :retry
                                              :data-schema [:map [:reasoning :string]]}
                                             {:event       :give-up
                                              :data-schema [:map [:reason :string]]}]
                      :initial-user-message (user-message-for-read-spec data)})})

                 (state {:id :read-spec}
                        (transition {:event :spec-ready :target :propose-patch :type :internal}))

                 (state {:id :propose-patch}
                        (on-entry {}
                                  (h/tell-llm {:expr (fn [_env data] (user-message-for-propose data))}))
                        (transition {:event :patch-applied :target :run-tests :type :internal}
                                    (script {:expr (fn [_env data]
                                                     [(ops/assign :iterations
                                                                  (inc (or (:iterations data) 0)))])})))

                 (state {:id :run-tests}
                        (on-entry {} (run-tests-action))
                        (transition {:event :tests-pass :target :finished})
                        (transition {:event :tests-fail :target :reflect :type :internal}))

                 (state {:id :reflect}
                        (on-entry {}
                                  (h/tell-llm {:expr (fn [_env data] (failure-message data))}))
                        ;; Retry only if we have headroom; otherwise mark exhausted on patch-applied returns
                        (transition {:event :retry :target :propose-patch :type :internal
                                     :cond (fn [_env data]
                                             (< (or (:iterations data) 0)
                                                (or (:max-iterations data) 3)))})
                        (transition {:event :retry :target :finished
                                     :cond (fn [_env data]
                                             (>= (or (:iterations data) 0)
                                                 (or (:max-iterations data) 3)))}
                                    (script {:expr (fn [_env _]
                                                     [(ops/assign :final-status :exhausted)])}))
                        (transition {:event :give-up :target :finished}
                                    (script {:expr (fn [_env _]
                                                     [(ops/assign :final-status :gave-up)])}))))

          (final {:id :finished}
                 (on-entry {}
                           (script {:expr (fn [_env data]
                                            ;; If we reached :finished from :run-tests via tests-pass, mark passed.
                                            (when-not (:final-status data)
                                              [(ops/assign :final-status :passed)]))}))))))
