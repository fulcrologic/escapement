# The Escapement Check

A pre-merge playbook. In a clock, the **escapement** releases energy one
controlled tick at a time — nothing advances until the gate lets it. This is
that gate for the repo: **no branch merges until every section below has a
recorded, passing result.**

**Scope of "what changed" — the total delta vs `main`, not the latest
commit.** A branch may have many "checkpoint" commits; the reviewer cares
about the *cumulative* effect, so always diff against the merge-base with
`main`, never just `HEAD~1` or the working tree. Cover all three of:

- cumulative committed work (collapses all checkpoint commits into one
  delta): `git diff $(git merge-base HEAD main)..HEAD --stat`
- uncommitted tracked: `git diff HEAD --stat`
- **untracked** (often the bulk of new namespaces): `git status --porcelain`,
  then read every untracked `src/**` file.

The change set is the **union** of all three, evaluated as a single delta
against `main`. Individual intermediate commits are irrelevant — only "what
does `main` look like before vs. after this merges."

Edge cases: if the current branch *is* `main` (no feature branch), the
merge-base is HEAD and the committed-diff is empty — expected; the staged +
unstaged + untracked set *is* the change set. Never conclude "nothing
changed" from an empty `git diff` alone; always reconcile against
`git status --porcelain`.

**State the goal first.** Before any gate, write one sentence:
"This branch exists to ____." Gates 2 and 3 are judged against it.

> The reviewer's job is to confirm these four gates ran and passed. The
> evidence lives in `CHANGELOG.md` (repo root) and `ai/scratch/collabnotes.md`
> (gitignored — local reviewer evidence, not committed).

---

## Execution model — isolate separable concerns in fresh subagents

A single agent that wrote (or just read) the code is a biased judge of it
and accumulates context that contaminates later steps. So the work is split
into **independent concerns, each run in its own subagent with a clean
context window**. A concern's subagent gets only: the diff/scope, this
playbook, and its one job. It must not see another concern's reasoning.

| Concern | Run in | Gets | Produces |
|---|---|---|---|
| **Coverage & tests** (Gate 1) | fresh subagent | scope + repo | the coverage table, `bb test`/`bb sanity` output, baseline-attribution proof |
| **Code review** (Gate 3) | fresh subagent, **no authoring context** — must not be the agent that wrote the code | the raw diff only | the subjective sign-off / debt findings |
| **Changelog summary** (Gate 2) | fresh subagent | the diff only | the `CHANGELOG.md` entry |
| **Proposal** (Gate 4) | may reuse the Gate 2 agent (same "describe the change" framing) | the diff + CHANGELOG entry | branch/commit/PR draft |
| **Assembling `ai/scratch/collabnotes.md`** | a separate collator agent | each concern's returned result verbatim | the merged notes + Result block |

Rules:
- The orchestrator **never performs a concern itself** — it only spawns
  subagents and collates their returned results. This keeps each judgment
  uncontaminated and the orchestrator's context small. **The sole
  exception is Gate 1.5's credential pause**: subagents cannot prompt the
  user for secrets, so the orchestrator (and only it) runs that
  find-or-ask interaction, then hands the live-run result back into the
  Gate 1 concern. It still never *judges* — it relays.
- Gate 3's reviewer being a fresh, non-authoring subagent is **mandatory**,
  not optional. A self-review by the code's author is an automatic Gate 3
  FAIL regardless of its conclusion.
- The collator transcribes results; it does not re-judge or soften them.
- If two concerns disagree on a fact (e.g. coverage vs. review), surface
  both in the notes — do not reconcile silently.

---

## Gate 1 — Everything that changed is tested

1. Enumerate changed source units using the full scope above. Focus on
   `src/**`. Granularity: **one row per logical unit** — a public fn or a
   tight cluster of related private helpers, not per-file (hides gaps) and
   not per-trivial-defn (noise).
