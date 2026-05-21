# CLAUDE.md

Guidance for Claude Code when working in the **Escapement** repository.

## Working context

**Read `workingcontext.md` first** (it is gitignored — local, per-session,
never committed). It holds the current goal, known issues, gotchas, and the
focused list of files we're working on this session. If it does not exist,
create it with these sections:

- **Goal** — one paragraph: what we're trying to accomplish this session.
- **Known Issues** — bugs/gaps in the area being worked on, grouped by
  domain. Each bullet: terse fact + `file:line`. When resolved, strike
  through with `~~…~~` and append **FIXED <date>** (keep the original line).
- **Gotchas** — non-obvious things a future session will trip over (silent
  no-ops, hot-reload caveats, sentinel values, wire-format quirks). One
  line per fact, `file:line` when relevant.
- **Relevant Files** — the focused subset this session is touching, grouped
  by domain. Trim aggressively; this is not a codebase index.

You may autonomously append to `workingcontext.md` whenever you discover
anything that will save us time later — surprises, gotchas, non-obvious
wiring, awkward patterns, undocumented conventions, or pointers to files
we'd otherwise have to re-search for. Keep entries terse (one line per
fact, with a file path + line number when relevant). Do not duplicate
what's already there; do not turn it into prose. Only add facts that a
future session would thank you for.

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

## Model registry

`src/escapement/llm/models-api.json` is the per-provider model catalog — large; always query with `jq` (e.g. `jq -r '.openai.models|keys[]' …`), never read whole.

## House rules

- No JVM-only paths in `src/`. If you reach for something SCI does not
  support, find a bb-friendly alternative or ask.
- No back-references to a now-deleted `SPIKE_FINDINGS.md`. Useful errata
  from that doc were folded into `plan.md` and source-level docstrings.
- Don't add kaocha or revive the `:kaocha` alias — it was removed
  deliberately when bb became the single test path.
