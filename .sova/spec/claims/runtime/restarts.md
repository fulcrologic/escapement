# §runtime/restarts — Restarting and resuming sessions

A session saved by one process can be resumed by another, after a crash, a restart or a
deploy, and carry on where it was: the work it had in flight is started again, it is never
reported finished while work is still running, and two processes never write the same session at
once.

## §runtime.restarts/reinvoke — Resuming restarts what was in flight

When a session is resumed from its checkpoint, every invocation declared by a state that is
active in the restored configuration (an LLM conversation, a child chart, a human-input prompt, a
`:future` or `:tool-call` outside call) is started again, as if its state had just been entered;
a resumed state with no invocation starts nothing. Before this, a resumed chart parked in such a
state had nothing running. The restart is recorded in the transcript (`:runner/reinvoked`, naming
the states), and an invocation restarted this way gets the same parameters it would get on entry;
what it already did before the crash is not undone or replayed, so an outside call may run twice
unless the chart or host guards against that.

## §runtime.restarts/live-count — In-flight invocations keep a run alive

A run counts every invocation in flight, whatever processor runs it, including the statecharts
`:future` processor, the `:tool-call` processor (§runtime.embedding/tool-call) and any processor
passed as an extra (§runtime.embedding/invocation-processors) that can report how many it has
running. While one is running the run never ends as `:done`, `:waiting` or `:sleeping`; it waits
for the invocation's result, so that result is processed rather than lost. A processor that
cannot report a count is treated as before.

## §runtime.restarts/single-writer — One writer per session

While a run or a stepping session holds a session (by checkpoint directory and session id), another
that tries to write the same session is refused with a clear error naming it, instead of both
writing and corrupting it. The hold ends when the run ends or its process dies, even by `kill -9`,
so a crash never leaves the session locked. Reading a session's checkpoint or transcript needs no
hold. `runner/run!` can be told not to take the hold (`:lock? false`).

## §runtime.restarts/checkpoint-version — Checkpoints name their format

Each checkpoint records the format version it was written in. Checkpoints written by earlier
versions of Escapement are still read and resumed; a checkpoint written by a newer version than
the running one is refused with an error saying so, rather than misread.

## §runtime.restarts/require-checkpoint — Resuming can insist on a checkpoint

`lib/run` and `runner/run!` accept `:require-checkpoint? true` alongside `:resume? true`. With
both, a run whose session has no checkpoint to resume from (none saved, or one with no active
configuration) fails with a clear error naming the session and checkpoint directory, and starts
nothing, instead of silently starting the chart fresh. Without the option, `:resume? true` on a
missing checkpoint starts fresh, as before.

## §runtime.restarts/inspect — Looking at a saved session without touching it

A host can inspect a saved session by checkpoint directory and session id (`lib/inspect`). The
answer says whether the session exists and, if it does, its chart id, its active states, whether
it is in a final state, its pending events (both those queued now and delayed ones, with their due
times, in delivery order) and its next wake time (§runtime.stepping/next-wake). Inspecting runs
nothing, takes no hold on the session (§runtime.restarts/single-writer) and writes nothing, not even
a missing directory, so it is safe while another process is running that session.
