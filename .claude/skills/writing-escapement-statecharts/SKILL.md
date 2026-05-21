---
name: writing-escapement-statecharts
description: Non-obvious gotchas when authoring Escapement statecharts — event naming, conversation lifecycle, transition types, SCI-safe wiring. Use when writing/modifying charts under src/escapement/examples/ or demos/.
---

# Writing Escapement statecharts

Traps the engine won't warn about. Sources: `Guide.adoc` (Idioms/gotchas, params-fn), `CLAUDE.md`, `examples/fired.clj`.

## Events

- **Never use `:done`** (or any prefix of an auto-raised framework event). SCXML descriptors are prefix-matched, so `:done` also matches synthesized `:done.state.X` from finalising compound/parallel states → re-entry loop, pegs CPU. Use `:finish`/`:exit`/namespaced kw. Same trap for prefixes of `error.*`. Canonical write-up: `examples/fired.clj`.
- **Event-tool encoding**: `:foo-bar` → `event__foo_bar`; `:my.ns/foo-bar` → `event__my_ns_foo_bar`; non-alphanum → `_`.
- **`submit_verdict` is reserved** when using `:verdict-schema` — don't collide via `:allowed-events`/`:real-tools`.

## Conversation lifecycle = state lifecycle

- **Leaving the bound state kills the worker** (history, cache, all). Use `:type :internal` to preserve it across transitions (`scan.clj` `:found-bug`, `iterate.clj` loop).
- **`h/tell-llm` only works inside the bound state**; outside it's silently dropped.
- **`tell-llm` broadcasts to all live `:llm-conversation`s**; use `h/tell-other-llm` with `:target <invokeid>` to target one (kw/string invokeids normalize).
- **`:on-end-turn-event` defaults to `:llm.idle`**, payload `{:text :from}`, fires once per logical turn (both `:end_turn` and batched-terminator `:tool_use` shapes).
- **Resume = fresh conversation.** Side-effecting tools (`:fs/edit`, `:shell/run`) are not at-most-once across resume — track durability in the data model (`iterate.clj` bumps `:iterations`).

## Chart structure

- **Top-level `final` empties the configuration** — always wrap in a compound parent.
- **Read trigger payload via `:_event`**: `(get-in data [:_event :data ...])` inside script `:expr`.
- **`:chart-tools` palette is snapshotted at conversation start** — late-registered service-region tools won't be callable in that conversation.

## `params-fn`

- **`:max-tokens` is ignored** — output cap comes from the model catalog (`models-api.json` `limit.output`). Remove it.
- **Explicit `:model` or `:models` disables auto-fallback**. Use `:needs` to filter without disabling the preference-ordered list.
- **Caching is on by default** (`:auto-cache? true`, 5-min ephemeral on system + tail of tools). Anthropic ignores cache_control below 1024 tokens (2048 Haiku).
- **Don't combine `:temperature` with `:thinking`** — temperature is ignored.

## SCI / Babashka (CLAUDE.md)

- Don't require (pulls promesa/core.async, crashes SCI): `statecharts.simple`/`simple-async`, `statecharts.testing`/`testing-async`, `statecharts.invocation.statechart`, any `*_async*`/`core_async_event_loop`, `statecharts.integration.fulcro*`.
- Use `escapement.engine.env` (env) and `escapement.engine.testing` (harness).
- Mock backends: `escapement.test-support` `ts/queue`/`ts/pop-first!`. SCI lacks `LinkedBlockingDeque`/`ConcurrentLinkedDeque`; `LinkedBlockingQueue` + `TimeUnit` are available.

## Threads

Worker threads (`llm-conv-...`) and transcript writer are daemons — they don't block bb exit. Drive graceful shutdown from the chart.
