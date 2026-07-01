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

## Layering: engine core vs. presentation add-ons

Escapement is a runtime/library **core** plus optional presentation add-ons.
The dependency direction is strictly one-way (`add-on → core`, never the
reverse), and that boundary is **enforced by a test**:

- **Engine/library core** — `engine.*`, `runner`, `lib`, `protocols`,
  `invocation.*` (incl. the `HumanRenderer` protocol + dependency-free
  `StdinRenderer`, so charts are interactive headlessly), `llm.*`, `tools.*`,
  `storage.*`, `transcript`, `debug.{controller,control-handle,d2}`, `config`,
  and the CLI (`cli.clj`). The embeddable entry point is `escapement.lib`. Core
  must NOT statically require the web/Pathom/RAD UI or the terminal UI.
- **Web/API add-on** — `escapement.ui.*` + Pathom/EQL/transit + the RAD/CLJS
  bundle. Loaded **lazily** by `--api-server` via `requiring-resolve`; if its
  deps are absent the flag fails with a clear message.

  These deps are kept OUT of every downstream-facing dependency manifest so a
  consumer using only the core lib/CLI is not infected by the Pathom/Fulcro/RAD
  tree: `pom.xml` (Clojars) omits them, and `deps.edn` base `:deps` omits them —
  they live in the **`:api` alias** instead (compose as `-M:api:ui-test`, etc.).
  Only `bb.edn`'s own `:deps` carries them, because the bb runtime/bbin product
  *is* the full tool (its `--api-server` runs the Pathom surface under bb). The
  only namespace that requires Pathom is `escapement.ui.resolvers` (via
  `server.clj`); it is server-side only and is NOT in the browser build graph.
- **Terminal-UI add-ons** — there are two, both used by the CLI front-end only; the
  embeddable library (`escapement.lib`) never pulls either:
  1. **JLine TUI** (default) — `escapement.tui` (+ JLine), in-process.
  2. **OpenTUI sidecar** (opt-in via `--tui=opentui`) — an out-of-process Bun + SolidJS UI under
     `tui/opentui/` (outside `src/`, so the boundary scanner never sees it). Its agent-side glue lives in
     the `escapement.ui.*` add-on (`ws_push`, `remote_renderer`, `opentui_sidecar`, the
     `escapement.human/answer` mutation in `resolvers.cljc`) and is reached from `cli.clj` only via
     `requiring-resolve` (like `--api-server`). The agent runs headless and pushes its transcript
     event stream to the sidecar over a WebSocket on the api-server; the sidecar owns the TTY. `bb
     test` covers the agent side (incl. `test/escapement/ui/opentui_push_test.clj`); the TypeScript UI
     is tested under `bun test` (`bb opentui-test`) — a separate test path. See
     [`docs/opentui-ui.md`](docs/opentui-ui.md).

`test/escapement/architecture_boundary_test.clj` scans every `ns` form under
`src/escapement` and fails if a core namespace statically requires the
forbidden layers (web/Pathom/RAD everywhere; the TUI everywhere except
`cli.clj`). Lazy `requiring-resolve` bridges are intentionally invisible to it.
When adding code, keep heavy presentation deps behind that seam.

## Statecharts caveats

This project uses `com.fulcrologic/statecharts`. The library's
`com.fulcrologic.statecharts.promise` namespace provides a host-portable
promise API (bb, CLJ, CLJS) with no hard dep on `funcool/promesa`, so
escapement requires no shim. Escapement code uses that namespace directly:

```clojure
(:require [com.fulcrologic.statecharts.promise :as p])
```

The full async family is available without ceremony:

- `com.fulcrologic.statecharts.simple-async`
- `com.fulcrologic.statecharts.testing-async`
- `com.fulcrologic.statecharts.invocation.statechart` (chart-as-invokable)
- `com.fulcrologic.statecharts.execution-model.lambda-async`
- `com.fulcrologic.statecharts.event-queue.async-event-loop`
- `com.fulcrologic.statecharts.event-queue.async-event-processing`
- `com.fulcrologic.statecharts.event-queue.core-async-event-loop`
- `com.fulcrologic.statecharts.algorithms.v20150901-async` (+ `-impl`)

Still avoid `com.fulcrologic.statecharts.integration.fulcro*` — it pulls
Fulcro client-side machinery that is not bb-compatible.

