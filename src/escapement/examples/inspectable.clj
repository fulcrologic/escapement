(ns escapement.examples.inspectable
  "Exp C — inspectability probe.

   A single small LLM conversation that is engineered to emit the FULL
   spectrum of inspectable transcript events, then captures the model's
   final answer to a named artifact so the run can be replayed/inspected
   offline (no interactive TUI required).

   ===========================================================================
   WHAT THE RUN DOES (deterministic event spectrum)
   ===========================================================================
   The task is tiny and forces every interesting event class:

     1. initial-user-message            → seeds the conversation
        (transcript: :llm/request, :llm/start)
     2. model calls real-tool `fs_write` to record a note
        (transcript: :llm/response stop-reason \"tool_use\",
                      :llm/tool-result {:tool \"fs/write\" :is-error false})
     3. model writes a final assistant answer and calls event-tool
        `event__done` (glm batches the event-tool INTO the tool_use turn —
        a literal end_turn stop-reason never occurs; see ORDERING below)
        (transcript: :llm/tool-result for the event tool;
                      parent chart event :done in :runner/event-processed)
     4. the turn-end hook then fires as the parent event :llm.idle
        carrying {:text <final text> :from <invokeid>}
     5. on :llm.idle the chart captures the final text to an artifact
        and only THEN advances to :finished
        (transcript: :artifact/captured {:name \"answer.md\" :bytes N})

   So a single transcript contains: user message seed, real tool_use +
   tool_result, the event-tool round-trip, the :artifact/captured marker,
   and the runner lifecycle envelope
   (:runner/started … :runner/event-processed … :runner/done).

   ===========================================================================
   CAPTURE / TERMINATION ORDERING  (task 001 P1 — the load-bearing fact)
   ===========================================================================
   glm-class models pack the terminating `event__done` into a `:tool_use`
   turn; a literal `end_turn` stop-reason NEVER occurs. For one logical
   turn the parent chart receives, IN THIS EXACT ORDER:
     1. the event-tool's own chart event `:done`
        (posted at llm_conversation.clj:574, inside the block doseq);
     2. then `:llm.idle` (default :on-end-turn-event), posted AFTER the
        doseq (llm_conversation.clj:1068), carrying {:text :from}.
   Therefore `:done` is `:type :internal` here — it records the summary but
   does NOT exit `:converse`. If `:done` targeted `:finished` (the old bug)
   the `:llm.idle`/`capture-llm-output` transition would be torn down at
   step 1, before the hook in step 2 ever fires → zero :artifact/captured.
   Termination is routed off the `:llm.idle` transition instead: it runs
   `capture-llm-output` (while `:converse` is still active) and only then
   targets `:finished`. Mirrors iterate.clj's all-internal `:work` idiom.

   ===========================================================================
   INSPECTION RECIPE  (run command + how to reconstruct the loop offline)
   ===========================================================================
   Run (z.ai auto-selected from ZAI_API_KEY; no --backend; default model
   glm-4.6, pin a cheaper one with --model glm-4.5-air). For task 005:

     export ZAI_API_KEY=...                       # see workingcontext.md
     cd /home/naomarik/github/escapement
     RUNS=/home/naomarik/.ai-dev/convo-turn-experiments/runs
     bb -m escapement.cli run escapement.examples.inspectable/agent \\
       --no-tui --model glm-4.5-air --work-dir \"$RUNS\" --session expC

   Produced paths (cheat-sheet §7):
     transcript : $RUNS/expC/transcript.jsonl       (single-writer JSONL,
                  one JSON object per line, monotonic :seq, per-event :ts)
     artifact   : $RUNS/expC/artifacts/answer.md    (the captured final text)
     scratch    : $RUNS/expC/scratch/note.txt       (real-tool fs_write target,
                  relative to --work-dir; non-destructive)

   --- Pretty-print / human scan -------------------------------------------
     cat $RUNS/expC/transcript.jsonl | jq -c '{seq,event,data}'

   --- Reconstruct the turn loop (the events that matter) -------------------
     jq -c 'select(.event|test(\"^llm/\")) |
            {seq, event, sr:.data[\"stop-reason\"], tool:.data.tool}' \\
       $RUNS/expC/transcript.jsonl
   The loop is reconstructable from, in :seq order:
     :llm/request        — what was sent (n-messages, model, invokeid)
     :llm/response       — stop-reason \"tool_use\" (glm batches the
                            terminating event-tool here; no \"end_turn\")
     :llm/tool-result    — each tool round-trip (real-tool + event-tool),
                            {:tool :input :is-error :content-preview}
     :artifact/captured   — {:name \"answer.md\" :bytes N}
   There is NO literal :end_turn event with glm-class models — end-of-turn
   is signalled by the parent event :llm.idle, and the chart advance shows
   up as :event-name :done then :event-name :llm.idle (in that order) in
   :runner/event-processed (task 001 P1; cheat-sheet §7).

   --- Confirm the artifact + the turn-end hook in one filter -------------
     jq -c 'select(.event==\"artifact/captured\" or
            (.event==\"runner/event-processed\" and
             .data[\"event-name\"]==\"llm.idle\"))' \\
       $RUNS/expC/transcript.jsonl
   The :llm.idle event-processed must appear AFTER the :done one and the
   :artifact/captured must appear (capture ran before :finished).

   --- bb one-liner replay (no jq dependency) ------------------------------
     bb -e '(->> (slurp \"'$RUNS'/expC/transcript.jsonl\")
                  clojure.string/split-lines
                  (map #(clojure.data.json/read-str % :key-fn keyword))
                  (map (juxt :seq :event))
                  (run! prn))'

   --- TUI format-event reference (assess via code, not interactive) -------
   The interactive TUI consumes these SAME event maps live through
   `escapement.tui/format-event` (src/escapement/tui.clj:277) → it returns a
   one-line `[<source>] <summary>` per event via `entries-for`
   (tui.clj:~200-275; default branch tui.clj:273-275). The JSONL transcript
   is the source of truth for Exp C (spec marks interactive TUI out of
   scope); format-event renders the same data offline-equivalently.

   ===========================================================================
   VALIDATION (no live LLM):
     bb -e '(require (quote escapement.examples.inspectable))
            (println (some? (:com.fulcrologic.statecharts/elements-by-id
                              escapement.examples.inspectable/agent)))'
   ==========================================================================="
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements
    :refer [state transition final script]]
   [escapement.chart.helpers :as h]))

(def system-prompt
  (str
   "You drive a statechart by calling tools. Follow these steps exactly, "
   "terse, no chit-chat:\n"
   "1. Call the real tool `fs_write` once with "
   "{\"path\":\"scratch/note.txt\",\"content\":\"sky is blue\"} to record the fact.\n"
   "2. After the tool result, write a ONE-sentence final answer stating the "
   "fact you recorded, then end your turn.\n"
   "3. Then call the event tool `event__done` exactly once with "
   "{\"summary\":\"<your one-sentence answer>\"} to finish.\n"
   "Do not call any other tools. Do not loop."))

(def agent ; runnable: bb -m escapement.cli run escapement.examples.inspectable/agent
  (chart/statechart
   {:initial :run}
   ;; Compound parent so the :finished `final` empties only this sub-config,
   ;; not the whole machine (cheat-sheet §3 / hello.clj pattern).
   (state {:id :run :initial :converse}
          (state {:id :converse}
                 (h/llm-conversation
                  {:id        "inspectable"
                   ;; autoforward? defaults true; not needed here (no steering).
                   :params-fn
                   (fn [_env _data]
                     {:system               system-prompt
                      ;; Real-tool: built-in fs/write writes a real scratch
                      ;; file under --work-dir (non-destructive). Whitelisted
                      ;; so the model can ONLY write, nothing else.
                      :real-tools           [:fs/write]
                      ;; Event-tool that advances the chart to :finished.
                      :allowed-events       [{:event       :done
                                              :data-schema [:map [:summary :string]]}]
                      ;; Conservative budgets so a live run can never hang
                      ;; (cheat-sheet §1: ALWAYS bound the loop).
                      :max-turns                    5
                      :max-conversation-duration-ms 120000
                      :initial-user-message
                      "Record the fact \"sky is blue\" and report it."})})

                 ;; Event-tool fires :done FIRST (task 001 P1: posted before
                 ;; :llm.idle for the same turn). It is `:type :internal` so
                 ;; it records the summary WITHOUT exiting :converse — the
                 ;; :llm.idle transition below stays active for step 2.
                 (transition {:event :done :type :internal}
                             (script
                              {:expr (fn [_env data]
                                       [(ops/assign
                                         :summary
                                         (get-in data [:_event :data :summary]))])}))

                 ;; The turn-end hook posts :llm.idle (default
                 ;; :on-end-turn-event) AFTER :done, carrying
                 ;; {:text final-text :from id}. Capture the final assistant
                 ;; text to a named artifact under --work-dir (emits the
                 ;; :artifact/captured transcript event AND writes a real
                 ;; file at <session-dir>/artifacts/answer.md), then — and
                 ;; only then — advance to :finished.
                 (transition {:event :llm.idle :target :finished}
                             (h/capture-llm-output {:as "answer.md"})))

          (final {:id :finished}))))
