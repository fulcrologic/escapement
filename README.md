# statechart-agents

A coding-agent framework whose control flow is a **statechart**, not a free-form
LLM loop. LLM conversations are bound to chart states via the statechart
library's invocation mechanism; while a state is active a background worker
talks to the model, dispatches tool calls, and posts named events back to the
chart. The chart decides what happens next.

The goal: make agent behavior **inspectable, resumable, and testable** by
keeping the control plane out of the LLM.

## Quickstart

```bash
git clone <this-repo>
cd statechart-agents
export ZAI_API_KEY=...                 # or ANTHROPIC_API_KEY
bb -m deep-cookie.cli run deep-cookie.charts.hello/agent \
   --backend api \
   --api-base-url https://api.z.ai/api/anthropic \
   --api-key-env ZAI_API_KEY \
   --model glm-4.6
```

Output: a per-session directory under `.deep-cookie/<session-id>/` containing
`transcript.jsonl` (every LLM request/response, every tool call, every state
transition) and `checkpoints/<session>.edn` (atomic working-memory snapshots).

To run the M7 iterative coding agent against a tmp directory:

```bash
bb bb_test/m7_live_iterate.clj
```

## Authoring a chart

A chart is a Clojure data structure (built with `com.fulcrologic.statecharts.chart/statechart`).
The simplest demo lives at `src/deep_cookie/charts/hello.clj`:

```clojure
(def agent
  (chart/statechart
   {:initial :run}
   (state {:id :run :initial :greeting}
          (state {:id :greeting}
                 ;; Bind an LLM conversation while :greeting is active.
                 (h/llm-conversation
                  {:id        "hello"
                   :params-fn (fn [_env _data]
                                {:system               "...prompt..."
                                 :real-tools           []
                                 :allowed-events       [{:event       :done
                                                         :data-schema [:map [:greeting :string]]}]
                                 :initial-user-message "Say hello."
                                 :max-tokens           120})})
                 ;; When the LLM fires event__done, transition to :finished.
                 (transition {:event :done :target :finished}
                             (script {:expr (fn [_env data]
                                              [(ops/assign :greeting
                                                           (get-in data [:_event :data :greeting]))])})))
          (final {:id :finished}))))
```

Two things give the chart its power:

1. **`real-tools`** — actual side-effecting tools exposed to the model. These
   are dispatched inside the worker and the results go back to the model;
   the chart never sees them.
2. **`allowed-events`** — names of events the LLM may emit by calling
   `event__<name>(<data>)`. These names become chart events; the chart decides
   what state to transition to.

Progressively richer examples (read in this order):

- `src/deep_cookie/charts/hello.clj`        — minimal single-region chart, one event.
- `src/deep_cookie/charts/scan.clj`         — uses a real tool (`fs/read`) plus a
                                              fan-out of multiple event-tool calls.
- `src/deep_cookie/charts/parallel_demo.clj` — two parallel regions, each with its
                                              own LLM conversation, joining on a
                                              compound final.
- `src/deep_cookie/charts/iterate.clj`      — non-trivial: read-spec → propose-patch
                                              → run-tests → reflect loop with
                                              `:max-iterations` cap, retry, and
                                              give-up paths.

## Architecture

```
            ┌─────────────────────────────────────────────────────┐
            │                       Runner                        │
            │  start! → pump events → save checkpoint → done      │
            └──────────┬──────────────────────────────┬───────────┘
                       │                              │
              ┌────────▼─────────┐         ┌──────────▼──────────┐
              │  Statechart proc │◀──────▶ │ LlmConversation     │
              │  (algorithm)     │  events │ InvocationProcessor │
              └────────┬─────────┘         └──────────┬──────────┘
                       │ on-entry                     │ background thread
                       │ script actions               │  ┌──────────────┐
                       ▼                              ▼  ▼              │
              ┌──────────────────┐             ┌────────────────────┐   │
              │ Tool registry    │◀────────────│ LLM backend        │───┘
              │ (:fs/read, ...)  │  side-effect│ (Anthropic / zai / │
              └──────────────────┘             │  claude-p)         │
                       ▲                       └────────────────────┘
                       │
              transcript JSONL  ◀── every step is logged
```

- **Chart** — `com.fulcrologic/statecharts` declarative state machine.
  Authoring sugar lives in `deep-cookie.chart.helpers`.
- **Processor** — `LlmConversationProcessor` (in `invocation/llm-conversation.clj`)
  is registered as an SCXML-style `<invoke>` handler. While its owning state is
  active, a background worker drives the LLM and dispatches tool calls.
- **LLM backend** — `LLMBackend` protocol (`llm/protocol.clj`) with two
  implementations: `llm/api.clj` (Anthropic-compatible HTTP) and `llm/claude_p.clj`
  (out-of-process `claude -p`). Both are wrappable by `llm/cache.clj`.
