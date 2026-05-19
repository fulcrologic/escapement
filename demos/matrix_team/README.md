# Matrix-team demo

A two-LLM collaborative chart for tuning a 3x3 matrix multiplier. Demonstrates
the chart-orchestrator primitives in `escapement.invocation.llm-conversation`
(`:verdict-schema`) and `escapement.chart.helpers` (`tell-other-llm!`).

## What it shows

Two LLM regions — `experimenter` and `tester` — run in parallel:

* Each gets a private JVM nREPL via a chart-owned service region
  (`:exp-repl` / `:test-repl`).
* Each declares a `:verdict-schema`. At every inference boundary the
  framework forces a `submit_verdict` tool call so the chart receives a
  typed payload (no free-text parsing).
* The chart routes the payloads:
  - Experimenter idles with `:proposed-new-version` → chart `tell-other-llm!`s
    the tester with the summary+approach.
  - Tester idles with `:pass` or `:fail` → chart `tell-other-llm!`s the
    experimenter with the verdict text.
  - Experimenter idles with `:done` → chart transitions to `:finished`.
  - Experimenter idles with `:stuck` → chart transitions to `:aborted`.

The LLMs themselves never fire chart events and have no awareness of each
other; from each LLM's perspective the workflow is "react to user messages,
end your turn with a verdict, get woken up with the next user message."

## What changed from the original (PR #5) design

The original demo used LLM-firable event tools (`event__new_version`,
`event__tester_passed`, etc.) and a deferred-reply mechanism that blocked
the experimenter's `event__new_version` tool call until the tester replied.
That worked but bled chart wiring (event names, reply correlation) into the
LLM prompts and required mid-turn steering complexity.

This version drops all LLM-visible event tools. Cross-region work is done
at the chart's inference boundaries (`:llm.idle` transitions), and the
typed verdict is the only contract between an LLM and the chart.

## Run it

```bash
# Required once: install clj-nrepl-eval (used by the chart's eval handler)
# See https://github.com/borkdude/clj-nrepl-eval for installation.

# Edit demos/matrix_team/example-input.edn to point :project-dir at your
# checkout of demos/tools, then:

bb -m escapement.cli run matrix-team.chart/agent \
   --input demos/matrix_team/example-input.edn \
   --debug    # opens the TUI; press 'c' to start the run
```

`--debug` is recommended: the matrix-team chart spawns two JVMs and runs
two LLMs in parallel, so seeing transcripts as they happen is much easier
than reading a wall of logs at the end.

## Required env

One of:
* `ZAI_API_KEY`  — for glm-class providers (works well; recommended)
* `ANTHROPIC_API_KEY` — Anthropic Claude
* `OPENAI_API_KEY` — OpenAI

The chart picks the default model from the project's model catalog; override
with `:model` in each region's params-fn if needed.

## Cost note

A single experimenter↔tester loop typically does 8-12 LLM round-trips
between the two regions (plus one extra `submit_verdict` inference per
idle). On glm-4.6 that runs cheap; on a frontier model it can add up.

## Cleanup

`Ctrl-C` triggers the chart's on-exit handlers, which destroy both nREPL
JVMs. If a JVM survives a crash, find it with `ps aux | grep clojure` and
`kill -9` the orphan.
