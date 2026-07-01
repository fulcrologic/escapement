# Changelog

## [unreleased] — docs/fix-web-ui-jar-delivery-claim — 2026-07-01

Documentation correction: the web-UI compiled bundle (`main.js`) is **not**
embedded in the Clojars jar. `mvn release:perform` builds from a fresh git
checkout of the release tag, where `main.js` is gitignored — so the jar ships
the manifest (`resources/escapement-ui.edn`) but not the bundle.

### Changed

- **CLAUDE.md, README.md, docs/web-ui.md** now state the actual bundle-delivery
  model: **both** jar and `bbin` installs fetch `main.js` from the matching
  GitHub release on first `--api-server` use and verify it against the pinned
  manifest SHA-256 (fails closed on mismatch). Removes the false claim that jar
  installs serve the bundle from the classpath. `docs/web-ui.md` adds a "Why the
  jar doesn't carry the bundle" note and corrects the release-steps section.

### Notes

- Doc-only delta (three files); no source or behavior change. Verified by the
  live release + jar inspection, not by `bb test`.
- Context: this correction accompanied **1.0.0-RC7**, the first proper web-UI
  release — the Clojars jar was deployed and the GitHub release
  `escapement-1.0.0-RC7` published with the SHA-verified `main.js` asset. The
  UI manifest was refreshed from a stale RC2 to RC7 and committed in `58271ce`
  (already on main).
- No Guide.adoc change — the guide delegates bundle-delivery detail to
  `docs/web-ui.md` (line ~3434) and makes no jar-embedding claim.
- Primitive surface untouched — skill/CLAUDE.md statecharts-sync N/A (CLAUDE.md
  is edited, but only its Web-UI delivery paragraph, not the Statecharts caveats
  / Test conventions sections).

## [unreleased] — feat/opentui-markdown-tables — 2026-06-08

Read-only session replay in the OpenTUI sidecar (`escapement open`) plus GFM
markdown-table rendering in the sidecar's markdown renderer.

### Added

- **`escapement open <session-dir> [--timing instant|paced|wallclock]`.** Opens
  a saved session directory read-only in the OpenTUI sidecar — replays the
  recorded `transcript.jsonl` without re-running the agent, no api-server, no
  WebSocket, and no live human-input back channel. The CLI validates the dir,
  transforms its transcript into a temp wire-envelope JSONL, and spawns the
  sidecar in replay mode (artifact / drill-in reads still resolve against the
  original session dir on disk). `--timing` controls replay pacing and defaults
  to `instant` (whole transcript rendered immediately); `paced`/`wallclock`
  re-time the stream. Requires a real TTY + `bun` + a built `tui/opentui/`;
  a non-TTY invocation exits non-zero without spawning. `ask` prompts and the
  pause/step Debugger are inert in replay (the session already happened).
- **`bb tui-debug <session-dir> [--timing …]`** task — convenience wrapper that
  delegates to `escapement open`.
- **GFM markdown tables in the OpenTUI sidecar.** Pipe tables with an alignment
  separator row now render as box-drawn, column-aligned tables with a bold
  header and per-column left/right/center alignment, replacing the previous raw
  pipe text.

### Notes

- Gate 1 flagged that the new markdown-table TypeScript units in
  `tui/opentui/src/domain/markdown.ts` have **no `bun test` coverage yet** — the
  sidecar TS UI is a separate test path (`bb opentui-test`); reviewer must
  eyeball table rendering.
- The `open` sidecar-spawn path needs a real TTY + `bun`, so it is **verified
  manually**, not under `bb test`. The pure disk→wire transform
  (`escapement.ui.replay-source`) is covered by `bb test`.

## [unreleased] — feat/llm-latency-failover — 2026-06-08

Opt-in time-to-first-token (TTFT) latency cap for LLM turns, plus supporting
doc touch-ups.

### Added

