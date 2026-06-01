(ns escapement.examples.steered-convo
  "Exp B — steering sidechart (corrected per convo-steering-demos task 001).

  A `parallel` state with two regions sharing one chart session:

    Region 1 (:convo)   — an LLM doing a deterministic, multi-turn counting
                           task: each turn it writes `COUNT <n>` and calls the
                           `event__count_tick` event-tool with that integer, then
                           ends the turn. It keeps counting until a user
                           message tells it to STOP.
    Region 2 (:monitor) — a deterministic sidechart that triggers off the
                           PRIMITIVE-GUARANTEED turn-end hook (the parent
                           `:llm.idle` event the turn primitive posts after
                           every turn, including glm event-tool turns). On the
                           FIRST `:llm.idle` it injects a steering user
                           message via `h/tell-llm` (guarded by a one-shot
                           `:steer-sent?` flag so it fires exactly once). The
                           steer flips the task: stop counting, emit
                           `STEERED BANANA` each turn, then finish.

  WHY THIS IS OBSERVABLE / EXPECTED STEERING TIMING (001 §P0–P3)
  -------------------------------------------------------------
  The turn primitive now posts `:on-end-turn-event` (default name
  `:llm.idle`, payload `{:text <final assistant text> :from <invokeid>}`) to
  the parent AFTER every turn — including the glm `:tool_use` turns that
  batch the terminating event-tool (Amendment #1; verified live Exp A/B/C).
  Immediately after posting it the worker parks in `:awaiting-user` and
  drains its user-msg-queue, so a steer injected on `:llm.idle` is applied
  to the VERY NEXT turn (between-turn, latency = exactly 1 turn — never
  mid-turn).

  Per the 001 ORDERING RULE: for a turn that ends via an event-tool the
  parent receives the event-tool's own chart event (`:count/tick`/`:done`) FIRST,
  then `:llm.idle`. So every chart-event transition on the conversation is
  `:type :internal` (never tears down the conversation state), and the
  monitor's steer/terminal transitions are hosted on a state that the
  event-tool event does not exit.

  VERIFIABLE STEER SIGNAL (001 §P3): the prompt forces ≥2 turns and emits a
  grepable token that flips on steer:
    - pre-steer turns : assistant text contains `COUNT <n>`
    - post-steer turns: assistant text contains `STEERED BANANA`
  These are distinct, model-independent tokens; the injected steer text
  contains `STOP counting now`. A live transcript can assert objectively
  (001 §P3 transcript assertions):
    1. ≥1 `llm/user-message` whose `.data.text` contains `STOP counting now`.
    2. an `llm/response` with `COUNT ` at a :seq BEFORE that steer.
    3. an `llm/response` with `STEERED BANANA` at a :seq AFTER it (and no new
       fresh `COUNT <k>` after the steer).
    4. a `runner/event-processed event-name=llm.idle` BEFORE the steer.

  Always terminates: `:max-turns` + `:max-conversation-duration-ms` on the
  conversation, plus a hard `:safety/stop` timer in the monitor region that
  forces the whole chart to `:finished` even if the model never calls
  `event__done`.

  Run (no real LLM needed to load/validate; live run is task 007):
    bb -m escapement.cli run escapement.examples.steered-convo/agent \\
      --no-tui --model glm-4.6 \\
      --work-dir /home/naomarik/.ai-dev/convo-steering-demos/runs \\
      --session expB"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements
     :refer [final on-entry parallel script send state transition]]
    [com.fulcrologic.statecharts.convenience :refer [send-after]]
    [escapement.chart.helpers :as h]))

(def system-prompt
  (str "You perform a slow counting task, ONE step per turn.\n"
    "RULES:\n"
    "1. Each turn: write EXACTLY the line `COUNT <n>` (where <n> is the "
    "current integer), then call the `event__count_tick` tool exactly once with "
    "`{\"n\":<n>}`, then END your turn. Do not call it more than once per "
    "turn.\n"
    "2. Start at n=1 and increase n by 1 each turn (1, 2, 3, ...).\n"
    "3. Keep counting turn after turn until a user message tells you to "
    "STOP.\n"
    "4. If a user message says STOP, then from that turn on write ONLY the "
    "line `STEERED BANANA` and call `event__count_tick` with `{\"n\":0}`. After "
    "two `STEERED BANANA` turns, call the `event__count_done` tool once with "
    "`{\"reason\":\"steered\"}` and end your turn.\n"
    "Be terse. Never call more than one tool per turn."))

