# Muse / Editor haiku-tournament experiment — session handoff

Everything needed to resume this experiment in a fresh session. Written
2026-05-26.

## 1. The question

In the dynamic haiku tournament, secretly give **one randomly-chosen poet a
"Muse"** and a **different random poet an "Editor"**, keep the judges blind to
this, and measure how it affects who wins.

- **Muse** = one extra LLM call *before* composing that hands the poet a few
  vivid image fragments; those fragments are injected into every haiku draft.
- **Editor** = after the poet drafts all 3 haiku, a critic LLM critiques the
  set, then the poet takes one more turn to rewrite all 3 applying the feedback.
- Every other poet composes unaided. Judges never see role labels.

## 2. Where the code lives

`src/escapement/examples/haiku_tournament_dynamic.clj` — the whole tournament
is one file. The chart var is `agent` (carries `^:multi-session? true`).

Pipeline (parent statechart): planner (plain-text START/ABORT) → composing
(multiplex of N poets) → judging-r1 (M×N judge1 children, each picks 1-of-3 for
one poet) → tally → judging-r2 (M judge2 children rank all finalists) → tally →
host writes `tournament-summary.md`.

### Key pieces added for this experiment
- **Role assignment** — in the parent `:plan/start` transition: picks
  `muse = (rand-int poets)` and a distinct `feedback = (rand-int (dec poets))`
  bumped past muse; stored as `:muse-poet` / `:feedback-poet`.
- **Per-poet role passed via multiplex** — the `:composing` `mo/child-params`
  sets `:role` to `:muse` / `:feedback` / `nil` per `idx`.
- **poet-chart** (the child) is a role-driven loop:
  `:role-route` → (`:musing` if muse) → `:composing` (draft loop over `:hidx`
  1..3, accumulates `:work`) → `:composing-route` → (if feedback poet:
  save `:drafts`, `:critique` → `:revising` → `:report`; else `:report`).
  `:report` sends `mux/reply :poet-result {:idx :haikus}` with the final
  `:work` (revised set for editor poet, drafts for everyone else).
- **Host summary** gets a `## Experiment` section and is told which poet had a
  Muse / Editor (judges are NOT — explicitly labeled in the host prompt only).

### Prompts (functions in the file)
- `muse-system` — "Muse whispering to Poet #n … 3-5 vivid images / sensory
  fragments / unexpected angles … Do NOT write a haiku yourself."
