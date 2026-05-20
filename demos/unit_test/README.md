# unit-test demo

A port of the [`unit_test` pi extension](file:///Users/tonykay/.pi/agent/extensions/unit-test/)
to an Escapement statechart. Drives an LLM through a multi-phase pipeline that
analyzes a Clojure function, writes (or patches) `fulcro-spec` tests, and seals
them with `:covers` metadata once they pass.

## Pipeline

```
init  →  behaviors  →  abstraction  →  ┬─ write → critique → refine  → finished
                                       └─ gap   → patch    → refine  ↗
```

- The NEW-tests path runs when no test file exists for the target function.
- The EXISTING-tests path runs when one is already present; the LLM is asked
  to fill coverage gaps and fix quality issues rather than rewrite.

Each phase is a single chart state holding one `:llm-conversation`
invocation. The LLM terminates each phase by calling `event__done` (or, for
`refine`, `event__sealed` / `event__give_up`). Phase prompts live under
`prompts/` and are rendered with `escapement.prompts` `{{VAR}}` substitution.

## Run

```bash
ANTHROPIC_API_KEY=sk-... \
  bb -m escapement.cli run unit-test.chart/agent \
    --input demos/unit_test/example-input.edn
```

Required input EDN keys:

| key               | required | description                                        |
|-------------------|----------|----------------------------------------------------|
| `:source-path`    | yes      | Clojure source file containing the target function |
| `:function`       | yes      | Function name (string)                             |
| `:test-file`      | no       | Defaults to `test/<…>_test.clj`                    |
| `:work-dir`       | no       | Defaults to `/tmp/escapement/unit-test/<ns>/<fn>/` |
| `:max-iterations` | no       | Surfaced in the refine prompt; default 10          |
| `:nrepl-port`     | no       | Port hint surfaced in the refine prompt            |

## Artifacts

Under `:work-dir` the chart accumulates:

- `behaviors.md`     — Phase 1 output
- `abstraction.md`   — Phase 2 output
- `gap-analysis.md`  — Phase 3 output (existing-tests path)

The test file itself is written/edited in place at `:test-file`.

The Escapement transcript and per-event checkpoints land under
`.escapement/<session-id>/`.

## Refine-phase prerequisites

The `refine.md` prompt drives the LLM to talk to a *live project nREPL* via the
`clj-nrepl-eval` CLI (the same helper used by Claude Code skills). The model
uses `:shell/run` to invoke `clj-nrepl-eval`.

Before running the demo, make sure:

1. `clj-nrepl-eval` is on `PATH`. Without it the model cannot run tests.
2. Either pass `:nrepl-port <int>` in your input EDN (pointing at an already-
   running test REPL), or leave it absent — the prompt instructs the model to
   discover or start one. If you let the model start its own REPL, your project
   must have a `:test-nrepl` / `:nrepl` alias as described in `refine.md`.
3. The target project has `kaocha` available (preferred); the prompt falls back
   to `clojure.test/run-tests` automatically when it isn't.

## Limitations

- The demo runs one function at a time; the pi extension's per-test-file mutex
  is intentionally omitted.
