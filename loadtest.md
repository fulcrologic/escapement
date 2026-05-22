# Escapement concurrency load test — findings

Question that prompted this: a single user can drive ~100 simultaneous
LLM streams; we may have thousands of users. If we route that through
Escapement (one statechart per session), where does it break, and can we
optimize without losing any current behavior?

## TL;DR

- **Token streaming is not the problem.** Token deltas never pump the
  statechart — they hit an `on-delta` callback that only writes a transcript
  row (`llm_conversation` `on-delta`). The chart's event queue only advances
  at *turn boundaries* (turn complete, tool call, error). So per-token
  throughput costs the same as hand-written thread+callback code.
- **The #1 bottleneck is logging, not the engine.** Per-run global logging
  mutation + DEBUG-to-stderr serialize all concurrent runs. Fixing it gave a
  **47–67× speedup at 100 concurrent sessions, and loses nothing.**
- After the log fix, this machine sustains **~1500–2000 chart-runs/sec** with
  sub-linear wall-time growth to 2000 concurrent.
- The remaining structural ceilings are the **thread-per-session model**
  (platform threads on Babashka/SCI) and the **checkpoint store**
  (full-snapshot fsync per event, single shared in-memory cache atom). Both
  are optimizable behind existing seams.

## How to reproduce

Harness: `bench/load_test.clj`. Drives the `hello` chart through the real
`escapement.lib/run` facade in N parallel threads against a **streaming mock
backend** (no API spend). It instruments the working-memory store to time
every checkpoint, and can swap the real disk-backed store for an in-memory
store to isolate I/O cost.

```bash
# args: N  store(disk|mem)  tokens-per-turn  token-sleep-ms  log(loud|quiet)
bb bench/load_test.clj 1000 disk 50 0 loud
```

`loud` here means "do not use the per-run quiet wrapper"; the harness instead
sets the Timbre level to `:warn` **once** at startup (the correct server
pattern). `quiet` exercises the current per-run `lib/run` default.

## Finding 1 — logging serializes everything (the big one)

A single `lib/run` of the `hello` chart takes ~52 ms. But 100 of them in
parallel took ~5.1 s — a ~100× collapse that is entirely contention, not work.

Two independent **global** serialization points, both unrelated to
statecharts:

1. **`escapement.lib/with-quiet-logging`** (`src/escapement/lib.clj`) calls
   `alter-var-root` on the global Timbre `*config*` var on **every run** (sets
   `:warn`, restores in a `finally`). N threads mutating one var = lock convoy.
2. With logging left at default level, the statecharts engine emits **DEBUG
   logs to stderr**, which is a globally synchronized stream.

The bisect (fresh process, 100 concurrent):

| Configuration                                   | wall (100 concurrent) |
| ----------------------------------------------- | --------------------- |
| `:quiet? true` (per-run `alter-var-root`)       | ~5100 ms              |
| `:quiet? false`, default DEBUG → stderr         | ~5180 ms              |
| log level set **once** to `:warn`, no wrapper   | **~77 ms**            |

**Fix (server pattern):** call `(taoensso.timbre/set-min-level! :warn)` once at
process startup and do **not** invoke the per-run quiet wrapper. 47–67× at
N=100. No behavior is lost — only the per-request global mutation is removed.

## Finding 2 — throughput after the log fix

Sweep with the log level set once (`loud`), mock backend, 50 streamed tokens
per turn, no inter-token sleep. `total-save-ms` is summed across all worker
threads (so it can exceed wall time); `avg-save-µs` is mean per-checkpoint.

| N    | store | wall  | runs/sec | avg-save | total-save | thread peak | heap after |
| ---- | ----- | ----- | -------- | -------- | ---------- | ----------- | ---------- |
| 100  | disk  | 110ms | ~900     | 4.8ms    | 1.4s       | 222         | 84 MB      |
| 100  | mem   | 121ms | ~820     | 71µs     | 21ms       | 303         | 30 MB      |
| 500  | disk  | 325ms | ~1500    | 5.7ms    | 8.6s       | 878         | 67 MB      |
| 1000 | disk  | 662ms | ~1500    | 15.0ms   | 45s        | 1264        | 149 MB     |
| 1000 | mem   | 601ms | ~1660    | 0.9ms    | 2.8s       | 1146        | 151 MB     |
| 2000 | disk  | 992ms | ~2000    | 3.8ms    | 22.5s      | 1247        | 190 MB     |
| 2000 | mem   | 972ms | ~2050    | 0.4ms    | 2.2s       | 1266        | 201 MB     |

