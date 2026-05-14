(ns escapement.charts.artifacts-demo
  "Demo chart that exercises the debug TUI's Invocations + Artifacts views.

   Two LLM conversations run in sequence:

     1. `writer` invents a short haiku → captured to
        `<session-dir>/artifacts/writer.md`.
     2. `critic` reads the writer's artifact via `{{writer.md}}` template
        substitution, comments on it → captured to
        `<session-dir>/artifacts/critic.md`.

   Both artifacts persist in the session dir so you can browse them in the
   inspector (`?` then `1` to see invocations, `Enter` on a row to drill in,
   `o` to open externally / `:internal` for the built-in pager).

   Run it:
     escapement run escapement.charts.artifacts-demo/agent --debug

   With `:debug :auto-pause? true` (default) the runner halts before the
   first event — press `c` to continue, or `s` to single-step. After each
   `:llm.idle` you'll see an `:artifact/captured` line in the scrollback."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [state transition final]]
   [escapement.chart.helpers :as h]))

(def writer-system
  (str "You are a poet. When asked, write ONE original haiku (3 lines, "
       "5/7/5 syllables) and then stop. Do not include any preamble, "
       "explanation, or trailing commentary — just the haiku."))

(def critic-system
  (str "You are a kind but candid editor. Given a haiku, respond with a "
       "two-paragraph critique in Markdown: paragraph one praises one "
       "specific image; paragraph two suggests one concrete improvement. "
       "Stop after the second paragraph."))

(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :writing}

          ;; ---- Stage 1: writer ---------------------------------------------
          (state {:id :writing}
                 (h/llm-conversation
                  {:id        "writer"
                   :params-fn (fn [_env _data]
                                {:system               writer-system
                                 :initial-user-message "Write a haiku about debugging a statechart at midnight."
                                 :max-tokens           200})})
                 (transition {:event :llm.idle :target :critiquing}
                             (h/capture-llm-output {:as "writer.md"})))

          ;; ---- Stage 2: critic (reads writer.md via template) --------------
          (state {:id :critiquing}
                 (h/llm-conversation
                  {:id        "critic"
                   :params-fn (fn [env _data]
                                {:system               critic-system
                                 :initial-user-message
                                 (h/render-template
                                  (str "Please critique the following haiku:\n\n"
                                       "{{writer.md}}\n")
                                  env)
                                 :max-tokens           400})})
                 (transition {:event :llm.idle :target :done}
                             (h/capture-llm-output {:as "critic.md"})))

          (final {:id :done}))))
