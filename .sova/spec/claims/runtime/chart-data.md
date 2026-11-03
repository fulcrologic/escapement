# §runtime/chart-data — Charts built from data

Besides charts written as Clojure code, a chart can be built from a data description, so a tool
or an AI can create and edit a workflow as plain EDN while its logic stays in ordinary functions.
The result is an ordinary chart value: it runs, resumes and is inspected exactly like a
hand-written one.

## §runtime.chart-data/build — A shape plus a namespace of step functions

A chart is built from two parts: a shape (EDN data naming the states, their children,
transitions, events, delays, invocations, and the guards and actions each uses, referenced by
keyword) and a namespace of step functions that those keywords name (qualified keywords name a
host's own vocabulary instead). Building resolves every keyword reference to the function it
names; a reference that names no function is reported as a problem, never left as a literal.
Building never throws for a mistake in the shape: it returns the problems.

A state can be an AI step (an LLM conversation), an outside call (a `:tool-call`,
§runtime.embedding/tool-call), any other invocation, or a wait for an outside event with a
timeout. A delay (on a transition's `:after`, or a wait's timeout) is either a number of
milliseconds or computed by a step function when the state is entered. A state's `:retry` retries
its call with bounded attempts and backoff, but only errors that are retryable: never an outside
call whose outcome is unknown (`error.call.unknown`, its effect may already have happened), never an
error the tool marks not retryable, and, when the retry names error codes, only those. Any other
error goes to the state's own error transitions; a retryable error out of attempts, or one no
transition takes, goes to the retry's exhausted target.

States and transitions carry the labels and conditions from the shape so the chart's diagram shows
them, and a built chart records in the transcript each state it enters (`:chart/entered`) and each
guard decision (`:chart/guard`).

## §runtime.chart-data/validation — Structural checks that report problems

Building a chart from data runs structural checks and returns every problem it finds, each naming
the state or transition it concerns in a readable sentence, instead of throwing on the first or
running a broken chart. The checks include the statecharts library's own problems, plus: every
state is reachable from the initial state; no non-final state is stuck (has no way out); every
state that invokes something (an AI step, an outside call, another invocation) has a transition for
that invocation's error events; retries are bounded; targets, initial states and step references
exist; an AI step or outside call has no unknown keys, an AI step has a message and an outside call
names its tool; no `:human-input` invocation; and a parallel state has at least two regions. A
shape with errors does not build a chart; warnings do not block it.

## §runtime.chart-data/version-identity — Each version has its own chart identity

Each version of a data-built chart is given its own chart id, and its step namespace is loaded
under a name unique to that version (`wf.<project>.v<sha12>`), never reloaded in place over an
earlier one: loading other code under a name already loaded is refused. Step code is checked for
portability before it loads (no file, shell, thread or host interop, no loading code at runtime),
and code that fails the check is refused. A session's checkpoint names the chart id it runs, so a
run started on one version stays on it: resuming finds that version's chart, and a newer version
deployed meanwhile does not take over runs already started. Resuming a checkpoint whose chart id is
not available is refused rather than run against another version.