Observations:

- Disk vs in-memory wall-time differ by only ~10% at N≤2000, because
  checkpoint writes parallelize across worker threads and the OS buffers them.
  Disk is **not** the wall-clock bottleneck at this scale.
- Thread peak ≈ 1.2–1.3 threads per *active* session (one runner pump thread +
  one invocation worker thread). The harness caps the pool at 512, so at
  N=2000 only ~512 run concurrently.
- Heap stays modest — but the `hello` chart's working memory is tiny. Real
  conversations carry full message history, which changes the store math
  (Finding 3).

## Finding 3 — the checkpoint store (the real scaling axis)

`escapement.engine.store/FileBackedStore`, driven once per event by the runner
(`drain-once!` → `save-working-memory!`):

```clojure
;; save-working-memory!  (per statechart event)
(swap! cache assoc session-id wmem)        ; single shared atom, all sessions resident
(atomic-write-edn! (session-file dir id) wmem)  ; full EDN serialize + temp file + fsync rename
```

Three structural risks that the `hello` benchmark hides because its state is
tiny and the sweep tops out at 2000 sessions:

1. **Full-snapshot write per event.** Each event re-serializes the *entire*
   working memory (which includes conversation history) and does an
   `ATOMIC_MOVE` rename (fsync). Cost grows with conversation length × event
   rate → write amplification on long conversations.
2. **One shared `cache` atom holds every session resident.** Memory grows with
   the count of all live sessions, and that atom is a swap-contention point at
   very high session counts.
3. **Checkpoint-per-event = N_events fsyncs/sec** of IOPS pressure.

**Good news — the store is a protocol** (`com.fulcrologic.statecharts.protocols/WorkingMemoryStore`)
and `lib/run` accepts a `:store` override (the harness uses it). You can drop
in a sharded / evictable in-memory store, an external store (Redis, Postgres),
or a write-behind async store **without touching any chart or engine code**.

## Finding 4 — architectural ceilings for thousands × 100

At thousands of users × ~100 streams = potentially **100k+ concurrent charts**:

- **Thread-per-runner + thread-per-invocation, on platform threads.** Babashka/
  SCI has no virtual threads. ~100k platform threads (~1 MB stack each) is not
  feasible. This is the hard ceiling. Options: a virtual-thread / event-loop
  invocation model (implies a JVM host, not bb), or horizontal sharding across
  processes.
- **Idle busy-poll.** While a runner waits on a live invocation it sleeps
  `:quiescent-sleep-ms` (default 50 ms) and re-checks (`runner.clj`). Long-lived
  streaming sessions spend most of their life here; at 100k runners that is
  ~2M wakeups/sec of `count-live-invocations` + queue-peek. Candidate to make
  event-driven (block on the queue with a timeout) instead of sleep-poll.

## Recommended optimizations (ranked; none remove functionality)

1. **Set the log level once at startup; drop the per-run quiet wrapper.**
   47–67× today, free. (Finding 1.)
2. **Pluggable store** — sharded/evictable in-memory or external backend; the
   `:store` seam already exists. (Finding 3.)
3. **Async / batched checkpoints** — write-behind instead of synchronous
   fsync-per-event. The only thing traded is crash-recovery granularity, which
   becomes tunable rather than fixed-per-event.
4. **Event-driven runner** — block on the event queue with a timeout instead of
   the 50 ms sleep-poll; eliminates idle CPU burn for long-lived streams.
5. **Thread model** — for 100k+ concurrent charts, move to virtual threads
   (JVM host) or shard across processes. This is the one real architectural
   decision.

## Caveats on these numbers

- Single workstation; absolute throughput will differ on server hardware.
- The `hello` chart runs a single LLM turn with tiny state. Real workloads have
  more turns (more checkpoints) and larger working memory (heavier per-write
  cost) — Findings 3 and 4 matter more there than the table in Finding 2
  suggests.
- The mock backend returns instantly; with `token-sleep-ms > 0` you can model a
  realistic token rate, which keeps runner threads parked longer and makes the
  thread-count ceiling (Finding 4) bind sooner.
