(ns unit-test.prompts
  "Phase prompt registry for the unit-test demo. Each phase points at a markdown
   template on the classpath and ships the substitution map and a small wrapper
   footer that tells the model how to terminate the phase (write its output to a
   file and call the right event tool)."
  (:require
    [clojure.java.io :as io]
    [escapement.prompts :as prompts]))

(def ^:private prompt-root "unit_test/prompts/")

(defn- resource-path
  "Resolve `name` either via classpath (`io/resource`) or by reading from the
   on-disk demos directory relative to the project root. We try classpath first
   so the demo works once `demos` is on `:paths`."
  [name]
  (or (io/resource (str prompt-root name))
    (str "demos/" prompt-root name)))

(def ^:private footer-analysis
  (str "\n\n================================================================\n"
    "ESCAPEMENT TERMINATION (read carefully)\n"
    "================================================================\n\n"
    "When you finish your analysis, do TWO things in this order:\n"
    "  1. Use the `fs_write` tool to save the FULL markdown analysis to:\n"
    "     `{{OUTPUT_FILE}}`\n"
    "  2. Call `event__done` with a one-sentence `summary` of the result.\n\n"
    "Do not call `event__done` before writing the file. Do not chat after the\n"
    "event tool — end your turn."))

(def ^:private footer-mutation
  (str "\n\n================================================================\n"
    "ESCAPEMENT TERMINATION (read carefully)\n"
    "================================================================\n\n"
    "When you finish, the test file at `{{TEST_FILE}}` must contain the\n"
    "complete updated content (use `fs_write` for a fresh file or `fs_edit`\n"
    "for targeted edits). Then call `event__done` with a brief one-sentence\n"
    "`summary` and end your turn."))

(def ^:private footer-refine
  (str "\n\n================================================================\n"
    "ESCAPEMENT TERMINATION (read carefully)\n"
    "================================================================\n\n"
    "End by calling EXACTLY ONE of:\n"
    "  * `event__sealed` with `signature` (the :covers signature you sealed)\n"
    "    and `iterations` (integer count of refine cycles you ran).\n"
    "  * `event__give_up` with a `reason` string if you cannot make tests pass.\n\n"
    "Do not call any event tool more than once. End your turn after the event."))

(def ^:private footer-repl-manager
  (str "\n\n================================================================\n"
    "ESCAPEMENT TERMINATION (read carefully)\n"
    "================================================================\n\n"
    "End by calling EXACTLY ONE of:\n"
    "  * `event__repl_ready` with `{\"port\": <integer>}` once a verified\n"
    "    TEST-mode nREPL is accepting evals.\n"
    "  * `event__repl_failed` with `{\"reason\": \"<one sentence>\"}` if you\n"
    "    cannot establish one.\n\n"
    "Do not call any event tool more than once. End your turn after the event."))

(def phases
  "Ordered registry of phases. Each value is a map of:
     * `:template`  — markdown template file name under demos/unit_test/prompts/
     * `:footer`    — string footer appended after rendering (already has its own
                      `{{...}}` placeholders that will be rendered with the same subs)
     * `:output-key`— key in chart data whose value is the path the model should
                      write to (for analysis phases). Mutation/refine phases use
                      `:test-file` directly."
  {:behaviors    {:template "behaviors.md" :footer footer-analysis :output-key :behaviors-file}
   :abstraction  {:template "abstraction.md" :footer footer-analysis :output-key :mock-strategy-file}
   :write        {:template "write.md" :footer footer-mutation :output-key nil}
   :critique     {:template "critique.md" :footer footer-mutation :output-key nil}
   :gap-analysis {:template "gap-analysis.md" :footer footer-analysis :output-key :gap-analysis-file}
   :patch        {:template "patch.md" :footer footer-mutation :output-key nil}
   :refine       {:template "refine.md" :footer footer-refine :output-key nil}
   :repl-manager {:template "repl-manager.md" :footer footer-repl-manager :output-key nil}})

(defn- repl-skill-text
  "Reads the canonical REPL-skill partial that is concatenated into the
   repl-manager system prompt."
  []
  (slurp (resource-path "repl-skill.md")))

(defn render-phase
  "Render the prompt for `phase-key` using the supplied `data` map. Returns a
   single string suitable for the LLM `:system` parameter."
  [phase-key data]
  (let [{:keys [template footer output-key]} (get phases phase-key)
        _    (assert template (str "Unknown phase: " phase-key))
        subs (cond-> {:FILE               (:source-path data)
                      :FUNCTION           (:function data)
                      :SOURCE_NAMESPACE   (:source-namespace data)
                      :TEST_FILE          (:test-file data)
                      :TEST_NAMESPACE     (:test-namespace data)
                      :BEHAVIORS_FILE     (:behaviors-file data)
                      :MOCK_STRATEGY_FILE (:mock-strategy-file data)
                      :GAP_ANALYSIS_FILE  (:gap-analysis-file data)
                      :MAX_ITERATIONS     (or (:max-iterations data) 10)
                      :NREPL_PORT         (or (:nrepl-port data) "NOT_FOUND")
                      :PROJECT_DIR        (or (:project-dir data) ".")
                      :REPL_SKILL         (repl-skill-text)}
               output-key (assoc :OUTPUT_FILE (get data output-key)))
        body (prompts/render-file (resource-path template) subs)]
    (prompts/render (str body footer) subs)))
