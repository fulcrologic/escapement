(ns stress.easy
  "TUI render stress — EASY tier (baseline).

   The simplest possible streaming render: one LLM conversation, one short
   single-turn reply, captured to a single small artifact. This is the control
   case — if THIS does not render cleanly, nothing else will. Use it to confirm
   the live-token panel, the single invocation row in the inspector, and a lone
   artifact all draw without overflow, garble, or layout drift.

   MODEL DEPENDENCY: live-token streaming is driven by real LLM deltas, so this
   chart needs a backend. Any backend works; a tiny local model is plenty. With
   no API key set, run on local ollama gemma3:1b:

     OLLAMA_API_KEY=dummy bb -m escapement.cli run \\
       stress.easy/agent \\
       --backend ollama --api-base-url http://localhost:11434/v1 \\
       --model gemma3:1b --max-tokens 2048 --overrun-retries 2 \\
       --overrun-temp-bump 0.3

   The chart logic is deterministic (one turn, one event-tool, one artifact);
   only the streamed token CONTENT depends on the model. The model just needs to
   call `event__stress_easy_done` once with a short greeting."
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.chart.helpers :as h]))

(def system-prompt
  (str "Reply with a single short friendly greeting (under 40 characters), then "
    "call the `event__stress_easy_done` tool exactly once with "
    "`{\"greeting\":\"<your greeting>\"}` and end your turn. Do not call any "
    "other tools and do not loop."))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :greeting}

      (state {:id :greeting}
        (h/llm-conversation
          {:id             "easy"
           :system         system-prompt
           :real-tools     []
           :allowed-events [{:event       :stress-easy/done
                             :data-schema [:map [:greeting :string]]}]
           :max-turns      3
           :budget-ms      60000
           :message        "Say hello in one short line."})

        ;; Capture the single reply, then finish. One invocation, one artifact.
        (transition {:event :llm.idle :target :finished}
          (h/capture-llm-output {:as "greeting.md"})))

      (final {:id :finished}))))
