# Changelog

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
