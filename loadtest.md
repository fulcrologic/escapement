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

---

# Part 2 — Constrained-box scale test (4 GB / 4 CPU, Docker)

Follow-up question: on a **4 GB / 4 CPU** box, what is the upper bound on
concurrent sessions, what is Escapement's overhead, and how does it compare to
the same logic written as plain code with **no statechart**?

## Method

`bench/scale_test.clj` runs ONE arm at ONE concurrency `C` and reports peak
RSS / threads / process-CPU / latency (read from `/proc/self/{status,stat}`).
`bench/Dockerfile` bakes the repo + warmed maven cache; `bench/ramp.sh` runs it
under `docker run --memory=4g --memory-swap=4g --cpus=4` and ramps `C` until
OOM (exit 137) or latency collapse. Babashka is a GraalVM native binary, so its
heap auto-sizes from the cgroup limit and thread stacks count against it — the
4 GB ceiling is real, not simulated.

Three arms, identical mock timing (only orchestration differs):
- **`hand`** — plain K-turn loop, no statechart / queue / checkpoint ("just code").
- **`sc-mem`** — real `escapement.lib/run`, in-memory store (no checkpoint I/O).
- **`sc-ckpt`** — real `lib/run` + disk `FileBackedStore` (production Escapement).

Workload (the profile we agreed on): **realistic chat** — 400 ms TTFT, 150
streamed tokens at 20 ms each ≈ **3.4 s/turn**, **6 turns/session** ≈ 20.4 s of
nominal work per session, launched all at once (steady-state overlap).
`latency-infl` = actual / nominal; 1.0× means perfectly I/O-bound, higher means
the orchestration is stealing time.

## Headline results (4 GB / 4 CPU)

Latency inflation vs concurrency:

| C    | hand   | sc-mem | sc-ckpt |
| ---- | ------ | ------ | ------- |
| 1000 | 1.01×  | 1.08×  | 1.08×   |
| 2000 | 1.01×  | 1.65×  | 1.53×   |
| 3000 | —      | —      | 3.30×   |
| 4000 | 1.05×  | —      | 4.77×   |
| 8000 | 1.27×  | —      | —       |

**Upper bound on a 4 GB / 4 CPU box (realistic streaming):**
- **Escapement (statechart): ~1500–2000 concurrent sessions.** Past ~2000,
  latency degrades fast (3000 → 3.3×, 4000 → 4.77×).
- **Hand-written: ~8000+ concurrent** before it even reaches 1.3× — roughly
  **4× denser** on the same hardware.

**The binding constraint is CPU, not RAM.** At C=4000 sc-ckpt used only ~1.6 GB
of the 4 GB and ~12k threads, but ~92 % of 4 CPUs (376 cpu-sec over 103 s wall).
Neither arm OOM-killed at this profile — sessions are mostly *sleeping* on the
mock's token delays, so they cost CPU only in bursts and memory only for state.

> Caveat: memory never bound because the synthetic per-session state is tiny.
> Real conversations carry full message history, all sessions resident in the
> store's single shared cache atom (Part 1, Finding 3) — at large histories the
> bound shifts toward RAM and that atom.

## The overhead of the statechart (vs plain code)

Measured per-turn / per-session, away from saturation (C≈1000):

| Metric                  | hand        | statechart   | overhead |
| ----------------------- | ----------- | ------------ | -------- |
| CPU per turn            | ~1.5 ms     | ~9–16 ms     | **~6–10×** |
| Threads per session     | 1           | ~3           | **~3×**  |
| Marginal RSS per session| ~0.05–0.1 MB| ~0.4–0.9 MB  | **~8×**  |
| Concurrency ceiling @4c | ~8000       | ~2000        | **~4×**  |

`hand` stays flat at ~1.0× through C=4000 (CPU ~15–20 %) — it is thread/memory
bound, not CPU bound, so it has large headroom. The statechart arms are
**CPU-bound** and that ~6–10× CPU/turn is exactly what caps their concurrency.

Where the statechart CPU goes (attributed by experiment):
- **Per-token delta plumbing under SCI** — the invocation builds a delta map
  and calls the transcript fn for *every* streamed token (1.8M delta calls at
  C=2000). `hand`'s on-delta is a `StringBuilder` append. This is the largest
  share.
- **Interpreted engine per turn** — transition selection, entry/exit,
  data-model assigns, eventless microsteps, all under Babashka/SCI.
- **The 50 ms quiescent poll** — a *minor* share: raising
  `:quiescent-sleep-ms` 50→200 ms at C=2000 cut CPU ~13 % and latency
  1.84×→1.60×. Helps, but is not the dominant cost.
- **`sc-mem` ≈ `sc-ckpt`** at every C → at this state size, checkpoint disk I/O
  is *not* a meaningful cost; the cost is CPU (engine + delta plumbing).

## What this means / how to lift the statechart ceiling

The statechart ceiling on 4 CPUs is set by per-turn + per-token CPU, in order of
payoff:
1. **Throttle/disable per-token transcript deltas** when not needed (sample, or
   only emit on turn boundaries). Biggest CPU lever for streaming workloads.
2. **Event-driven runner** instead of the 50 ms sleep-poll (≈10–15 % CPU at high C).
3. The Part 1 logging fix is already assumed here (level set once).
4. For order-of-magnitude more concurrency on a small box, the architectural
   move is a lighter-weight invocation/runner (fewer threads/session, compiled
   rather than SCI-interpreted hot path) — i.e. the engine, not the box, is the
   limit.

Rule of thumb from these runs: **budget ~2 ms of CPU per streamed token per turn
for the statechart path.** On 4 CPUs that's the ~2000-session ceiling at 150
tok/turn × 6 turns; halve the per-token cost and the ceiling roughly doubles.

## Reproduce

```bash
docker build -f bench/Dockerfile -t escapement-scale .
# single point:
docker run --rm --memory=4g --memory-swap=4g --cpus=4 \
  escapement-scale sc-ckpt 2000 6 400 150 20      # arm C turns ttft tokens tok-ms [qsleep]
# ramp until collapse/OOM:
bench/ramp.sh sc-ckpt 6 400 150 20  500 1000 2000 3000 4000
```