2. Run **`bb test`** and **`bb sanity`**. Capture the numeric summary.
3. **If the suite fails or won't load**, attribute it before judging:
   `git stash` (or check out clean `HEAD`/`main`) and re-run. If it fails
   *identically* on the untouched baseline, the failure is **pre-existing
   and branch-unrelated** — record the proof (file:line, both runs) and do
   not count it against the branch. If the branch introduces or worsens any
   failure, Gate 1 FAILs.
4. Build the table. Each changed unit gets exactly one status:

   | Changed unit | Test file / deftest | Status |
   |---|---|---|
   | `src/.../foo.clj` `bar-fn` | `test/.../foo_test.clj` `bar-fn-test` | `covered` |

   Allowed statuses:
   - `covered` — a test exercises it and ran green.
   - `blocked: <reason>` — a correct test exists but **cannot execute in
     this environment** (e.g. JVM/jline-only path under SCI). Reviewer must
     run it elsewhere or eyeball; note which.
   - `credential-gated: <what credential, where it likely lives>` — the
     code **can** be exercised end-to-end, but only with a secret the
     harness does not have: an API key, a paid subscription/seat (OpenAI,
     Anthropic, opencode, Ollama Cloud, …), or an OAuth session owned by an
     existing coding agent on this machine. This is **not** `untestable` —
     it is testable, just not by the agent alone. It triggers the
     pause-and-ask procedure below; it must never be silently waved through
     as `untestable`.
   - `untestable: <reason>` — **inherently** cannot be unit-tested: UX/TUI
     feel, prompt wording, subjective LLM quality, pure config/doc. "Hard
     to test" is **not** valid, and "needs a key/subscription" is
     `credential-gated`, **not** `untestable` — only genuine, credential-
     independent inability qualifies here.

### Gate 1.5 — credential-gated paths: pause and ask the user

Any unit marked `credential-gated:` does **not** get to coast through on
its label. Before Gate 1 can reach a verdict, the orchestrator must
**stop and involve the user** — this is the one place the orchestrator
talks to the user directly, because subagents cannot prompt for secrets.
For each distinct credential:

1. **Look before asking.** Check whether the secret is already reachable:
   the relevant env var (`OPENAI_API_KEY`, etc.), and the config of an
   existing coding agent on this machine that the user already pays for
   (e.g. `~/.config/opencode/`, `~/.ollama/`, `~/.config/`-style agent
   credentials). If a usable credential is found, use it for the live run
   and skip the prompt.
2. **Otherwise pause and ask the user**, presenting concretely:
   - exactly which path/feature is unverified and why (which provider /
     subscription it needs);
   - the options: (a) paste the key/token for **this session only**, (b)
     point to a file/agent where it already lives so the run can read it,
     or (c) decline — accept the path goes unverified this merge.
