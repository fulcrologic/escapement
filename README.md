# Escapement

WARNING: Not even alpha yet. Breaking changes will be common until we finish exploring the space.

[![Clojars Project](https://img.shields.io/clojars/v/com.fulcrologic/escapement.svg)](https://clojars.org/com.fulcrologic/escapement)
[![test](https://github.com/fulcrologic/escapement/actions/workflows/test.yml/badge.svg)](https://github.com/fulcrologic/escapement/actions/workflows/test.yml)

*Escapement regulates LLM agents the way a watch escapement regulates a mainspring.*

A statechart-driven autonomous coding agent in Clojure/Babashka.

## What's interesting

- Control flow is a **statechart**, not a free-form LLM loop. The chart, not the model, decides what happens next.
- An LLM conversation is **bound to a chart state** via a custom `InvocationProcessor`. While the state is active a
  worker thread holds the conversation; when the chart leaves the state the conversation dies.
- Two kinds of tools: **real tools** (fs/shell/repl, invisible to the chart) and **event tools** (synthesized from each
  state's `:allowed-events`; calls become chart events with Malli-validated payloads).
- **Multi-agent / team patterns** via `:target`-routed messages between concurrent LLM invocations and `tell-other-llm`.
- **Human-in-the-loop** via the `:human-input` invocation (text / select / multi-select / confirm / progress kinds) and
  the `with-llm-questions` helper that lets an LLM ask the user mid-conversation.
- **Persistent TUI** (JLine, Mode 2026 atomic frames when supported) shows the live chart configuration, transcript
  scrollback, and modal input. Esc sends `:ui.interrupt` to the chart.
- **File-backed artifacts** under `<session>/artifacts/` so agent outputs can be addressed by name in follow-on prompts
  via mustache-style `{{name}}` templates.
- **Self-cancelling invocations** via `:max-turns` and `:max-conversation-duration-ms` budgets that fire SCXML-canonical
  `:error.llm.*` events.
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
# Any one of these will be auto-detected (precedence top-to-bottom):
export ANTHROPIC_API_KEY=...     # claude-sonnet-4-6 by default
export OPENAI_API_KEY=...        # gpt-4o-mini by default (override via OPENAI_MODEL)
export OPENROUTER_API_KEY=...    # openai/gpt-4o-mini by default (override via OPENROUTER_MODEL)
export ZAI_API_KEY=...           # glm-4.6 (cheap dev option)

escapement run escapement.examples.hello/agent
```

The CLI auto-detects a backend from those env vars in the order above; pass `--backend` explicitly to override. A
logged-in OpenAI Codex OAuth token (between ANTHROPIC and OPENAI in precedence), `OPENCODE_GO_API_KEY`, and
`OLLAMA_API_KEY` are also picked up automatically when present. Useful run-time flags:

- `--param key=value` — seed initial-data entries (repeatable; merges over `--input <edn-file>`).
- `--debug` — force the persistent TUI on (paused at first event so you can step or press `c` to continue). This is the
  recommended way to watch a non-interactive chart's LLM conversations stream by in real time.
- `--no-tui` — force the TUI off (useful in CI; `:human-input` prompts fall back to stdin). Charts marked
  `^{:interactive? true}` open the TUI by default when a TTY is present.

Output goes under `.escapement/<session>/{transcript.jsonl,checkpoints/}`.
Expected `final-config`: `[:run :finished]`.

### Project configuration

Drop an optional `.escapement.edn` at the root of any project to pin its layout once. Keys: `:source-paths` (extra
classpath roots for chart utility namespaces), `:deps` (runtime Maven/git coordinates resolved via
`babashka.deps/add-deps`), `:tools-ns` (registration fn symbol or vector), `:work-dir` (default transcript/checkpoint
location), `:default-chart` (used when `escapement run` is invoked without a chart symbol). Escapement walks up from the
current directory to find the file, so invocation is location-independent. See the *Project configuration* section of [
`Guide.adoc`](Guide.adoc) for the full schema and precedence rules.

## Embedding Escapement as a library

Escapement has **three usage modes**:

1. **CLI** — the `escapement run …` flow above (see [Quickstart](#quickstart)). Unchanged.
2. **Hosted library** — call `escapement.lib/run` from your own JVM/bb process. The facade validates a closed option
   map, generates a stable `:run-id`, defaults transcript + checkpoint to a fresh temp dir, and quiets statecharts-impl
   logging by default. It is **hermetic**: it never reads `.escapement.edn` from disk and never sniffs credential env
   vars — a host injects providers and config as explicit data on every call. This section.
3. **Future daemon direction** — a long-lived multi-session HTTP/socket service is an intended later direction. It is *
   *deferred / not in this slice**; no daemon API exists yet. Described for orientation only in [
   `Guide.adoc`](Guide.adoc).

### Hosted-library quickstart

`escapement.lib/run` takes a closed option map and returns a summary map. The only required keys are `:chart` and
`:session-id`; everything else is optional and defaults sensibly (no `:transcript-path`/`:checkpoint-dir` needed — a
fresh temp dir is created). Attach `escapement.lib.event-sink` to `:transcript-tap` to receive a stable, normalized
public event stream. Correlation stays in **your** closure — capture `:run-id`/`:session-id` yourself; no host
identifiers go into payloads.

Copy-run as written (from a clone of this repo):

```clojure
;; bb -e "$(cat this-snippet.clj)"   (or paste into a bb REPL)
(require '[escapement.lib :as lib]
         '[escapement.lib.event-sink :as sink]
         '[com.fulcrologic.statecharts.chart :as chart]
         '[com.fulcrologic.statecharts.data-model.operations :as ops])
(import '[com.fulcrologic.statecharts.elements])
(refer 'com.fulcrologic.statecharts.elements :only '[state transition final script on-entry])

;; A tiny chart: assign into the data model, then transition to a final state.
(def greet
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :go}
          (state {:id :go}
                 (on-entry {} (script {:expr (fn [_ _]
                                               [(ops/assign :msg "hi from embedded escapement")])}))
                 (transition {:target :done}))
          (final {:id :done}))))

;; Correlation stays in the HOST closure: collect normalized public events,
;; tag them with whatever the host already knows. No host id is put in payloads.
(let [events  (atom [])
      adapter (sink/make-adapter)
      result  (lib/run {:chart          greet
                        :session-id     "demo-session"
                        :transcript-tap (fn [row]
                                          (doseq [e (sink/feed! adapter row)]
                                            (swap! events conj e)))})]
  (println "run-id      :" (:run-id result))
  (println "status      :" (:status result))
  (println "final-config:" (:final-config result))
  (println "transcript  :" (:transcript result))
  (println "public events:")
  (doseq [e @events]
    (println "  " (:type e) "run-id=" (:run-id e)))
  (println "all events carry run-id? :"
           (every? #(= (:run-id result) (:run-id %)) @events)))
```

Output (the `:run-id` and temp path differ each run; stderr is quiet by default):

```
run-id      : 3d8e6cb3-7e0f-4bcb-a687-6c19a550aa0d
status      : :done
final-config: [:run :done]
transcript  : /tmp/escapement-run-13211622512067290675/transcript.jsonl
public events:
   :run-started run-id= 3d8e6cb3-7e0f-4bcb-a687-6c19a550aa0d
   :chart-config run-id= 3d8e6cb3-7e0f-4bcb-a687-6c19a550aa0d
   :run-done run-id= 3d8e6cb3-7e0f-4bcb-a687-6c19a550aa0d
all events carry run-id? : true
```

### Hermetic config and credentials

A chart with an LLM conversation is driven entirely by injected data — no disk, no env on the lib path. A host
configures everything once at startup and passes it on every `run`:

- **`:credentials`** — **required**: an ordered vector of provider descriptor maps. Each names a `:provider` keyword
  plus the env-free secret/override it needs (`:api-key`, `:base-url`, `:model`, or `:subscription true`). The backend
  is assembled from these (an explicit `:backend` is an escape hatch that wins verbatim).
- **`:config`** — optional: the `.escapement.edn`-shaped map (`:llm/preferences`, `:llm/ratings`,
  `:llm/eligibility-strict?`). Absent ⇒ an empty ratings table and the built-in default preference order, never a disk
  fallback.

```clojure
(lib/run
  {:chart       my.app.charts/agent
   :session-id  :req-42
   ;; REQUIRED for any chart with an :llm-conversation — the facade wires the
   ;; LLM processor only when BOTH a backend and a :tool-registry are present.
   :tool-registry (escapement.tools.protocol/new-registry)   ; or new-builtin-registry
   :credentials [{:provider :z-ai-plan :subscription true}
                 {:provider :anthropic :api-key (System/getenv "ANTHROPIC_API_KEY")}]
   :config      {:llm/preferences [{:provider :z-ai-plan :model "glm-4.6"}
                                   {:provider :anthropic :model "claude-opus-4-7"}]
                 :llm/ratings     {"claude-opus-4-7" {:clojure 9}}
                 :llm/eligibility-strict? true}})
```

A chart node expresses what it `:needs` (a flat eligibility gate that **filters**; ranking is the sole job of the sorted
`:llm/preferences`). Two `run` calls in one process with different `:config` ratings resolve independently — there is no
process global. The full worked reference example (host config + a two-node statechart + the resolution walk-through) is
in the **Hosted library** section of [`Guide.adoc`](Guide.adoc).

The snippet above is deliberately a *no-LLM* chart so it runs with zero secrets. For a **realistic embedded LLM chart
** — authored with `escapement.chart.helpers`, sharing context between phases via file-backed artifacts, injecting
`:credentials`/`:config`/`:initial-data`/`:session-dir`, and **streaming assistant tokens live via the
public `:text-delta` event** (the supported alternative to hand-matching raw `:llm/delta` rows) — see the runnable [
`demos/lib/`](demos/lib/) example (`bb -m lib.embed-example`).

A run is made cancellable by passing `:cancel` an atom (or a delivered promise/future/delay); when it becomes truthy the
run aborts promptly at a safe boundary and the result map's `:status` is `:aborted` (omitting `:cancel` always yields
`:status :done` for a normal run). The full closed option schema, result-map keys, the public event union, the locked
design decisions, migration notes, and known limitations are in the **Hosted library** section of [
`Guide.adoc`](Guide.adoc).

## See [`Guide.adoc`](Guide.adoc) for the full guide

The guide covers:

- **Introduction** — design rationale and one-paragraph overview
- **Quickstart** — clone-to-running in five lines
- **Core concepts** — chart, bound state, real tool vs event tool, lifecycle, transcript, checkpoint, resume
- **Authoring a chart** — line-by-line walkthrough of `hello.clj` plus annotated excerpts of `scan`, `parallel_demo`,
  `iterate`
- **The `:llm-conversation` invocation** — every `params-fn` key (incl. `:max-turns` / `:max-conversation-duration-ms`),
  `h/llm-conversation` / `h/tell-llm` / `h/tell-other-llm`, SCXML-canonical `:error.llm.*` events, internal-vs-external
  transitions, bad-tool-use retry, event-tool naming
- **Human interaction and the TUI** — `:human-input` invocation kinds, `human-input` chart helper, `with-llm-questions`
  for LLM-asks-human, the persistent TUI, `^{:interactive? true}` chart marker
- **Multi-agent / team patterns** — `:target`-routed messages, parallel-region advisors, `tell-other-llm`, file-backed
  artifact helpers (`capture-llm-output` / `render-template` / `forward-llm-output`)
- **LLM backends** — Anthropic + z.ai + OpenAI/OpenRouter configuration, the caching wrapper
- **File-based prompts** — `escapement.prompts` and the `{{VAR}}` substitution model (distinct from artifact templates)
- **Tools** — `Tool` protocol, the eight built-ins (fs read/write/edit/multi-edit/glob/grep + shell + repl), how to
  register a custom tool
- **Transcript, runner, CLI** — event vocabulary, `jq` recipes, `--resume` / `--param` / `--debug` / `--no-tui`,
  work-dir layout
- **Idioms and gotchas** — `:type :internal`, `tell-llm` mid-binding, parallel regions, the top-level-`final` trap,
  resume side-effect caveat
- **Testing** — the bb-friendly harness (`escapement.engine.testing`), mocking `LLMBackend`, stubbing tools, stub
  `HumanRenderer`, live smoke scripts; run with `bb test`
- **Project layout** — one paragraph per top-level directory
- **Known limitations and roadmap**
- **Contributing** — adding a chart, tool, or backend

## Built-in tools

All bb-resident. The fs and search tools are at Claude-Code-parity ergonomics for the LLM.

| Tool             | Purpose                                                                                                               |
|------------------|-----------------------------------------------------------------------------------------------------------------------|
| `:fs/read`       | Read with `cat -n` line numbers; pageable via `offset`/`limit`                                                        |
| `:fs/write`      | Atomic UTF-8 write (temp-file + `ATOMIC_MOVE`)                                                                        |
| `:fs/edit`       | String-replacement edit; unique-match by default, opt-in `replace-all`                                                |
| `:fs/multi-edit` | Atomic batch of edits to one file; later edits see earlier output                                                     |
| `:fs/glob`       | `PathMatcher`-backed walk; matches `**/*.foo` at root and nested                                                      |
| `:fs/grep`       | `rg` if available, `grep -rE` fallback; files / content / count modes                                                 |
| `:shell/run`     | `bash -lc` with timeout                                                                                               |
| `:web/search`    | Google search via Gemini `google_search` grounding (only registered when `GEMINI_API_KEY` is set)                     |
| `:web/fetch`     | HTTP GET streamed to a temp file under `$TMPDIR/escapement-fetch/`; returns metadata so the LLM reads slices off disk |

These are seeded into `escapement.tools.builtin/default-registry` — a `defonce` singleton the CLI uses. Custom tools
self-register by side effect at the top of their namespace:

```clojure
(ns my.app.tools
  (:require [escapement.tools.builtin :as builtin]
            [escapement.tools.protocol :as tp]))
(tp/register! builtin/default-registry (->HttpGetTool))
```

Any chart whose require-graph reaches `my.app.tools` will then have `:http/get` available. By default a chart state's
`:llm-conversation` exposes **every** real tool in the registry; pass `:real-tools [:fs/read :http/get]` (vector or set)
to whitelist a subset. See the *Tools* section of the [Guide](Guide.adoc) for full schemas, the `--tools-ns` CLI flag,
and how to use a fresh isolated registry (`new-builtin-registry`) for tests or multi-tenant hosts.

## File-based prompts

`escapement.prompts` provides a tiny `{{VAR}}` template helper for charts whose system prompts are too large or too
parameterized for inline `(str ...)` blobs. Tokens of the form `{{IDENT}}` (uppercase + digits + underscores) are
substituted from a map keyed by keyword, symbol, or string; unresolved tokens fail loudly. See
`demos/unit_test/prompts/` for example prompts and `demos/unit_test/prompts.clj` for the per-phase render wiring.

## Demo charts

Small worked examples under `src/escapement/examples/`:

- [`hello.clj`](src/escapement/examples/hello.clj) — minimal single-region chart, one event tool
- [`scan.clj`](src/escapement/examples/scan.clj) — real tool (`:fs/read`) plus fan-out of multiple event-tool calls
- [`parallel_demo.clj`](src/escapement/examples/parallel_demo.clj) — two parallel regions, independent conversations,
  join on compound final
- [`iterate.clj`](src/escapement/examples/iterate.clj) — non-trivial coding loop with `tell-llm` mid-binding,
  `:max-iterations` cap, retry, and give-up paths
- [`clj_refactor.clj`](src/escapement/examples/clj_refactor.clj) — gates model auto-selection on per-dimension ratings
  via a declarative `:needs` eligibility gate (`{:clojure [:>= 8] :tool-calling [:>= 6]}`)
- [`artifacts_demo.clj`](src/escapement/examples/artifacts_demo.clj) — sequential LLM phases sharing context through
  file-backed artifacts and `{{name}}` template rendering
- [`ask.clj`](src/escapement/examples/ask.clj) — `:human-input` invocation patterns (text / confirm) plus Esc-based
  `:ui.interrupt`

End-to-end demo under `demos/`:

- [`demos/lib/`](demos/lib/) — **embedding Escapement as a library**: a two-phase LLM chart driven via
  `escapement.lib/run` with injected credentials/config/initial-data, artifact-shared context, and live `:text-delta`
  streaming via `escapement.lib.event-sink`. The example a host project should read first.
- [`demos/unit_test/`](demos/unit_test/) — port of the pi `unit_test` extension. Drives an LLM through behaviors →
  abstraction → (write|gap-analysis) → (critique|patch) → refine to author and seal `fulcro-spec` tests for a target
  function. Includes a sibling **REPL-manager parallel region** that establishes a project nREPL (cheap scripted
  discovery first; LLM-driven `deps.edn` inspection on miss) and hands the port to refine via shared data + one
  coordination event. Tested end-to-end against [`fulcrologic/fulcro`](https://github.com/fulcrologic/fulcro): generated
  and sealed a 52-assertion test file for `resolve-tempids`.

See [`plan.md`](plan.md) for design history.

Status: prototype.
