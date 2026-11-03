(ns escapement.engine.liveness
  "How the runner learns that work is in flight.

   A run must not end (`:done`, `:waiting`, `:sleeping`) while an invocation it started is still
   running: that invocation's result would land in a queue nobody drains, and be lost. The runner
   asks every invocation processor in the env how many invocations it has running. Any processor
   (a host's own, passed through `:invocation-processors`) can answer by implementing `ActiveCount`.

   Built-in answers live in `escapement.runner/count-live-invocations`: the LLM-conversation and
   human-input processors, the statecharts `:future` processor (its `:active-futures`), and the
   duck-typed `:workers` atom used by test stand-ins. A processor that cannot report a count adds 0,
   as before.")

(defprotocol ActiveCount
  (active-count [processor]
    "How many invocations `processor` currently has running (a non-negative integer)."))
