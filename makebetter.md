# makebetter.md — synthesis of three independent reviews

Three reviewers (a performance engineer, a principal architect, and a skeptic
red-teamer) independently reviewed `loadtest.md` + `loadtest-plan.md` and the
source. This doc synthesizes them into what we actually do, in what order, and
what we deliberately decided NOT to do (and why).

Baseline being improved (4 GB / 4 CPU, realistic chat ≈3.4 s/turn, 6 turns):
statechart knee at C≈2000 (1.5× latency), collapse by C≈3000; hand-written
loop reaches ~8000; CPU-bound, not RAM-bound in the synthetic test.

---

## What all three agree on

1. **The single cheapest, safest, highest-impact change is to stop flushing the
   transcript per line.** `transcript.clj` `write-line!` does `.flush` after
   every row, i.e. a write syscall per token (~1.8M at C=2000) on top of a
   `BufferedWriter` that already buffers. Flush on a queue-drain/`poll`-timeout
   or every N rows instead. Lossless (durability latency bounded by the
   interval), ~1 line, and likely beats most of plan-item A on its own. **Do
   this first.**

2. **Verify before optimizing the rest.** The CPU attribution ("per-token
   plumbing under SCI") is currently asserted, not profiled. Run the Phase-0
   experiments (no-op sink, `stream? false`) *with GC logging* before investing
   in A2/B/C. If a no-op sink doesn't collapse CPU/turn toward ~3 ms, the whole
   transcript-block is aimed wrong.

3. **For "thousands of users × 100 streams" (100k+ charts), single-box density
   is a multiplier, not the answer — horizontal sharding across processes/nodes
   is unavoidable.** All three say single-box tuning cannot reach that scale; it
   only sets the per-shard constant. Design the store/runner so sessions are
   *relocatable* (so sharding is possible later).

4. **The latent production wall is the store, not the engine.** The single
   shared `cache` atom in `FileBackedStore` holds every live session resident,
   and it does a full-snapshot EDN + fsync rename per event. The synthetic
   bench hides this (tiny state). Build a **large-state profile** before
   investing in store changes — but expect this to bite first in real use.

5. **The 50 ms poll is real but minor (~13%) and semantically load-bearing.**
   It is the mechanism for delayed-send wakeups and frozen-config detection. An
   event-driven runner is worth doing but must preserve both; sequence it after
   the transcript work and only if attribution shows the poll actually binds.

---

## Where they disagree (decisions we must make)

### A. Is the benchmark fair? — the skeptic's main charge
The hand-written baseline runs the loop **inline on one thread with a
`StringBuilder`**, while the statechart arm pays for a durable JSONL transcript,
per-event checkpoint, a queue, and ~3 threads. So the headline "4× denser" is
**statechart + all its I/O machinery vs. nothing** — it conflates the engine
with the I/O the plan already wants to cut.
- **Decision:** add a **4th arm — hand loop that writes the same JSONL
  transcript through the same sink** (and optionally checkpoints). This isolates
  *true engine overhead* from *I/O machinery overhead*. Until we have it, treat
  the 6–10× CPU/turn and 4× density numbers as an upper bound on the penalty,
  not the engine's intrinsic cost.

### B. JVM + virtual-thread runtime (plan F) — three different verdicts
- **Architect:** do it *now*, as a primary track — Loom + compiled engine
  attacks both walls at once (SCI CPU tax *and* the platform-thread ceiling);
  the `.cljc` migration already paid most of the integration cost.
- **Perf:** it's the hard-wall escape hatch; reach for it only after the cheap
  fixes plateau.
- **Skeptic:** cut it for now — dual-runtime maintenance + tension with the
  bb-only guarantee in `CLAUDE.md` almost certainly costs more than another
  container; horizontal scaling is cheaper.
- **Decision:** **do not start F yet.** Gate it on evidence: finish the cheap
  fixes + the fair-baseline measurement, and explicitly cost out horizontal
  sharding. If (and only if) per-box CPU is still the binding wall *and* more
  boxes are genuinely off the table, spike a `clojure -M` entry that loads the
  same `.cljc` nss on a virtual-thread executor and re-run the harness. Keep
  `src/` SCI-clean regardless.

### C. Routing deltas off disk (plan A2) — "lossless"?
- **Perf:** high-impact; deltas exist for live host streaming via the in-process
  tap, the durable log only needs turn-level rows.
- **Skeptic:** this is the *highest-risk* "lossless" claim — it silently changes
  the durability contract and could break token-level resume/audit/replay.
- **Decision:** keep it **opt-in, default off**, and before flipping the
  default, confirm no resume/audit path reads `:llm/delta`. Ship A1 (batched
  flush) first; A2 only if attribution shows encode cost (not just flush) still
  dominates after A1.

---

## Suspects to rule out during verification (skeptic + perf)

Run these alongside Phase 0 so we don't optimize the wrong thing:
- **GC under churn.** Every token allocates a fresh delta map → `assoc` →
  enqueue. On GraalVM SerialGC in a 4 GB cgroup this can be a real share. GC-log
  the runs.
- **Queue put/take cost.** 1.8M `LinkedBlockingQueue` ops/run — measure whether
  it's lock/condvar cost vs. encode cost (the no-op-sink-but-still-enqueue
  variant separates these).
