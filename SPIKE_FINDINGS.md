# Milestone 0 Spike — Findings

Date: 2026-05-13
Environment: macOS 25.4.0 (darwin/arm64), Babashka **v1.12.218**, Clojure 1.12.0, `com.fulcrologic/statecharts` **1.4.0-RC13** (latest on Clojars).

## Summary table

| # | Item | Result |
|---|------|--------|
| 1 | `statecharts.chart` loads under bb | PASS |
| 2 | Trivial chart runs (manual env) | PASS |
| 3 | Custom `InvocationProcessor` works under bb | PASS |
| 4 | `invocation.future` works under bb | PASS |
| 5 | `claude -p --output-format json` shell-out via `babashka.process` | PASS |
| 6 | `com.fulcrologic.statecharts.testing/new-testing-env` under bb | **FAIL** (promesa/SCI incompatibility) |

---

## 1. Library loads — PASS

```
$ bb -e "(require '[com.fulcrologic.statecharts.chart :as c]) :ok"
:ok
```

`chart`, `elements`, `algorithms.v20150901`, `data-model.working-memory-data-model`,
`event-queue.manually-polled-queue`, `execution-model.lambda`,
`registry.local-memory-registry`, `working-memory-store.local-memory-store`,
`environment`, `events`, `protocols` — all load cleanly.

The `simple` namespace does NOT load under bb. It transitively requires
`com.fulcrologic.statecharts.invocation.statechart`, which requires
`promesa.core`, which fails in SCI with:

```
Protocol not found: clojure.core/Inst
  promesa/util.cljc:26 — (extend-protocol clojure.core/Inst ...)
```

SCI does not expose `clojure.core/Inst` as an extendable protocol the way real
Clojure does. This blocks `simple` and `invocation.statechart` and `testing`
(see item 6).

## 2. Trivial chart — PASS

See `spike/trivial_chart.clj`. Manual env assembly (skipping `simple` and
`invocation.statechart`). Idle → `:go` → final `:done` inside a parent
compound state.

```
after start, configuration = #{:idle :work}
after :go, configuration = #{:done :work}
PASS: reached :done
```

Gotcha: a **top-level** `final` state empties the configuration set after
entry (chart terminates). Wrap the final in a compound parent state to
observe it.

## 3. Custom `InvocationProcessor` — PASS

See `spike/custom_invocation.clj`. Defined `EchoProcessor` via `defrecord`
implementing `sp/InvocationProcessor`. Registered via
`::sc/invocation-processors` in the env map. `(invoke {:type :echo :id "echo-1" ...})`
correctly routes to `start-invocation!`.

```
[echo] supports?  :echo
[echo] start-invocation! data= {:invokeid "echo-1", :src nil, :type :echo, :params {:hello "world"}}
[echo] sending event :done.invoke.echo-1 to sid :echo-sess
final config = #{:got-it}
PASS: custom InvocationProcessor fired and event was received
```

Gotchas:
- The processor receives `:invokeid` (not `:id`).
- `forward-event!` is **3-arity** `[this env event-data]` (the clj-only skill
  doc shows 4 — the doc is wrong).
- Use `(env-ns/session-id env)` to address the parent session in the `send!`
  call. Set `:source-session-id` and `:sendid` on the send map (the future
  processor source does this; omitting them appears to still work but match
  the convention).
- This is **the linchpin of the project**, and it works cleanly under bb.

## 4. `invocation.future` — PASS

`com.fulcrologic.statecharts.invocation.future` is in a `.clj` file (not
`.cljc`) and loads fine under bb. The future runs on a real JVM thread (bb's
GraalVM substrate threads), `Thread/sleep` works, the `done.invoke.<id>` event
is delivered back to the parent session.

```
future-chart final config = #{:ok}
PASS: future-based invocation completed
```

Gotcha: `:src` is called as `(src params)` — **one** arg, not `[env params]`.
The `clj-only.md` doc example shows two args; that example is wrong.

## 5. `claude -p` shell-out — PASS

See `spike/claude_p_probe.clj`. Output saved to `spike/claude-p-sample.json`.

### Actual JSON shape (top-level keys, sorted)

