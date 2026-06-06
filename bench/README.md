# bench — concurrency / scale harness

A local performance harness for driving many concurrent agent sessions in one
process. Originated from the issue #11 multi-tenant scaling work; kept here for
ongoing perf regression testing — run it before and after a change to compare.

It is a hand-run developer tool: **not part of the shipped library**, not
covered by `bb test`, and not referenced from `Guide.adoc`.

## What it measures

`scale_test.clj` runs ONE arm at ONE concurrency `C`, drives each session with a
realistic streaming **mock** LLM (no network: `ttft-ms` + per-token `tok-ms`),
and reports peak RSS / peak thread count / CPU / p50–p99 session latency /
latency-inflation. The chart grows its data-model by ~1 KB per turn so checkpoint
snapshots grow (exposes full-snapshot write cost).

Arms (same mock timing; only orchestration differs):
- `sc-ckpt` — `lib/run` + on-disk `FileBackedStore` (production shape)
- `sc-mem`  — `lib/run` + in-memory store (isolates engine/queue cost)
- `hand`    — bare K-turn loop, no statechart/queue/checkpoint (floor)

Toggles (env):
- `SCALE_VT=1` — drive sessions on a virtual-thread-per-task executor. Combine
  with the JVM flag `-Descapement.virtual-threads=true` so escapement's own
  worker threads (transcript-writer, llm-conv worker) are virtual too. JVM only;
  under bb the sessions stay on a platform thread pool.
- `SCALE_STATE_KB=N` — seed each session with an ~N KB opaque payload.

## Run locally

```bash
# args: <arm> <C> <turns> <ttft-ms> <tokens> <tok-ms> <qsleep> <state-kb>
bb bench/scale_test.clj sc-mem 100 6 400 150 20 50 0
```

To compare platform vs. virtual threads on a real JVM (watch `:peak-threads`):

```bash
# platform threads
clojure -Sdeps '{:paths ["src" "bench"]}' -M bench/scale_test.clj sc-mem 1000 4 200 50 5 50 0

# virtual threads (Loom)
SCALE_VT=1 clojure -J-Descapement.virtual-threads=true -Sdeps '{:paths ["src" "bench"]}' \
  -M bench/scale_test.clj sc-mem 1000 4 200 50 5 50 0
```

Each run prints a one-line `RESULT {…}` map (plus a pretty-printed copy) with
`:peak-threads`, `:peak-rss-mb`, `:p50/p99-session-ms`, `:latency-infl`,
`:errors`, etc. Capture those before and after a change to see the delta.
