(ns escapement.examples.inspect-showcase
  "Task 006 — chart-inspect SHOWCASE.

   A short, deterministic, *two-phase* LLM conversation that produces TWO
   distinct named artifacts under `--work-dir` and a clean,
   fully-reconstructable JSONL transcript. Shipped with a copy-pasteable
   inspection recipe (jq + bb + TUI format-event) so a reader can replay the
   whole agent loop offline and list every artifact it captured.

   ===========================================================================
   WHAT THE RUN DOES  (deterministic, >=2 artifacts)
   ===========================================================================
   ONE long `:llm-conversation` is bound at the top of `:converse`. The task
   is split into two phases that are guaranteed to span >=2 turns, so the
   primitive's end-of-turn hook (`:llm.idle`) fires at least twice and the
   chart captures one artifact per phase:

     Phase 1 — PLAN
       * initial-user-message seeds the conversation
         (transcript: :llm/start, :llm/request)
       * model writes a short PLAN, then calls event-tool `event__step`
         {\"phase\":\"plan\"} to mark phase 1 complete
         (transcript: :llm/response stop-reason \"tool_use\";
                       :llm/tool-result for event__step;
                       :runner/event-processed event-name=step)
       * the turn-end hook posts :llm.idle {:text <plan text> :from <id>}
       * chart captures the plan text -> artifact `plan.md`
         (transcript: :artifact/captured {:name \"plan.md\" :bytes N})
       * chart steers the SAME conversation into phase 2 via h/tell-llm
         (transcript: :llm/user-message — the phase-2 instruction)

     Phase 2 — ANSWER
       * model records a fact with the real tool `fs_write` (scratch file)
         (transcript: :llm/tool-result {:tool \"fs/write\" :is-error false})
       * model writes the FINAL answer, calls event-tool `event__done`
         {\"summary\":...}
         (transcript: :llm/tool-result for event__done;
                       :runner/event-processed event-name=done)
       * the turn-end hook posts :llm.idle a SECOND time
       * chart captures the final answer text -> artifact `answer.md`
         (transcript: :artifact/captured {:name \"answer.md\" :bytes M})
       * chart advances to :finished
         (transcript: :runner/done; final config [run finished])

   Net: a single transcript contains the full loop envelope plus TWO
   `:artifact/captured` markers and TWO real files on disk:
     <work-dir>/<session>/artifacts/plan.md
     <work-dir>/<session>/artifacts/answer.md
   (and one scratch file <work-dir>/<session>/scratch/fact.txt).

   ===========================================================================
   CAPTURE / TERMINATION ORDERING  (task 001 P1 — the load-bearing fact)
   ===========================================================================
   glm-class models pack the terminating event-tool into a `:tool_use` turn;
   a literal `end_turn` stop-reason NEVER occurs. For one logical turn the
   parent chart receives, IN THIS EXACT ORDER:
     1. the event-tool's own chart event (`:step` or `:done`)
        — posted at llm_conversation.clj:574, inside the block doseq;
     2. then `:llm.idle` (default :on-end-turn-event), posted AFTER the
        doseq (llm_conversation.clj:1068), carrying {:text :from}.
   Therefore BOTH event-tool events here are `:type :internal`: they record
   data and bump the phase counter WITHOUT exiting `:converse`, so the
   `:llm.idle` capture transition is still active when the hook fires.
   Termination is routed off the SECOND `:llm.idle` (phase 2). This mirrors
   inspectable.clj (single capture) and iterate.clj's all-`:type :internal`
   `:work` idiom (the binding/context is never torn down between phases).

   The capture transition itself is `:type :internal` and dispatches on a
   `:phase` counter held in the data model: phase 1 -> write plan.md + steer
   to phase 2; phase 2 -> write answer.md + advance to :finished. The phase
   counter is a CHART-state counter (incremented by the event-tool events),
   NOT an LLM-behaviour heuristic (task 001 P5: never trigger on tick-count /
   nth-turn / wording).

   ===========================================================================
   INSPECTION RECIPE  (run it, then reconstruct the loop + list artifacts)
   ===========================================================================
   Run (z.ai auto-selected from ZAI_API_KEY; no --backend; default model
   glm-4.6, pin a cheaper one with --model glm-4.5-air):

     export ZAI_API_KEY=...                       # see workingcontext.md
     cd /home/naomarik/github/escapement
     RUNS=/home/naomarik/.ai-dev/convo-steering-demos/runs
     bb -m escapement.cli run escapement.examples.inspect-showcase/agent \\
       --no-tui --model glm-4.5-air --work-dir \"$RUNS\" --session showcase

   Produced paths:
     transcript : $RUNS/showcase/transcript.jsonl   (single-writer JSONL,
                  one JSON object per line, monotonic :seq, per-event :ts)
     artifact 1 : $RUNS/showcase/artifacts/plan.md   (phase-1 PLAN text)
     artifact 2 : $RUNS/showcase/artifacts/answer.md (phase-2 final answer)
     scratch    : $RUNS/showcase/scratch/fact.txt    (real-tool fs_write,
                  relative to --work-dir; non-destructive)

   --- 1. List every captured artifact (>=2 expected) ----------------------
     jq -c 'select(.event==\"artifact/captured\") | .data' \\
       $RUNS/showcase/transcript.jsonl
     # => {\"name\":\"plan.md\",\"bytes\":...}
     #    {\"name\":\"answer.md\",\"bytes\":...}
     ls -l $RUNS/showcase/artifacts/        # the two real files on disk

   --- 2. Reconstruct the FULL loop, in :seq order -------------------------
     jq -c 'select(.event|test(\"^llm/\")) |
            {seq, event, sr:.data[\"stop-reason\"], tool:.data.tool}' \\
       $RUNS/showcase/transcript.jsonl
   Read top-to-bottom this is, per phase:
     :llm/start          — conversation/invocation begins
     :llm/request        — what was sent (n-messages, model, invokeid)
     :llm/response       — stop-reason \"tool_use\" (glm batches the
                            event-tool here; no literal \"end_turn\")
     :llm/tool-result    — each tool round-trip (real-tool + event-tool):
                            {:tool :input :is-error :content-preview}
     (phase boundary)    — :llm/user-message  (the chart's steer into phase 2)
     ... phase 2 repeats :llm/response / :llm/tool-result ...

   --- 3. Reconstruct the chart-event spine + artifact markers -------------
     jq -c 'select((.event==\"runner/event-processed\" and
                    (.data[\"event-name\"]==\"step\" or
                     .data[\"event-name\"]==\"done\" or
                     .data[\"event-name\"]==\"llm.idle\")) or
                   .event==\"artifact/captured\") |
            {seq, event, name:(.data[\"event-name\"] // .data.name)}' \\
       $RUNS/showcase/transcript.jsonl
   Expected :seq-ordered shape (task 001 P1: event-tool event BEFORE its
   :llm.idle; capture BEFORE the next phase / :finished):
     step -> llm.idle -> artifact/captured(plan.md)
          -> done -> llm.idle -> artifact/captured(answer.md)

   --- 4. bb one-liner replay (no jq dependency) ---------------------------
     bb -e '(->> (slurp \"'$RUNS'/showcase/transcript.jsonl\")
                  clojure.string/split-lines
                  (map #(clojure.data.json/read-str % :key-fn keyword))
                  (map (juxt :seq :event))
                  (run! prn))'

   --- 5. TUI format-event parity (offline, assess via code) ---------------
   The interactive TUI consumes these SAME event maps live through
   `escapement.tui/format-event` (src/escapement/tui.clj:277) -> a one-line
   `[<source>] <summary>` per event via `entries-for` (tui.clj:~200-275).
   Render the whole transcript exactly as the live TUI would, offline:
     bb -e '(require (quote [escapement.tui :as tui])
                      (quote [clojure.data.json :as json])
                      (quote [clojure.string :as str]))
            (doseq [l (str/split-lines
                        (slurp \"'$RUNS'/showcase/transcript.jsonl\"))]
              (when-some [s (tui/format-event
                              (json/read-str l :key-fn keyword))]
                (println s)))'

   ===========================================================================
   VALIDATION (no live LLM):
     bb -e '(require (quote escapement.examples.inspect-showcase))
            (println (some? (:com.fulcrologic.statecharts/elements-by-id
                              escapement.examples.inspect-showcase/agent)))'
   Live end-to-end is task 007.
   ==========================================================================="
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements
     :refer [final script state transition]]
    [escapement.chart.helpers :as h]))

(def system-prompt
  (str
    "You drive a statechart by calling tools. Work in TWO phases, terse, "
    "no chit-chat.\n\n"
    "PHASE 1 (PLAN): Write a numbered 2-3 step PLAN for answering the user's "
    "question. Do NOT answer the question yet. Then call the event tool "
    "`event__step` exactly once with {\"phase\":\"plan\"} to finish phase 1.\n\n"
    "PHASE 2 (ANSWER): You will receive a user message telling you to execute "
    "the plan. First call the real tool `fs_write` exactly once with "
    "{\"path\":\"scratch/fact.txt\",\"content\":\"<the key fact>\"} to record "
    "the key fact. After the tool result, write your FINAL one-paragraph "
    "answer to the original question. Then call the event tool `event__done` "
    "exactly once with {\"summary\":\"<one-sentence summary of your answer>\"} "
    "to finish.\n\n"
    "Do not call any other tools. Do not loop. One event tool per phase."))

(def phase-2-message
  (str
    "Phase 1 accepted. Now execute the plan: call `fs_write` once to record "
    "the key fact at scratch/fact.txt, then write your FINAL one-paragraph "
    "answer, then call `event__done` with a one-sentence summary."))

(def agent                                                  ; runnable: bb -m escapement.cli run escapement.examples.inspect-showcase/agent
  (chart/statechart
    {:initial :run}
    ;; Compound parent so the :finished `final` empties only this sub-config,
    ;; not the whole machine (cheat-sheet §3 / hello.clj pattern).
    (state {:id :run :initial :converse}

      (state {:id :converse}
        (h/llm-conversation
          {:id "showcase"
           ;; autoforward? defaults true — REQUIRED so the phase-2
           ;; h/tell-llm steer reaches this conversation (task 001 P2).
           :params-fn
           (fn [_env _data]
             {:system                       system-prompt
              ;; Real-tool: built-in fs/write writes a real scratch
              ;; file under --work-dir (non-destructive). Whitelisted
              ;; so the model can ONLY write, nothing else.
              :real-tools                   [:fs/write]
              ;; Two event-tools: one per phase boundary.
              :allowed-events
              [{:event       :step
                :data-schema [:map [:phase :string]]}
               {:event       :done
                :data-schema [:map [:summary :string]]}]
              ;; Conservative budgets so a live run can never hang
              ;; (cheat-sheet §1: ALWAYS bound the loop). Two phases
              ;; need >=2 turns; 6 leaves slack for tool round-trips.
              :max-turns                    6
              :max-conversation-duration-ms 120000
              :initial-user-message
              (str "Question: In one paragraph, what is a statechart "
                "and why is it useful for driving an autonomous "
                "agent? Begin with PHASE 1: produce the PLAN only.")})})

        ;; --- Event-tool events (task 001 P1: posted BEFORE :llm.idle
        ;; for the same turn). BOTH are `:type :internal` — they
        ;; record data and bump the :phase counter WITHOUT exiting
        ;; :converse, so the :llm.idle capture transition below stays
        ;; active for the same turn (mirrors iterate.clj). ----------
        (transition {:event :step :type :internal}
          (script
            {:expr (fn [_env _data]
                     [(ops/assign :phase 1)])}))

        (transition {:event :done :type :internal}
          (script
            {:expr (fn [_env data]
                     [(ops/assign :phase 2)
                      (ops/assign
                        :summary
                        (get-in data [:_event :data :summary]))])}))

        ;; --- Phase 1 :llm.idle: capture plan.md, steer to phase 2.
        ;; Guarded on (:phase data) == 1 (a CHART counter set by the
        ;; :step event above — NOT an LLM heuristic, task 001 P5).
        ;; `:type :internal` so :converse (and the conversation
        ;; binding) survives into phase 2. ------------------------
        (transition {:event :llm.idle :type :internal
                     :cond  (fn [_env data] (= 1 (:phase data)))}
          (h/capture-llm-output {:as "plan.md"})
          (h/tell-llm {:expr (fn [_env _data] phase-2-message)}))

        ;; --- Phase 2 :llm.idle: capture answer.md, THEN finish.
        ;; Guarded on (:phase data) == 2. Capture runs while
        ;; :converse is still active (the :done event was internal),
        ;; then this transition advances to :finished. ------------
        (transition {:event :llm.idle :target :finished
                     :cond  (fn [_env data] (= 2 (:phase data)))}
          (h/capture-llm-output {:as "answer.md"})))

      (final {:id :finished}))))