```
:api_error_status   nil
:duration_api_ms    5745
:duration_ms        4309
:fast_mode_state    "off"
:is_error           false
:modelUsage         {<model-id> {:inputTokens ... :cacheReadInputTokens ...}}
:num_turns          1
:permission_denials []
:result             "Hi there friend"            ;; <-- ASSISTANT TEXT HERE
:session_id         "3a183b1c-cc53-4e6e-8797-d6a6bdef05f6"  ;; <-- USE WITH --resume
:stop_reason        "end_turn"                   ;; <-- matches Anthropic API
:subtype            "success"
:terminal_reason    "completed"
:total_cost_usd     0.1903975
:type               "result"
:usage              {:input_tokens ... :output_tokens ...
                     :cache_creation_input_tokens ...
                     :cache_read_input_tokens ...
                     :cache_creation {:ephemeral_5m_input_tokens ...
                                      :ephemeral_1h_input_tokens ...}
                     :iterations [{...per-message...}] ...}
:uuid               "6098e0e5-..."
```

### Implications for the design

- `result` is a **flat assistant-text string**, NOT an Anthropic-style
  `content` blocks array. The `claude-p` adapter must synthesize a single
  `{:type "text" :text result}` block. If `tool_use` is involved, `-p` mode
  will not surface those blocks at the top level — investigate
  `--output-format stream-json` later for tool-use support.
- `stop_reason` matches Anthropic API verbatim — good.
- `session_id` is present and intended for `--resume` — confirms our
  prefix-continuation caching strategy is viable.
- `usage` includes `cache_creation_input_tokens` / `cache_read_input_tokens`
  — the CLI itself caches the system prompt aggressively (30k of cache_creation
  on a 3-word prompt, see `modelUsage`). We do not need to send
  `cache_control` markers; `--resume` is the lever.
- `is_error` and `api_error_status` give us a clean error channel.
- The `modelUsage` map confirms the CLI may use multiple models in one
  request (haiku + opus here — haiku for routing, opus for the answer).
  The protocol layer should expose this for observability.

## 6. `testing/new-testing-env` — FAIL under bb

```
$ bb -e "(require '[com.fulcrologic.statecharts.testing :as t]) :ok"
Protocol not found: clojure.core/Inst
... testing -> simple -> invocation.statechart -> promesa.core -> promesa.util
```

Same promesa/SCI block as item 1. Verified the same require **works** on JVM
(`clojure -e ...` succeeded after dep download).

**Workarounds:**
- (a) Author and run chart unit tests on the JVM (`clj -M:test` with kaocha).
  Chart code is plain Clojure; no bb-only deps in `src/`. This is the
  pragmatic path.
- (b) Roll a tiny home-grown `new-testing-env` shim that wires up the manual
  env (data-model + queue + lambda + processor) plus a `goto-configuration!`
  helper. ~50 lines. Worth doing if/when we have many chart tests.

---

## Bottom-line recommendation: **HYBRID — bb runtime, JVM for tests**

- **Runtime / CLI / smoke runs**: stay on **Babashka**. Items 1–5 all pass.
  The linchpin (custom `InvocationProcessor` + `:future` invocations + shelling
  to `claude -p`) works end-to-end. Fast startup is genuinely useful for a CLI
  agent.
- **Tests**: use **JVM** via `clj -M:test` (the same `deps.edn` already lists
  everything needed). The blocker is one transitive dep (`promesa`) used in
  one ns (`invocation.statechart`) used by `simple` and `testing`. None of
  those are needed at runtime if we assemble the env manually.
- **Caveat**: if we later want nested child-chart invocations (`:type :statechart`),
  that processor also pulls promesa. We can either (i) write our own
  nested-chart processor, or (ii) run that part on JVM. Defer until needed.

### What the next implementer needs to know

1. **Do not require `com.fulcrologic.statecharts.simple` from bb code.** Use
   the manual env assembly shown in `spike/trivial_chart.clj`. The clj-only
   skill doc's "Full Manual Setup" works; the "Simple Environment" section
   does not under bb.
2. **Do not require `invocation.statechart` from bb code** (same reason).
3. The `:src` fn in a `(invoke {:type :future})` takes **one** arg (`params`),
   not two. The clj-only doc is wrong on this.
4. `InvocationProcessor/forward-event!` is `[this env event-data]` (3 args).
5. Use `com.fulcrologic.statecharts.environment/session-id` to address sends
   to the current session from inside `start-invocation!` — don't grab the
   key from the env map by hand.
6. A top-level `final` state empties the configuration set. Wrap finals in a
   compound parent state if you need to observe their entry from outside.
7. Versions used: bb 1.12.218, statecharts 1.4.0-RC13, malli 0.16.4,
   cheshire 5.13.0, babashka.process 0.5.22.
8. `bb.edn` and `deps.edn` are intentionally kept in sync. `bb.edn` omits
   `babashka.process` (bundled with bb).
9. `claude -p --output-format json` returns a flat `:result` string, not
   Anthropic-style content blocks. The adapter must synthesize blocks. For
   tool-use support, investigate `--output-format stream-json`.