- **`:resilience {:latency {:first-token-ms N :fallback [...]}}` TTFT cap.**
  OFF by default. When set per node, a backend that emits no first token within
  `:first-token-ms` is abandoned and the turn fails over to the next candidate
  (or the inline `:fallback` chain). The slow model is NOT marked `:down` —
  slowness is treated as transient, so it stays in rotation for later turns. The
  cap measures time-to-first-token only (model load + queue + prompt eval); once
  the first token arrives the turn rides to completion. With nowhere left to
  switch (sole/last candidate), the turn rides out the slow model — a slow
  answer beats no answer. `:fallback` is an optional ordered vector of
  `{:provider :model …}` targets appended to the resolved candidate list,
  honored only when `:first-token-ms` is set.
- **`:llm/latency-switch` transcript event.** Fired when a TTFT-cap breach
  abandons a model and fails over. `:data` is `{:model :provider
  :first-token-ms :remaining :invokeid :session-id}`.

### Changed

- `params->resilience` defaults now carry `:latency {:first-token-ms nil
  :fallback nil}` (disabled), alongside the existing `:overrun` defaults.
- `haiku_tournament_dynamic.clj` demo rewritten to exercise the
  latency-failover path.
- README built-in tool list corrected to list web fetch/search; the
  design-history pointer now points to `Guide.adoc` + `CHANGELOG.md` instead of
  `plan.md`; added an "OpenTUI sidecar" subsection.

## [unreleased] — feat/llm-credentials-config — 2026-06-07

Escapement can now load LLM provider credentials declaratively from
`.escapement.edn` instead of env vars or shell `source`, with the local config
untracked and a committed example template.

### Added

- **`:llm/credentials` in `.escapement.edn`.** When no `--backend` flag is
  given and credentials are present, the CLI assembles a multi-dispatch LLM
  backend at startup from an ordered vector of provider descriptors — the same
  hermetic injected-credentials path the embeddable lib uses. No env vars and
  no shell `source` required.