- **on-delta allocation when nobody's listening.** `llm_conversation.clj`
  builds `(assoc d :model :invokeid)` and calls through a try/catch per token
  even when there's no tap and we're not persisting. Skip building the event
  entirely when `(not (or persist? tap))`.
- **The /proc sampler itself** reads `/proc/self/{status,stat}` every 25 ms and
  is attributed into the measured CPU — keep it but discount it.
- **Confirmed NOT a suspect:** guardrails/malli `>defn` instrumentation is off
  under bb (only enabled with `-Dguardrails.enabled=true` in JVM `:test`/`:dev`
  aliases). Good — one fewer thing to chase.

Also note benchmark-fidelity caveats for honest reporting: `Thread/sleep` parks
a real platform thread per session (so "CPU-bound" is workload-specific — real
history-carrying sessions flip toward RAM); big-bang launch + CFS `--cpus=4`
quota produce bursty tail latency that isn't the same as steady arrivals.

---

## Revised sequence (the actual plan)

**Stage 0 — measure honestly (do before any prod change):**
- 0.1 Add the **4th arm** (hand + same transcript sink) → isolates true engine cost.
- 0.2 Run **Phase 0a/0b** (no-op sink; `stream? false`) **with GC logging**.
- 0.3 Add a **large-state profile** (big `:initial-data`, 12+ turns) → find the
  real RAM/store bound.
- *Gate:* these three decide whether the transcript block (A) or the store (E)
  is the real target.

**Stage 1 — cheap, safe, lossless (ship + re-ramp):**
- 1.1 **Batched transcript flush** (the consensus #1 win).
- 1.2 **Skip on-delta event construction when no tap and not persisting.**
- *Expected:* knee ~2000 → ~3000–4000; verify by re-ramping.

**Stage 2 — conditional, after Stage-1 measurement:**
- 2.1 **A2 deltas-to-tap-only**, opt-in, only if encode still dominates.
- 2.2 **Event-driven runner** (blocking take w/ timeout), preserving
  delayed-send + frozen-config; only if the poll is shown to bind.
- 2.3 **Shared writer pool** — only after 1.1 makes the writer nearly idle.

**Stage 3 — production-scale architecture (the real wall):**
- 3.1 **Write-behind / coalesced checkpoint** + separate durable conversation
  log from the working-memory snapshot.
- 3.2 **Sharded, evictable store** behind the `:store` protocol seam
  (Redis/PG), designed for **session relocatability**.
- 3.3 **Horizontal sharding** across processes/nodes (consistent-hash sessions).
  This — not single-box tuning — is what reaches 100k+.

**Deferred (evidence-gated):**
- **JVM + virtual-thread runtime (plan F)** — only if Stage 1–2 plateau on CPU
  *and* horizontal scaling is ruled out. Decided, not assumed.

## Success criteria (unchanged, plus honesty)
Knee moves C≈2000 → **C≈4000+**, CPU/turn ~10 ms → **≤4 ms**, threads/session
~3 → **≤2**, with durable transcript, resume, host streaming, and the debug
controller all intact (`bb test` + streaming smoke). **And** report the fair
4th-arm number so we know how much of the gain is engine vs. I/O.