3. **If a credential is provided or found**, actually execute the
   credential-gated path end-to-end, capture the result, and the unit
   becomes `covered` (record: "covered via user-supplied credential, live
   run <result>"). The secret is used in-session only — **never** echoed
   into notes/CHANGELOG, logged, committed, or persisted to the repo.
4. **If the user declines (option c)**, the unit stays `credential-gated:`
   and Gate 1 is **CONDITIONAL** on that named live run — it is *not*
   PASS, and it is *not* downgraded to `untestable`.

The collator records, per credential: whether it was found/supplied/
declined, and for supplied/found ones the live-run outcome (pass/fail) —
but not the secret itself. A credential-gated path that the user supplied
a key for and which then **fails** the live run is a Gate 1 **FAIL**.

**Gate 1 verdict:**
- **PASS** — `bb sanity` green, no branch-introduced test failures, and
  every unit is `covered` / `untestable: …` (including any
  `credential-gated:` unit that was verified live via a found/supplied
  credential).
- **CONDITIONAL** — as PASS, but ≥1 unit is `blocked: …`, **or** ≥1
  `credential-gated:` unit the user declined to supply a credential for;
  mergeable only after the named external/live run is performed and
  reported.
- **FAIL** — branch introduces/worsens a failure; a supplied-credential
  live run fails; or a unit is silently uncovered with no
  `untestable`/`blocked`/`credential-gated` justification.

Record the table, the numeric summary, and any baseline-attribution proof
in `ai/scratch/collabnotes.md`.

## Gate 2 — Human-readable change summary → CHANGELOG.md

Write a summary a reviewer can read in under a minute and know exactly what
functionality was **added / changed / removed**. No implementation
narration — describe behavior and intent.

Prepend a new entry to `CHANGELOG.md` at the repo root (create the file if
absent, newest entry on top):

```
## [unreleased] — <branch-name> — <date>

### Added
- …

### Changed
- …

### Removed
- …

### Notes
- Anything untestable/subjective from Gate 1, called out plainly.
```

Omit empty sections. Keep bullets concrete ("`escapement run` now resolves
model aliases from preferences" — not "refactored model code").

**Gate 2 passes** when the entry is prepended and a non-author could
correctly describe the branch's user-visible effect from it alone.

## Gate 3 — Subjective code-review sign-off

**Must run in a fresh subagent that did not author the code** (see
Execution model). Read the actual diff as a reviewer, not the author.
Confirm in writing (returned to the collator for `collabnotes.md`):

- The repo is in a **better** state than before this branch.
- **No new tech debt**: no dead code, no commented-out blocks, no TODOs left
  without an issue/ticket, no copy-paste that should be a function, no
  SCI-incompatible JVM-only paths in `src/` (see CLAUDE.md house rules).
- Naming, comment density, and idioms match surrounding code.
- The change is the *smallest* one that achieves the **stated goal** (from
  the top of this run) — scope creep beyond it is debt.

If any item fails, **fix it before merge** (or, if out of scope, log it
explicitly as accepted debt with a reason — silent debt is a gate failure).

**Gate 3 passes** when every item is affirmed or remediated, written down
with a one-line justification each.

## Gate 4 — Commit, branch, and PR proposal

Record in `ai/scratch/collabnotes.md`:

- **Branch name** — `kebab-case`, scope-prefixed (`feat/`, `fix/`,
  `chore/`, `docs/`), describing the change not the ticket.
- **Commit message** — imperative subject ≤72 chars, body explaining *why*.
  Follow repo commit conventions, **except**: never add a `Co-Authored-By`
  trailer (session rule overrides repo history, no exceptions).
- **PR/MR summary** — title + body: what changed, why, how it was verified
  (link Gate 1 result), and explicit list of anything untestable/subjective
  the reviewer must eyeball manually.

**Gate 4 passes** when all three are written and internally consistent with
the CHANGELOG entry.

---

## Result block (paste into ai/scratch/collabnotes.md)

```
# Escapement Check — <branch> — <date>
Goal:                 <one sentence: this branch exists to ___>
Gate 1 (tested):      PASS / CONDITIONAL / FAIL  — bb test: <n tests, n assertions>; bb sanity: <ok>
Gate 2 (changelog):   PASS / FAIL                — entry prepended
Gate 3 (review):      PASS / FAIL                — repo better, no new debt
Gate 4 (proposal):    PASS / FAIL                — branch/commit/PR drafted
OVERALL:              MERGEABLE / CONDITIONAL / BLOCKED
```

- **MERGEABLE** — all gates PASS.
- **CONDITIONAL: <named external step>** — code is sound on its merits but
  one gate is `CONDITIONAL` (e.g. `blocked:` tests must run in a
  JVM/jline environment, or a `credential-gated:` path the user declined
  to supply a key for must be run live with their subscription/API key).
  Merges only after that step is done and recorded.
- **BLOCKED** — any gate FAIL. Remediate and re-run the check.

A branch is **MERGEABLE** only when all four gates are PASS. Any FAIL blocks
the merge until remediated and the check is re-run.
