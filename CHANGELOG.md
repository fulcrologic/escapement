# Changelog

## [unreleased] — feat/llm-backend-resilience — 2026-05-19

Hardens the LLM backend layer end to end: structured error categories,
SSE token streaming on both backend families, conversations that
self-recover from transient failures and output-cap truncation,
multi-provider catalog-driven dispatch, a normalized hosted-library
event-sink API, and cooperative runner cancellation. Purely additive at
the API level — the CLI path is byte-for-byte unchanged and any omitted
new option preserves prior behavior. The only removal is the obsolete
`:max-tokens` chart param (now catalog-driven).

### Added
- **Structured backend error categories.**
  `escapement.llm.protocol` exports `error-categories`
  (`#{:rate-limited :overloaded :auth :invalid-request :context-length
  :timeout :transport}`), an `llm-error` constructor, and an
  `error-category` accessor (walks the `ex-cause` chain). A backend
  throw with a known category is mapped to a finer
  `:error.llm.<category>` chart event (e.g. `:error.llm.rate-limited`)
  so a statechart can branch "rate-limited → wait & resume" vs
  "invalid-request → fail". `:llm/error` / `:llm/model-down` transcript
  events gained an additive `:category` key. The Anthropic `api`
  backend now categorizes non-2xx HTTP (429→`:rate-limited`,
  529→`:overloaded`, 401/403→`:auth`, 400/422→`:invalid-request` or
  `:context-length`, timeouts→`:timeout`, else `:transport`) and SSE
  `error` events, preserving the legacy message/`:status`/`:body`/`:url`
  ex-data.
- **SSE token streaming on both backend families.** New optional
  `escapement.llm.protocol/StreamingLLMBackend` (`stream-turn`) plus
  `streaming?` / `send-turn*` capability helpers. The Anthropic `api`
  backend streams via the Messages SSE wire; the OpenAI/OpenRouter
  backend streams via Chat Completions (`stream:true` +
  `stream_options.include_usage`). Both accumulate then finalize
  through the same translator as the buffered path, so a streamed turn
  yields a `Response` structurally identical (blocks + usage) to a
  buffered one. The `escapement.llm.multi` dispatcher now also
  implements `StreamingLLMBackend` iff every selectable sub-backend
  streams, delegating via the same routing logic as `send-turn`.
  Charts opt in per state with the `:stream?` `llm-conversation` param;
  incremental output surfaces as `:llm/delta` transcript events
  (`{:type :text-delta|:thinking-delta :text … :model … :invokeid …}`),
  each carrying an optional cumulative `:usage` key for live UX (the
  finalized `Response` usage stays the billing source of truth). Chart
  semantics and the final Response are unchanged; no-op on backends
  without streaming.
- **Image (vision) content blocks.** A new `:image` content block
  (`escapement.llm.types/ImageBlock`) is accepted on `:user` messages
  with `:base64` (inline data + media-type) or `:url` sources. The
  Anthropic backend serializes it to the Messages API `image`/`source`
  wire shape and parses it back symmetrically (survives a streamed
  turn). Enables vision-model steps at the protocol level with no
  invocation-code changes.
- **Self-recovering conversations.** Driven by the error categories:
  *transient* failures (`:rate-limited` / `:overloaded` / `:timeout` /
  `:transport`) auto-retry on the same model with exponential backoff
  (honoring an explicit `:retry-after-ms` from ex-data) before any
  model fallback; *terminal* failures (`:auth` / `:invalid-request` /
  `:context-length`) fail fast so a bad key or oversized prompt cannot
  burn quota in a loop. Tunable per state via a new `:resilience
  {:max-retries N :backoff-ms MS}` param (defaults
  `{:max-retries 3 :backoff-ms 500}`, on by default; `:max-retries 0`
  restores fail-fast). A `:llm/retry` transcript event is emitted per
  attempt.
- **Unbounded `:max_tokens` continuation.** A turn the API truncates at
  the output cap (`stop_reason :max_tokens`) is no longer an error:
  the partial assistant content is used as prefill and the turn is
  continued until a genuine terminal stop, then segments are stitched
  into one coherent Response (text merged across the boundary, usage
  summed). No tool runs and no chart event fires until the message is
  complete. There is no continuation limit; the only guard is forward
  progress — a continuation that adds nothing (a stuck model) aborts
  with `:error.llm.unexpected-stop` (`:detail :no-forward-progress`)
  rather than looping. A `:llm/continuation` transcript event is
  emitted per segment.
