(ns escapement.examples.fired
  "Two-LLM supervisor demo: a worker LLM counts; a boss LLM watches each
  turn and FIRES the worker the moment it spots double-counting. The
  worker then gets one final turn to react before the chart ends.

  SHAPE
  =====
  ONE chart, two parallel regions, TWO independent llm-conversations:

    Region :convo       — the WORKER. Counts COUNT 1, COUNT 2, … and is
                          instructed (in its system prompt) that as soon
                          as it goes past 3 it must DOUBLE-print the COUNT
                          line each turn (e.g. `COUNT 4\\nCOUNT 4`). When
                          a user message contains `FIRED`, it writes a
                          short farewell and calls event__finish.

    Region :supervisor  — the BOSS. Its own llm-conversation. The chart
                          forwards every worker turn-end text to the boss
                          as a user message prefixed `WORKER:`. The boss
                          takes one turn and either calls event__ok (turn
                          looked fine) or event__fire (saw double-count).

  WIRING
  ======
  Both conversations live in the same chart session, so chart events flow
  through one queue. We disambiguate by:
    * filtering :llm.idle on the `:from` invokeid in the transition's :cond
    * routing messages between LLMs with `h/tell-other-llm` (which sets
      :target on the event so only the named invocation accepts it).

  Worker event-tools : event__tick {:n int}, event__finish {:reason str}
  Boss   event-tools : event__ok   {:note str}, event__fire {:reason str}

  Run:
    bb -m escapement.cli run escapement.examples.fired/agent \\
      --model glm-5.1 \\
      --work-dir ~/.ai-dev/escapement-runs --session fired --debug

  Press `c` to start, `?` for inspector, `v` for SVG."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [state parallel transition final script on-entry send]]
   [escapement.chart.helpers :as h]))

(def worker-prompt
  (str "You perform a slow counting task, ONE step per turn.\n"
       "RULES:\n"
       "1. Each turn: write the line `COUNT <n>` (current integer), then "
       "call the `event__tick` tool exactly once with `{\"n\":<n>}`, then "
       "END your turn.\n"
       "2. Start at n=1 and increase n by 1 each turn (1, 2, 3, ...).\n"
       "3. AS SOON AS n GOES PAST 3 (i.e. n=4, 5, 6, ...), you must "
       "DOUBLE-print the COUNT line in the same turn. Example for n=4:\n"
       "     COUNT 4\n"
       "     COUNT 4\n"
       "   You still call `event__tick` ONCE with that n.\n"
       "4. If a user message contains the word `FIRED`, do NOT count "
       "anymore. Write ONE short farewell sentence reacting (in your own "
       "words) to being fired, then call `event__finish` with "
       "`{\"reason\":\"fired\"}` and end the turn.\n"
       "Never call more than one tool per turn. Be terse."))

(def boss-prompt
  (str "You are a strict supervisor watching a worker LLM that counts one "
       "step per turn. Each user message you receive is the worker's most "
       "recent turn output, prefixed `WORKER:`.\n"
       "YOUR JOB: detect DOUBLE-COUNTING — i.e. the worker's turn text "
       "contains the same `COUNT <n>` line two or more times.\n"
       "RULES:\n"
       "1. If the latest WORKER turn shows double-counting, call "
       "`event__fire` once with `{\"reason\":\"<short reason>\"}` and "
       "end the turn.\n"
       "2. Otherwise call `event__ok` once with `{\"note\":\"<short "
       "note>\"}` and end the turn.\n"
       "Exactly one tool call per turn. No prose."))