`escapement.engine.env` and `escapement.engine.testing` remain in use as
the project's env builder and InvocationProcessor-mock test harness. They
are not replaced by the upstream `simple-async` / `testing-async` — those
serve different layers (chart logic vs. invocation wiring) and can be
adopted alongside as appropriate.

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
bb test               # engine + queue + control-plane suite (bb, guardrails 1.2.16)
bb ui-test            # RAD/TUI explorer tests (JVM, guardrails 1.3.2) — render-target-isolated JVMs; see "Web UI"
bb sanity             # engine smoke
bb opentui-test       # OpenTUI sidecar UI tests (bun test in tui/opentui/: unit + snapshot) — see "OpenTUI sidecar UI"
bb opentui-build      # OpenTUI sidecar typecheck (bun x tsc --noEmit in tui/opentui/)
bb haiku-opentui      # haiku tournament via the OpenTUI sidecar (--tui=opentui); needs a real TTY + bun + ollama gemma3:1b
bb -m escapement.cli run escapement.examples.hello/agent   # run a chart
bbin install .        # install the CLI
bb build-ui           # release-compile the browser SPA + refresh resources/escapement-ui.edn
bb watch-ui           # dev-compile + hot-reload the browser SPA
bb release-ui         # publish the already-built main.js to GitHub release escapement-<version> (needs gh; verify-not-rebuild)
```

## Web UI (RAD explorer + live debugger)

`--api-server <port>` on `run` publishes the read-only EQL/Pathom API (`escapement.ui.server` +
`escapement.ui.resolvers`) AND serves an optional Fulcro browser SPA (`escapement.ui.client`) on the
same origin. The SPA is a **statechart-driven RAD explorer** (Sessions → Events → Artifacts) plus a
live Debugger + embedded statechart visualizer. The RAD reports/model are **shared CLJC** and render
two ways: Semantic-UI in the browser (`escapement.ui.rendering.semantic-ui`) and a fulcro-tui terminal
target (`escapement.ui.rendering.tui`). CLJS deps live only in the `:cljs` alias (never on the bb
runtime). The compiled bundle is gitignored and is **not** in the Clojars jar either (it is absent from
the release-tag checkout `release:perform` builds from) — so both jar and `bbin` installs fetch it from
the GitHub release on first use (SHA-256-verified against the committed `resources/escapement-ui.edn`).
The bundle now carries the RAD/Semantic-UI/elk stack; `bb build-ui` is otherwise unchanged. **Read
[`docs/web-ui.md`](docs/web-ui.md) before touching the UI build/release.** After changing
`client.cljs` (or any UI code), run `bb build-ui` and commit the refreshed manifest; never commit
`resources/public/js/`. Release order (see docs/web-ui.md): `bb build-ui` → commit `escapement-ui.edn`
→ maven release (tags) → `bb release-ui` (verify-not-rebuild: publishes the on-disk `main.js`, refusing
unless its SHA matches the committed manifest and the tag is on origin). bbin installs pin to the latest
git tag and fetch+verify that tag's manifest SHA, so the served JS is content-addressed to the
installed ref.

**Live single-stepping:** `--api-server <port> --debug` runs headless + auto-paused; the web Debugger
drives it through control mutations (`escapement.control/{pause,step,continue,arm-pause-on-next-external}`)
and live resolvers (`:session/{paused?,step-budget,live-configuration,pending-events}`). The pause gate
lives in `escapement.engine.instrumented-queue` (engaged only with a `:debug-controller`). `--debug` is
independent of the TUI.

**Two test paths.** The engine + instrumented queue + control plane + the
`test/escapement/ui/live_control_http_test.clj` HTTP proof run under **`bb test`** (guardrails 1.2.16,
the Pathom-under-SCI pin). The RAD/TUI UI code is **JVM-only** (it loads the Fulcro RAD stack which
wants **guardrails 1.3.2**) and runs via **`bb ui-test`**; those namespaces are listed in
`bb_test/escapement/test/runner.clj`'s `jvm-only-namespaces` skip-set so `bb test` stays green. The
engine + api-server stay bb (1.2.16); only the RAD/TUI render code is 1.3.2.

> Render-target isolation: the TUI, Semantic-UI, and headless RAD plugins each register the SAME global
> `fr/render-field` / `render-element` multimethods, so only one render target can load per JVM (last
> `defmethod` wins). `bb ui-test` therefore runs three subprocess groups — `tui` (tui-render + tui-form),
> `web-sui` (web-render), and `headless+core` (screens-load + control + instrumented-queue +
> control-resolvers) — each in its own JVM. Running all UI test namespaces in one `clojure -M:api:ui-test`
> process will show spurious failures (plugins clobber each other); always use `bb ui-test`.

> CLJC gotcha: `::fully.qualified.ns/kw` (double-colon + full ns) resolves in CLJS but is an INVALID
> TOKEN in CLJ — a `.cljc` file using it compiles under shadow yet fails to load under `:ui-test`
> (JVM). Use the single-colon `:fully.qualified.ns/kw` (or a real `::alias/kw`) in cljc.

## OpenTUI sidecar UI (opt-in terminal UI)

`--tui=opentui` renders the session through an out-of-process **Bun + SolidJS + OpenTUI** sidecar
(under `tui/opentui/`) instead of the in-process JLine TUI (which stays the default). The bb agent runs
**headless** and streams its transcript events to the sidecar over a **WebSocket** on the api-server
(`escapement.ui.ws_push`); human-input/control flow back over the same WS; the sidecar owns the TTY.
Agent-side glue is in `escapement.ui.*` (`ws_push`, `remote_renderer`, `opentui_sidecar`) reached via
`requiring-resolve`; the `tui/opentui/` TS tree is outside `src/`. `bb test` covers the agent half;
`bb opentui-test` (= `bun test` in `tui/opentui/`) covers the TS UI. **Read
[`docs/opentui-ui.md`](docs/opentui-ui.md)** (architecture, run, develop, test, known limits) and
[`docs/opentui-wire.md`](docs/opentui-wire.md) (the JSON wire contract) before touching the sidecar.
Snapshot updates: `cd opentui && bun test test/snapshot/ -u`, review the diff, commit.

## Model registry

`src/escapement/llm/models-api.json` is the per-provider model catalog — large; always query with `jq` (e.g. `jq -r '.openai.models|keys[]' …`), never read whole.

## House rules

- No JVM-only paths in `src/`. If you reach for something SCI does not
  support, find a bb-friendly alternative or ask.
- No back-references to a now-deleted `SPIKE_FINDINGS.md`. Useful errata
  from that doc were folded into `plan.md` and source-level docstrings.
- Don't add kaocha or revive the `:kaocha` alias — it was removed
  deliberately when bb became the single test path.
