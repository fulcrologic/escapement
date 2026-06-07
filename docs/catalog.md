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
