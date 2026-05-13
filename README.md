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
- **File-based prompts** — `escapement.prompts` and the `{{VAR}}` substitution model
- **Tools** — `Tool` protocol, the eight built-ins (fs read/write/edit/multi-edit/glob/grep + shell + repl), how to register a custom tool
- **Transcript, runner, CLI** — event vocabulary, `jq` recipes, `--resume`, work-dir layout
- **Idioms and gotchas** — `:type :internal`, `tell-llm` mid-binding, parallel regions, the top-level-`final` trap, resume side-effect caveat
- **Testing** — the JVM-only harness, mocking `LLMBackend`, stubbing tools, live smoke scripts
- **Project layout** — one paragraph per top-level directory
- **Known limitations and roadmap**
- **Contributing** — adding a chart, tool, or backend

## Built-in tools

All available from both bb and JVM. The fs and search tools are at
Claude-Code-parity ergonomics for the LLM.

| Tool | Purpose |
|---|---|
| `:fs/read`       | Read with `cat -n` line numbers; pageable via `offset`/`limit` |
| `:fs/write`      | Atomic UTF-8 write (temp-file + `ATOMIC_MOVE`) |
| `:fs/edit`       | String-replacement edit; unique-match by default, opt-in `replace-all` |
| `:fs/multi-edit` | Atomic batch of edits to one file; later edits see earlier output |
| `:fs/glob`       | `PathMatcher`-backed walk; matches `**/*.foo` at root and nested |
| `:fs/grep`       | `rg` if available, `grep -rE` fallback; files / content / count modes |
| `:shell/run`     | `bash -lc` with timeout |
| `:repl/eval`     | Sandboxed Clojure eval (fresh namespace per call) |

See the *Tools → Built-in tools* section of the [Guide](Guide.adoc) for full schemas.

## File-based prompts

`escapement.prompts` provides a tiny `{{VAR}}` template helper for charts whose system prompts are too large or too parameterized for inline `(str ...)` blobs. Tokens of the form `{{IDENT}}` (uppercase + digits + underscores) are substituted from a map keyed by keyword, symbol, or string; unresolved tokens fail loudly. See `demos/unit_test/prompts/` for example prompts and `demos/unit_test/prompts.clj` for the per-phase render wiring.

## Demo charts

Small worked examples under `src/escapement/charts/`:

- [`hello.clj`](src/escapement/charts/hello.clj) — minimal single-region chart, one event tool
- [`scan.clj`](src/escapement/charts/scan.clj) — real tool (`:fs/read`) plus fan-out of multiple event-tool calls
- [`parallel_demo.clj`](src/escapement/charts/parallel_demo.clj) — two parallel regions, independent conversations, join on compound final
- [`iterate.clj`](src/escapement/charts/iterate.clj) — non-trivial coding loop with `tell-llm` mid-binding, `:max-iterations` cap, retry, and give-up paths

End-to-end demo under `demos/`:

- [`demos/unit_test/`](demos/unit_test/) — port of the pi `unit_test` extension. Drives an LLM through behaviors → abstraction → (write|gap-analysis) → (critique|patch) → refine to author and seal `fulcro-spec` tests for a target function. Includes a sibling **REPL-manager parallel region** that establishes a project nREPL (cheap scripted discovery first; LLM-driven `deps.edn` inspection on miss) and hands the port to refine via shared data + one coordination event. Tested end-to-end against [`fulcrologic/fulcro`](https://github.com/fulcrologic/fulcro): generated and sealed a 52-assertion test file for `resolve-tempids`.

See [`plan.md`](plan.md) for design history and [`SPIKE_FINDINGS.md`](SPIKE_FINDINGS.md) for the Babashka/JVM compatibility notes.

Status: prototype.
