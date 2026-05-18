# CLAUDE.md

Guidance for Claude Code when working in the **Escapement** repository.

## Project shape

Escapement is a statechart-driven autonomous coding agent. **Everything runs
under Babashka** — the agent process, the CLI, and the test suite (`bb test`).
No JVM is required.

- Runtime: bb (SCI). Source under `src/` and `demos/` must stay bb-compatible.
- Tests: bb via `bb test`, using `fulcrologic/fulcro-spec 3.2.9` (which added
  bb support). Test runner: `bb_test/escapement/test/runner.clj`. Discovers
  `test/**/*_test.clj` automatically.
- Sanity smoke: `bb sanity` (runs `bb_test/sanity.clj`).
- An optional `:test` alias exists in `deps.edn` for IDE/JVM REPL workflows,
  but the project does **not** depend on it.

## Statecharts caveats

This project uses `com.fulcrologic/statecharts` but assembles its own env to
avoid the namespaces that pull `promesa`/`core.async` (which crash SCI). Do
**not** require any of:

- `com.fulcrologic.statecharts.simple` / `simple-async`
- `com.fulcrologic.statecharts.testing` / `testing-async`
- `com.fulcrologic.statecharts.invocation.statechart`
- any `*_async*` or `core_async_event_loop` namespace
- `com.fulcrologic.statecharts.integration.fulcro*`

Use `escapement.engine.env` to build the env and `escapement.engine.testing`
as the test harness in place of the library's `testing`.

## Skill loading

- `clojure` skill — load when touching any `.clj`/`.cljs`/`.cljc` file.
- `clojure-repl` skill — load when running code in a REPL. **Tests in this
  project run via `bb test`, not kaocha**; the skill's general REPL guidance
  is still useful for evaluation.
- `fulcro-spec-tdd` skill — load when writing tests with fulcro-spec macros
  (`specification`, `assertions`, `=>`, `=throws=>`, `provided!`,
  `when-mocking!`).
- `statechart` skill — load when authoring or editing charts.
- `guardrails` skill — required for any function-spec work.
- `bb-tui` skill — load when touching `src/escapement/tui.clj` or other TUI
  bits.

## Test conventions

- Mock backends use atom-of-vector queues via `escapement.test-support`
  (`ts/queue`, `ts/pop-first!`). Do **not** import
  `java.util.concurrent.LinkedBlockingDeque` or `ConcurrentLinkedDeque` —
  SCI does not expose them.
- `java.util.concurrent.LinkedBlockingQueue` and `TimeUnit` are available
  if a real concurrent queue is needed.
- Live-API tests are gated on env vars (e.g. `OPENAI_API_KEY`) and skip
  cleanly when unset.

## Common commands

```bash
bb test               # full suite (133 tests, 614 assertions)
bb sanity             # engine smoke
bb -m escapement.cli run escapement.examples.hello/agent   # run a chart
bbin install .        # install the CLI
```

## House rules

- No JVM-only paths in `src/`. If you reach for something SCI does not
  support, find a bb-friendly alternative or ask.
- No back-references to a now-deleted `SPIKE_FINDINGS.md`. Useful errata
  from that doc were folded into `plan.md` and source-level docstrings.
- Don't add kaocha or revive the `:kaocha` alias — it was removed
  deliberately when bb became the single test path.
