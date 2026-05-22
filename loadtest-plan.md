# Optimization plan — raising the concurrency ceiling

Grounded in `loadtest.md`. Goal: lift the statechart's ~2000-session ceiling on
a 4 GB / 4 CPU box toward the ~8000 the hand-written baseline reaches, **without
losing current behavior** (durable transcript, per-event checkpoint/resume,
streaming to hosts, debug controller).

Everything here is measurable with the existing harness:
`bench/scale_test.clj` + `bench/ramp.sh` under `docker --memory=4g --cpus=4`.
Baseline to beat (realistic chat, 6 turns): `sc-ckpt` ~9–16 ms CPU/turn,
~3 threads/session, knee at C≈2000 (1.5×), collapse by C≈3000 (3.3×).

## Where the cost actually is (root causes)

1. **Per-token transcript plumbing.** Every streamed token → a `:llm/delta`
   row → enqueued to a **per-run** daemon writer thread that JSON-encodes and
   **`.flush`es the file on every line** (`src/escapement/transcript.clj`
   `write-line!` / `writer-loop`). 1.8M flush+encode ops at C=2000. Active even
   in `sc-mem` because `lib/run` always opens a transcript.
2. **A writer thread per run** → 3rd thread/session (runner pump + invocation
   worker + transcript writer). Drives the ~3× thread overhead.
3. **50 ms quiescent poll** per runner (`runner.clj`) → idle
   `count-live-invocations` spins; ~13 % of CPU at high C.
4. **SCI-interpreted engine** per turn (transition select, entry/exit, assigns,
   eventless microsteps) — the irreducible-on-bb share.
5. **(Not bound here, but real at scale)** full-snapshot fsync checkpoint per
   event + single shared store cache atom (`engine/store.clj`).

---

## Phase 0 — attribution (do first, ~30 min)

Establish the ceiling of the *pure engine* so we know the max possible win
from transcript work.

- **0a. No-op transcript sink.** Add a harness switch that injects a sink which
  drops all rows (no encode, no write, no thread). Run `sc-mem` at C=2000.
  - *Tells us:* the upper bound for Experiments A+B. If CPU/turn drops from
    ~10 ms toward ~3–4 ms, transcript is the majority and A+B are worth it.
- **0b. `stream? false`.** Same chart, no per-token deltas (one row/turn).
  - *Tells us:* delta-volume cost vs writer-mechanism cost.

Both are pure measurement; no production code changes.

---

## Experiments, ranked by expected payoff

### A — Transcript: stop the per-token flush/encode storm  *(biggest lever, lossless)*

- **A1 — Batch flush.** In `transcript.clj writer-loop`, flush on a timer
  (e.g. every 20–50 ms) or when the queue drains via `poll` timeout, instead of
  `.flush` per line. Keep flush-on-close and flush-on-non-delta if needed.
  - *Lossless:* same rows, same order; only durability latency changes (bounded
    by the flush interval). fsync mode stays opt-in.
- **A2 — Deltas to tap, not to disk.** Make `:llm/delta` rows go to the
  in-process `:transcript-tap` (how hosts stream to users) but **not** to the
  JSONL file by default — the durable transcript keeps turn-level rows
  (`:llm/request`, `:llm/response`, tool results). Gate with a
  `:persist-deltas?` option (default false).
  - *Lossless for hosts:* streaming UIs read the tap; the durable log keeps
    everything that matters for resume/audit. Opt back in when you need
    token-level forensics.
- **A3 — Coalesce deltas.** Accumulate token text and emit one delta row per
  flush tick / per N tokens. Reduces row count ~10–50×.
- *Measure:* CPU/turn + latency-infl at C=2000 (`sc-mem` and `sc-ckpt`); then
  re-ramp to find the new knee.
- *Hypothesis:* A1+A2 move the knee from ~2000 toward ~4000+.

### B — Share the transcript writer across sessions  *(thread lever, lossless)*

- One writer thread (or a small pool) multiplexing many sessions' queues,
  keyed by path/session, instead of one thread per `open-transcript`.