- **`escapement.lib/run` hosted facade.** A thin additive delegation
  over the runner for embedding Escapement in your own process: a
  **closed** Malli option schema (`escapement.lib/Options`, unknown
  keys rejected, `validate-options` previews errors without running), a
  generated stable `:run-id` (returned and emitted on
  `:runner/started`), temp-dir defaulting for transcript/checkpoint, an
  optional `:store` passthrough, and quiet-by-default logging
  (`:quiet?`). The CLI does not use the facade and is unchanged.
- **Hermetic library configuration & credentials.** The
  `escapement.lib/run` facade is now fully hermetic: it never reads
  `.escapement.edn` from disk and never sniffs credential env vars. Two
  new schema keys carry everything as explicit data:
  `:credentials` — **required**, an ordered vector of provider
  descriptor maps (`{:provider :anthropic :api-key "…"}`,
  `{:provider :z-ai-plan :subscription true}`, …) from which the
  backend is assembled (an explicit `:backend` remains an escape hatch
  that wins verbatim); and `:config` — optional, the
  `.escapement.edn`-shaped map (`:llm/preferences`, `:llm/ratings`,
  `:llm/eligibility-strict?`). Absent `:config` ⇒ an empty ratings
  table plus the built-in `default-preferences` order, never a disk
  fallback. Two `run` calls in one process with different `:config`
  ratings resolve eligibility independently — there is no process
  global. The CLI path is unchanged (it keeps its own disk/env
  sniffing via `runner/run!`).
- **`:needs` eligibility-gate surface.** A new ergonomic
  `llm-conversation` `params-fn` key, `:needs`: a **flat**
  `fact → constraint` map (one nesting level) translated at the
  invocation boundary into the canonical
  `escapement.llm.catalog/satisfies-policy?` policy by the new
  `escapement.llm.needs` namespace. A bare value means exact equality,
  `[:>= n]` an inclusive numeric floor, `[:<= n]` an inclusive ceiling
  — only those two comparators (no `:>`/`:<`/`:=`); a malformed entry
  throws an `ex-info` naming the offending key. The gate **filters**;
  it never ranks. **All ordering comes from the sorted
  `:llm/preferences` list** — a model rated `7` and one rated `10` are
  interchangeable under `[:>= 6]`; preference order alone decides which
  survivor runs.
- **Documented objective fact vocabulary.** `escapement.llm.catalog`
  now publishes `eligibility-facts` — the stable, enumerated set of
  objective `:needs`/policy keys (`:vision?`, `:tool-call?`,
  `:reasoning?`, `:context-tokens`, `:max-output-tokens`, `:company`,
  `:family`, `:knowledge`) with one-line meanings. Subjective rating
  keys from `:llm/ratings` mix into the same keyspace and are
  deliberately not enumerated (host-defined, free-form).
- **`:llm/eligibility-strict?` fail-closed option.** When every
  candidate is filtered out the default remains **fail-open** (proceed
  on the unfiltered list, a `:llm/model-policy-empty` transcript event
  records the gap — the CLI bias). Setting
  `:config :llm/eligibility-strict? true` makes the lib path
  **fail-closed**: error the node rather than silently run an
  unintended model.
- **`escapement.lib.event-sink` normalized public events.** A pure
  normalization adapter over `:transcript-tap` exposing a closed,
  stable public Malli event union (`PublicEvent`) with
  `:session-id`/`:run-id`/`:invokeid` correlation; synthesizes the tool
  call/result/validation split and model-fallback events; drops
  internal rows. Entry points `make-adapter` / `feed!` / `normalize` /
  `valid-event?`.
- **Cooperative runner cancellation.** A new optional `:cancel` runner
  option (atom/`IDeref`, or a delivered promise/future/delay) requests
  a prompt abort at a safe pump-loop boundary (between events, never
  mid-write), emitting `:runner/aborted` `{:reason :cancelled}` and a
  new additive `:status` (`:done` | `:aborted`) on `:runner/done` and
  the summary map. `runner/run!` also gained additive `:store` and
  `:run-id` options. Omitting any of these preserves prior behavior.
- **`escapement.llm.providers`** — the env→provider→backend matrix
  (`detect-available-credentials`, `build-credential-backend`, the
  backend builders) extracted into a public namespace and now the
  single source of truth shared by the CLI's auto-detection and the
  e2e suite.
- **`bb test:e2e`** — a live end-to-end suite (`e2e/escapement/e2e/`)
  that, for every provider credential present in the environment,
  exercises the real wire: a basic turn, streaming, vision,
  `:max_tokens` truncation detection, and (credential-independently)
  the `:transport` / `:timeout` / `:auth` error categories plus catalog
  freshness. Credential-less providers report SKIP, never a failure;
  secrets are never printed. NOT run by `bb test`.
