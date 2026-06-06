# Benchmarking — concurrency / scale

How to measure Escapement's behavior under many concurrent agent sessions, and
how to get a clean before/after when changing anything that touches threading,
the engine pump, checkpointing, or per-session memory.

This is a hand-run developer workflow. The harness (`bench/`) is **not** part of
the shipped library, **not** covered by `bb test`, and **not** referenced from
the user guide.

## Why a constrained container

On a fat host, resource ceilings never bite — you can spawn tens of thousands of
OS threads before anything fails, so a regression hides in latency noise. The
honest test is a **small box**: cap CPU and memory so contention actually
collapses the process, then ramp concurrency until it does. We use **2 CPU /
2 GB** by default (override via `CPUS=`/`MEM=`).

Running in a container also isolates the harness's scratch: each session writes a
transcript/checkpoint dir, and at high concurrency that is *millions* of tiny
files. In a container that scratch dies with the container instead of
exhausting the host's `/tmp` inodes.

## What the harness measures

`bench/scale_test.clj` runs ONE arm at ONE concurrency `C`, drives each session
with a realistic streaming **mock** LLM (no network — `ttft-ms` first-token delay
+ per-token `tok-ms`), and prints a one-line `RESULT {…}` map:

- `peak-threads` — peak OS-level thread count (`/proc/self/status` `Threads`).
  Virtual threads are **not** OS threads, so they do not inflate this.
- `peak-rss-mb`, `rss-kb/sess` — peak resident memory, and per session.
- `p50/p99-session-ms`, `latency-infl` — session latency and how many times
  worse it is than the ideal (`nominal-turn-ms × turns`).
- `cpu-ms/turn`, `cpu-sec`, `errors`, `ok`, `wall-ms`.

Arms (identical mock timing; only orchestration differs):

| arm | shape |
|---|---|
| `sc-ckpt` | `lib/run` + on-disk `FileBackedStore` (production shape) |
| `sc-mem`  | `lib/run` + in-memory store (isolates engine/queue cost) |
| `hand`    | bare K-turn loop, no statechart/queue/checkpoint (floor) |

Toggles (env): `SCALE_VT=1` drives the **session** executor on a
virtual-thread-per-task executor; `SCALE_STATE_KB=N` seeds each session with an
~N KB payload so checkpoint snapshots are realistically large.

## Strategy for a before/after

The same `bench/Dockerfile` builds against whatever `src/` is in the build
context, so one Dockerfile produces both images — just build from each checkout:

```bash
# "after" — from the branch under test (e.g. the virtual-threads branch)
docker build -f bench/Dockerfile -t esc-bench:branch .

# "before" — from a detached worktree at the merge-base
git worktree add --detach /tmp/esc-base "$(git merge-base HEAD main)"
cp -r bench /tmp/esc-base/bench          # the harness may not exist on the base
docker build -f /tmp/esc-base/bench/Dockerfile -t esc-bench:base /tmp/esc-base
git worktree remove --force /tmp/esc-base
```

The image bakes `SCALE_VT=1`, so the **session** executor is virtual in *both*
images. That deliberately removes the session pool as a variable — the only
platform-thread difference left is Escapement's own long-lived worker threads
(transcript-writer + llm-conversation worker, ~2 per session). That isolates
exactly what a threading change affects.

Then ramp each image until it collapses (OOM exit 137, native-thread exhaustion,
or a 600s timeout):

```bash
# args: <image> <arm> <turns> <ttft> <tokens> <tok-ms> <C1> <C2> ...
bench/ramp.sh esc-bench:base   sc-mem 6 200 50 5 500 1000 2000 4000 8000
bench/ramp.sh esc-bench:branch sc-mem 6 200 50 5 500 1000 2000 4000 8000
```

`ramp.sh` stops at the first failure and prints where the knee is.

## Reference result — virtual-thread worker threads (issue #11)

Worker threads platform (pre-change) vs. virtual (auto-on). `sc-mem`, 6 turns,
ttft 200ms, 50 tokens @ 5ms, container **2 CPU / 2 GB**:

| C (sessions) | platform `peak-threads` | platform outcome | virtual `peak-threads` | virtual outcome |
|---:|---:|---|---:|---|
| 500   | 1,009 | ok — 3.18× latency-infl | **8** | ok — 1.78× |
| 1,000 | 2,012 | ok — 7.43× | **8** | ok — 4.40× |
| 2,000 | 4,013 | ok — 14.40× | **8** | ok — 11.32× |
| 4,000 | 8,011 | ok — 26.81×, 7.11 cpu-ms/turn | **8** | ok — 26.07×, 4.42 cpu-ms/turn |
| 8,000 | ~16,000 | **DIED — 600s timeout** | **8** | **ok** — 62× (CPU-bound), 0 errors |

Takeaways:

- **OS threads stay flat at 8** with virtual threads, regardless of `C`; the
  platform build grows ~2 threads per session (≈ `2 × C`).
- The platform build **collapses at C=8000** — ~16k threads on 2 CPUs cannot
  finish a 6-turn workload inside 600s. The virtual build completes it with 0
  errors.
- Virtual threads also cut **CPU per turn by ~35%** (no platform context-switch
  overhead) and lower per-session memory.
- At C≤4000 *latency* is similar between the two: there the bottleneck is the 2
  CPUs doing mock token work, not threads. The thread model's win is the flat
  thread count, lower CPU/turn, and survival past the platform ceiling — i.e. how
  many concurrent tenants fit in one process before it falls over.
