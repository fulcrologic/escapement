# Embedding Escapement as a library

`escapement.lib/run` is the supported entrypoint for driving a chart from
your own bb/JVM process. The README's hosted-library quickstart shows a
*trivial no-LLM* chart so it stays copy-runnable with zero secrets. This
demo is the part it leaves out: **a real LLM chart, embedded the way a host
project actually does it.**

## What [`embed_example.clj`](embed_example.clj) demonstrates

| Concern | How |
|---|---|
| Authoring a real LLM chart | `escapement.chart.helpers` — `h/llm-conversation`, `h/capture-llm-output`, `h/render-template` (don't hand-roll `invoke` elements) |
| Hermetic credentials | `:credentials` is plain injected data; the lib path never reads `.escapement.edn` or sniffs env. The host owns secret resolution. |
| Enabling LLM invocations | `:tool-registry` — **required in practice for any chart with an `:llm-conversation`**. The facade only wires the LLM processor when *both* a backend and a registry are present; omit it and you get `No processor for :llm-conversation`. Use `escapement.tools.protocol/new-registry` (empty) or `escapement.tools.builtin/new-builtin-registry` (built-ins). |
| Per-run config | `:config` carries the `.escapement.edn`-shaped `:llm/preferences` / `:llm/eligibility-strict?` map |
| Seeding the data model | `:initial-data` (used by `params-fn` via the `data` arg) |
| Where artifacts land | `:session-dir` → `<session-dir>/artifacts/<name>` |
| Sharing context between phases | phase 1 `h/capture-llm-output` writes `brief.md`; phase 2 `h/render-template` substitutes `{{brief.md}}` |
| **Live streaming** | attach `escapement.lib.event-sink` to `:transcript-tap` and consume the public **`:text-delta`** event — do *not* match raw `:llm/delta` rows by hand |
| Correlation | `:run-id` stays in the host closure; no host id leaks into payloads |
| Vision input | commented variant: `:needs {:vision? true}` + a base64 image content block |

## Run it

`demos` is on the bb classpath, so from the repo root:

```bash
OPENAI_API_KEY=sk-...    bb -m lib.embed-example
ANTHROPIC_API_KEY=sk-... bb -m lib.embed-example "an idea of your choosing"
ZAI_API_KEY=...          bb -m lib.embed-example     # cheap dev option
OLLAMA_API_KEY=...       bb -m lib.embed-example
```

Phase 2's tokens stream to stdout as they generate. Final artifacts land in
`demos/lib/.session/artifacts/{brief.md,pitch.md}`; the JSONL transcript path
is printed at the end.

## Adapting to your project

1. Hold the `:credentials` vector at app startup (like a DB connection
   pool); pass it on every `run`.
2. Build your chart with the helpers in a namespace your project owns.
3. If you need custom tools, register them into a registry and pass
   `:tool-registry` — see *Tools* / `new-builtin-registry` vs
   `escapement.tools.protocol/new-registry` (empty) in
   [`Guide.adoc`](../../Guide.adoc).
4. The full closed option schema, the public event union, cancellation
   (`:cancel`), and locked design decisions are in the **Hosted library**
   section of [`Guide.adoc`](../../Guide.adoc).