- Docs: a hosted-library quickstart in `README.md` (the CLI quickstart
  is unchanged) and a **Hosted library** section in `Guide.adoc`
  (option/result schema, public event union, locked design decisions,
  migration notes, known limitations), plus streaming/error-category/
  resilience/cancellation coverage in the LLM-backends, `:llm-conversation`,
  and Runner sections.

### Changed
- A backend error in a transient category now triggers a bounded retry
  **before** surfacing as `:error.llm.<category>`; charts that
  previously saw an immediate `:error.llm.rate-limited` now see it only
  after retries are exhausted (set `:resilience {:max-retries 0}` to
  restore fail-fast).
- `stop_reason :max_tokens` no longer maps to
  `:error.llm.unexpected-stop`; it is continued transparently. Only a
  no-forward-progress continuation still surfaces
  `:error.llm.unexpected-stop` (now carrying
  `:detail :no-forward-progress`).
- The per-turn output cap (`max_tokens` on the wire) is now purely
  catalog-driven: it is always the resolved model's
  `catalog/max-output-tokens` (`models-api.json` `limit.output`), with
  the api backend's wire default (8192) for models the catalog doesn't
  know. To give a state more output room, pick a model with a larger
  output limit rather than tuning a param.
- An uncategorized backend throwable still collapses to exactly
  `:error.llm.backend` with `:reason :backend`, unchanged (back-compat
  for existing `:error.llm.backend` consumers).
- The `:model-policy` `llm-conversation` node key is **deprecated in
  favor of `:needs`**. It still works as an alias for one cycle: the
  canonical nested `{:require {…} :min {…} :max {…}}` shape is still
  accepted so existing charts (including the bundled
  `escapement.examples.clj-refactor`, now ported to `:needs`) do not
  break. Prefer `:needs` for new charts.
- `escapement.llm.catalog/satisfies-policy?` now takes the subjective
  ratings table as an explicit argument (3-arity). The catalog no
  longer carries a process-global ratings cache
  (`def`-of-`delay` over `config/load-config`): ratings flow as a
  plain value threaded through the invocation context, resolved once
  per run (from `:config` on the lib path, from disk at startup on the
  CLI path — same seam, different source). `catalog/info` and the
  objective accessors are opinion-free (ratings are no longer merged
  into `info`). The 2-arity remains as a backward-compatible CLI seam
  that resolves ratings from `.escapement.edn` per call.

### Removed
- The `:max-tokens` `llm-conversation` param. It is no longer a chart
  concern (see Changed above) and was dropped from all bundled example
  charts; setting it in `params-fn` now has no effect. It remains only
  on the low-level `escapement.llm.types/Request` for backend wire
  translation.

### Notes
- Resilience (backoff, `:retry-after-ms` honoring, fail-fast on
  terminal categories, `:max-retries 0` disable), the unbounded
  `:max_tokens` continuation (segment stitching, usage summing,
  no-forward-progress abort), SSE reconstruction for both backends,
  `send-turn*` capability dispatch, image-block round-trip,
  `effective-max-tokens`, the status→category mapping, the hosted
  facade option schema, the event-sink normalization, and runner
  cancellation are all unit-covered offline under `bb test` with a mock
  backend — none require a credential.
- `bb test:e2e` is the only credential-gated surface: its live
  per-provider sweep (basic turn, streaming, vision, `:max_tokens`
  truncation detection) runs only for providers whose API key is
  present (`ANTHROPIC_API_KEY` / `ZAI_API_KEY` / `OPENAI_API_KEY` /
  `OPENROUTER_API_KEY` / `OLLAMA_API_KEY` / `OPENCODE_GO_API_KEY`, or a
  saved Codex OAuth token) and reports credential-less providers as
  SKIP. The credential-independent checks (`:transport` / `:timeout` /
  `:auth` categories, catalog freshness) always run. A reviewer with
  real keys should run `bb test:e2e` to verify the live wire; the
  harness cannot exercise it without secrets.
- Known limitation: billing usage is captured on successful turns only
  (see the Hosted library "known limitations" in `Guide.adoc`).
- Accepted debt (Gate 3, cosmetic, non-blocking): the
  `:llm/model-policy-deprecated` transcript warning is emitted from the
  per-turn conversation path, so a node that takes multiple turns or
  triggers `:max_tokens` continuation segments repeats the same
  deprecation notice per segment rather than once per node. A docstring
  describes it as a per-node notice (an overclaim). Behavior is
  unaffected — `:model-policy` still works as the documented one-cycle
  `:needs` alias; only the warning's emission frequency is noisier than
  the docstring implies. Slated for de-duplication when `:model-policy`
  is removed.

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
