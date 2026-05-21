(ns escapement.examples.turn-loop
  "Exp A — full conversation turn loop.

  Demonstrates Escapement's LLM conversation \"turn\" primitive driving a
  genuine multi-tool turn:

    receive prompt
      -> assistant states a plan (text)
      -> calls the read tool   (`fs_read`)  : reads the scratch file
      -> calls the write tool  (`fs_write`) : a REAL file edit
      -> re-reads the file     (`fs_read`)  : confirms the edit landed
      -> calls `event__done`                : ends the turn
    chart transitions to a wrapped `final` state.

  Real-tools used are the built-ins `:fs/read` and `:fs/write` (registered in
  `escapement.tools.builtin/default-registry`, passed by the CLI as
  `:tool-registry`). The write is non-destructive: it only touches a scratch
  file under the work-dir's `.escapement/scratch/` path. The chart never sees
  the real-tool calls — only the terminal `event__done`.

  Conservative budgets (`:max-turns` + `:max-conversation-duration-ms`) so a
  live run can never hang.

  RUN COMMAND (task 005 — z.ai auto-selected from ZAI_API_KEY, no --backend):

    bb -m escapement.cli run escapement.examples.turn-loop/agent \\
      --no-tui --model glm-4.6 \\
      --work-dir /home/naomarik/.ai-dev/convo-turn-experiments/runs \\
      --session expA

  Transcript: <work-dir>/expA/transcript.jsonl
  Acceptance: transcript shows a `:llm/tool-result` for `fs_write` and a final
  `:llm/response` with stop-reason \"end_turn\"."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements :refer [final script state transition]]
    [escapement.chart.helpers :as h]))

;; Scratch file the model reads/writes. Relative to the run's work dir; the
;; built-in fs tools resolve it there. Non-destructive (a dedicated scratch
;; path that this experiment owns).
(def scratch-path ".escapement/scratch/turn-loop.txt")

(def system-prompt
  (str "You are completing a tiny file task by chaining tools in ONE turn. "
    "The scratch file is at \"" scratch-path "\". Do exactly this, in order:\n"
    "1. Briefly state your plan in one sentence.\n"
    "2. Call `fs_read` with {\"path\":\"" scratch-path "\"} to read the "
    "current contents. If the file does not exist yet, that is expected — "
    "treat it as empty and continue.\n"
    "3. Call `fs_write` with {\"path\":\"" scratch-path "\","
    "\"content\":\"turn-loop ok\\n\"} to write the file.\n"
    "4. Call `fs_read` again with {\"path\":\"" scratch-path "\"} to "
    "confirm the new contents.\n"
    "5. Call `event__done` exactly once with "
    "{\"summary\":\"<one short sentence confirming the write>\"} and end "
    "your turn.\n"
    "Use only these tools. Keep all text terse. Do not repeat steps."))

(def agent                                                  ; runnable: bb -m escapement.cli run escapement.examples.turn-loop/agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :converse}                    ; compound parent so :finished final is non-fatal
      (state {:id :converse}
        (h/llm-conversation
          {:id        "coder"
           :params-fn (fn [_env _data]
                        {:system                       system-prompt
                         :real-tools                   [:fs/read :fs/write]
                         :allowed-events               [{:event       :done
                                                         :description "Signal the turn-loop task is complete."
                                                         :data-schema [:map [:summary :string]]}]
                         :max-turns                    8
                         :max-conversation-duration-ms 120000
                         :initial-user-message
                         (str "Run the scratch-file turn-loop task now: "
                           "plan, read, write, re-read, then call event__done.")})})
        (transition {:event :done :target :finished}
          (script {:expr (fn [_env data]
                           [(ops/assign :summary
                              (get-in data [:_event :data :summary]))])})))
      (final {:id :finished}))))