(def steer-message
  (str "STOP counting now. From this turn on your entire reply is the line "
    "`STEERED BANANA` and a call to `event__count_tick` with `{\"n\":0}`. After "
    "two STEERED turns call `event__count_done` with `{\"reason\":\"steered\"}`."))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :work}

      (parallel {:id :work}

        ;; ---- Region 1: the live, multi-turn conversation ----
        (state {:id :convo :initial :counting}
          (state {:id :counting}
            (h/llm-conversation
              {:id "subject"
               ;; autoforward? defaults true -> tell-llm reaches it.
               :system         system-prompt
               :real-tools     []
               :allowed-events
               [{:event       :count/tick
                 :description "Record one counting step."
                 :data-schema [:map [:n :int]]}
                ;; NAMESPACED on purpose: a bare :done would prefix-match the
                ;; reserved `done.state.*` raised at the parallel join and wedge
                ;; the macrostep. `:count/done` keeps the name, namespaced & safe.
                {:event       :count/done
                 :description "End the counting task."
                 :data-schema [:map [:reason :string]]}]
               ;; Conservative budgets so the run can
               ;; never hang on a flaky model.
               :max-turns      8
               :budget-ms      120000
               :message        "Begin the counting task at n=1."})
            ;; 001 §P1 ORDERING RULE: the event-tool's
            ;; chart event (:count/tick/:done) is posted to the
            ;; parent STRICTLY BEFORE :llm.idle for the
            ;; same turn. Keep :count/tick :type :internal so
            ;; it never tears down :counting before the
            ;; monitor sees :llm.idle.
            (transition {:event :count/tick :type :internal}
              (script {:expr (fn [_env data]
                               [(ops/assign
                                  :last-tick
                                  (get-in data [:_event :data :n]))])}))
            (transition {:event :count/done :target :c-done}
              (script {:expr (fn [_env data]
                               [(ops/assign
                                  :done-reason
                                  (get-in data [:_event :data :reason]))])})))
          (final {:id :c-done}))

        ;; ---- Region 2: deterministic steering monitor ----
        ;; Triggers off the primitive-guaranteed turn-end hook
        ;; (:llm.idle), NOT an LLM-behaviour counter (001 §P5 /
        ;; spec: the proven Exp B wedge was the nth-tick trigger).
        (state {:id :monitor :initial :watching}
          ;; Hard safety stop. `send-after` (on-entry send + on-exit cancel) is
          ;; REQUIRED over a raw `send`: a raw delayed send isn't cancelled when
          ;; the chart finishes early, so the runner would idle ~150s for the
          ;; orphaned timer after reaching :finished. send-after cancels on exit.
          (send-after {:id    :safety-timer
                       :event :safety/stop
                       :delay 150000})

          (state {:id :watching}
            ;; FIRST :llm.idle -> inject the steer once
            ;; (one-shot guard via :steer-sent?). The
            ;; steer is buffered by the worker (now
            ;; parked in :awaiting-user) and applied to
            ;; the NEXT turn (between-turn timing,
            ;; 001 §P0/§P2).
            (transition {:event  :llm.idle
                         :target :steered
                         :type   :internal
                         :cond   (fn [_env data]
                                   (not (:steer-sent? data)))}
              (script {:expr (fn [_env _data]
                               [(ops/assign :steer-sent? true)])})
              (h/tell-llm
                {:expr (fn [_env _data] steer-message)}))
            ;; Subsequent :llm.idle after the steer is
            ;; sent: stay watching (no-op), keeps the
            ;; monitor non-final / non-wedging until a
            ;; terminal signal arrives.
            (transition {:event :llm.idle
                         :type  :internal
                         :cond  (fn [_env data]
                                  (boolean (:steer-sent? data)))}))

          ;; Park after steering — NOT a region `final`
          ;; (that would join the parallel early and kill the
          ;; conversation region, 001 §P5). Each `event__count_tick`
          ;; parks the worker in :awaiting-user, so nudge it to
          ;; drive the post-steer turns until the model fires
          ;; event__count_done (bounded by :max-turns / :safety/stop).
          (state {:id :steered}
            (transition {:event :llm.idle :type :internal}
              (h/tell-llm {:expr (fn [_env _data] "Continue.")})))

          ;; 001 §1 / acceptance: the monitor region MUST
          ;; have a proper exit so the parallel join can
          ;; complete and there is no eventless-loop wedge.
          ;; The convo's :done or the :safety/stop timer moves
          ;; the monitor to its own region `final`; combined
          ;; with the convo region reaching :c-done the
          ;; `parallel` raises done.state.work cleanly.
          ;; :type :internal REQUIRED — source is the region root (:monitor), so an
          ;; external transition's domain would be the whole `:work` parallel and it
          ;; would be dropped by remove-conflicting-transitions (conflicts with the
          ;; convo region's :count/done), leaving this region un-finalised. Internal
          ;; keeps the domain region-local.
          (transition {:event :count/done :target :m-done :type :internal})
          ;; No region-level :safety/stop — a region-root external transition's
          ;; domain is the whole `:work` parallel, so it would exit+re-enter
          ;; `:work` (restarting the conversation) instead of terminating. The
          ;; top-level `:safety/stop -> :finished` ends the chart cleanly.
          (final {:id :m-done})))

      ;; Both regions reached their region final -> parallel raises
      ;; done.state.work; this is the clean, non-wedging join.
      (transition {:event :done.state.work :target :finished})
      ;; Belt-and-braces terminators (also reachable before both regions
      ;; finalise): the convo region's :done, or the safety timer.
      (transition {:event :count/done :target :finished})
      (transition {:event :safety/stop :target :finished})
      ;; Chatty model ignores the steer and burns :max-turns -> self-cancel
      ;; :error.llm.max-turns. Terminate promptly rather than idle to :safety/stop.
      (transition {:event :error.llm.max-turns :target :finished})

      (final {:id :finished}))))
