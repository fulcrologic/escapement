# Changelog

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
