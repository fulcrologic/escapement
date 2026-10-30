# §runtime/embedding — Embedding Escapement in a host

`escapement.lib/run` is the public, stable way to run a chart inside another program, and
`escapement.runner/run!` is the lower-level entry the CLI uses. Both keep their closed option
contract: an unknown option key is still rejected by `lib/run`. Everything below is added to that
contract, is optional, and changes nothing for a caller that does not pass it.

## §runtime.embedding/human-renderer — Human input through the host's own renderer

`lib/run` accepts `:human-renderer`, a `HumanRenderer`, so a chart's `:human-input` steps are
shown and answered by the host (a web page, a chat card, a test script) rather than only by the
CLI's terminal. Each prompt the renderer receives says which session (`:session-id`) and which
invocation (`:invokeid`) it belongs to, so a host running many sessions can route the answer back
to the right one. Without the option, `lib/run` behaves as before.

## §runtime.embedding/debug-controller — Pause and single-step from the host

`lib/run` accepts `:debug-controller`, the same pause/step controller the CLI's `--debug` uses,
so a host can pause a run, process one event at a time and continue. While a human-input prompt is
open the gate does not hold the chart back from answering it: the host may say how to tell with
`:human-input-active?`, and otherwise the run asks its own human-input processor whether a prompt
is open. Without the option no event is gated, as before. The gate does not apply to a session
driven event by event (§runtime.stepping/one-event), where the host is already the stepper.

## §runtime.embedding/invocation-processors — Extra invocation processors

`lib/run` and `runner/run!` both accept `:invocation-processors`, a vector of extra statecharts
invocation processors (for example the library's `:future` processor, or a host's own "outside
call" type). They are installed alongside Escapement's built-in LLM, human-input and child-chart
processors, so a chart can `invoke` those types without the host building the environment itself.
The built-in processors are always present and keep handling their own types.

## §runtime.embedding/tool-call — Calling a tool as an invocation

A chart can `invoke` `:type :tool-call` with params `{:tool, :input, :timeout-ms}` (`:timeout-ms`
optional). The named tool, taken from the run's tool registry, runs off the chart's thread, so the
chart keeps processing other events meanwhile; its input is first checked against the tool's input
schema. The call counts as in-flight work (§runtime.restarts/live-count) until its result event is
queued, so the run does not end while it runs, and it is cancelled if its state is left, after
which nothing is reported for it. On success the chart gets `done.invoke.<id>` carrying the tool's
whole result. On failure it gets exactly one of:

- `error.call.tool` — the tool reported an error, or the input did not fit its schema, or the
  registry has no such tool;
- `error.call.unknown` — the tool reported it can't tell whether its effect happened
  (`:outcome :unknown`; this wins over a reported error);
- `error.call.exception` — the tool threw;
- `error.call.timeout` — the call ran longer than `:timeout-ms` (its effect may still have
  happened).

Every error event's data names the invocation and the tool. The `:tool-call` type is installed
automatically when the run has a tool registry; a run without one is unchanged.

## §runtime.embedding/compatibility — Existing callers keep working

Every new option in the `§runtime` area is opt-in. A caller, example chart or CLI invocation that
uses none of them keeps the same options and CLI flags, the same `lib/run` and `runner/run!` result
statuses (`:done`, `:aborted`, `:frozen-config`; `:waiting` and `:sleeping` appear only when asked
for), the same transcript rows and fields (new rows and fields may appear, and the run result gains
keys such as `:data` and `:metrics`; nothing an existing reader relies on is removed or renamed),
and checkpoints written by an earlier version still resume.

A few changes apply to every run, because the old behaviour was a defect:

- each event is acknowledged in the checkpoint written after it, and an event whose processing
  throws reaches the chart as `error.execution` instead of being dropped (§runtime.stepping/ack);
- a cancelled delayed send is never delivered, even when it was already due;
- a run that reaches its top-level final state ends at once, dropping its pending delayed sends
  (recorded as a `:runner/pending-dropped` row) instead of sleeping until they fire;
- resuming restarts the invocations of active states (§runtime.restarts/reinvoke), and a run never
  ends while a counted invocation is in flight (§runtime.restarts/live-count);
- two runs of the same session are refused (§runtime.restarts/single-writer), and a checkpoint is
  not resumed against a chart id that is not available (§runtime.chart-data/version-identity).
