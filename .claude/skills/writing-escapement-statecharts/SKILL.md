---
name: writing-escapement-statecharts
description: Non-obvious gotchas when authoring Escapement statecharts — event naming, conversation lifecycle, transition types, SCI-safe wiring. Use when writing/modifying charts under src/escapement/examples/ or demos/.
---

# Writing Escapement statecharts

Traps the engine won't warn about. Sources: `Guide.adoc` (Idioms/gotchas, params-fn), `CLAUDE.md`, `examples/fired.clj`.

## Events

- **Namespace EVERY application event** (e.g. `:count/done`, `:count/tick`, `:iterate/retry`). SCXML descriptors are prefix-matched, so a *bare* `:done` also matches synthesized `:done.state.X`/`:done.invoke.Y` from finalising compound/parallel states (and bare `:error*` matches the `error.*` family) → in a `parallel` chart the join re-fires your transition → eventless re-entry loop ("Eventless transition loop exceeded 1000 iterations"), pegs CPU. A namespaced kw has no dot in its first token so it can never collide — and you keep the natural name (`:count/done` is fine; bare `:done` is the hazard). Every chart under `src/escapement/examples/` follows this. Leave framework events bare: `:llm.idle`, `:llm.user-message`, `error.*`, `done.state.*`, `done.invoke.*`. Canonical write-up: `examples/fired.clj`; full rationale: `Guide.adoc` "Event naming".
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
- **`:chart-tools` palette is snapshotted at conversation start** — late-registered service-region tools won't be callable in that conversation. Region-tools are NOT auto-discovered: you MUST declare `:chart-tools [{:owner <registering-state-id>}]` or the model never sees them.
- **Region-final transition on a region ROOT must be `:type :internal`.** A transition whose source is a `parallel` region's root state and whose target is that region's `<final>` is, as an *external* transition, given the whole `parallel` as its SCXML domain (LCCA of root + its final child). Its exit set then spans every sibling region, so `remove-conflicting-transitions` drops it and the region never finalises → the join never completes. `:type :internal` keeps the domain in-region. (A transition sourced on a *deeper* substate is fine — its domain is the region.)
- **Use `send-after` for safety timers, not a raw `(send {:delay …})`.** A raw delayed send is NOT cancelled when the chart finishes early, so the runner idles for the full delay waiting on the orphaned timer after the chart already reached its final. `(send-after {:id … :event … :delay …})` (from `com.fulcrologic.statecharts.convenience`) pairs an on-entry send with an on-exit cancel. Also: a region-root `:safety/stop` transition has the same external-domain trap as above — terminate via a *top-level* `:safety/stop -> :finished` instead, and handle `:error.llm.max-turns` at top level so a chatty model that burns `:max-turns` ends promptly.

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