- **Tools** — `Tool` protocol (`tools/protocol.clj`) with built-ins for
  `:fs/read`, `:fs/write`, `:fs/edit`, `:shell/run`, `:repl/eval`.
- **Transcript** — every interesting event (LLM request, response, tool call,
  state transition, checkpoint write) is written as a JSONL row by
  `transcript.clj`.

## Operating model

**Inspect a transcript** with `jq`:

```bash
jq -c 'select(.event=="llm/response")' .deep-cookie/<sid>/transcript.jsonl
jq -c 'select(.event=="runner/event-processed") | .data.event-name' \
   .deep-cookie/<sid>/transcript.jsonl | sort | uniq -c
```

**Resume from a checkpoint** with the CLI `--resume` flag:

```bash
bb -m deep-cookie.cli run deep-cookie.charts.iterate/agent \
   --session my-session --resume
```

The runner skips `start!` if a non-empty checkpoint exists for that session
and continues from the saved working memory.

**Switch backends** — three are supported:

| Backend            | When to use                                                                |
|--------------------|----------------------------------------------------------------------------|
| `api` + Anthropic  | Production; `--api-key-env ANTHROPIC_API_KEY`                              |
| `api` + z.ai       | Cheap-and-fast dev; `--api-base-url https://api.z.ai/api/anthropic`        |
| `claude-p`         | When you want the local `claude` CLI to do the talking (no API key needed) |

## Common idioms

- **`:type :internal` transitions** — keep the LLM binding alive across hops
  inside a compound state. Used heavily in `iterate.clj` so the conversation
  context survives `propose-patch → run-tests → reflect → propose-patch`.
- **`h/tell-llm` mid-binding** — post a `:llm.user-message` event from a chart
  script; the autoforwarded invocation routes it into the live conversation as
  a new user turn. Works only while the binding state is active.
- **Parallel regions with `parallel`** — see `parallel_demo.clj`. Two
  independent LLM conversations run on separate threads; the parent state
  reaches its final config when both regions hit `:done`.
- **Event-tool naming** — the worker exposes `:foo-bar` as the LLM-facing tool
  name `event__foo_bar` (qualified keywords become `<ns>_<name>`). Names must
  be alphanum/underscore; Anthropic enforces this.

## Known limitations

- `claude -p` does not expose true streaming tool-use semantics. The
  `claude-p` backend works for end-turn-only flows but does not support
  iterative tool dispatch. Prefer the `api` backend for anything non-trivial.
- **Checkpoint-resume restarts LLM conversations.** An in-flight conversation
  does not survive a process restart — re-entering a state with a
  `:llm-conversation` invocation starts a new worker (this is idempotent for
  read tools like `:fs/read` but **at-most-once cannot be guaranteed for
  side-effecting tools** like `:fs/edit` or `:shell/run`). Charts that need
  durable side-effect tracking must drive it themselves via the data model.
- LLM token budgets are per-turn, not per-session. Long iterate runs can
  accumulate context. Pass `:max-tokens` in `params-fn` and consider
  prompt-engineering for brevity (the `iterate` chart caps responses at 512).

## Project layout

- `src/deep_cookie/engine/` — custom engine pieces (queue, store, exec model,
  env, testing harness) that replace `com.fulcrologic.statecharts.simple`,
  which crashes under Babashka due to a `promesa`/SCI conflict.
- `src/deep_cookie/llm/` — backends (`api`, `claude-p`), the `LLMBackend`
  protocol, caching wrapper, and shared request/response types.
- `src/deep_cookie/tools/` — `Tool` protocol, registry, and built-in tool
  implementations.
- `src/deep_cookie/invocation/` — `LlmConversationProcessor` (background
  worker + tool dispatch + event posting).
- `src/deep_cookie/chart/` — authoring helpers (`llm-conversation`, `tell-llm`).
- `src/deep_cookie/charts/` — demo charts: `hello`, `scan`, `parallel_demo`,
  `iterate`.
- `src/deep_cookie/runner.clj` — top-level pump loop with checkpoint/resume.
- `src/deep_cookie/cli.clj` — Babashka/JVM CLI entry point.
- `test/` — kaocha unit tests; run via REPL (`(k/run :unit)`).
- `bb_test/` — Babashka-runnable scripts including live smoke tests
  (`m6_live_smoke.clj`, `m7_live_iterate.clj`).
- `spike/` — original spike scripts that proved the patterns.

See [`plan.md`](plan.md) for the detailed design history and
[`SPIKE_FINDINGS.md`](SPIKE_FINDINGS.md) for the Babashka/JVM compatibility
notes that motivated the custom engine.
