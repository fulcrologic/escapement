# Changelog

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
