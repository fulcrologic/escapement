# §runtime/testing — Testing charts without real time or real AI

Escapement ships what a chart's tests need to run fast and the same way every time, for use from
any test runner or host: a test clock, a scripted AI backend, pretend tools that record their calls
and answer from a script, and test cases written as data that run a chart on the test clock and
check where it went.

## §runtime.testing/clock — A test clock that skips waiting

A run (`lib/run`, `runner/run!`, or a stepping session) can be given a clock (`:clock`) in place of
the wall clock. With the manual test clock, delayed events (timers, retry backoffs, long waits)
become due when the test advances the clock, or straight away when it is set to advance on its
own, so a chart that waits two days is tested in milliseconds. Delayed events are still delivered
in due-time order. A host may supply its own clock. Without a clock the wall clock is used, as
before.

## §runtime.testing/scripted-llm — A scripted AI backend

A scripted LLM backend answers a chart's AI steps from a script instead of a provider. The script
gives each AI step its own list of replies (with a default list for steps it doesn't name), and
each AI turn of that step takes the next one: text, a call to one of the step's event tools (so the
chart transitions as if the model chose it), a call to one of its real tools, a structured verdict,
a malformed answer, or a backend failure of a given kind. It makes no network call and needs no
credentials. A turn the script has no reply for fails clearly as an AI error naming the step and
turn, rather than hanging, unless the script says to repeat the step's last reply.
