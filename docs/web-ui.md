# Web UI (read-only inspector)

Escapement can serve a browser app over the same **read-only EQL/Pathom surface** the CLI exposes
with `--api-server <port>`. The app is a **statechart-driven RAD explorer**: a Sessions report that
drills into a session's events and artifacts, plus a live **Debugger** screen with an embedded
statechart visualizer. The remote and Fulcro Inspect are fully wired.

It is **optional**: the `/api` surface works with or without it, and a normal `escapement run` never
loads any of the UI code.

The explorer's RAD reports/model are **shared CLJC** and render two ways from one definition: in the
browser with a Semantic-UI adapter, and in a terminal with a fulcro-tui target (see
[RAD explorer](#rad-explorer-shared-reportsmodel-two-render-targets) below). Adding `--debug` turns
the surface into a **live single-stepping** control plane (see
[Live single-stepping](#live-single-stepping-pausestep-over-api)).

## Architecture

| Piece | File | Role |
|-------|------|------|
| SPA entry | `src/escapement/ui/client.cljs` | Fulcro app. `:remote` POSTs transit EQL to `/api`. Installs the Semantic-UI RAD adapter, mounts the explorer `Root`/routing, installs Fulcro Inspect via `ido` (elided from release builds). |
| Explorer model | `src/escapement/ui/model/*.cljc` | Shared CLJC RAD attributes (session / transcript-event / artifact) — all virtual, backed by the Pathom resolvers (no storage adapter). |
| Explorer screens | `src/escapement/ui/screens/*.cljc` | Shared CLJC statechart `defsc-report`/screens: Sessions → Events → Artifacts, session detail, plus the live `debugger` + `chart-view`. |
| Render adapters | `src/escapement/ui/rendering/{semantic-ui,tui}/*.cljc` | Two render targets for the same reports: Semantic-UI DOM (browser) and fulcro-tui terminal nodes. |
| HTTP surface | `src/escapement/ui/server.clj` | http-kit server. `POST /api` runs EQL (reads **and** control mutations); `GET` serves the SPA (same origin, so the relative `/api` remote just works). |
| Resolvers | `src/escapement/ui/resolvers.cljc` | Read resolvers + the live control plane — host-agnostic, shared with the CLI/TUI. |
| Build | `shadow-cljs.edn` `:main` build | `:target :browser` → `resources/public/js/main/main.js`. Now includes the RAD + Semantic-UI + elkjs stack. |
| Page | `resources/public/index.html` | Committed template; `__MAIN_JS__` is replaced at serve time with the content-addressed bundle URL. |
| Fetch manifest | `resources/escapement-ui.edn` | Committed. `{:version :sha256 :asset}` — pins which release asset to fetch and verifies it. |

The CLJS deps (Fulcro, fulcro-rad, fulcro-rad-statecharts, fulcro-rad-semantic-ui, Fulcro Inspect,
devtools-remote, transit-cljs, shadow) live **only** in the `:cljs` alias in `deps.edn`; they are
never on the bb runtime classpath.

## RAD explorer: shared reports/model, two render targets

The explorer is built with **fulcro-rad-statecharts** (RAD reports whose state machine is a
statechart). Its model and screens are **CLJC and shared**, so one report definition drives both the
browser and a terminal:

- **Model** (`escapement.ui.model.{session,transcript-event,artifact}`) — RAD `defattr`s that mirror
  the read-surface resolver output. They are *virtual* attributes (no `ao/schema`): the values come
  from the existing Pathom 2 resolvers (`escapement.ui.resolvers`), not from a RAD storage adapter.
- **Screens** (`escapement.ui.screens.*`) — `defsc-report`s and routing: **SessionsReport** (landing,
  `:escapement/all-sessions`) → row-action drill into **EventsReport** (`::sc/session-id` route param)
  → **ArtifactsReport**, plus session detail, the **Debugger**, and the **ChartView** visualizer.
- **Render adapters** — the same reports render through whichever RAD render plugin is installed:
  - **Browser:** `escapement.ui.rendering.semantic-ui.*` — a hybrid adapter (defmethods for the
    statechart report/form/field render multimethods + a controls map) emitting Semantic-UI-classed
    DOM. Installed by `client.cljs`.
  - **Terminal:** `escapement.ui.rendering.tui.*` — a fulcro-tui plugin whose leaves return terminal
    element nodes instead of DOM. The same RAD reports/model run headless under the JVM.

Because the report logic, query, and model are shared CLJC, there is no second copy of the explorer:
the only per-target code is the thin render adapter.

## How the bundle is delivered

The compiled bundle (~1.2 MB) is **not committed to git** — it would bloat history, since it changes
on every build. Instead the server resolves it on demand, cheapest-first:

1. **Classpath** `public/js/main/main.js` — present in the release jar and after a local `bb build-ui`.
2. **Local cache** `~/.cache/escapement/ui/<version>/main.js` (honours `$XDG_CACHE_HOME`).
3. **GitHub release asset** — downloaded from
   `https://github.com/fulcrologic/escapement/releases/download/escapement-<version>/main.js`,
   **verified against the SHA-256** in `resources/escapement-ui.edn`, then cached.

So a `bbin install` (which builds its classpath from the git tree, with no bundle in it) fetches the
bundle from the matching GitHub release the first time you use `--api-server`, and caches it. With no
bundle and no network, `/` still loads and shows a notice; `/api` always works.

### Cache busting

The bundle is served content-addressed at `/js/main/<sha>.js` with
`Cache-Control: public, max-age=31536000, immutable`, and `GET /` injects that SHA path into
`index.html`. A freshly built/published bundle has a new SHA, so the browser never serves a stale
bundle from cache. (`/js/main/main.js` remains as a `no-cache` alias.)

## Live single-stepping (pause/step over `/api`)

With `--api-server <port> --debug`, a run starts **headless and auto-paused**, and the web Debugger
drives it event-by-event. The mechanism, edge to core:

- **Instrumented event queue** (`escapement.engine.instrumented-queue`). The debug pause gate has been
  **relocated out of the runner loop and into the event queue**. `InstrumentedQueue` wraps the plain
  `InProcessQueue`; in `receive-events!` it applies the gate per deliverable event *before* the
  handler runs (arm-from-external → park on paused → consume a step budget), then delivers. The gate is
  a no-op unless a `escapement.debug.controller` is attached, so a normal run pays nothing.
  `send!`/`cancel!` delegate straight through and stay **non-blocking** — invocation worker threads
  call `send!` while the single draining thread is parked, and that must never deadlock. bb + JVM safe
  (atoms + promises only). It also exposes `pending-events` / `last-delivered` for the live resolvers.
- **Controller** (`escapement.debug.controller`). A plain atom holding `{:mode :step-budget :gate
  :pause-on-next-external?}`. `pause!`/`step!`/`continue!`/`arm-pause-on-next-external!` mutate it and
  release the gate promise.
- **Control handle** (`escapement.debug.control-handle`). The api-server starts *before* the runner
  builds its env, so it cannot be handed the env directly. `cmd-run` creates a shared handle (an atom)
  and passes it to both the server ctx (`:escapement/live`) and `run!`'s `on-env-ready`, which `fill!`s
  it with `{:controller :env :session-id :queue}` the moment the live env exists. Live
  resolvers/mutations deref the handle each request and degrade to nil before it is filled.
- **Control plane on `/api`** (`escapement.ui.resolvers`). The Pathom parser now sets
  `::p/mutate pc/mutate`, so a `POST /api` can dispatch a mutation as well as a read:
  - **Mutations:** `escapement.control/{pause,step,continue,arm-pause-on-next-external}` →
    `{:debug/paused? :debug/step-budget}`.
  - **Live resolvers:** `:session/paused?`, `:session/step-budget`, `:session/live-configuration`
    (the active state set, via `runtime/current-configuration` over the live working-memory store) and
    `:session/pending-events` (queued-but-undelivered events from the instrumented queue).
  - All are gated `#?(:clj …)` (the controller/handle/runtime are JVM/bb-only) so `resolvers.cljc`
    stays cljs-safe; under cljs `live-resolvers` is empty.
- **Screens.** `escapement.ui.screens.debugger` renders Pause/Step/Continue/Arm + the live snapshot;
  `escapement.ui.screens.chart-view` embeds the statechart visualizer (ELK layout) and highlights the
  live configuration. Both read/refresh the single shared `[:component/id ::control/live]` entity, so a
  step in the Debugger updates the chart highlight and vice-versa.

Usage:

```bash
escapement run escapement.examples.hello/agent --api-server 8920 --debug
# open http://localhost:8920/  → Debugger → Pause / Step / Continue / Arm; Chart → live highlight
```

`--debug` is independent of the TUI. The full `POST /api` transit → handler → parser → control path is
proved deterministically (no socket, no engine thread) by
`test/escapement/ui/live_control_http_test.clj`, which runs under `bb test`.

## Two test paths: `bb test` vs `clojure -M:ui-test`

The UI work spans two classpaths that **cannot coexist**, because of a guardrails version conflict:

- **`bb test`** — the engine, the instrumented queue, the control plane, the resolvers, and the
  `live_control_http_test` integration proof. These run under babashka, where Pathom 2.4.0 only loads
  with **guardrails pinned to 1.2.16** (Pathom's transitive guardrails uses a timbre macro newer SCI
  builds reject). These test namespaces stay bb-compatible (no Fulcro RAD).
- **`clojure -M:ui-test`** — the RAD/TUI UI tests (e.g. `escapement.ui.{screens-load,tui-render}-test`).
  These load the Fulcro RAD stack, which wants **guardrails 1.3.2**, and run under the JVM (where
  Pathom has no SCI constraint, so 1.3.2 and Pathom coexist). The `:ui-test` alias overrides the
  guardrails pin for that classpath only.

So the RAD/TUI namespaces are **excluded from `bb test`** via the `jvm-only-namespaces` skip-set in
`bb_test/escapement/test/runner.clj`. Run both:

```bash
bb test                 # engine + queue + control plane + live-control HTTP proof (bb, guardrails 1.2.16)
clojure -M:ui-test      # RAD/TUI explorer screens (JVM, guardrails 1.3.2)
```

> **CLJC gotcha:** `::fully.qualified.ns/kw` (double-colon + full ns) *resolves* in CLJS when that ns
> is required, but is an **invalid token in CLJ** — so a `.cljc` file using it compiles under shadow
> yet fails to load under `:ui-test` (JVM). In CLJC use the single-colon fully-qualified form
> `:fully.qualified.ns/kw`, or a real `::alias/kw` against an established alias.

## Working on the UI in dev

```bash
# Terminal 1 — recompile + hot-reload on every save (dev build, with shadow's reload runtime).
bb watch-ui            # = npx shadow-cljs watch main  (output to resources/public/js/main, gitignored)

# Terminal 2 — serve the app + the live API from one origin so the relative `/api` remote works.
escapement run escapement.examples.ask/agent --no-tui --api-server 8920
```

Open <http://localhost:8920/>. The page loads, the remote hits `/api` on the same origin, and the
active session id renders.

- **Hot reload** is driven by shadow's websocket (it connects back to the `watch` process regardless
  of who served the HTML); `:after-load escapement.ui.client/refresh` re-renders. You normally do not
  refresh the page.
- **Fulcro Inspect**: install the Chrome extension; the dev build includes the
  `com.fulcrologic.devtools.chrome-preload`. (Open a new tab / restart Chrome after first installing
  the extension. See the [fulcro-inspect README](https://github.com/fulcrologic/fulcro-inspect).)
- **If you hard-refresh and see stale code**: the content-addressed SHA is hashed from the bundle once
  per server process, so restart the `--api-server` run to re-hash after a rebuild. (Hot reload does
  not need this.)
- The SPA's remote uses the relative URL `/api`, so it must be served from the same origin as the API
  — i.e. by the escapement server, as above. Serving the HTML from shadow's own dev http server would
  put `/api` on shadow's origin (404). Keep the escapement-served loop unless you add a dev proxy.

## Releasing the UI

The bundle ships two ways — bundled in the clojars jar **and** as a GitHub release asset for bbin.

Two tasks cover it:

- **`bb build-ui`** — `shadow-cljs release main` compiles the optimized bundle to
  `resources/public/js/main/main.js` and rewrites `resources/escapement-ui.edn` with the bundle's
  `:version` (read from `pom.xml`, minus `-SNAPSHOT`) and `:sha256`. **This is the only task that
  produces the bundle and the manifest.**
- **`bb release-ui`** — **verify-not-rebuild**: it never compiles. It asserts the on-disk `main.js`
  SHA-256 equals the committed `escapement-ui.edn` (so the published asset is byte-identical to what
  every bbin install verifies against), that the manifest is committed, and that the tag is on
  `origin`; then it uses `gh` to publish `main.js` on the release `escapement-<version>`, **creating
  the release if needed** (the `maven-release-plugin` makes the git *tag* but not a GitHub *release*)
  and `--clobber`ing an existing asset. Needs `gh` authenticated (`gh auth login`).

The ordering matters — the git tag must capture the same manifest whose SHA the asset will match, and
the bundle must not be recompiled after the manifest is committed:

1. **`bb build-ui`** — compile + write the manifest.
2. **Commit** `resources/escapement-ui.edn`.
3. **Cut the version** (`maven-release-plugin`), which bumps the pom to the release version, commits,
   and tags `escapement-<version>` *at the commit that includes the manifest from step 2*, then pushes.
   `release:perform` builds/deploys the jar — and because `bb build-ui` already populated the working
   tree, the `pom.xml` `<resource>` bundles `main.js` into the jar, so jar users serve it from the
   classpath and never fetch.
4. **`bb release-ui`** — publishes that exact `main.js` to the GitHub release.

Do **not** run `bb watch-ui` between steps 1 and 4 — it overwrites `main.js` with a dev build, and
`release-ui` will refuse to publish (SHA mismatch). If you do, just re-run `bb build-ui`.

## Stability & versioning (the bbin timeline)

`bbin install io.github.fulcrologic/escapement` resolves to the **latest git tag** and pins the
install to that tag's exact commit SHA (it only falls back to latest-`sha` if the repo has *no* tags).
So:

- **Users track tagged releases, pinned immutably.** Intermediate, untagged commits are never
  installed by default; making commits without a new tag does not change what `bbin install` resolves.
- **The fetched JS is content-addressed to the installed ref.** The ref carries both the source and
  the `escapement-ui.edn` manifest; the server fetches `escapement-<version>/main.js` and verifies it
  against that manifest's SHA-256, **failing closed** on any mismatch. A user can never receive a
  bundle that doesn't match the hash committed at the ref they installed.
- **`release-ui` guarantees published-asset == manifest.** Because it verifies rather than recompiles,
  the asset on the GitHub release is byte-identical to the manifest SHA at the tag. No reproducible-
  build assumption, and no possibility of a checksum failure for end users.
- **Jar/library consumers never fetch** — `main.js` is baked into each clojars jar version, so source
  and UI are inherently consistent for that artifact.

The one residual is process discipline: if you change `client.cljs` but forget step 1 (rebuild) before
tagging, the tag's manifest still points at the *previous* bundle, so bbin users get the older UI JS
with newer server source. Because the SPA talks to the server only through the read-only, additive EQL
API, that is a "stale UI" (missing the newest UI features), not a broken one — and `release-ui`'s SHA
check still prevents any mismatch between the manifest and the published asset.

> A dev install (`bbin install .` or `--latest-sha`) can reference a manifest whose
> `escapement-<version>` release doesn't exist yet (the version is the *next* release). The fetch then
> 404s and the UI degrades to its offline notice; `/api` is unaffected. Use a tagged install for a
> guaranteed bundle.

## Files & commands at a glance

- `bb watch-ui` — dev compile + hot reload.
- `bb build-ui` — release compile + refresh `escapement-ui.edn`.
- `bb release-ui` — publish the already-built `main.js` to the GitHub release `escapement-<version>`
  via `gh` (verify-not-rebuild; run `bb build-ui` + commit the manifest first).
- `escapement run … --api-server <port>` — serve `/api` + the SPA on `<port>`.
- `escapement run … --api-server <port> --debug` — same, but headless + auto-paused for live stepping.
- `bb test` — engine + queue + control plane + the `live_control_http_test` HTTP proof (bb).
- `clojure -M:ui-test` — the RAD/TUI explorer screen tests (JVM, guardrails 1.3.2).
- Committed: `resources/public/index.html`, `resources/escapement-ui.edn`, all `src/escapement/ui/*`.
- Gitignored (build artifacts): everything under `resources/public/js/`.
