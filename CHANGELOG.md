# Changelog

## [unreleased] — tui-improvements — 2026-06-01

This branch adds a complete read-only inspection + live-control surface over a
running agent — a Pathom EQL API, a statechart-driven RAD explorer that renders
both in the browser (Semantic-UI) and in the terminal (fulcro-tui), and a live
single-stepping debugger — and hardens every bundled example chart so it runs
cleanly end-to-end. The earlier `2026-05-29` and `2026-05-30` entries below are
the incremental ledger for the API/SPA/RAD/debugger work; this entry is the
consolidated, user-facing view of the whole branch delta vs `main`, including
the example-chart and runner changes not previously recorded.

### Added

- **Read-only EQL/Pathom API on `run` (`--api-server <port>`).** Publishes a
  transit+json EQL endpoint at `POST /api` over the `--work-dir` disk store:
  the active session, past sessions, paged transcript, artifacts, and the
  per-node invocation drill-in. No mutations on a plain `--api-server` run.
- **Statechart-driven RAD explorer (browser + terminal).** A shared CLJC RAD
  model and screens (Sessions → Events → Artifacts, plus session detail) render
  two ways from one definition: a **Semantic-UI** browser SPA and a
  **fulcro-tui** terminal target. The browser bundle is lazy-loaded and
  content-addressed (SHA-256-verified against the committed manifest; jar
  installs serve it from the classpath, `bbin` installs fetch it from the
  GitHub release). A normal `run` without `--api-server` loads no UI code.
- **Live single-stepping debugger (`--api-server --debug`).** A `--debug` run is
  headless-friendly, auto-paused, and now exposes its pause/step controller over
  the api-server. The browser Debugger drives the *same* live run as the TUI:
  **Pause / Step / Continue / Arm-pause-on-next-external**, the live state
  configuration, and the events still queued but undelivered, plus an embedded
  ELK chart visualizer that highlights the active states as you step. Control
  mutations `escapement.control/{pause,step,continue,arm-pause-on-next-external}`
  and live resolvers `:session/{paused?,step-budget,live-configuration,pending-events}`
  back it. The control plane exists only under `--debug`.
- **Instrumented, pausable event queue.** The debug pause/step gate moved out of
  the runner loop into `escapement.engine.instrumented-queue`; it engages only
  when a `:debug-controller` is present (zero overhead otherwise), so the TUI
  keys (`s`/`c`/`p`/`P`), the web Debugger, and the runner loop all single-step
  through one primitive with no double-gating. `send!`/`cancel!` stay
  non-blocking so worker threads never deadlock while the drainer is parked.
- **`--dump-d2`** on `run` prints the chart's `d2` diagram source to stdout and
  exits before any execution machinery (no LLM backend, no session dir, no API
  key) — pipe it to `d2` to render: `… run my.ns/agent --dump-d2 | d2 -`.

### Changed

- **Every bundled example chart now namespaces its application events**
  (`:done`→`:hello/done`, `:tick`→`:count/tick`, `:found-bug`→`:scan/found-bug`,
  `:spec-ready`→`:iterate/spec-ready`, `:translated`→`:parallel/translated`,
  `:step`/`:done`→`:inspect/step`/`:inspect/done`, `:done`→`:refactor/done`/
  `:turn/done`, and the haiku `mux/reply` events to `:haiku/*`). A *bare* event
  whose first dotted token collides with SCXML's reserved `done.*`/`error.*`
  families (e.g. a plain `:done`) re-fires on the engine's auto-raised
  `done.state.*`, which in a `parallel` chart wedges the macrostep in an
  eventless loop. Namespaced names cannot prefix-match a reserved family.
  System-prompt text and the synthesized event-tool names update in lockstep
  (`:scan/found-bug` → `event__scan_found_bug`, etc.).
- **Parallel example charts: region-root → region-`<final>` transitions are now
  `:type :internal`.** As external transitions their SCXML domain is the whole
  `parallel`, so `remove-conflicting-transitions` dropped them and the region
  never finalised (the join never completed). Internal keeps the domain
  in-region. Applied across `steered_convo`, `steer_midturn`, and `supervisor`.
- **Runner frozen-config guard default raised 200 → 4000 cycles** (~10s → ~200s
  at the default 50ms quiescent sleep). A single slow LLM turn no longer trips
  the `:frozen-config` abort during legitimate idle while the model is thinking.
- **Example charts gained `send-after` safety-stop timers** (on-entry send +
  on-exit cancel, so no orphaned timer survives reaching `:finished`) and a fast
  exit on `:error.llm.max-turns`, so a live run terminates promptly instead of
  hanging or idling into the safety stop.
- **`scan` and `clj-refactor` input hardening.** `scan` now names a concrete
  absolute repo root, instructs the model to read at most two real files (no path
  guessing/retries), and bounds the conversation with `:max-turns 8` /
  `:budget-ms 120000`. `clj-refactor` now gives the model real `:fs/read`/
  `:fs/edit` tools, takes an optional `:target-path`, and uses the namespaced
  `:refactor/done` completion event.

### Notes

- The browser RAD explorer's visual rendering (Semantic-UI report/form layout,
  the ELK live-chart highlight, the Pause/Step/Continue button wiring) is **not**
  unit-tested — the RAD model, resolvers, screen queries, control wire-path, and
  the TUI render target are covered, but the in-browser appearance and
  click-through must be eyeballed against a live `--api-server --debug` run.
- The example charts and the namespaced-event/turn-end behaviour exercise live
  LLM backends; their end-to-end behaviour (and the no-hang safety timers) is
  credential-gated and best confirmed against a live provider.
