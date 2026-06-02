(ns escapement.examples.supervisor
  "Task 005 — the supervisor pattern: ONE chart, parallel regions, where a
  deterministic supervisor sidechart simultaneously MONITORS the conversation,
  INJECTS exactly one steer, and CAPTURES an artifact — all three observable
  in a single transcript.

  ===========================================================================
  SHAPE
  ===========================================================================
  A `parallel` state `:work` with two regions sharing one chart session:

    Region 1 (:convo)      — an LLM doing the deterministic, multi-turn
                             counting task from task 002: each turn it writes
                             `COUNT <n>` and calls the `event__count_tick`
                             event-tool with that integer, then ends the
                             turn. It counts until a user message tells it to
                             STOP, then emits `STEERED BANANA` for two turns
                             and calls `event__done`.

    Region 2 (:supervisor) — a deterministic supervisory sidechart. It does
                             NOT model-watch a tick counter (the proven Exp B
                             wedge, task 001 §P5). Instead it triggers off the
                             PRIMITIVE-GUARANTEED turn-end hook the turn
                             primitive posts to the parent after EVERY turn
                             (the `:on-end-turn-event`, default name
                             `:llm.idle`, payload {:text <final text>
                             :from <invokeid>}). On that hook it does the
                             three supervisory jobs:
                               (a) MONITOR  — every `:llm.idle` is observed;
                                   each one records the turn into chart data
                                   (`:turns-seen`) so the monitoring is
                                   visible/assertable.
                               (b) STEER    — on the FIRST `:llm.idle` ONLY
                                   (one-shot `:steer-sent?` guard) it injects
                                   a single steering user message via
                                   `h/tell-llm`. The worker, parked in
                                   `:awaiting-user` right after posting
                                   `:llm.idle`, drains it and applies it to
                                   the NEXT turn (between-turn, latency =
                                   exactly 1 turn — task 001 §P0/§P2).
                               (c) CAPTURE  — on EVERY `:llm.idle` it captures
                                   the final assistant text of that turn to a
                                   named artifact via `h/capture-llm-output`
                                   (latest-write-wins; the last capture holds
                                   the post-steer `STEERED BANANA` text). This
                                   emits `:artifact/captured` and writes a real
                                   file at <session-dir>/artifacts/supervised.md.

  ===========================================================================
  WHY ALL THREE FIT ON `:llm.idle`  (task 001 P1 — the load-bearing fact)
  ===========================================================================
  glm-class models pack the terminating `event__done` into a `:tool_use`
  turn; a literal `end_turn` stop-reason NEVER occurs. For one logical turn
  the parent chart receives, IN THIS EXACT ORDER:
    1. the event-tool's own chart event (`:count/tick`/`:done`)
       (posted at llm_conversation.clj:574, inside the block doseq);
    2. then `:llm.idle` (default :on-end-turn-event), posted AFTER the
       doseq (llm_conversation.clj:1068), carrying {:text :from}.

  Two consequences this chart obeys:

    * In Region :convo, `:count/tick` is `:type :internal` so the event-tool's
      chart event never tears down `:counting` before the supervisor sees
      `:llm.idle` (task 001 §P1, mirrors iterate.clj's all-internal idiom).

    * The supervisor's monitor/steer/capture transitions live in a SEPARATE
      region. The convo region's `:count/tick`/`:done` never exit the supervisor's
      `:watching` state, so when `:llm.idle` is processed (strictly AFTER the
      event-tool event for that turn) the supervisor transition is still
      active and runs steer + capture. This is the parallel-region form of
      task 001 §P1(a) / §P4 (capture-before-terminate).

  ===========================================================================
  NO-WEDGE GUARANTEE  (task 001 §1 / §P5 — every region has a terminal exit)
  ===========================================================================
    * Region :convo reaches its region `final` `:c-done` on `:done`.
    * Region :supervisor reaches its region `final` `:s-done` on the convo's
      `:done` or the hard `:safety/stop` timer. The post-steer park
      `:supervising` is a
      PLAIN non-final substate (task 001 §P5: a region `final` on the steer
      boundary would join the parallel early and kill the conversation).
    * When BOTH regions reach their region final the `parallel` raises
      `done.state.work`, which targets the top-level `:finished`. Belt-and-
      braces top-level terminators (`:done`, `:safety/stop`) also reach
      `:finished` so the chart can never eventless-loop / wedge.

  ===========================================================================
  VERIFIABLE SIGNAL  (task 001 §P3 — single transcript shows all three)
  ===========================================================================
  The prompt forces ≥2 turns and a token that flips on steer:
    - pre-steer  turns : assistant text contains `COUNT <n>`
    - post-steer turns : assistant text contains `STEERED BANANA`
  The injected steer text contains `STOP counting now`. One transcript at
  <run>/transcript.jsonl will show, objectively:
    1. MONITOR : ≥2 `runner/event-processed` with event-name `llm.idle`.
    2. STEER   : exactly 1 `llm/user-message` whose .data.text contains
                 `STOP counting now`, at a :seq AFTER the first `llm.idle`
                 and BEFORE a later one; a pre-steer `llm/response` with
                 `COUNT ` and a post-steer `llm/response` with
                 `STEERED BANANA` (no fresh `COUNT <k>` after the steer).
    3. CAPTURE : ≥1 `artifact/captured` {:name \"supervised.md\"}; the file
                 exists at <work-dir>/<session>/artifacts/supervised.md and
                 (last-write-wins) contains the final `STEERED BANANA` text.

  Run (no real LLM needed to load/validate; live run is task 007):
    export ZAI_API_KEY=...                       # see workingcontext.md
    cd /home/naomarik/github/escapement
    RUNS=/home/naomarik/.ai-dev/convo-steering-demos/runs
    bb -m escapement.cli run escapement.examples.supervisor/agent \\
      --no-tui --model glm-4.6 --work-dir \"$RUNS\" --session supervisor

  VALIDATION (no live LLM):
    bb -e '(require (quote escapement.examples.supervisor))
           (println (some? (:com.fulcrologic.statecharts/elements-by-id
                             escapement.examples.supervisor/agent)))'"
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

(def agent                                                  ; runnable: bb -m escapement.cli run escapement.examples.supervisor/agent
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
               ;; from the supervisor region reaches it
               ;; (task 001 §P2).
               :system         system-prompt
               :real-tools     []
               :allowed-events
               [{:event       :count/tick
                 :description "Record one counting step."
                 :data-schema [:map [:n :int]]}
                ;; NAMESPACED on purpose. A bare :done would prefix-match the
                ;; SCXML-reserved `done.state.*` events raised when the parallel
                ;; regions finalise — re-firing these transitions and wedging the
                ;; macrostep in an eventless loop. `:count/done` keeps the natural
                ;; "done" name but namespaces it, so it can never collide.
                {:event       :count/done
                 :description "End the counting task."
                 :data-schema [:map [:reason :string]]}]
               ;; Conservative budgets so the run can
               ;; never hang on a flaky model.
               :max-turns      8
               :budget-ms      120000
               :message        "Begin the counting task at n=1."})
            ;; task 001 §P1 ORDERING RULE: the
            ;; event-tool's chart event (:count/tick/:done) is
            ;; posted to the parent STRICTLY BEFORE
            ;; :llm.idle for the same turn. Keep :count/tick
            ;; :type :internal so it never tears down
            ;; :counting before the supervisor sees
            ;; :llm.idle.
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

        ;; ---- Region 2: deterministic supervisor sidechart ----
        ;; MONITOR + STEER + CAPTURE, all off the
        ;; primitive-guaranteed turn-end hook (:llm.idle), NEVER
        ;; an LLM-behaviour counter (task 001 §P5).
        (state {:id :supervisor :initial :watching}
          ;; Hard safety stop: if the model never calls event__count_done, force
          ;; the chart down after a generous wall-clock budget. `send-after`
          ;; (on-entry send + on-exit cancel) is REQUIRED over a raw `send`: a
          ;; raw delayed send is NOT cancelled when the chart finishes early, so
          ;; the runner would idle ~150s waiting for the orphaned timer after
          ;; the chart already reached :finished. send-after cancels it on exit.
          (send-after {:id    :safety-timer
                       :event :safety/stop
                       :delay 150000})

          ;; --- watching: pre-steer ---
          (state {:id :watching}
            ;; FIRST :llm.idle -> do all three jobs at
            ;; once: MONITOR (record the turn), STEER
            ;; (inject once, one-shot via :steer-sent?),
            ;; CAPTURE (write the artifact). Then park
            ;; in :supervising (a PLAIN non-final
            ;; substate, task 001 §P5 — NOT a region
            ;; final, which would join the parallel
            ;; early and kill the conversation region).
            (transition {:event  :llm.idle
                         :target :supervising
                         :cond   (fn [_env data]
                                   (not (:steer-sent? data)))}
              (script {:expr (fn [_env data]
                               [(ops/assign :steer-sent? true)
                                (ops/assign :turns-seen
                                  (inc (long (:turns-seen data 0))))])})
              (h/tell-llm
                {:expr (fn [_env _data] steer-message)})
              (h/capture-llm-output {:as "supervised.md"})))

          ;; --- supervising: post-steer park ---
          (state {:id :supervising}
            ;; Subsequent turn-ends are still monitored
            ;; AND re-captured (latest-write-wins so the
            ;; artifact ends up holding the final
            ;; post-steer `STEERED BANANA` text). The
            ;; steer is NOT re-injected (one-shot). This
            ;; transition is :type :internal so the park
            ;; stays non-final / non-wedging until a
            ;; terminal signal arrives.
            (transition {:event :llm.idle :type :internal}
              (script {:expr (fn [_env data]
                               [(ops/assign :turns-seen
                                  (inc (long (:turns-seen data 0))))])})
              (h/capture-llm-output {:as "supervised.md"})
              ;; Each `event__count_tick` parks the worker in :awaiting-user, so the
              ;; chart must resume it to drive the next (post-steer) turn. Nudge
              ;; with a neutral "Continue." until the model fires event__count_done
              ;; after its two STEERED BANANA turns. :max-turns / :safety/stop bound it.
              (h/tell-llm {:expr (fn [_env _data] "Continue.")})))

          ;; task 001 §1 / acceptance: the supervisor region
          ;; MUST have a proper exit so the parallel join
          ;; completes and there is no eventless-loop wedge.
          ;; The convo's :done / the safety timer move the
          ;; supervisor to its own region final; combined with
          ;; the convo region reaching :c-done the `parallel`
          ;; raises done.state.work cleanly.
          ;; :type :internal is REQUIRED: this transition's source is the region
          ;; root (:supervisor), so an EXTERNAL transition's domain would be the
          ;; whole `:work` parallel (LCCA of :supervisor + its child :s-done),
          ;; making its exit set span BOTH regions — it would then conflict with
          ;; the convo region's :count/done transition and be dropped by
          ;; remove-conflicting-transitions, so this region would never finalise.
          ;; :internal keeps the domain at :supervisor (region-local).
          (transition {:event :count/done :target :s-done :type :internal})
          ;; NB: no region-level :safety/stop here. As a region-root EXTERNAL
          ;; transition its domain would be the whole `:work` parallel, so on a
          ;; safety stop it would EXIT+RE-ENTER `:work` — restarting the live
          ;; conversation instead of terminating. The top-level
          ;; `:safety/stop -> :finished` (below) ends the chart cleanly.
          (final {:id :s-done})))

      ;; Both regions reached their region final -> parallel raises
      ;; done.state.work; this is the clean, non-wedging join.
      (transition {:event :done.state.work :target :finished})
      ;; Belt-and-braces terminators (also reachable before both regions
      ;; finalise): the convo region's :done, or the safety timer.
      (transition {:event :count/done :target :finished})
      (transition {:event :safety/stop :target :finished})
      ;; If a chatty model ignores the steer and burns the conversation's
      ;; :max-turns budget, the worker self-cancels with :error.llm.max-turns.
      ;; Terminate promptly+cleanly on it rather than idling until :safety/stop.
      (transition {:event :error.llm.max-turns :target :finished})

      (final {:id :finished}))))
