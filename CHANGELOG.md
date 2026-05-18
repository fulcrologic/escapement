# Changelog

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
- User-configurable subjective ratings via `:llm/ratings` in `.escapement.edn`,
  deep-merged over the built-in defaults (override one model's opinion without
  restating the rest; dated ids resolve to the family entry).
- `ai/escapement-check.md` — the four-gate pre-merge "Escapement Check"
  playbook is now part of the repo.

### Changed
- The legacy `:intelligence N` floor on a conversation node still works
  unchanged — it is now folded into the new declarative policy as a
  `:min {:intelligence N}` floor; the `:llm/intelligence-filter-empty`
  transcript event now also carries the resolved `:policy`.
- `escapement.llm.models` is now a backward-compatible shim re-exporting the
  catalog accessors; existing callers keep working with no change. Its
  `pricing` is the "cheapest metered list price" answer; new code should call
  `escapement.llm.catalog/pricing` with an explicit provider.
- Empty/blank credential env vars are now treated as unset during backend
  auto-detection (previously a blank value could register a dead route).
- More OpenAI-compatible model families (`glm-`, `kimi-`, `deepseek-`,
  `minimax-`, `mimo-`, `gpt-oss`) now correctly use the legacy `max_tokens`
  request key instead of `max_completion_tokens`.

### Removed
- The hand-maintained `known-models` fact table in `escapement.llm.models`
  (context windows, output caps, per-model `:intelligence`/`:provider`) is
  gone; those facts now come from the catalog's three layers.

### Notes
- The new `cli_test.clj` provider-wiring tests exercise `escapement.cli`,
  which transitively requires `escapement.tui`'s JVM-only `org.jline.*`
  `:import` that Babashka's SCI cannot resolve. This is a pre-existing
  condition on `main` (a clean baseline run fails identically), not a
  branch regression, but it means the new CLI provider-routing tests must
  be eyeballed or run in a JVM/jline environment — verify Ollama /
  OpenCode-Go route selection and base-url defaults there. (Authoritative
  test verdict is Gate 1's.)
- Backend behavior against the real Ollama Cloud and OpenCode Go endpoints
  is credential-gated (`OLLAMA_API_KEY` / `OPENCODE_GO_API_KEY`) and
  subjective — list-price/quality figures in `:llm/ratings` are opinion,
  not asserted facts.
- `src/escapement/llm/models-api.json` is a large bundled models.dev data
  dump, intentionally checked in as the catalog's objective source.
