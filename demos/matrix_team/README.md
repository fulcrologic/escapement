# matrix-team — two LLM agents collaborating via service-region REPLs

A demo of the **chart-region-as-tool** pattern (`escapement.chart.service`)
playing out as a tiny team: an EXPERIMENTER agent races for the fastest
3x3 matrix multiplier while a TESTER agent independently verifies
correctness, with each agent holding its **own private JVM nREPL** against
the `demos/tools` subproject.

## Topology

```
parallel :work
  ├── :exp-repl    — service region, owns :exp/eval -> experimenter's nREPL
  ├── :test-repl   — service region, owns :test/eval -> tester's nREPL
  ├── :experimenter — LLM, pulls :chart-tools from :exp-repl
  └── :tester       — LLM, pulls :chart-tools from :test-repl
```

The experimenter announces new versions via `event__new_version`; the chart
routes that to the tester as a user message (`tell-other-llm`). The tester
reports back via `event__tester_passed` / `event__tester_failed`; the chart
routes those to the experimenter. No shared variables, no busy flags — just
two LLMs trading user messages, each working against their own REPL.

## Bootstrap

There is no manual bootstrap. The chart **owns the JVM lifecycle**:

* Each service region (`:exp-repl`, `:test-repl`) on entry shells
  `clojure -M:nrepl` in `:project-dir` via `babashka.process`, watches the
  merged stdout for `nREPL server started on port NNN`, and fires a
  per-region ready event carrying the port.
* The tool (`:exp/eval` / `:test/eval`) is registered immediately on
  entry to the compound region, so the LLM regions also start
  immediately — they do not gate on a "REPL ready" signal. Any eval
  call the LLM makes while the JVM is still booting is **queued** in a
  region-local atom by the substate's handler (which returns nil =
  deferred reply). On transition into `:*-ready`, an async drain plays
  the queued calls against the now-live nREPL and posts each reply.
  From the LLM's point of view the only effect is that its first eval
  takes a few seconds longer.
* The compound service region's on-exit script destroys the JVM (via
  `babashka.process/destroy-tree`), so chart termination cleans up both
  processes — including the case where the chart aborts because a JVM
  failed to start at all. The abort path drains the queue with error
  replies so no LLM worker hangs waiting for a reply that will never come.

You only need `clojure` on `PATH` and the absolute project-dir.

## Run

Edit `demos/matrix_team/example-input.edn` so `:project-dir` is the
absolute path to `demos/tools` on your machine, then from the escapement
project root:

```bash
$ bb -m escapement.cli run matrix-team.chart/agent \
     --input demos/matrix_team/example-input.edn
```

Watch the transcript: you'll see the two REPL regions emit
`:exp-repl/ready` / `:test-repl/ready` after JVM warmup (cold start is
a few seconds), then the experimenter's first turn write to
`demos/tools/src/com/example/matrix.clj`, evaluate setup in its REPL via
`region__exp_eval`, then call `event__new_version`. That triggers a
`tell-other-llm` to the tester, which sets up its own REPL, expands the
tests, runs them, and reports back. The two agents alternate until the
experimenter calls `event__experiment_done`.

## What to watch for

- **Are the LLMs talking past each other?** Look for cases where the tester
  reports a failure but the experimenter starts a new perf experiment
  anyway. The prompt forbids this — but if it happens we want to know.
- **REPL state confusion.** With persistent nREPL sessions, stale vars or
  unreloaded namespaces can mask bugs. `clj-reload.core/reload` is what
  the prompts tell both agents to use; observe whether they actually do.
- **Latency.** Each `region__*_eval` call shells out to `clj-nrepl-eval`.
  How does the round-trip time feel compared to fs edits?

## Notes on primitives this experiment surfaces

This demo was built specifically to find candidate library primitives.
Things that came up during construction:

1. **stdin on `:shell/run`.** The builtin doesn't accept stdin, so the
   chart-local eval handler writes code to a tmp file and uses bash
   redirection inside the command string. A primitive `:stdin`/`:input`
   parameter on `:shell/run` would let the handler stay one-liner-clean.

2. **A real `:nrepl/eval` builtin.** Right now we shell to
   `clj-nrepl-eval` per call (JVM startup is not paid because the binary
   reuses a persistent session — but it's still a process spawn per call).
   A native nREPL client tool that takes `{:port :code}` and pools
   connections per-port would sharpen this.

3. **JVM/process-lifecycle region with request-queueing.** The
   matrix-team chart spawns + tears down two `clojure -M:nrepl` processes
   inline. The full shape — compound state with `:starting` / `:ready` /
   `:aborted` substates, a stdout reader future that fires `:*/ready`
   with a parsed value, tool registered at the compound so palettes
   capture it immediately, deferring handler in `:starting` that queues
   eval requests, async drain on entry to `:ready`, error-drain on entry
   to `:aborted`, on-exit destroy-tree — is generic. It works for any
   "command produces a port/url/pid on stdout, then runs forever, and
   we want callers to be able to invoke before it's up" tool. This wants
   to be `escapement.chart.process-region` alongside the existing
   `repl-service` helper, with `:line-regex`, ready/failed event
   keywords, and an eval-fn as inputs.

4. **A "wait until peer says X" helper.** Today we rely on each agent's
   prompt and `event__*_*` event-tools to gate behavior. A first-class
   `wait-for-message` helper that an agent could call from a tool —
   blocking the current turn until a routed `tell-llm` matching a
   predicate arrives — would let prompts be shorter and policy more
   robust.

5. **Symmetric peer-message tool.** The current pattern is "fire an event,
   the chart routes via tell-other-llm". For peer chat that's overhead.
   A `peer/message` tool that takes `:to` and `:text` and is routed
   directly by an `:llm-conversation` invocation would be a closer fit
   to the team-mate model.