- UI tests run on a separate path: engine/queue/control/HTTP-proof under
  `bb test` (guardrails 1.2.16); the RAD/TUI render code is JVM-only and runs via
  `bb ui-test` (guardrails 1.3.2) in render-target-isolated subprocess JVMs,
  because the TUI/Semantic-UI/headless plugins register the same global
  `render-field`/`render-element` multimethods (last `defmethod` wins).

## [unreleased] — RAD explorer + live single-stepping — 2026-05-30

### Added

- **Statechart-driven RAD explorer (web + TUI).** A shared CLJC RAD model
  (`escapement.ui.model.*`) and reports/screens (`escapement.ui.screens.*`:
  Sessions → Events → Artifacts, plus session detail) over the existing
  read-only Pathom surface. The *same* reports render two ways: in the browser
  with a **Semantic-UI** render adapter (`escapement.ui.rendering.semantic-ui.*`)
  and in a terminal with a **fulcro-tui** target (`escapement.ui.rendering.tui.*`).
- **Instrumented, pausable event queue** (`escapement.engine.instrumented-queue`).
  Relocates the debug pause gate out of the runner loop and into the event
  queue; it engages only when a `:debug-controller` is present (zero overhead
  otherwise). `send!`/`cancel!` stay non-blocking so worker threads never
  deadlock while the draining thread is parked. bb + JVM safe (atoms + promises).
- **Live single-step control API on `--api-server`.** Control mutations
  `escapement.control/{pause,step,continue,arm-pause-on-next-external}` and live
  resolvers `:session/{paused?,step-budget,live-configuration,pending-events}`
  (`escapement.ui.resolvers`, with `::p/mutate pc/mutate` now wired into the
  parser). A shared `escapement.debug.control-handle` atom bridges the running
  engine's live env to the server, filled in `run!`'s `on-env-ready`.
- **The debug controller is now shared with `--api-server`.** A `--debug` run
  (which still forces the TUI on and auto-pauses when `:debug :auto-pause?` is
  true, default) now also exposes its pause/step controller over the api-server,
  so the browser Debugger can Pause/Step/Continue the *same* live run
  concurrently with the TUI keys (`s`/`c`/`p`/`P`). The control plane is present
  only under `--debug`; a plain `--api-server` run stays read-only.
- **Browser Debugger + embedded statechart visualizer**
  (`escapement.ui.screens.{debugger,chart-view}`): Pause/Step/Continue/Arm,
  live configuration + pending-events, and an ELK-rendered chart that highlights
  the live active states as you step.
- **Dual test path.** Engine/queue/control tests run under `bb test`
  (guardrails 1.2.16). The RAD/TUI UI tests load the Fulcro RAD stack and run
  under the JVM via `clojure -M:ui-test` (guardrails 1.3.2); those namespaces are
  excluded from `bb test`. A deterministic `escapement.ui.live-control-http-test`
  proves the full `POST /api` transit → handler → parser → control path under bb.

### Changed

- **The debug pause/step gate moved from the runner loop into the event
  queue.** When `--debug` (or any `:debug-controller`) is active the runner now
  uses `escapement.engine.instrumented-queue`, which applies the pause/step gate
  once per event as it drains — so the TUI keys, the api-server control plane,
  and the runner loop all single-step through one primitive with no
  double-gating. A normal run (no controller) uses the plain in-process queue and
  carries zero stepping overhead.

### Notes

- The browser RAD explorer's visual rendering (Semantic-UI report/form layout,
  the ELK live-chart highlight, Pause/Step/Continue button wiring) is **not**
  unit-tested — the RAD model, resolvers, screen queries, control wire-path, and
  the TUI render target are covered, but the actual in-browser appearance and
  click-through must be eyeballed against a live `--api-server --debug` run.
- The instrumented queue's non-blocking `send!`/`cancel!` while the draining
  thread is parked on the gate is a concurrency property; it is exercised by the
  queue tests but its deadlock-freedom under real concurrent worker load is best
  confirmed live.

## [unreleased] — read-only EQL API + browser inspector — 2026-05-29

### Added

- **`--api-server <port>` on `run`.** Publishes a read-only EQL/Pathom API
  (`escapement.ui.server` + `escapement.ui.resolvers`, over a multi-session
  disk read store rooted at `--work-dir`) for the active session, past
  sessions, transcript paging, artifacts, and the invocation drill-in.
  `POST /api` with a transit+json EQL query. No mutations.
- **Browser inspector SPA (`escapement.ui.client`).** A minimal Fulcro app
  (a `Root` that loads the active session id) whose `:remote` posts transit
  EQL to `/api`, with Fulcro Inspect wired in. Served by the same
  `--api-server` on the same origin. CLJS deps live only in the `:cljs`
  alias — a normal `run` never loads any UI code. Build with `bb build-ui`
  (release) / `bb watch-ui` (dev). See [`docs/web-ui.md`](docs/web-ui.md).
- **Lazy, content-addressed UI delivery.** The ~1.2 MB compiled bundle is
  not committed to git. Jar installs serve it from the classpath; `bbin`
  installs fetch it once from the GitHub release asset
  (`escapement-<version>/main.js`), verify it against the SHA-256 in the
  committed `resources/escapement-ui.edn`, and cache it under
  `~/.cache/escapement/ui/`. Served at `/js/main/<sha>.js` with an immutable
  cache header; `index.html` gets the SHA path injected at serve time, so a
  rebuilt bundle never reads stale from cache.
- **UI build/release tasks.** `bb build-ui` (release-compile + refresh the
  `escapement-ui.edn` manifest), `bb watch-ui` (dev compile + hot reload), and
  `bb release-ui` (verify-not-rebuild: publishes the already-built `main.js` to
  the GitHub release `escapement-<version>` via `gh` — creating the release for
  the pushed tag if needed — only after asserting its SHA-256 matches the
  committed manifest, so the published asset equals what bbin installs verify
  against). Documented in [`docs/web-ui.md`](docs/web-ui.md), including the
  bbin install timeline and stability guarantees.