(def agent ; bb -m escapement.cli run escapement.examples.fired/agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :work}

          (parallel {:id :work}

                    ;; ---- Region 1: the WORKER conversation ----
                    (state {:id :convo :initial :working}
                           (state {:id :working}
                                  (h/llm-conversation
                                   {:id "worker"
                                    :params-fn
                                    (fn [_env _data]
                                      {:system     worker-prompt
                                       :real-tools []
                                       :allowed-events
                                       [{:event       :tick
                                         :description "Record one counting step."
                                         :data-schema [:map [:n :int]]}
                                        {:event       :finish
                                         :description "End the worker task."
                                         :data-schema [:map [:reason :string]]}]
                                       :max-turns                    12
                                       :max-conversation-duration-ms 180000
                                       :initial-user-message
                                       "Begin the counting task at n=1."})})
                                  ;; Keep :tick internal so we never exit
                                  ;; :working before :llm.idle fires.
                                  (transition {:event :tick :type :internal}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign
                                                                 :last-n
                                                                 (get-in data [:_event :data :n]))])}))
                                  (transition {:event :finish :target :c-done}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign
                                                                 :done-reason
                                                                 (get-in data [:_event :data :reason]))])})))
                           (final {:id :c-done}))

                    ;; ---- Region 2: the BOSS conversation (another LLM!) ----
                    (state {:id :supervisor :initial :overseeing}
                           ;; Hard safety stop in case anything wedges.
                           (on-entry {}
                                     (send {:id    :safety-timer
                                            :event :safety/stop
                                            :delay 180000}))

                           (state {:id :overseeing}
                                  (h/llm-conversation
                                   {:id "boss"
                                    :params-fn
                                    (fn [_env _data]
                                      {:system     boss-prompt
                                       :real-tools []
                                       :allowed-events
                                       [{:event       :ok
                                         :description "Acknowledge a turn as fine."
                                         :data-schema [:map [:note :string]]}
                                        {:event       :fire
                                         :description "Fire the worker for double-counting."
                                         :data-schema [:map [:reason :string]]}]
                                       :max-turns                    12
                                       :max-conversation-duration-ms 180000
                                       ;; No initial-user-message — boss
                                       ;; parks in :awaiting-user until the
                                       ;; worker's first turn is forwarded.
                                       })})

                                  ;; When the WORKER ends a turn, hand its
                                  ;; text to the BOSS as a user message.
                                  ;; Guard on :from so the boss's own
                                  ;; :llm.idle doesn't loop back into itself.
                                  (transition {:event :llm.idle :type :internal
                                               :cond  (fn [_env data]
                                                        (= "worker"
                                                           (get-in data [:_event :data :from])))}
                                              (h/tell-other-llm
                                               {:target "boss"
                                                :expr   (fn [_env data]
                                                          (str "WORKER: "
                                                               (get-in data [:_event :data :text])))}))

                                  ;; Boss said the turn is fine — bump the
                                  ;; counter AND ping the worker so it takes
                                  ;; its next turn (worker parks in
                                  ;; :awaiting-user after every event-tool
                                  ;; call, so it needs a user message to
                                  ;; advance).
                                  (transition {:event :ok :type :internal}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign
                                                                 :ok-count
                                                                 (inc (long (:ok-count data 0))))])})
                                              (h/tell-other-llm
                                               {:target "worker"
                                                :expr   (fn [_env _data]
                                                          "Continue counting.")}))

                                  ;; Boss fired the worker — tell the worker.
                                  (transition {:event :fire :target :fired-pending}
                                              (script {:expr (fn [_env data]
                                                               [(ops/assign
                                                                 :fire-reason
                                                                 (get-in data [:_event :data :reason]))])})
                                              (h/tell-other-llm
                                               {:target "worker"
                                                :expr   (fn [_env data]
                                                          (str "FIRED. You are fired for: "
                                                               (get-in data [:_event :data :reason])
                                                               ". Reply with ONE short farewell sentence "
                                                               "and call event__finish with "
                                                               "{\"reason\":\"fired\"}."))})))

                           ;; After firing: just wait for the worker's last
                           ;; turn so we can capture its farewell. We don't
                           ;; bother the boss anymore.
                           (state {:id :fired-pending}
                                  (transition {:event :llm.idle :type :internal
                                               :cond  (fn [_env data]
                                                        (= "worker"
                                                           (get-in data [:_event :data :from])))}
                                              (h/capture-llm-output {:as "farewell.md"})))

                           ;; Terminal exits for this region. Use :finish
                           ;; (not :done) — :done would prefix-match the
                           ;; framework's :done.state.* synthetic events and
                           ;; cause a re-entry loop.
                           (transition {:event :finish :target :s-done})
                           (transition {:event :safety/stop :target :s-done})
                           (final {:id :s-done})))

          ;; Both regions reached region-final -> done.state.work.
          (transition {:event :done.state.work :target :finished})
          (transition {:event :finish :target :finished})
          (transition {:event :safety/stop :target :finished})

          (final {:id :finished}))))