- **`:llm/credential-sources` + `:key-from`.** Keys stay out of the config
  file: declare external JSON key stores (e.g. OpenCode's `auth.json`) under
  `:llm/credential-sources`, then pull each credential's key with
  `:key-from [<source> <json-path…>]`. JSON stores are read and cached once per
  run. `:codex` uses the ChatGPT OAuth file and needs no key.
- **`.escapement.edn.example`** — a committed, provider-generic template;
  `cp .escapement.edn.example .escapement.edn` to start.
- **`docs/catalog.md`** — documents the `models-api.json` refresh procedure
  (curl the live models.dev API, minify with `jq -c`, verify with `bb test`).
- Malli `project-schema` now validates `:llm/credential-sources` and
  `:llm/credentials` on config load.

### Changed

- `.escapement.edn` is now gitignored and untracked (it points at a personal
  credential store); the tracked file is the new `.escapement.edn.example`.
- `src/escapement/llm/models-api.json` refreshed from the live models.dev API
  (140 providers) and kept minified.

### Notes

- Env-var auto-detect (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, …) remains the
  fallback when no `:llm/credentials` are configured.
- The end-to-end config-only credential path depends on local external key
  stores; verified live with a config-only run (see Gate 1).

## [unreleased] — feat/live-token-tui — 2026-06-07

A TUI overhaul (live streaming-token panel + themed inspector/transcript,
split into modules), a new opt-in out-of-process OpenTUI (Bun + SolidJS)
sidecar renderer, and an LLM "overrun" resilience primitive that re-runs a
turn truncated at the output-token cap instead of stitching an unbounded
continuation.

### Added

- **Live streaming-token TUI panel.** The default JLine TUI now renders a
  live "mission-control" pane showing each concurrent LLM invocation as it
  streams — per-invocation status, running output token counts, and
  tokens/sec, with concurrent invocations grouped by role and collapsed past
  a cap. Streaming tokens appear in real time rather than only at turn end.
- **Themed inspector / transcript.** The TUI gained a themed inspector
  overlay and transcript pager with drill-in: focus an invocation, open its
  transcript, inspect an individual event's detail, and open captured
  artifacts — all styled through a shared semantic theme.
- **OpenTUI sidecar renderer (`--tui=opentui`).** An opt-in, out-of-process
  Bun + SolidJS terminal UI under `tui/opentui/`. The agent runs headless and
  the sidecar owns the TTY; it renders the same header, live panel,
  transcript, inspector, log pane, and modals. `--debug --tui=opentui` drives
  live pause/step/continue from the sidecar over the back-channel. Requires a
  real TTY, `bun`, and `bun install` in `tui/opentui/`.
- **WebSocket transcript push.** The headless agent streams its transcript
  event stream to the sidecar over a WebSocket on the api-server
  (`escapement.ui.ws_push` + `remote_renderer`); human-input and control flow
  back over the same socket. The api-server port is auto-picked when not given
  explicitly.
- **LLM `:overrun` resilience primitive.** A new `:resilience :overrun` block
  treats a `stop-reason :max_tokens` truncation as a trip wire: rather than
  stitching an unbounded continuation, it re-runs the SAME turn with identical
  context up to `:max-retries` times. `:on-exhausted` selects `:truncate`
  (accept the truncated turn, the default) or `:fail` (surface an `:overrun`
  failure envelope); an optional `:temperature-bump` nudges a deterministic
  model off an identical re-truncation. Off by default.
- **CLI `--flag=value` inline form.** Flags now accept both `--flag value` and
  `--flag=value`.
- **New bb tasks.** `haiku` (dynamic haiku tournament on a fast local ollama
  `gemma3:1b` model with the live-token TUI), `haiku-opentui` (same via the
  OpenTUI sidecar), `opentui-test` (the sidecar's `bun test` unit + snapshot
  suite), and `opentui-build` (sidecar `tsc --noEmit` typecheck).
- **Repo-local `.escapement.edn`.** A committed local-first config pins
  alias resolution so a no-model CLI run in this repo resolves to the local
  `gemma3:1b` first (avoiding the prior slow cloud-alias failover), while
  keeping the cloud aliases available for charts that name them explicitly.
- **Docs.** `docs/opentui-ui.md` (sidecar architecture/run/develop/test),
  `docs/opentui-wire.md` (the JSON wire contract), and
  `docs/opentui-port-analysis.md`. Plus the `run-tui` skill for launching and
  screenshotting the TUI, and a `tui/README.md`.

### Changed

- **`escapement.tui` split into modules.** The monolithic `tui.clj` is now a
  thin facade over focused namespaces under `src/escapement/tui/`
  (`live`, `inspector`, `transcript`, `compositor`, `theme`, `phase`,
  `markdown`, `log`, `util`). Behavior is preserved; the architecture-boundary
  test still permits the TUI only in `cli.clj`.
- **Transcript events now carry `:invokeid` + `:session-id`** on more LLM
  worker/turn/error events, so the live panel and sidecar can attribute
  streaming output to the right concurrent invocation.

### Notes

- **Dead CLI flags (tech debt).** The `bb haiku` / `bb haiku-opentui` tasks
  pass `--max-tokens 2048 --overrun-retries 2 --overrun-temp-bump 0.3`, but
  `cli.clj` does NOT register or thread these flags — they are silently
  discarded. The overrun primitive is therefore exercised in those tasks only
  via the example chart's own hardcoded node-level `:resilience`, not via the
  CLI flags. Wiring `--max-tokens` / `--overrun-*` through `cli.clj` into the
  conversation resilience map is follow-up work.
- **Subjective / eyeball-only.** TUI rendering (live panel, themed inspector),
  the OpenTUI sidecar visuals, and live-LLM streaming behavior are visual/UX
  surfaces verified by snapshot tests and committed reference PNGs under
  `tui/stress/`, not by headless assertions — a reviewer should run
  `bb haiku` / `bb haiku-opentui` to confirm the live feel.

## [unreleased] — feat/multitenant-logging-vthreads — 2026-06-04

Addresses two scaling concerns for multi-tenant hosting (issue #11): a
logging-isolation fix so concurrent hosted runs no longer race on the
process-global logger, and a virtual-thread seam that lifts the platform
thread-per-session ceiling wherever the runtime supports virtual threads.

### Added

- **Virtual threads for engine worker threads (auto-on).** The engine's
  long-lived worker threads (transcript-writer, llm-conversation worker,
  human-input worker) now run on Loom virtual threads by default on any host
  whose runtime supports `Thread/ofVirtual` (Java 21+, including recent
  Babashka/GraalVM builds), removing the platform thread-per-session ceiling
  for high-concurrency hosting. Hosts without virtual-thread support
  transparently fall back to named platform daemon threads — the prior
  behavior — so pre-Java-21 embedders are unaffected and never crash.
  The choice is overridable: `escapement.threads/set-virtual-threads!`
  (programmatic, for library embedders that cannot pass JVM flags) or the
  `escapement.virtual-threads` system property (`"true"`/`"false"`) take
  precedence over auto-detection.

### Changed

- **`escapement.lib/run` quiet-logging is now per-run thread-local.** Quieting
  the statecharts DEBUG/INFO chatter no longer mutates the process-global
  Timbre config; each run scopes its `:warn` min-level to its own thread via a
  `binding`. Concurrent hosted runs no longer serialize on, or race to restore,
  the shared logger. Behavior for a single run is unchanged.

### Notes

- The scaling benefit is exercised by an out-of-tree Docker load harness (see
  `docs/benchmark.md`), not the unit suite. On a 2 CPU / 2 GB container, OS-level
  peak thread count stays flat at ~8 under virtual threads regardless of
  concurrency, versus ~2× the session count on platform threads (e.g. 2,012 at
  C=1000, 4,013 at C=2000). The platform build dies at C=8000 (~16k threads,
  600s timeout) while the virtual build completes with 0 errors. The full
  `bb test` suite passes with virtual threads auto-on under Babashka.

## [unreleased] — feat/cache-conversation-history — 2026-06-02

Extends Anthropic prompt caching from the static system+tools prefix to the
**accumulated conversation transcript**. Long tool loops previously
re-billed the entire growing `messages` array at full input price every
turn; now the bulk of the transcript reads at cache-read rates and only the
newest exchange is re-prefilled.

### Added

- **Transcript caching for `:llm-conversation`.** The growing message
  history now gets a rolling, message-level `cache_control` breakpoint that
  lands on the last *stable* message (the one just before the newest turn)
  and advances every turn — so the cached prefix grows with the conversation
  and the newest turn is never the marker site. On by default whenever
  `:auto-cache?` is true.
- **`:message-cache-control` authoring knob** on the conversation node,
  sibling to `:system-cache-control` / `:tools-cache-control`. Values:
  `nil`/absent (default ON, strategy `:last-stable`, 5-minute TTL); `false`
  (disable only the message breakpoint, leaving system/tools markers);
  a map `{:strategy :last-stable | {:tail N} :ttl :5m | :1h}` for explicit
  control. `:auto-cache? false` still disables every marker, messages
  included.

### Changed

- Anthropic's 4-breakpoint cap is now shared across system, tools, and
  messages, allocated in prompt order **system → tools → messages**:
  messages receive the remaining budget and the total can never exceed 4
  (an over-budget `{:tail N}` is silently capped).

### Notes

- **Anthropic-only.** The breakpoint is placed on the provider-neutral
  request; the Anthropic wire serializes it, every other backend drops it.
  On **OpenAI** (and OpenAI-compatible backends, incl. `openai-codex`) the
  message-level marker is a complete wire no-op — OpenAI caches prefixes
  automatically server-side, so nothing is lost, and `cached_tokens` still
  reads back as `:cache-read-input-tokens`. **Gemini** out-of-band
  `CachedContent` caching is not implemented (future work).
- Verify it from the transcript: `cache-read-input-tokens` in `usage` should
  grow across turns within a state instead of staying flat at the
  system+tools prefix size.

## [unreleased] — feat/llm-facade — 2026-06-02

A public, script-facing LLM API for making LLM calls directly from a chart's
`<script>` expressions (or any core code), backed by the same
model-resolution + failover engine the `:llm-conversation` worker already uses.

### Added

- **`escapement.llm` namespace — one-shot & fan-out LLM calls from a script.**
  - `(llm/ask env {:prompt "…"})` issues ONE LLM turn and returns a result
    envelope whose `:response` (on success) is the extracted assistant text;
    `(llm/ask* env {:prompt "…"})` is identical but returns the full Response
    map instead of just the text. Both accept alias-keyword `:model`/`:models`
    (resolved through `:llm/aliases` + `:llm/preferences` exactly as a
    conversation node does) plus the usual generation knobs (`:system`,
    `:temperature`, `:max-tokens`, `:needs`, `:resilience`, `:tools`, …).
  - `(llm/elect-model env params)` resolves + verifies a working model once
    (issuing a tiny probe turn) and returns a reusable *pinned* ctx; pass it
    back to `ask`/`map-prompt` to skip re-resolution and run that exact model.
  - `(llm/map-prompt env opts ->prompt coll)` fans the same prompt across a
    collection with **bounded concurrency** (`:concurrency`, default 16),
    returning a vector of per-item result envelopes in input order. It elects a
    working model by running item 0, pins it for the rest, re-elects (bounded)
    if that model dies mid-run, and supports `:on-error :collect` (default —
    each failure is its own envelope) or `:abort` (stop dispatching; remaining
    items return `:status :skipped`/`:aborted`). The motivating use case is
    "analyze N documents/files in parallel from one script."
  - Every public call returns a **uniform status envelope** — `:status` is
    `:ok` or a categorized failure (`:exhausted`, `:eligibility-empty`,
    `:unknown-alias`, `:string-model`, `:string-models`, `:interrupted`) — so a
    caller can branch on per-item outcome (e.g. a batch that runs out of credit
    mid-run) instead of catching exceptions; these helpers never throw on a
    backend/categorized failure.
- **Chart env now exposes the LLM backend + resolution inputs.** `new-env`
  surfaces `:escapement/llm-backend` plus the alias/preference/ratings/
  eligibility matrix on the env map, so a chart `<script>` can call the
  env-aware `llm/ask`/`map-prompt` arities and get full alias resolution for
  free — the same matrix the `:llm-conversation` worker resolves against.

### Changed

- **Model resolution + single-turn retry/failover is now shared, single-source.**
  The keyword-alias → ordered-candidate resolution, the `:needs` eligibility
  gate, transient-error retry/backoff, and cross-provider failover all live in
  `escapement.llm` (`resolve-candidates`, `run-turn`). The `:llm-conversation`
  worker's `try-models!` is now a thin hook-driven adapter over
  `escapement.llm/run-turn` (it still owns its transcript events and request
  capture via `:hooks`); conversation behavior is unchanged.

### Notes

- `escapement.llm` is a `.clj` (JVM/bb) namespace, not `.cljc`: it uses
  blocking `p/await!` and `future`-based fan-out, exactly like the sibling
  `:llm-conversation` worker. It pulls only the LLM core + statechart promise —
  no web/Pathom/RAD/TUI code.
- Live behavior against a real provider is credential-gated (needs an API
  key/subscription); the suite exercises resolution, envelope shapes, and
  fan-out wiring with a mock backend.

## [unreleased] — chart-owned-termination + output-handles + invocation-reconstruction — 2026-06-02

Three internal improvements driven by debuggability and correctness.

### Changed

- **Chart-owned termination.** The runner no longer aborts a legitimately
  infinite *event-driven* chart. The old lifetime `:max-iterations` cap counted
  total pump iterations without resetting and so mis-aborted a chart that simply
  loops on events; it is now a deprecated no-op. The only runner backstop is the
  `:max-frozen-cycles` wedge guard, which resets to 0 on any progress (so an
  event chart never trips it) and a chart waiting on its own future-dated timer
  is now unbounded. The genuine *transitionless* (eventless) loop guard lives in
  the statecharts library, unchanged. Region-tool default timeout raised
  30s → 120s for slower models (still per-call overridable).
- **Conversation output is delivered as a handle, not inline.** When an
  `:llm-conversation` turn ends, the `on-end-turn-event` (`:llm.idle`) now
  carries only `:output-ref` (a locator into the `ArtifactStore`) + an ≤80-char
  `:io/snippet`, never the full assistant text — so working memory, checkpoints,
  and the transcript stay small. The full text is captured to
  `nodes/<node-id>/<visit>/turns/<turn>/output.edn`. Chart helpers
  (`capture-llm-output`, `forward-llm-output`) and the new public
  `escapement.chart.helpers/deref-output` dereference the handle on demand;
  with no artifact store the event falls back to inline `:text`.

### Added

- **Invocation reconstruction (EQL).** New `escapement.ui.resolvers`
  resolvers reconstruct one `:llm-conversation` node invocation (entry→exit) as
  an ordered `:invocation/timeline` — turns (request/response/tool-results/output
  refs) interleaved with the statechart events the conversation fired (`events
  sent`, recorded as new stamped `:llm/event-posted` transcript rows). Keyed by
  `[:llm.conversation/invocation-id [sid node-id visit]]`. The output handle is
  exposed as `:llm.conversation/output-ref` (rides the read) + a lazy
  `:llm.conversation/output` resolver that derefs the blob. Plus
  `:session/invocations` to enumerate invocation idents.
- **`escapement.examples.large-files`** — a single-conversation demo that gives
  the LLM shell/glob tools to find the largest files under `$HOME` and writes a
  Markdown report to `artifacts/large-files.md`. Exercises all three features
  end-to-end.

## [unreleased] — llm-aliases-mandatory — 2026-05-27

Makes named `:llm/aliases` the single, mandatory way to define an LLM
target. Every model reference is now a keyword that resolves through
`:llm/aliases` into an ordered, cross-provider failover list; the legacy
bare-string and `{:provider :model}` pair shapes are removed and now raise
loud, categorized errors instead of silently working.

### Changed

- **BREAKING-CHANGE:** `:llm/aliases` is the only target definition. A node
  selects a model by **alias keyword** (`:model :opus`, `:models [:opus
  :glm]`); `:llm/preferences` is now an ordered **vector of alias keywords**;
  `:llm/ratings` is **keyed by alias keyword**. Each alias maps to a
  non-empty, ordered vector of provider-keyed target maps (`{:provider
  :model …}`, with optional per-target `:temperature`/`:top-p`/`:top-k`/
  `:thinking`/`:max-tokens`), tried in author order with failover across
  providers.
- `:needs` eligibility filtering now operates at **target granularity**:
  ineligible targets are dropped, an alias survives if ≥1 target is
  eligible, subjective scores are read from the originating alias's
  `:llm/ratings` entry. Empty result is fail-open by default;
  `:llm/eligibility-strict? true` makes it fail-closed.
- Credential-backend route ordering now derives from the distinct,
  first-seen provider order across the flattened preference targets.
- No-config path now resolves through a built-in default alias set plus a
  default preference vector (still purely via the alias path).
- `:llm/ratings` lookup is exact by alias keyword; the old dated-id →
  family longest-prefix string resolution is gone (the alias already
  collapses per-provider naming divergence).

### Removed

- **BREAKING-CHANGE:** Bare model-id string `:model`/`:models` on a node,
  `{:provider :model}` pairs in `:llm/preferences`, and string-keyed
  `:llm/ratings` are no longer legal. A string `:model` raises
  `:error.llm.invalid-request` (`:detail :string-model`); a string in
  `:models` raises `:detail :string-models`; non-keyword preference or
  rating entries (and any keyword not present in `:llm/aliases`, i.e. a
  dangling reference) are rejected at config load with a message naming the
  offending key. An unknown alias named on a node fails fast with
  `:detail :unknown-alias` (never shipped to a backend as a model string).

### Notes

- Config validation gained referential-integrity checks: `:llm/preferences`
  and `:llm/ratings` keys must each exist in `:llm/aliases`.
- Migration is mechanical — see Guide.adoc `<<llm-aliases-migration>>`:
  wrap old pairs/strings as named aliases and reference them by keyword.
- Examples/demos migrated to the alias model (`demos/lib/embed_example.clj`,
  `examples/clj_refactor.cljc` docstring).

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
