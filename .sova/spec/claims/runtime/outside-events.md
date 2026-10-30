# §runtime/outside-events — Waiting for, and delivering, outside events

A workflow often stops to wait for something outside it: an approval, an incoming email, a
webhook. Escapement lets a host tell such a run apart from a finished one, and hand the outside
event to the session that is waiting for it, without holding a thread for the wait.

## §runtime.outside-events/waiting-status — A waiting run says it is waiting

When the caller opts in (`:report-waiting? true` on `lib/run` or `runner/run!`), a run that has
nothing in flight (no invocation running, no delayed event pending) but has not reached a final
state reports that it is `:waiting`, not `:done`, and its result names the states it is waiting in
(`:waiting-in`). A run that reached a final state still reports `:done`. Without the opt-in, such a
run reports `:done` as it always has. A run is never reported waiting or done while an invocation
it started is still running (§runtime.restarts/live-count).

## §runtime.outside-events/deliver — Delivering an outside event to a session

A host can deliver an outside event (a name and data) to a session by checkpoint directory and
session id, whether the session is running in this process (a run or a stepping session) or saved
and running nowhere. The event joins that session's queue and is processed in order like any
other; it is written into the session's checkpoint before delivery returns, so an accepted event is
not lost if the process stops before it is processed. The answer says where it went: to the
running session, which processes it, or to the saved session, which processes it when the host
next resumes it. Each processed outside event is marked as coming from outside in the transcript
(§runtime.replay/recording). Delivery is refused with a reason, never silently ignored, when the
session has finished, has no checkpoint, is being written by another process, or its run in this
process held the session past the delivery's timeout (for example while paused in the debugger).

## §runtime.outside-events/arrival-time — Outside events keep the time they really arrived

An outside event delivered to a session, or passed in a run's `:resume-events`, may carry the time
it actually arrived (`:at-ms`, epoch milliseconds on the session's clock; for example when the
person tapped Approve), which can be earlier than the time it is delivered. Pending events are
processed in order of that time, so an answer given before a timeout was due is processed before
the timeout, even if the host was down in between and both are due by the time it comes back. An
arrival time in the future is never processed before it. Without `:at-ms`, nothing changes: a
delivered event is stamped with the time it is delivered.
