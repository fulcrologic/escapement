(ns escapement.examples.steer-midturn
  "Mid-turn steering variant — characterizes steering LATENCY by injecting
  the steer via a NON-turn-end path (a region-tool reply at the first tool
  boundary) instead of the between-turn `:llm.idle` hook used by
  `steered_convo.clj`.

  WHY A DIFFERENT INJECTION PATH (the experiment)
  -----------------------------------------------
  `steered_convo.clj` injects on the FIRST `:llm.idle` — the
  primitive-guaranteed *turn-end* boundary. That is the between-turn
  baseline (001 §P0/§P2): the worker is parked in `:awaiting-user`, drains
  its `user-msg-queue`, and the steer lands on the VERY NEXT turn (latency
  ≈ 1 turn).

  This chart injects from a REGION-TOOL handler instead. A region-tool is
  dispatched *synchronously inside the LLM turn* — the chart's handler runs
  while the turn is still in flight, BEFORE that turn has ended
  (`llm_conversation.clj:593-636`: the request event is posted to the
  parent, the handler replies, and the reply is fed back into the SAME
  conversation as a `tool_result`; `:posted-event?` is deliberately NOT set,
  so the worker does NOT park — the turn keeps going). Calling `h/tell-llm`
  from that handler is therefore a genuinely near-mid-turn injection point.

  THE LATENCY FINDING THIS CHART MAKES VISIBLE
  --------------------------------------------
  Even though the injection happens mid-turn, `h/tell-llm` →
  `:llm.user-message` → `forward-event!` still only `.offer`s the steer text
  onto `user-msg-queue` (`helpers.clj:240-244`,
  `llm_conversation.clj` `forward-event!`). That queue is drained ONLY in
  the `:awaiting-user` branch, which is entered ONLY after a turn ends
  (`convo-turn-experiments/report.md` §Steerability; 001 §P0). So the steer
  is BUFFERED until the in-flight turn ends and is applied on the next
  turn — exactly like the between-turn path.

  ==> Empirical conclusion the transcript proves: the injection PATH does
      not change the latency. Mid-turn (region-tool) injection and
      between-turn (`:llm.idle`) injection both yield the SAME latency of
      ≈1 turn, because the worker only ever consumes steers at the
      turn-end / `:awaiting-user` drain. This is a FINDING, not a failure
      (acceptance criterion: the chart still RUNS and makes the buffering
      latency visible).

  HOW THE CHART GUARANTEES A FIRST TOOL BOUNDARY
  ----------------------------------------------
  Region `:probe` is a service region that registers the region-tool
  `region__steer_probe`. The system prompt orders the model to call
  `region__steer_probe` once at the START of every turn (with the current
  turn number `t`) BEFORE it counts. That call is the first tool boundary
  of turn 1, so the steer is injected during turn 1, mid-turn. The handler
  fires the steer exactly once (one-shot `:steer-sent?` flag) and replies
  with an ack the model must echo, so the probe call is itself grepable.

  LATENCY-MEASUREMENT RECIPE (run `<run>/transcript.jsonl` = T)
  ------------------------------------------------------------
  The prompt forces ≥2 turns and emits model-independent tokens:
    - every turn the model first calls region__steer_probe -> a
      `runner/event-processed event-name=steer-probe` (the mid-turn
      boundary) AND a `llm/tool-result` whose content contains
      `PROBE-ACK t=<t>`.
    - pre-steer turns assistant text contains `COUNT <n>`.
    - post-steer turns assistant text contains `STEERED MANGO`.
    - the injected steer text contains `STOP counting now`.

  1. Injection boundary (mid-turn, NOT a turn end):
       jq -c 'select(.event==\"runner/event-processed\"
                      and .data[\"event-name\"]==\"steer-probe\")' T
     -> first occurrence :seq = INJECT_SEQ. Confirm an `:llm.idle` for the
        SAME turn occurs at a LATER :seq than INJECT_SEQ (proves the steer
        was posted mid-turn, before that turn ended):
       jq -c 'select(.event==\"runner/event-processed\"
                      and .data[\"event-name\"]==\"llm.idle\")' T
       -> first llm.idle :seq  >  INJECT_SEQ.

  2. The steer was actually injected:
       jq -c 'select(.event==\"llm/user-message\")' T | wc -l   # >= 1
       and that event's .data.text contains \"STOP counting now\".

  3. Pre-steer evidence (a `COUNT ` turn before the steer landed):
       jq -c 'select(.event==\"llm/response\")
              | {seq, t:[.data.content[]?|select(.type==\"text\")|.text]}' T
       -> some response with `COUNT ` at :seq < (llm/user-message :seq).

  4. Post-steer effect + LATENCY:
       -> first response containing `STEERED MANGO` at :seq = EFFECT_SEQ.
       Latency in TURNS = (PROBE-ACK turn number `t` of the EFFECT_SEQ
       response) minus (the `t` carried in the steer-probe call that
       triggered injection). Because the probe is called once per turn and
       its `t` is echoed in `PROBE-ACK t=<t>`, you can count whole turns
       between inject and first reflection directly from the transcript —
       no wall-clock guessing. Expected from the design above: latency = 1
       turn (buffered-until-next-turn), identical to the between-turn
       `:llm.idle` path — the documented finding.

  Always terminates: `:max-turns` + `:max-conversation-duration-ms` on the
  conversation, plus a hard `:safety/stop` timer that forces the chart to
  `:finished` even if the model never calls `event__done`.

  Run (no real LLM needed to load/validate; live run is task 007):
    bb -m escapement.cli run escapement.examples.steer-midturn/agent \\
      --no-tui --model glm-4.6 \\
      --work-dir /home/naomarik/.ai-dev/convo-steering-demos/runs \\
      --session steerMid"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements
     :refer [final on-entry on-exit parallel script send state transition]]
    [escapement.chart.helpers :as h]
    [escapement.chart.service :as service]))

(def system-prompt
  (str "You perform a slow counting task, ONE step per turn.\n"
    "RULES:\n"
    "1. At the START of EVERY turn, FIRST call the `region__steer_probe` "
    "tool exactly once with `{\"t\":<this turn's number>}` (turn 1, 2, "
    "3, ...). It returns a string `PROBE-ACK t=<t>`; copy that exact "
    "string as the FIRST line of your reply.\n"
    "2. THEN, on the SAME turn, write the line `COUNT <n>` (n starts at "
    "1 and increases by 1 each turn) and call the `event__tick` tool "
    "exactly once with `{\"n\":<n>}`, then END your turn.\n"
    "3. Keep doing this turn after turn until a user message tells you "
    "to STOP.\n"
    "4. If a user message says STOP: from that turn on, still call "
    "`region__steer_probe` first and echo its `PROBE-ACK t=<t>` line, "
    "but then write ONLY the line `STEERED MANGO` (no more `COUNT`) and "
    "call `event__tick` with `{\"n\":0}`. After two `STEERED MANGO` "
    "turns, call the `event__done` tool once with "
    "`{\"reason\":\"steered\"}` and end your turn.\n"
    "Be terse. One `event__tick` per turn; never skip the "
    "`region__steer_probe` call."))

(def steer-message
  (str "STOP counting now. From this turn on your entire reply is the "
    "`PROBE-ACK t=<t>` line followed by the line `STEERED MANGO` and a "
    "call to `event__tick` with `{\"n\":0}`. After two STEERED turns "
    "call `event__done` with `{\"reason\":\"steered\"}`."))

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
               ;; autoforward? defaults true -> tell-llm
               ;; (fired from the region-tool handler)
               ;; reaches this conversation. region-tools
               ;; are auto-discovered from the service
               ;; registry — no :real-tools needed here.
               :system         system-prompt
               :real-tools     []
               :allowed-events
               [{:event       :tick
                 :description "Record one counting step."
                 :data-schema [:map [:n :int]]}
                {:event       :done
                 :description "End the counting task."
                 :data-schema [:map [:reason :string]]}]
               :max-turns      8
               :budget-ms      120000
               :message        "Begin the counting task at turn 1, n=1."})
            ;; 001 §P1 ORDERING RULE: keep the
            ;; event-tool's chart event :type :internal
            ;; so it never tears down :counting before
            ;; the parent observes it / :llm.idle.
            (transition {:event :tick :type :internal}
              (script {:expr (fn [_env data]
                               [(ops/assign
                                  :last-tick
                                  (get-in data [:_event :data :n]))])}))
            (transition {:event :done :target :c-done}
              (script {:expr (fn [_env data]
                               [(ops/assign
                                  :done-reason
                                  (get-in data [:_event :data :reason]))])})))
          (final {:id :c-done}))

        ;; ---- Region 2: service region exposing a region-tool ----
        ;; The region-tool handler runs MID-TURN (synchronous
        ;; request/reply inside the LLM turn). On the FIRST probe
        ;; call it injects the steer via h/tell-llm — a non-turn-
        ;; end injection path. The steer is still buffered until
        ;; the turn ends (documented latency finding, see ns
        ;; docstring).
        (state {:id :probe :initial :serving}

          ;; Hard safety stop: if the model never calls
          ;; event__done, force the whole chart down after a
          ;; generous wall-clock budget.
          (on-entry {}
            (send {:id    :safety-timer
                   :event :safety/stop
                   :delay 150000}))

          (state {:id :serving}
            ;; Register the region-tool on entry; remove
            ;; it on exit (service-region contract).
            (on-entry {}
              (service/register-tool!
                {:tool         :steer-probe
                 :description
                 (str "Call this exactly once at the start of "
                   "every turn with the current turn number "
                   "before doing anything else. Returns the "
                   "string PROBE-ACK t=<t> which you must echo.")
                 :input-schema [:map [:t :int]]}))
            (on-exit {}
              (service/unregister-tool! :steer-probe))

            ;; Mid-turn handler. Records the probe turn
            ;; number; on the FIRST call it also fires
            ;; the steer (one-shot via :steer-sent?).
            ;; The synchronous string reply is what the
            ;; LLM echoes (PROBE-ACK t=<t>), making the
            ;; injection boundary grepable per-turn.
            (service/handle
              :steer-probe
              (fn [_env request]
                ;; Synchronous reply the LLM echoes as
                ;; `PROBE-ACK t=<t>` (first line of its
                ;; turn) — makes the per-turn mid-turn
                ;; boundary grepable. The actual steer
                ;; injection + one-shot bookkeeping is
                ;; done by the :steer-probe transitions
                ;; below (the same request event is also
                ;; posted to the parent chart at
                ;; llm_conversation.clj:614).
                {:result   (str "PROBE-ACK t="
                             (get-in request [:data :t]))
                 :is-error false})))

          ;; The region-tool request event (:steer-probe) is
          ;; ALSO posted to the parent chart
          ;; (llm_conversation.clj:614) — observe it here to
          ;; (a) record the injection turn and (b) fire the
          ;; one-shot steer via h/tell-llm on the FIRST probe.
          ;; :type :internal so :serving (which owns the tool
          ;; registration) is never torn down mid-turn.
          (transition {:event :steer-probe
                       :type  :internal
                       :cond  (fn [_env data]
                                (not (:steer-sent? data)))}
            (script {:expr (fn [_env data]
                             [(ops/assign :steer-sent? true)
                              (ops/assign
                                :inject-turn
                                (get-in data [:_event :data :t]))])})
            (h/tell-llm
              {:expr (fn [_env _data] steer-message)}))
          ;; Subsequent probes after the steer: just record
          ;; the latest probed turn (latency bookkeeping).
          (transition {:event :steer-probe
                       :type  :internal
                       :cond  (fn [_env data]
                                (boolean (:steer-sent? data)))}
            (script {:expr (fn [_env data]
                             [(ops/assign
                                :last-probe-turn
                                (get-in data [:_event :data :t]))])}))

          ;; Region exit so the parallel join can complete
          ;; (mirrors steered_convo.clj's monitor exit, 001
          ;; §1 / 002 Notes). Reached on the conversation's
          ;; :done, the safety timer, or the turn-end hook.
          (transition {:event :done :target :p-done})
          (transition {:event :safety/stop :target :p-done})
          (final {:id :p-done})))

      ;; Both regions reached their region final -> parallel raises
      ;; done.state.work; the clean, non-wedging join.
      (transition {:event :done.state.work :target :finished})
      ;; Belt-and-braces terminators.
      (transition {:event :done :target :finished})
      (transition {:event :safety/stop :target :finished})

      (final {:id :finished}))))
