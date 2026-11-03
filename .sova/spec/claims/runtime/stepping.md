# §runtime/stepping — Event-driven stepping

A host can drive a session one event at a time instead of handing it to a long-running pump:
take the next due event, process it, keep the result, and learn when to come back. This is what
lets a session live in a service that restarts, sleeps between events, or (later) runs where
there are no threads to park. `runner/run!` and `lib/run` keep their blocking behaviour for
existing callers; stepping is an additional way in.

## §runtime.stepping/one-event — Process one event, then stop

A host opens a session for stepping with the same options a run takes (starting it, or with
`:resume? true` resuming it from its checkpoint), which holds the session
(§runtime.restarts/single-writer) until the host closes it. Each step processes at most one due
event (the earliest by due time, then by arrival), running the chart's whole macrostep for it, the
same as the pump would. Every step, and a plain look at the session, reports where it stands: its
active states, whether it reached a final state, its pending events, the invocations in flight, its
next wake time, and a status: `:running` (something is due now or in flight), `:sleeping` (only
delayed events not yet due), `:waiting` (nothing pending; only an outside event can move it) or
`:final`. When no event is due, nothing is processed and the step says so.

## §runtime.stepping/ack — Each event is kept, then acknowledged

An event stays in the queue until its own turn and is taken out only just before it is processed;
the checkpoint written after processing it holds the new state together with every other pending
event, and that one write is the event's acknowledgement. So a crash at any point either leaves the
event pending in the saved checkpoint (it is processed again from the state saved before it) or
leaves it processed and gone; it is never lost between being taken and being saved, and never
applied twice to a saved state. A delayed send cancelled before its turn is never delivered, even
when it was already due. An event whose processing throws is not silently dropped: nothing is
saved for it, the chart gets `error.execution` (naming the failed event) and the transcript gets a
`:runner/event-failed` row, and the run continues from there; an error event that itself throws
does not loop.

## §runtime.stepping/next-wake — The host learns when to wake next

Each step tells the host the earliest time a pending event (a `send` with a delay, a retry
backoff, a "wait two days" timer) becomes due, in epoch milliseconds on the run's clock
(§runtime.testing/clock), or that none is pending. A host can sleep or set a single timer until
then, and a delayed event is delivered no earlier than its due time. With the default file-backed
checkpoint store, delayed events survive a restart: they are part of the saved queue, and are due
at the same time after it. A custom store keeps no timers to reload.

## §runtime.stepping/park-timers — A run can park on a timer instead of sleeping

`lib/run` and `runner/run!` accept `:park-timers? true`. With it, when the only work left in a run
is a delayed event not yet due (nothing in flight, nothing deliverable now), the run saves its
checkpoint and returns `:status :sleeping` with `:next-wake-at`, the delayed event's due time in
epoch milliseconds on the run's clock (§runtime.testing/clock), instead of holding a thread asleep
until then. Resuming the session at or after that time restores the timer from the checkpoint and
delivers it, so the chart carries on as if it had never stopped; resuming earlier just parks again
with the same wake time. `:sleeping` is distinct from `:waiting` (§runtime.outside-events/waiting-status),
which means no delayed event is pending at all. Parking needs the default file-backed checkpoint
store, the only one that keeps timers: a run given `:park-timers?` with a custom `:store` is
refused before it starts. Without the option, a run with a pending timer sleeps until it is due,
as before.
