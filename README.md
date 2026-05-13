# Escapement

*Escapement regulates LLM agents the way a watch escapement regulates a mainspring.*

A statechart-driven autonomous coding agent in Clojure/Babashka.

## What's interesting

- Control flow is a **statechart**, not a free-form LLM loop. The chart, not the model, decides what happens next.
- An LLM conversation is **bound to a chart state** via a custom `InvocationProcessor`. While the state is active a worker thread holds the conversation; when the chart leaves the state the conversation dies.
- Two kinds of tools: **real tools** (fs/shell/repl, invisible to the chart) and **event tools** (synthesized from each state's `:allowed-events`; calls become chart events with Malli-validated payloads).
- Parallel regions get **independent workers**. Fan-out is natural.
- **JSONL transcript** of every LLM request, response, tool call, transition, and checkpoint. Full replay possible.
- **Atomic checkpointing** of working memory after every event for crash-resume.
- Runs under **Babashka**; tests run on the JVM with kaocha.

## Install

Bootstrap [bbin](https://github.com/babashka/bbin) (macOS):

```bash
brew install babashka/brew/bbin
```

Then install Escapement:

```bash
# Local clone:
bbin install .

# Or from GitHub:
bbin install io.github.fulcrologic/escapement
```

## Quickstart

```bash
export ZAI_API_KEY=...    # or ANTHROPIC_API_KEY
escapement run escapement.charts.hello/agent
```

The CLI auto-detects an LLM backend from `ANTHROPIC_API_KEY` or `ZAI_API_KEY`; no `--backend` flag is required.

Output goes under `.escapement/<session>/{transcript.jsonl,checkpoints/}`.
Expected `final-config`: `[:run :finished]`.

## See [`Guide.adoc`](Guide.adoc) for the full guide

The guide covers:

- **Introduction** — design rationale and one-paragraph overview
- **Quickstart** — clone-to-running in five lines
- **Core concepts** — chart, bound state, real tool vs event tool, lifecycle, transcript, checkpoint, resume
- **Authoring a chart** — line-by-line walkthrough of `hello.clj` plus annotated excerpts of `scan`, `parallel_demo`, `iterate`
- **The `:llm-conversation` invocation** — every `params-fn` key, `h/llm-conversation` / `h/tell-llm`, internal-vs-external transitions, bad-tool-use retry, event-tool naming
- **LLM backends** — Anthropic + z.ai configuration, `claude -p` adapter and its limits, the caching wrapper
- **Tools** — `Tool` protocol, the five built-ins, how to register a custom tool
- **Transcript, runner, CLI** — event vocabulary, `jq` recipes, `--resume`, work-dir layout
- **Idioms and gotchas** — `:type :internal`, `tell-llm` mid-binding, parallel regions, the top-level-`final` trap, resume side-effect caveat
- **Testing** — the JVM-only harness, mocking `LLMBackend`, stubbing tools, live smoke scripts
- **Project layout** — one paragraph per top-level directory
- **Known limitations and roadmap**
- **Contributing** — adding a chart, tool, or backend

## Demo charts

- [`src/escapement/charts/hello.clj`](src/escapement/charts/hello.clj) — minimal single-region chart, one event tool
- [`src/escapement/charts/scan.clj`](src/escapement/charts/scan.clj) — real tool (`:fs/read`) plus fan-out of multiple event-tool calls
- [`src/escapement/charts/parallel_demo.clj`](src/escapement/charts/parallel_demo.clj) — two parallel regions, independent conversations, join on compound final
- [`src/escapement/charts/iterate.clj`](src/escapement/charts/iterate.clj) — non-trivial coding loop with `tell-llm` mid-binding, `:max-iterations` cap, retry, and give-up paths

See [`plan.md`](plan.md) for design history and [`SPIKE_FINDINGS.md`](SPIKE_FINDINGS.md) for the Babashka/JVM compatibility notes.

Status: prototype.