## [unreleased] — io-refactor-capture-replay — 2026-05-27

First slice of the IO refactor: heavy LLM I/O is no longer the body of the
transcript. Full request/response/tool-result payloads (and a replayable
seed) are externalized to a protocol-backed, navigable artifact store; each
transcript event keeps only an ≤80-char snippet plus an `:io/ref` locator
that round-trips to the on-disk blob. A single-turn replay primitive lets
you re-issue one captured turn with overrides without re-running the chart.

### Added

- **IO protocols (`escapement.protocols`).** Three host-agnostic,
  session-scoped protocols — `TranscriptStore` (append/read ordered events;
  owns `:transcript/seq`), `ArtifactStore` (write/read/list both author
  files and captured-I/O blobs, addressed solely by `path`), and the
  cross-session `SessionIndex` (`list-sessions`). Checkpoints stay on the
  library's `WorkingMemoryStore`, unchanged. A single backend record may
  implement all of them at once.
- **Capture layer (`escapement.capture`).** Externalizes full LLM I/O to an
  `ArtifactStore` and hands back `{:io/ref :io/snippet}` for the transcript
  event. Blobs are EDN (lossless round-trip) at node-relative locators that
  *are* the opaque id:
  `nodes/<node-id>/<visit>/seed.edn`,
  `nodes/<node-id>/<visit>/turns/<n>/request.edn`,
  `…/response.edn`, `…/tool-results/<tool_use_id>.edn`.
  `capture-request!` is first-write-wins (a fallback / `:max_tokens`
  continuation within a turn keeps the base turn request, so replay tunes
  the real prompt). Pure string work + protocol calls only — no filesystem,
  so it runs under bb/CLJ/CLJS.