- Drops 3 → 2 threads/session; less scheduler pressure at high C.
- *Measure:* peak-threads/session and the ramp ceiling.
- *Risk:* writer becomes a shared bottleneck — mitigate with a sharded pool
  (e.g. `min(4, cpus)` writers). Combine with A so the writer does far less.

### C — Event-driven runner (kill the 50 ms poll)  *(modest, lossless)*

- Replace `Thread/sleep quiescent-sleep-ms` + re-check with a **blocking take
  with timeout** on the event queue, waking on enqueue. Keep a long timeout as
  the safety tick for delayed sends so the frozen-config / delayed-timer logic
  is preserved.
- *Measure:* CPU/turn at C=2000–3000; expect the ~13 % poll share to drop and
  per-turn latency floor (~50 ms/turn) to shrink.
- *Risk:* must preserve cancel-at-boundary, delayed-send wakeups, and
  frozen-config detection — these are why the poll exists. Needs the queue to
  expose a blocking/notifying take (`engine/queue.cljc`).

### D — Fewer threads per invocation  *(thread lever)*

- Today each `llm-conversation` invoke runs a dedicated blocking-stream worker
  thread, and the turn-loop chart re-spawns one per turn. Options:
  - **D1** keep a persistent worker across turns (avoid per-turn spawn/teardown);
  - **D2** a shared bounded worker pool for streaming calls;
  - **D3** virtual threads — only on a JVM host (see F), not bb.
- *Measure:* threads/session, spawn-rate, ceiling.
- *Risk:* the dedicated-thread-per-invoke model underpins cancellation and
  mid-turn steering; changes must keep `:dying`/interrupt semantics intact.

### E — Store: async checkpoint + sharded/evictable cache  *(matters when RAM-bound)*

- Not the bound in the synthetic test (tiny state), but the real risk for long
  conversations: full-snapshot EDN + fsync rename **per event**, all sessions
  resident in one cache atom.
  - **E1** write-behind: coalesce checkpoints (per quiescence or per interval)
    on a background writer; trade crash-recovery granularity (make tunable).
  - **E2** swap the default `FileBackedStore` for a sharded/evictable in-memory
    store, or an external one (Redis/PG) — the `:store` protocol seam already
    exists (the harness uses it).
- *Measure:* add a "large-state" profile (big `:initial-data`, 12+ turns);
  watch RSS slope and checkpoint CPU; ramp to a RAM-bound OOM.
- *Risk:* E1 weakens per-event resume; keep per-event as an option.

### F — Compiled hot path off SCI  *(largest lever, architectural)*

- Part of the 6–10× CPU/turn is Babashka/SCI interpreting the engine. Most
  source is now `.cljc` (post-migration). Offer a **JVM runtime mode** for
  high-scale hosting (compiled engine + virtual threads) while keeping bb for
  dev/CLI.
- *Measure:* run `bench/scale_test.clj` arms on JVM Clojure vs bb; compare
  CPU/turn and ceiling. (Build a `clojure -M` entry that loads the same nss.)
- *Risk:* biggest scope; dual runtime to maintain; must not regress the
  bb/SCI guarantees in `CLAUDE.md`. Treat as a separate track, decided only if
  A–D don't get us far enough.

---

## Suggested order

1. **Phase 0** (attribution) — confirms the ceiling A+B can reach.
2. **A1 + A2** — expected biggest, lossless win; re-ramp.
3. **C** — cheap, compounds with A.
4. **B** — thread reduction once A makes the writer cheap.
5. Re-measure the ceiling. If it clears the target, stop.
6. **E** only if a large-state profile shows RAM/store as the new bound.
7. **D / F** only if CPU is still the wall after A–C and we need an order of
   magnitude more on the same box.

## Success criteria

- 4 GB / 4 CPU, realistic chat, 6 turns: knee (1.5× latency) moves from
  C≈2000 to **C≈4000+**; CPU/turn from ~10 ms to **≤4 ms**; threads/session
  from ~3 to **≤2** — with the durable transcript, resume, host streaming, and
  debug controller all still working (verified by `bb test` + a streaming
  smoke).
