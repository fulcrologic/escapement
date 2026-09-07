# Model catalog (`models-api.json`)

`src/escapement/llm/models-api.json` is a minified [models.dev](https://models.dev)
dump, keyed by provider. Loaded at runtime by `escapement.llm.catalog-source`
(CLJ/bb) and baked in at CLJS compile time by `escapement.llm.catalog-macros`.

## Update

Refresh from the **live** models.dev API (fresher than opencode's vendored
`packages/opencode/test/tool/fixtures/models-api.json`, which is a stale pin):

```bash
curl -fsSL https://models.dev/api.json | jq -c '.' > src/escapement/llm/models-api.json
bb test   # catalog_test asserts exact values (e.g. gpt-5 ctx/pricing) — confirms the schema still parses
```

Keep it minified (`jq -c`). Query with `jq`, never read the whole file.

## Provider coverage

The dump is only half the story: `catalog-source`'s **allowlist** decides which
providers are surfaced, so refreshing the JSON does not by itself make a new
provider visible. The other half is the hand-curated `local-providers` overlay
in `escapement.llm.catalog`, which is the correct source for providers
models.dev does not carry — or carries in a way that would mislead.

Every provider in `providers/provider-templates` is covered by one of the two,
and `catalog_source_test` asserts that no template is invisible to the catalog.

Two mappings are deliberate and should not be "fixed" into the allowlist:

* **`:codex` / `:openai-codex`** stay on the overlay. The ChatGPT-account
  endpoint serves OpenAI models, so mapping it to the `openai` dump entry looks
  right and is not: the dump lists 48 models and that path accepts **six**.
  Advertising the dump's list would let a `:needs` gate pick a model guaranteed
  to fail with *"The '<id>' model is not supported when using Codex with a
  ChatGPT account"*.
* **`:claude-cli`** has no models.dev provider at all, and its `--model` takes
  the CLI's own aliases (`sonnet`), not Anthropic ids — so borrowing
  Anthropic's table would attach real prices and context windows to
  identifiers that provider does not accept. The overlay carries the aliases.