- `critic-system` — "trusted editor … you will see the poet's three draft
  haiku … brief concrete feedback … Do NOT rewrite them; the poet will revise."
  (Free-form — see bug #3 below.)
- `poet-draft-msg` — injects the muse text ("Your muse whispers: …") when present.
- `poet-revise-msg` — shows all 3 drafts (`format-numbered-haikus :drafts`) +
  the editor feedback, asks to rewrite all three, blank-line separated.
- `parse-haiku-set` — parses 3 haiku back out of the single revise response.

## 3. How to run

Models / keys (do NOT hardcode secrets; values are in `workingcontext.md`
under "Dataico haiku tournament — CLI invocations"):
- **ZAI (glm-5.1)** — `ZAI_API_KEY` (auto-detected backend `zai`, Anthropic-wire).
- **Ollama Cloud (kimi-k2.6)** — `OLLAMA_API_KEY` (value in `~/webapps/rakibadesigns/.env`).

Shared prompt (the "Dataico" theme):
```
user-input="Run a haiku tournament with 10 poets and 10 judges. Theme: A mobile PoS application for Brazil MEIs called Dataico near MVP status."
```

Single run, glm-5.1:
```bash
ZAI_API_KEY=<key> bb -m escapement.cli run \
  escapement.examples.haiku-tournament-dynamic/agent \
  --no-tui --model glm-5.1 --session t-glm51-foo --max-frozen-cycles 24000 \
  --param 'user-input="Run a haiku tournament with 10 poets and 10 judges. Theme: A mobile PoS application for Brazil MEIs called Dataico near MVP status."'
```
Single run, kimi-k2.6: swap `OLLAMA_API_KEY=<key>` and `--model kimi-k2.6`.

Per-run output lives at `.escapement/<session>/`:
- `artifacts/tournament-summary.md` — host's write-up (incl. `## Experiment`).
- `checkpoints/session/<session>.edn` — structured `:muse-poet`,
  `:feedback-poet`, `:haikus`, `:finalists`, `:result`, `:judge2-votes`.
- `transcript.jsonl` — every event across all sessions (the only place the
  muse text / critic feedback / revise prompts are recorded).

A 10-run sequential batch is just a bash `for i in $(seq 1 10)` loop running the
single command with `--session t-<tag>-r${i}` (glm-5.1 ≈ 2 min/run; do them
sequentially — they're network-bound, not CPU-bound, so parallel doesn't help
and just complicates).

## 4. CRITICAL gotchas / bugs (all found & fixed this session)

1. **`child-safety-ms` must stay ≤ 60s.** Each child schedules a delayed
   `:child/safety-stop`. Those timers are **not cancelled when a child session
   finalizes**, so a large value makes the *process hang for that full duration
   after the tournament already finished* (the multi-session pump waits on the
   orphaned timers). Bumping it to 30 min caused ~30-min post-completion hangs.
   It is back at `60000`. → Engine-level fix worth doing: cancel pending
   `:child/safety-stop` on child finalize.

2. **Revise-prompt empty-draft bug (FIXED).** `poet-revise-msg` used
   `(:draft data)` which was never assigned (only `:drafts` vector exists), so
   in the *first* batch the editor poet revised **blind** — its prompt had an
   empty slot where its draft should be, and feedback referencing drafts it
   couldn't see. Fixed to show the whole draft set.

3. **Critic "ONE haiku" mismatch (FIXED).** Old `critic-system` said "you will
   see ONE draft haiku … feedback on *it*", but the user message showed all
   three → the model cherry-picked one haiku to critique. Rewritten to free-form
   feedback on all three; revise is now a single turn over the whole set.

4. **Occasional stall at `[:run :tallying-r2]`** — a couple of runs
   (`t-glm51-r3`, `t-glm51c-r5`) exited before writing `tournament-summary.md`
   (likely hit `--max-frozen-cycles`). Their *judging tally still survives in
   the checkpoint* (`:result`/`:judge2-votes`), so results aren't lost — always
   compute winners from the checkpoint, not by parsing the prose summary (the
   host writes winners free-form, including ties).

5. Other timeout edits (http-timeout 60s→300s, max-conversation-duration bumps)
   were temporary kimi hacks and have all been **reverted**. Current working
   diff is just the muse/editor feature + the critique redesign.

## 5. Runs completed & where the data is

All artifacts copied to **`~/haikutournamentartifacts/<session>/`**
(`artifacts/tournament-summary.md` + `checkpoint.edn` each). 22 sessions:

- **Broken-critique** (empty-draft bug present): `t-glm51-exp` (solo),
  `t-glm51-r1..r10`, `t-kimi26-exp`.
- **Fixed-critique** (drafts shown, free-form critic, single revise turn):
  `t-glm51c-r1..r10`.

NOTE: only summaries + checkpoints were copied — **transcripts were NOT**
(they're large; kimi's is ~10 MB). The transcripts under `.escapement/` are the
only artifact that records the muse text, critic feedback, and revise prompts.
If you need those preserved, copy `.escapement/<session>/transcript.jsonl`.

## 6. Results

Winner classification: muse / editor / unaided, by mapping the checkpoint
`:result` (winner finalist idx or `:tie [...]`) through `:finalists` to a
`:poet-idx`, then comparing to `:muse-poet` / `:feedback-poet`. Ties count for
each role present.

| Condition | Muse wins | Editor wins | Unaided wins |
|---|---|---|---|
| **Broken critique** (12 runs: solo+10+kimi) | **12/12 (100%)** | **0/12** | 2/12 (ties only) |
| **Fixed critique** (10 runs, t-glm51c) | **9/10 (90%)** | **1/10** | 3/10 |

Headline:
- The **Muse dominates** in both conditions. With 10 poets, a designated poet
  should win ~10% by chance; Muse won 12/12 then 9/10 — a huge effect.
- Fixing the Editor moved it from **0 → 1** win (one 3-way tie, run
  `t-glm51c-r7`); still **zero outright wins**. So the broken result was not
  masking a strong Editor — the Editor is genuinely the weaker intervention.

Mechanism (from host summaries + transcripts): the Muse hands over ready-made,
concrete, multi-sensory Brazilian imagery (calloused/açaí thumb, green PIX/QR
glow, São Paulo dusk, dissolving paper receipts) that the poet just compresses
into 5-7-5 — and because all 3 haiku share that image bank, the set reads as a
unified, distinctive voice, which is what the round-2 "pick the single best"
vote rewards. The Editor produces competent, *corrected* haiku that survive
round-1 consensus but lack the surprise that wins round 2.

## 7. Verification done (all PASS)

- **Judges are blind**: judge1/judge2 system prompts and child-params contain no
  role info; grep of judge prompts for muse|editor|critic|role → 0 matches.
  Roles appear only in the post-judging host prompt.
- **Critiqued poet's haiku reach judges only after revision**: enforced by chart
  ordering — `:poet-result` is sent only at `:report` (after revise), and
  judging starts only on `:done.invoke.poets`. Empirically, every judge LLM
  request starts after the last poet/critic request.
- **Every critiqued poet received its critique** (all 22 runs): in each run the
  `critic` ran in the editor poet's own session (`llm/start` carries
  `session-id "multiplex.poets.<feedback-poet>"`), produced non-empty feedback,
  and that feedback appears in a non-empty revise prompt. (In the broken batch
  the *critique* was received; only the *draft* was missing.)

## 8. Important caveat on interpretation

The "Muse" is not gentle inspiration — it is a **second full LLM creative pass**
that does the hard part (inventing surprising concrete imagery) and hands the
poet a copyable palette. So the experiment conflates "inspiration" with "extra
divergent creative compute + a richer prompt." The clean takeaway is:
**front-loading concrete imagery from a second model pass dominates; after-the-
fact editorial polish does not** — even when the revision loop works correctly.

n is small (≤12 per condition), single model family, different random
assignments across batches — directionally strong, not statistically firm.

## 9. Open / next-step ideas

- **Engine fix**: cancel pending `:child/safety-stop` timers on child finalize
  so larger safety windows don't hang the process (needed before kimi/ollama
  10×10 is practical).
- **Concurrency cap**: ollama-cloud limits 3 concurrent requests; the judging-r1
  fan-out is M×N (100 for 10×10). No cap is built — a global N-permit gate in
  the LLM layer would make kimi 10×10 viable within the ≤60s safety rule.
- **Cleaner Muse test**: restrict the muse to abstract moods/angles only (forbid
  concrete phrasings the poet can copy), OR give unaided poets a matched
  "brainstorm then write" pass, to separate "inspiration" from "extra compute."
- **Re-run kimi with the fixed Editor** for a cross-model check.
- Bigger n per condition for statistical confidence.