- **Replay primitive (`escapement.replay/refine-turn`).** Re-issue ONE
  captured turn at `(node-id, visit, turn)` against an injected
  `LLMBackend`, deep-merging `:overrides` (e.g. `{:system "tuned prompt"
  :model "claude-opus-4-7" :temperature 0.2}`) onto the captured request,
  with no statechart engine involved. Returns
  `{:request :response :original-request}` for diffing. The tight
  prompt-tuning inner loop; node-invocation (#2, from `seed.edn`) and
  sub-chart (#3) refine are designed-for but not yet implemented.
- **Storage backends.** `escapement.storage.memory/new-store` — a single
  in-memory store implementing all three IO protocols plus
  `WorkingMemoryStore` (the test stub and a legitimate ephemeral backend;
  assigns `:transcript/seq` and nothing else). `escapement.storage.disk/new-artifact-store`
  — a bb/CLJ `ArtifactStore` bound to one session dir, writing every blob at
  `<session-dir>/<path>` atomically (temp + rename) so the captured-I/O tree
  is literally walkable; `:io/ref` is a relative path with no translation
  table.
- **`:escapement/artifact-store` env key + per-run `:escapement/visit-counts`.**
  `engine.env/new-env` and the `engine.testing` harness now accept an
  `:artifact-store`; the runner builds a `DiskArtifactStore` from
  `:session-dir` and injects it. Absent store ⇒ capture is a no-op (the
  default in tests). `:escapement/visit-counts` is a per-run atom the
  capture layer reads to stamp `:transcript/visit` (the library does not
  track node re-entry).

### Changed

- **Transcript LLM events now reference, not inline, heavy payloads.**
  `:llm/request`, `:llm/response`, and `:llm/tool-result` carry an `:io/ref`
  to the captured blob and an ≤80-char `:io/snippet` for human correlation.
  The former inline preview fields (`:content`, `:user-blocks`,
  `:system-preview`, `:content-preview`, per-block `:text`/`:thinking`) are
  now those same short snippets — not 8192-char truncated full text. The
  full value is available in the referenced blob (and to the live
  conversation buffer, unchanged).

### Removed

- The 8192-byte per-content-block transcript truncation
  (`transcript-block-cap`, `transcript-truncate-marker`,
  `truncate-for-transcript`) in `llm-conversation`. Full content is now
  captured to a blob instead of truncated inline; the inline event carries
  only the ≤80-char snippet.

### Notes

- Capture is a no-op when no `:artifact-store` is wired, so existing
  charts/tests that don't inject one are unaffected.
- Gate 1: the runner's CLI artifact-store wiring (build a `DiskArtifactStore`
  from `:session-dir` and inject it) is config-glue — marked untestable, but
  both operands (the disk store and the env key) are covered.
- All capture writes (request / response / tool-result / seed) are
  best-effort: each is wrapped so a storage/IO hiccup yields an absent
  `:io/ref` rather than aborting a live turn — parity with `transcript!`.

## [unreleased] — flat-authoring-api — 2026-05-26

### Changed

- **BREAKING-CHANGE:** `llm-conversation` / `human-input` / `with-llm-questions`
  now take flat, literal-or-fn keys (`:system` / `:message` / `:max-turns` /
  `:budget-ms` / `:allowed-events` / …). The `:params` and `:params-fn` keys
  are removed. `:message` aliases `:initial-user-message`; `:budget-ms` aliases
  `:max-conversation-duration-ms`; `human-input` `:render` passes through as a
  raw function.

## [unreleased] — n-subagents-dynamic-spawn — 2026-05-22

Adds a runner mode that pumps every statechart session in one env from a
single loop, so a chart can fan out to a runtime-sized fleet of child
sessions via the upstream multiplex invocation processor, collect their
replies, and continue. A chart can now spawn an LLM-chosen number of
child agents without core.async.

### Added

- `runner/run!` `:multi-session? true` option — drains ALL session
  queues in the env per tick and routes each event to the sid named in
  `(:target event)` (falling back to the parent sid). Required whenever a
  chart fans out with the multiplex invocation processor
  (`com.fulcrologic.statecharts.invocation.multiplex`); without it the
  parent only pumps its own sid and child sessions wedge with un-drained
  events.
- `escapement run` honours `^:multi-session?` metadata on the chart var
  and threads it into `runner/run!`. Authors opt in once at the var; no
  new CLI flag.
- `:runner/event-processed` transcript rows now carry `:session-id`
  unconditionally (single- and multi-session runs alike), giving offline
  reducers and a timeline UI a uniform per-session join key; rows also
  gained `:entered`/`:exited` (the state-membership delta for that event).
- `:runner/event-dropped` transcript row — in `:multi-session?` runs, a
  trailing event still queued for a child session that has already reached
  its final state (e.g. a late `:done.invoke.*`) is now dropped and logged
  with `:reason :session-finished` instead of being delivered to a
  torn-down session (which printed a benign but noisy `Statechart not
  found` to stderr). Normal multiplex teardown, not an error.
- Example charts under `escapement.examples`: `n_subagents_demo`
  (deterministic skeleton — workers chosen from data, no LLM) and
  `haiku_tournament_dynamic` (parent LLM decides N poets / M judges at
  runtime, then spawns and judges via multiplex, wired for small local
  models via plain-text I/O — see Changed below).
- Tool-input coercion: when a tool/event input arrives from the LLM with
  a nested collection serialized as a JSON string (common with small
  models, e.g. `{"haikus": "[\"a\",\"b\"]"}`), the runtime now re-parses
  the string before Malli validation. If parsing fails the original
  value is preserved and the same humanized validation error is reported.
- `:llm/response` transcript rows now carry `:elapsed-ms` and
  `:output-tps` (output tokens per second) alongside the existing model
  and context-window fields; the TUI shows them inline on the response
  line (`… 42.5t/s 1200ms`).
- OpenAI-compat backend now categorizes HTTP errors (`:rate-limited`,
  `:overloaded`, `:auth`, `:context-length`, `:invalid-request`,
  `:timeout`, `:transport`) the same way the Anthropic path does, so
  the existing retry/backoff/fallback machinery in
  `llm-conversation/run-turn!` applies uniformly. Honors `Retry-After`
  on 429.
- Docs: `docs/structured-output-from-small-models.md` — when to prefer
  plain-text LLM output over `:allowed-events` with small local models,
  with measurements against llama3.2:3b on ollama.
- Authoring skill: `.claude/skills/writing-escapement-statecharts/` —
  non-obvious chart-authoring gotchas (event naming, conversation
  lifecycle, transition types, SCI-safe wiring).

### Changed

- `deepseek-v4-pro` `:max-output-tokens` clamped to 16384 in the model
  catalog. The provider advertises 1 048 576 but the underlying API
  rejects `max_tokens > 393216`; 16k is well under every observed wire
  cap and sufficient for a single turn.
- `haiku_tournament_dynamic` example rewritten to drive each child LLM
  with `:allowed-events []` and parse plain-text replies, so it runs
  end-to-end against llama3.2:3b on ollama. The default run command in
  its docstring now targets ollama instead of ZAI/GLM-4.6.
- `runner/run!` no longer declares a run `:done` while a delayed `send`
  (e.g. a safety-stop timer) is still queued with a future delivery time.
  When there are no live invocations but the event queue has pending
  events whose delivery time has **not** yet arrived, it sleeps the
  quiescent interval and keeps pumping instead of losing the timer — this
  is planned idle, not a wedge, so the frozen-config counter is not bumped.
- `runner/run!` now fails fast instead of hanging when events are
  deliverable *now* but stranded on sessions the pump is not draining —
  the classic symptom of a multiplex chart run without `:multi-session?`.
  Previously such a run spun forever in the planned-idle branch; it now
  trips `:frozen-config` (bounded by `:max-frozen-cycles`) and the
  `:runner/error` row carries `:pending`, `:deliverable-now`, and a `:hint`
  pointing at the missing `^:multi-session?`. Backed by
  `engine.queue/deliverable-now-count`.

### Fixed

- `n_subagents_demo`'s `agent` var was missing the `^:multi-session?`
  metadata its sibling `haiku_tournament_dynamic` carries, so `escapement
  run` drove it single-session and it wedged (children's `done.invoke.*`
  events stranded; parent never reached `:finished`). The chart passed its
  own test only because that test drives it via the in-memory testing-env
  drain, not the CLI runner. Metadata added; it now completes via
  `escapement run`.

### Notes

- Children are spawned with the upstream `multiplex` invocation element
  (`com.fulcrologic.statecharts.invocation.multiplex`): the parent
  declares a `multiplex` with `mo/count` (runtime N) and `mo/child-params`
  (per-child `:src` chart + `:params`); each child auto-receives an
  identity (`mo/from`/`:idx`), replies to the parent via `mux/reply`, and
  the library's aggregator fires `:done.invoke.<id>` once every child
  reaches a final state. Result accumulation per child is the parent's
  job (an internal transition keyed off the reply event).
- This is real SCXML `<invoke>`-style child sessions, not a bespoke
  primitive; the only escapement-side requirement is `:multi-session?` so
  the one runner loop pumps the parent, the multiplex aggregator, and
  every child session together.
- Bumps `com.fulcrologic/statecharts` `1.4.0-RC15` → `1.4.0-RC16-SNAPSHOT`
  (bb.edn + deps.edn) — the snapshot ships the multiplex/statechart-as-
  invokable processors this feature is built on. Both are now registered
  in every env.

## [unreleased] — feat/turn-primitive-correctness — 2026-05-19

Makes the `:llm-conversation` turn primitive correct and observable
end-to-end: turns now end reliably across model families, built-in file
tools stay inside the session, a wedged run can no longer hang forever,
and six runnable example charts demonstrate the behaviour.

### Added

- `--log-level debug|info|warn|error` CLI flag (case-insensitive). An
  explicit value always wins; with no explicit value, headless
  (`--no-tui`) runs default to `info` so live archiving stays cheap while
  interactive runs keep the library default (`debug`). An unrecognized
  value exits with usage error 2.
- Built-in path-taking tools (`fs_read`, `fs_write`, `fs_edit`,
  `fs_multi_edit`, `fs_glob`, `fs_grep`) now resolve **relative** paths
  against the session work directory instead of the process working
  directory; absolute paths are unchanged. An LLM that writes
  `notes.md` lands inside the session dir.
- Every built-in file tool's `:llm/tool-result` transcript event now
  carries `:resolved-path` — the absolute path the tool actually acted
  on — so transcripts and tests can assert where a tool wrote.
- `runner` `:max-frozen-cycles` option (default 200, ≈10s at the default
  50ms quiescent sleep). If the pump makes no progress for that many
  consecutive quiescent cycles while live invocations remain, it emits
  `:runner/error {:reason :frozen-config}` and exits cleanly instead of
  spinning forever. The counter resets on any progress or when no live
  invocations remain.
- Example charts under `escapement.examples`: `turn-loop` (full
  multi-tool turn driving real `fs_read`/`fs_write`), `steered-convo`
  (between-turn steering via the `:llm.idle` hook), `steer-midturn`
  (mid-turn steering via a region-tool reply, characterizing latency),
  `supervisor` (one parallel chart that monitors, steers once, and
  captures an artifact), `inspectable` (emits the full inspectable event
  spectrum and captures the final answer), and `inspect-showcase`
  (two-phase run producing ≥2 named artifacts with an offline inspection
  recipe).

### Changed

- The turn primitive now ends the turn when a model batches the
  terminating event-tool (`event__done` / `event__tick`) into a
  `:tool_use` response instead of emitting a separate `:end_turn` (the
  glm-class behaviour). Such a turn now fires `:on-end-turn-event`
  (default `:llm.idle`) with the assembled final text and parks the
  worker in `:awaiting-user`, exactly as a real `:end_turn` does —
  guaranteed exactly once per logical turn. Charts that key off
  `:llm.idle` for turn boundaries now work uniformly across model
  families.
- A region-tool reply is now explicitly NOT treated as end-of-turn: it
  is a synchronous request/reply fed back into the same conversation and
  the worker keeps going (previous behaviour, now made correct and
  documented; region/service/repl/scan flows no longer risk parking
  mid-turn).
- `scan.clj` now re-drives the bound conversation after each recorded
  finding (an event-tool turn ends the LLM turn), prompting the model
  for the next finding or the terminating `:scan-complete` so the scan
  loop actually progresses.

### Notes

- The glm batched-event-tool turn-end behaviour and the example charts
  exercise live LLM backends (z.ai / glm-class via `ZAI_API_KEY`, etc.);
  their end-to-end behaviour and steering-latency findings are
  credential-gated and must be eyeballed against a live provider — they
  cannot be asserted in the offline unit suite.
- Repo-hygiene only (no behaviour): `CLAUDE.md` now documents (and inlines
  the structure of) a `workingcontext.md` working-context convention;
  `.gitignore` ignores `workingcontext.md`, `scratch/`, and `.session/`.

---

## [unreleased] — feat/hermetic-hosted-library — 2026-05-19

Makes Escapement embeddable as a hermetic library and replaces the
chart-facing model-policy DSL with an ergonomic `:needs` gate. Additive
over the now-merged backend-resilience work — the CLI path is
byte-for-byte unchanged and every new option preserves prior behavior
when omitted. The one breaking change is the removal of the unreleased
`:model-policy` node key (never shipped in a release): use `:needs`.

### Added

- **`escapement.lib/run` hosted facade.** Embed Escapement in your own
  process without the CLI. A **closed** Malli option schema
  (`escapement.lib/Options`, unknown keys rejected; `validate-options`
  previews errors without running), a generated stable `:run-id`
  (returned and emitted on `:runner/started`), temp-dir defaulting for
  transcript/checkpoint/session, an optional `:session-dir` for artifact
  output (`<session-dir>/artifacts/<name>`, echoed back in the result
  map), an optional `:store` passthrough, and quiet-by-default logging
  (`:quiet?`). The CLI does not use the facade.
- **Hermetic library configuration & credentials.** `escapement.lib/run`
  never reads `.escapement.edn` from disk and never sniffs credential
  env vars. Two schema keys carry everything as explicit data:
  `:credentials` — **required**, an ordered vector of provider
  descriptor maps (`{:provider :anthropic :api-key "…"}`,
  `{:provider :z-ai-plan :subscription true}`, …) from which the backend
  is assembled (an explicit `:backend` remains an escape hatch that wins
  verbatim); and `:config` — optional, the `.escapement.edn`-shaped map
  (`:llm/preferences`, `:llm/ratings`, `:llm/eligibility-strict?`).
  Absent `:config` ⇒ an empty ratings table plus the built-in
  `default-preferences` order, never a disk fallback. Two `run` calls in
  one process with different `:config` ratings resolve eligibility
  independently — there is no process global. The injected
  provider→backend matrix mirrors CLI auto-detection fact-for-fact, so
  the two paths cannot drift.
- **`escapement.lib.event-sink` normalized public events.** A pure
  normalization adapter over `:transcript-tap` exposing a closed, stable
  public Malli event union (`PublicEvent`) with
  `:session-id`/`:run-id`/`:invokeid` correlation; synthesizes the tool
  call/result/validation split and model-fallback events and drops
  internal rows. Entry points `make-adapter` / `feed!` / `normalize` /
  `valid-event?`.
- **`:needs` eligibility-gate `llm-conversation` param.** A **flat**
  `fact → constraint` map (one nesting level) translated at the
  invocation boundary into the canonical
  `escapement.llm.catalog/satisfies-policy?` policy by the new
  `escapement.llm.needs` namespace. A bare value means exact equality,
  `[:>= n]` an inclusive numeric floor, `[:<= n]` an inclusive ceiling —
  only those two comparators (no `:>`/`:<`/`:=`); a malformed entry
  throws an `ex-info` naming the offending key. The gate **filters**, it
  never ranks: all ordering still comes from the sorted
  `:llm/preferences` list (a model rated 7 and one rated 10 are
  interchangeable under `[:>= 6]`).
- **Documented objective fact vocabulary.** `escapement.llm.catalog`
  publishes `eligibility-facts` — the stable, enumerated set of
  objective `:needs`/policy keys (`:vision?`, `:tool-call?`,
  `:reasoning?`, `:context-tokens`, `:max-output-tokens`, `:company`,
  `:family`, `:knowledge`) with one-line meanings. Subjective rating
  keys from `:llm/ratings` mix into the same keyspace and are
  deliberately not enumerated (host-defined, free-form).
- **`:llm/eligibility-strict?` fail-closed option.** When every
  candidate is filtered out the default is still **fail-open** (proceed
  on the unfiltered list; a `:llm/model-policy-empty` transcript event
  records the gap — the CLI bias). Setting
  `:config :llm/eligibility-strict? true` on the lib path makes it
  **fail-closed**: error the node rather than silently run an
  unintended model.
- **`:initial-messages` `llm-conversation` param.** An optional vector
  of pre-built message maps to seed a conversation with (e.g. a
  multi-block first user message carrying an `:image`, or a short prior
  exchange). When non-empty it takes precedence over
  `:initial-user-message` and the worker starts in `:running`.
- **Cooperative runner cancellation.** A new optional `:cancel` runner
  option (atom/`IDeref`, or a delivered promise/future/delay) requests a
  prompt abort at a safe pump-loop boundary (between events, never
  mid-write), emitting `:runner/aborted` `{:reason :cancelled}` and a
  new additive `:status` (`:done` | `:aborted`) on `:runner/done` and
  the summary map. `runner/run!` also gained additive `:store` and
  `:run-id` options. Omitting any of these preserves prior behavior.
- **Runnable embedding example.** `demos/lib/embed_example.clj` (plus
  `demos/lib/README.md`) shows end-to-end use of `escapement.lib/run`
  with explicit `:credentials`/`:config` and the event-sink adapter. A
  hosted-library quickstart was added to `README.md` (the CLI
  quickstart is unchanged) and a **Hosted library** section to
  `Guide.adoc` (option/result schema, public event union, locked design
  decisions, migration notes, known limitations), plus `:needs` and
  cooperative-cancellation coverage in the `:llm-conversation` and
  Runner sections.

### Removed

- The unreleased `:model-policy` `llm-conversation` node key. It only
  ever lived on the now-merged backend-resilience branch and was never
  part of a release, so it is removed outright (no alias, no
  `:llm/model-policy-deprecated` transcript notice) rather than carried
  as deprecated. The ergonomic flat `:needs` gate fully replaces it;
  charts express eligibility solely via `:needs` (the bundled
  `escapement.examples.clj-refactor` already does).

### Changed

- `escapement.llm.catalog/satisfies-policy?` now takes the subjective
  ratings table as an explicit argument (new 3-arity). The catalog no
  longer carries a process-global ratings cache
  (`def`-of-`delay` over `config/load-config`): ratings flow as a plain
  value threaded through the invocation context, resolved once per run
  (from `:config` on the lib path, from disk at startup on the CLI
  path — same seam, different source). `catalog/info` and the objective
  accessors are now opinion-free (ratings are no longer merged into
  `info`). The 2-arity remains as a backward-compatible CLI seam that
  resolves ratings from `.escapement.edn` per call.

### Notes

- The hosted-facade option schema, hermetic credential/config assembly,
  event-sink normalization, `:needs`→policy translation,
  `eligibility-facts`, the `satisfies-policy?` 3-arity, `:initial-messages`
  seeding, and cooperative runner cancellation are all unit-covered
  offline under `bb test` with a mock backend — none require a
  credential.
- This branch adds no new credential-gated surface. The `bb test:e2e`
  live wire suite is unchanged from the merged backend-resilience work;
  a reviewer with real keys may still run it to re-verify the live
  providers.

---

## [unreleased] — feat/lib-compat — 2026-05-19

Resilience + a live end-to-end harness on top of the structured error
categories: conversations now recover from transient backend failures and
output-cap truncation on their own, and a new `bb test:e2e` exercises the
real provider wire.

### Added

- Automatic recovery in `:llm-conversation`, driven by the error
  categories. **Transient failures auto-retry**: a backend throw
  categorized `:rate-limited` / `:overloaded` / `:timeout` / `:transport`
  is retried on the same model with exponential backoff (honoring an
  explicit `:retry-after-ms` from the throwable's ex-data) before any
  model fallback. **Terminal failures fail fast**: `:auth` /
  `:invalid-request` / `:context-length` are never retried, so a bad key
  or oversized prompt cannot burn quota in a loop. Tunable per state via a
  new `:resilience {:max-retries N :backoff-ms MS}` param (defaults
  `{:max-retries 3 :backoff-ms 500}`, on by default; `:max-retries 0`
  disables retry). A `:llm/retry` transcript event is emitted per attempt.
- **Unbounded `:max_tokens` continuation.** A turn the API truncates at the
  output cap (`stop_reason :max_tokens`) is no longer an error — the
  partial assistant content is used as prefill and the turn is continued
  until a genuine terminal stop, then the segments are stitched into one
  coherent Response (text merged across the boundary, usage summed). No
  tool runs and no chart event fires until the message is actually
  complete. There is no continuation limit; the only guard is forward
  progress — a continuation that adds nothing (a stuck model) aborts with
  `:error.llm.unexpected-stop` rather than looping. A `:llm/continuation`
  transcript event is emitted per segment.
- `escapement.llm.providers` — the env→provider→backend matrix
  (`detect-available-credentials`, `build-credential-backend`, the backend
  builders) extracted into a public namespace and now the single source of
  truth shared by the CLI's auto-detection and the e2e suite.
- `bb test:e2e` — a live end-to-end suite (`e2e/escapement/e2e/`) that, for
  every provider credential present in the environment, checks the real
  wire: a basic turn, streaming, vision, `:max_tokens` truncation
  detection, and (credential-independently) the `:transport` / `:timeout`
  / `:auth` error categories, plus catalog freshness. Providers without a
  credential are reported as SKIP, never a failure; secrets are never
  printed. It is NOT run by `bb test`.

### Changed

- A backend error categorized as a transient category now triggers a
  bounded retry **before** surfacing as `:error.llm.<category>`; charts
  that previously saw an immediate `:error.llm.rate-limited` will now see
  it only after retries are exhausted (set `:resilience {:max-retries 0}`
  to restore fail-fast).
- `stop_reason :max_tokens` no longer maps to
  `:error.llm.unexpected-stop`; it is continued transparently. Only a
  no-forward-progress continuation still surfaces
  `:error.llm.unexpected-stop` (now carrying `:detail :no-forward-progress`).

### Notes

- Transient-retry (backoff, `:retry-after-ms` honoring, fail-fast on
  terminal categories, `:max-retries 0` disable) and the unbounded
  `:max_tokens` continuation (segment stitching, usage summing,
  no-forward-progress abort) are unit-covered offline under `bb test`
  with a mock backend — they do not require any credential.
- `bb test:e2e` is the only credential-gated surface here: its live
  per-provider sweep (basic turn, streaming, vision, `:max_tokens`
  truncation detection) runs only for providers whose API key is present
  in the environment (`ANTHROPIC_API_KEY` / `ZAI_API_KEY` /
  `OPENAI_API_KEY` / `OPENROUTER_API_KEY` / `OLLAMA_API_KEY` /
  `OPENCODE_GO_API_KEY`, or a saved Codex OAuth token) and reports
  credential-less providers as SKIP. The credential-independent checks
  (`:transport` / `:timeout` / `:auth` categories, catalog freshness)
  always run. A reviewer with real keys should run `bb test:e2e` to
  verify the live wire; the harness cannot exercise it without secrets.

---

## [unreleased] — feat/lib-compat — 2026-05-18

Builds on the now-merged LLM catalog work: SSE token streaming with a
catalog-driven per-turn output cap, plus image content blocks in the LLM
request protocol.

### Added

- Structured backend error categories in the LLM protocol contract.
  `escapement.llm.protocol` now exports `error-categories`
  (`#{:rate-limited :overloaded :auth :invalid-request :context-length
  :timeout :transport}`), an `llm-error` constructor, and an
  `error-category` accessor (walks the `ex-cause` chain). Backends SHOULD
  throw `(protocol/llm-error category msg ...)`; the `llm-conversation`
  consumer now maps a known category to a finer
  `:error.llm.<category>` chart event (e.g. `:error.llm.rate-limited`) so a
  statechart can branch "rate-limited → wait & resume" vs
  "invalid-request → fail". The `:llm/error` and `:llm/model-down`
  transcript events gained an additive `:category` key. **Back-compat: an
  uncategorized throwable still collapses to exactly `:error.llm.backend`
  with `:reason :backend`, unchanged.** The native Anthropic api backend
  now participates: non-2xx HTTP maps status→category (429 →
  `:rate-limited`, 529/overloaded → `:overloaded`, 401/403 → `:auth`,
  400/422 → `:invalid-request` or `:context-length`, timeouts →
  `:timeout`, else `:transport`) and the SSE `error` event categorizes as
  `:overloaded`/`:transport`, all preserving the legacy message text and
  `:status`/`:body`/`:url` ex-data.
- Token streaming. New optional
  `escapement.llm.protocol/StreamingLLMBackend` (`stream-turn`) plus
  `streaming?` / `send-turn*` capability helpers. The Anthropic api
  backend implements SSE streaming (`"stream": true`), rebuilding a
  byte-identical Response from `content_block_*` events. A new
  `:stream?` `llm-conversation` param opts a state in: incremental output
  is published as `:llm/delta` transcript events
  (`{:type :text-delta|:thinking-delta :text … :model … :invokeid …}`)
  for relay to a UI while the turn is in flight. Chart semantics and the
  final Response are unchanged; no-op on backends without streaming.
- Image (vision) attachments in the LLM request protocol: a new `:image`
  content block (`escapement.llm.types/ImageBlock`) accepted on `:user`
  messages, with `:base64` (inline data + media-type) or `:url` sources.
  The Anthropic backend serializes it to the Messages API
  `image`/`source` wire shape and parses it back symmetrically (survives
  a streamed turn). Enables vision-model steps (e.g. reference-image →
  description pipelines) at the protocol level without invocation-code
  changes.

### Changed

- The per-turn output cap (`max_tokens` on the wire) is now purely
  catalog-driven: it is always the resolved model's
  `catalog/max-output-tokens` (models-api.json `limit.output`), with the
  api backend's wire default (8192) for models the catalog doesn't know.
  To give a state more output room, pick a model with a larger output
  limit rather than tuning a param.

### Removed

- The `:max-tokens` `llm-conversation` param. It is no longer a chart
  concern (see Changed above) and was dropped from all bundled example
  charts; setting it in `params-fn` now has no effect. It remains only on
  the low-level `escapement.llm.types/Request` for backend wire
  translation.

### Notes

- Protocol/translation logic is unit-covered offline: SSE
  reconstruction (`parse-anthropic-sse!`), `send-turn*` capability
  dispatch, image-block round-trip, `effective-max-tokens`, the
  status→category mapping, and the categorized vs uncategorized
  `:error.llm.*` consumer behavior all run green under `bb test`. The
  end-to-end paths that need a live Anthropic-compatible endpoint —
  a real streamed HTTP turn, a real non-2xx status producing a
  categorized throw, and a real vision request — are credential-gated
  (`ANTHROPIC_API_KEY` / `ZAI_API_KEY`) and exercised only by the
  offline simulations above; a reviewer with a key should smoke one
  live streamed + one vision turn.

## [unreleased] — feat/llm-catalog-and-merge-playbook — 2026-05-18

### Added

- Ollama Cloud and OpenCode Go LLM backends. `escapement run --backend ollama`
  and `--backend opencode-go` are now selectable, `OLLAMA_API_KEY` /
  `OPENCODE_GO_API_KEY` are auto-detected for the default multi-backend, and
  both are reported by `escapement info` and listed in the no-credentials
  help text alongside the existing Anthropic/z.ai/OpenAI/OpenRouter options.
- OpenCode Go automatically picks Anthropic-shaped wiring for `minimax-*`
  models and OpenAI-shaped wiring for `glm-*`/`kimi-*`/`mimo-*` models;
  `--api-base-url` is honored as an override.
- Declarative model policy for `llm-conversation` nodes: a chart can express
  `:model-policy {:require … :min … :max …}` over any objective model fact
  (`:vision?`, `:tool-call?`, `:context-tokens`, …) or subjective rating
  (`:intelligence`, plus arbitrary chart-defined opinion keys) to filter the
  auto-fallback model list with no invocation-code change per new key.
- Three-layer LLM catalog (`escapement.llm.catalog`): objective facts load
  from a bundled models.dev dump, a small local fact overlay covers ids the
  dump lacks (e.g. `claude-sonnet-4-7`, the `:openai-codex` subscription
  endpoint), and a config-driven subjective `:llm/ratings` overlay supplies
  `:intelligence` and any other opinion keys. Per-provider pricing
  `(catalog/pricing provider id)` is now available; subscription providers
  (`:z-ai-plan`, `:ollama`, `:openai-codex`) report zero marginal cost.
- User-configurable, priority-ordered model preferences via `:llm/preferences`
  in `.escapement.edn` (ordered `{:provider :model}` pairs, validated against
  the catalog; unreachable entries are dropped; a built-in default order is
  used when unset).
- User-configurable subjective ratings via `:llm/ratings` in `.escapement.edn`.
  There is **no built-in opinion**: the table comes entirely from config, so
  with nothing configured no model carries a rating key and a rating-gated
  policy matches nothing. Dated ids resolve to the family entry via
  longest-prefix.
- `ai/escapement-check.md` — the four-gate pre-merge "Escapement Check"
  playbook is now part of the repo.
- New worked example `escapement.examples.clj-refactor` demonstrating
  declarative model auto-selection gated on per-dimension ratings
  (`:model-policy {:min {:clojure 8 :tool-calling 6}}`).

### Changed

- **Breaking:** demo charts moved from `escapement.charts.*` to
  `escapement.examples.*` (e.g. `escapement run escapement.examples.hello/agent`).
  Any caller using the old `escapement.charts.*` names must update.
- The legacy `:intelligence N` floor on a conversation node still works
  unchanged — it is now folded into the new declarative policy as a
  `:min {:intelligence N}` floor. The transcript event for an
  all-models-excluded fallback was renamed `:llm/intelligence-filter-empty`
  → `:llm/model-policy-empty` and now carries the resolved `:policy` and the
  `:default-models` it rejected (anyone matching on the old event name must
  update; the TUI summary line was updated to match).
- Empty/blank credential env vars are now treated as unset during backend
  auto-detection (previously a blank value could register a dead route).
- More OpenAI-compatible model families (`glm-`, `kimi-`, `deepseek-`,
  `minimax-`, `mimo-`, `gpt-oss`) now correctly use the legacy `max_tokens`
  request key instead of `max_completion_tokens`.

### Removed

- The entire `escapement.llm.models` namespace was deleted (no shim, no
  re-export): its hand-maintained `known-models` fact table (context
  windows, output caps, per-model `:intelligence`/`:provider`) and the
  unused `approaching-limit?` helper are gone. All callers were migrated to
  `escapement.llm.catalog`; those facts now come from the catalog's three
  layers, and pricing is `escapement.llm.catalog/pricing` with an explicit
  provider.

### Notes

- The full suite (including the new `cli_test.clj` provider-wiring tests
  and the new `:model-policy` wiring tests) runs green under `bb test`:
  145 tests, 711 assertions, 0 failures, 0 errors; `bb sanity` passes.
  Ollama / OpenCode-Go route selection and base-url defaults are unit-
  covered offline.
- Backend behavior against the real Ollama Cloud and OpenCode Go endpoints
  is credential-gated (`OLLAMA_API_KEY` / `OPENCODE_GO_API_KEY`) and
  subjective — list-price/quality figures in `:llm/ratings` are opinion,
  not asserted facts.
- `src/escapement/llm/models-api.json` is a large bundled models.dev data
  dump, intentionally checked in as the catalog's objective source.
