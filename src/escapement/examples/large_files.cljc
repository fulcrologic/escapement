(ns escapement.examples.large-files
  "Single-conversation demo: the LLM is given shell + glob tools and asked to find the largest files
   under the user's HOME directory (recursively), then write a Markdown report. It PICKS and drives
   the tools itself (e.g. `find`/`du` via `:shell/run`). The final report is captured to
   `<session-dir>/artifacts/large-files.md`.

   This is the end-to-end exercise for three recent features:

     1. Chart-owned termination — the scan may take many tool round-trips with no chart-visible event
        between them; the runner no longer aborts it by an iteration cap. The chart ends only when the
        model finishes its report (`:end_turn` → `:llm.idle`).
     2. Output as a handle — the assistant's final report is delivered to the chart as an
        `:output-ref` (a pointer into the ArtifactStore), NOT inline. `h/capture-llm-output` derefs
        that handle to write `large-files.md`, so working memory and the transcript stay small.
     3. Reconstructable invocation — every turn's request/response/tool-results/output and every event
        the conversation fires is captured under `nodes/scanner/0/…`, so the run is fully replayable
        via the EQL reconstruction API (see `escapement.ui.resolvers/invocation-transcript-resolver`).

   Run it (a real backend is required — set e.g. ANTHROPIC_API_KEY or OPENAI_API_KEY):

     bb -m escapement.cli run escapement.examples.large-files/agent --no-tui

   Then inspect the captured report and the reconstructable invocation:

     cat .escapement/<session-id>/artifacts/large-files.md
     ls  .escapement/<session-id>/nodes/scanner/0/turns/"
  (:require
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.elements :refer [final state transition]]
    [escapement.chart.helpers :as h]))

(def system-prompt
  (str "You are a disk-usage investigator with shell access. Your job: find the LARGEST files under "
    "a given home directory, recursively."
    "When you have the data, STOP calling tools and reply with the final report ONLY (no preamble): a "
    "Markdown document titled `# Largest files under <HOME>` followed by a table or list of the top "
    "files with human-readable sizes and absolute paths, plus a one-line summary of the total. End "
    "your turn after the report."))

(def agent
  (chart/statechart
    {:initial :run}
    (state {:id :run :initial :scanning}
      (state {:id :scanning}
        (h/llm-conversation
          {:id         "scanner"
           :system     system-prompt
           :real-tools [:shell/run :fs/glob]
           :message    (fn [_env _data]
                         (let [home (System/getProperty "user.home")]
                           (str "Find the largest files under this home directory (recursively): "
                             home "\nUse your tools, then write the Markdown report.")))})
        (transition {:event :llm.idle :target :done}
          (h/capture-llm-output {:as "large-files.md"})))
      (final {:id :done}))))
