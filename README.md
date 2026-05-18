# Escapement

WARNING: Not even alpha yet. Breaking changes will be common until we finish exploring the space.

[![test](https://github.com/fulcrologic/escapement/actions/workflows/test.yml/badge.svg)](https://github.com/fulcrologic/escapement/actions/workflows/test.yml)

*Escapement regulates LLM agents the way a watch escapement regulates a mainspring.*

A statechart-driven autonomous coding agent in Clojure/Babashka.

## What's interesting

- Control flow is a **statechart**, not a free-form LLM loop. The chart, not the model, decides what happens next.
- An LLM conversation is **bound to a chart state** via a custom `InvocationProcessor`. While the state is active a worker thread holds the conversation; when the chart leaves the state the conversation dies.
- Two kinds of tools: **real tools** (fs/shell/repl, invisible to the chart) and **event tools** (synthesized from each state's `:allowed-events`; calls become chart events with Malli-validated payloads).
- **Multi-agent / team patterns** via `:target`-routed messages between concurrent LLM invocations and `tell-other-llm`.
- **Human-in-the-loop** via the `:human-input` invocation (text / select / multi-select / confirm / progress kinds) and the `with-llm-questions` helper that lets an LLM ask the user mid-conversation.
- **Persistent TUI** (JLine, Mode 2026 atomic frames when supported) shows the live chart configuration, transcript scrollback, and modal input. Esc sends `:ui.interrupt` to the chart.
- **File-backed artifacts** under `<session>/artifacts/` so agent outputs can be addressed by name in follow-on prompts via mustache-style `{{name}}` templates.
- **Self-cancelling invocations** via `:max-turns` and `:max-conversation-duration-ms` budgets that fire SCXML-canonical `:error.llm.*` events.
- Parallel regions get **independent workers**. Fan-out is natural.
- **JSONL transcript** of every LLM request, response, tool call, transition, and checkpoint. Full replay possible.
- **Atomic checkpointing** of working memory after every event for crash-resume.
- Backends: Anthropic Messages API, **OpenAI Chat Completions / OpenRouter**, and z.ai's GLM family (Anthropic-compat).
- Runs under **Babashka**, including the test suite (`bb test`). No JVM required.

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
# Any one of these will be auto-detected:
export ANTHROPIC_API_KEY=...     # claude-sonnet-4-6 by default
export ZAI_API_KEY=...           # glm-4.6 (cheap dev option)
export OPENAI_API_KEY=...        # gpt-4o-mini by default (override via OPENAI_MODEL)
export OPENROUTER_API_KEY=...    # openai/gpt-4o-mini by default (override via OPENROUTER_MODEL)

escapement run escapement.examples.hello/agent
```

The CLI auto-detects a backend from those env vars in the order above; pass `--backend` explicitly to override. Useful run-time flags:

- `--param key=value` — seed initial-data entries (repeatable; merges over `--input <edn-file>`).
- `--debug` — force the persistent TUI on (paused at first event so you can step or press `c` to continue). This is the recommended way to watch a non-interactive chart's LLM conversations stream by in real time.
- `--no-tui` — force the TUI off (useful in CI; `:human-input` prompts fall back to stdin). Charts marked `^{:interactive? true}` open the TUI by default when a TTY is present.

Output goes under `.escapement/<session>/{transcript.jsonl,checkpoints/}`.
Expected `final-config`: `[:run :finished]`.

### Project configuration

Drop an optional `.escapement.edn` at the root of any project to pin its layout once. Keys: `:source-paths` (extra classpath roots for chart utility namespaces), `:deps` (runtime Maven/git coordinates resolved via `babashka.deps/add-deps`), `:tools-ns` (registration fn symbol or vector), `:work-dir` (default transcript/checkpoint location), `:default-chart` (used when `escapement run` is invoked without a chart symbol). Escapement walks up from the current directory to find the file, so invocation is location-independent. See the *Project configuration* section of [`Guide.adoc`](Guide.adoc) for the full schema and precedence rules.

## See [`Guide.adoc`](Guide.adoc) for the full guide

The guide covers:

- **Introduction** — design rationale and one-paragraph overview
- **Quickstart** — clone-to-running in five lines
- **Core concepts** — chart, bound state, real tool vs event tool, lifecycle, transcript, checkpoint, resume
- **Authoring a chart** — line-by-line walkthrough of `hello.clj` plus annotated excerpts of `scan`, `parallel_demo`, `iterate`
- **The `:llm-conversation` invocation** — every `params-fn` key (incl. `:max-turns` / `:max-conversation-duration-ms`), `h/llm-conversation` / `h/tell-llm` / `h/tell-other-llm`, SCXML-canonical `:error.llm.*` events, internal-vs-external transitions, bad-tool-use retry, event-tool naming
- **Human interaction and the TUI** — `:human-input` invocation kinds, `human-input` chart helper, `with-llm-questions` for LLM-asks-human, the persistent TUI, `^{:interactive? true}` chart marker
- **Multi-agent / team patterns** — `:target`-routed messages, parallel-region advisors, `tell-other-llm`, file-backed artifact helpers (`capture-llm-output` / `render-template` / `forward-llm-output`)
- **LLM backends** — Anthropic + z.ai + OpenAI/OpenRouter configuration, the caching wrapper
- **File-based prompts** — `escapement.prompts` and the `{{VAR}}` substitution model (distinct from artifact templates)
- **Tools** — `Tool` protocol, the eight built-ins (fs read/write/edit/multi-edit/glob/grep + shell + repl), how to register a custom tool
- **Transcript, runner, CLI** — event vocabulary, `jq` recipes, `--resume` / `--param` / `--debug` / `--no-tui`, work-dir layout
- **Idioms and gotchas** — `:type :internal`, `tell-llm` mid-binding, parallel regions, the top-level-`final` trap, resume side-effect caveat
- **Testing** — the bb-friendly harness (`escapement.engine.testing`), mocking `LLMBackend`, stubbing tools, stub `HumanRenderer`, live smoke scripts; run with `bb test`
- **Project layout** — one paragraph per top-level directory
- **Known limitations and roadmap**
- **Contributing** — adding a chart, tool, or backend

## Built-in tools

All bb-resident. The fs and search tools are at Claude-Code-parity ergonomics for the LLM.

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

These are seeded into `escapement.tools.builtin/default-registry` — a `defonce` singleton the CLI uses. Custom tools self-register by side effect at the top of their namespace:

```clojure
(ns my.app.tools
  (:require [escapement.tools.builtin :as builtin]
            [escapement.tools.protocol :as tp]))
(tp/register! builtin/default-registry (->HttpGetTool))
```

Any chart whose require-graph reaches `my.app.tools` will then have `:http/get` available. By default a chart state's `:llm-conversation` exposes **every** real tool in the registry; pass `:real-tools [:fs/read :http/get]` (vector or set) to whitelist a subset. See the *Tools* section of the [Guide](Guide.adoc) for full schemas, the `--tools-ns` CLI flag, and how to use a fresh isolated registry (`new-builtin-registry`) for tests or multi-tenant hosts.

## File-based prompts

`escapement.prompts` provides a tiny `{{VAR}}` template helper for charts whose system prompts are too large or too parameterized for inline `(str ...)` blobs. Tokens of the form `{{IDENT}}` (uppercase + digits + underscores) are substituted from a map keyed by keyword, symbol, or string; unresolved tokens fail loudly. See `demos/unit_test/prompts/` for example prompts and `demos/unit_test/prompts.clj` for the per-phase render wiring.

## Demo charts

Small worked examples under `src/escapement/examples/`:

- [`hello.clj`](src/escapement/examples/hello.clj) — minimal single-region chart, one event tool
- [`scan.clj`](src/escapement/examples/scan.clj) — real tool (`:fs/read`) plus fan-out of multiple event-tool calls
- [`parallel_demo.clj`](src/escapement/examples/parallel_demo.clj) — two parallel regions, independent conversations, join on compound final
- [`iterate.clj`](src/escapement/examples/iterate.clj) — non-trivial coding loop with `tell-llm` mid-binding, `:max-iterations` cap, retry, and give-up paths
- [`clj_refactor.clj`](src/escapement/examples/clj_refactor.clj) — gates model auto-selection on per-dimension ratings via a declarative `:model-policy` (`:min {:clojure 8 :tool-calling 6}`)

End-to-end demo under `demos/`:

- [`demos/unit_test/`](demos/unit_test/) — port of the pi `unit_test` extension. Drives an LLM through behaviors → abstraction → (write|gap-analysis) → (critique|patch) → refine to author and seal `fulcro-spec` tests for a target function. Includes a sibling **REPL-manager parallel region** that establishes a project nREPL (cheap scripted discovery first; LLM-driven `deps.edn` inspection on miss) and hands the port to refine via shared data + one coordination event. Tested end-to-end against [`fulcrologic/fulcro`](https://github.com/fulcrologic/fulcro): generated and sealed a 52-assertion test file for `resolve-tempids`.

See [`plan.md`](plan.md) for design history.

Status: prototype.
