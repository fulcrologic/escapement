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
| **Coverage & tests** (Gate 1) | fresh subagent | scope + repo + **the orchestrator's baseline result** (see below) | the coverage table, `bb test`/`bb sanity` output, baseline comparison |
| **Code review** (Gate 3) | fresh subagent, **no authoring context** — must not be the agent that wrote the code | the raw diff only | the subjective sign-off / debt findings |
| **Changelog + Docs sync** (Gate 2) | fresh subagent | the diff + `Guide.adoc`'s TOC and the specific sections covering changed areas (orchestrator extracts these, so the subagent does not grep the ~4200-line guide blindly) + **if the diff touches the authoring-primitive surface (see Gate 2), the full text of `.claude/skills/writing-escapement-statecharts/SKILL.md` and CLAUDE.md's "Statecharts caveats" section** (both small — passed whole) | the `CHANGELOG.md` entry **and** the `Guide.adoc` edits **and** any skill/CLAUDE.md edits (or a written "no doc change needed" justification per surface) |
| **Proposal** (Gate 4) | may reuse the Gate 2 agent (same "describe the change" framing) | the diff + CHANGELOG entry | branch/commit/PR draft |
| **Assembling `ai/scratch/collabnotes.md`** | a separate collator agent | each concern's returned result verbatim | the merged notes + Result block |

Rules:
- The orchestrator **never performs a concern itself** — it only spawns
  subagents and collates their returned results. This keeps each judgment
  uncontaminated and the orchestrator's context small. **Two carve-outs**,
  both relaying not judging — the orchestrator does what a subagent
  structurally cannot do safely, then hands the result down:
  - **Gate 1.5's credential pause**: subagents cannot prompt the user for
    secrets, so the orchestrator (and only it) runs that find-or-ask
    interaction, then hands the live-run result back into Gate 1.
  - **The test baseline** (see "Establishing the test baseline" below):
    a subagent must never mutate the shared working tree (stash/checkout)
    to get a baseline, especially while other gates edit it in parallel.
    The orchestrator establishes the baseline upfront in an isolated
    worktree and hands the numbers to Gate 1, which only compares. It
    still never *judges* — it relays a fact.
- Gate 3's reviewer being a fresh, non-authoring subagent is **mandatory**,
  not optional. A self-review by the code's author is an automatic Gate 3
  FAIL regardless of its conclusion.
- The collator transcribes results; it does not re-judge or soften them.
- If two concerns disagree on a fact (e.g. coverage vs. review), surface
  both in the notes — do not reconcile silently.

### Establishing the test baseline (orchestrator, upfront)

A red `bb test` should not block the branch if `main` was already red — so
Gate 1 needs a baseline. But "is the merge-base green?" is a fixed
environmental fact, knowable *before any gate runs* and independent of
anything Gate 1 produces. The orchestrator establishes it **once, upfront,
in an isolated worktree** — never by mutating the live tree, and never
inside a subagent (a stash/checkout there races against Gate 2's
CHANGELOG/`Guide.adoc` edits on the same shared tree, and orphans stashes).

Right after establishing scope, before spawning any gate:

```
git worktree add --detach /tmp/escapement-check-baseline <merge-base>
( cd /tmp/escapement-check-baseline && bb test ; bb sanity )   # capture numbers
git worktree remove --force /tmp/escapement-check-baseline
```

(`<merge-base>` = `git merge-base HEAD main`. A worktree shares the object
store but has its own working dir + index, so the live tree is untouched —
nothing to stash. Run it serially before spawning gates so two concurrent
`bb test` runs don't collide on `.escapement/` transcripts or REPL ports.)

Record the baseline `bb test` / `bb sanity` result and hand it to the
Gate 1 subagent as data. If the baseline cannot be built (e.g. worktree
fails), say so and let Gate 1 fall back to comparing against a plain
description of `main`'s known state — but it still must not stash/checkout.

---

## Gate 1 — Everything that changed is tested

1. Enumerate changed source units using the full scope above. Focus on
   `src/**`. Granularity: **one row per logical unit** — a public fn or a
   tight cluster of related private helpers, not per-file (hides gaps) and
   not per-trivial-defn (noise).
2. Run **`bb test`** and **`bb sanity`**. Capture the numeric summary.
3. **If the suite fails or won't load**, attribute it before judging by
   comparing against the **baseline result the orchestrator supplied**
   (established upfront in an isolated worktree — see "Establishing the
   test baseline"). If a failure is present *identically* in the baseline,
   it is **pre-existing and branch-unrelated** — record the proof (file:line,
   both runs: your current-tree run and the supplied baseline) and do not
   count it against the branch. If the branch introduces or worsens any
   failure, Gate 1 FAILs. **Do not `git stash`, `git checkout`, reset, or
   otherwise mutate the working tree** — you run read-only against the
   current tree; baseline comparison is data handed to you, not something
   you reproduce by switching the tree. If no baseline was supplied, say so
   and judge against `main`'s described state, still without mutating the tree.
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

## Gate 2 — Human-readable change summary → CHANGELOG.md + docs sync

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

### Guide.adoc must stay in sync

`Guide.adoc` (repo root) is the canonical user-facing manual — it documents
how to author, run, and extend agents. Unlike `CHANGELOG.md` (a running
ledger), the guide must reflect the **current** state of the code after this
branch merges. Any branch that changes user-visible behavior, public API,
CLI surface, config keys, or the authoring model **must** update the
relevant `Guide.adoc` sections in the same merge — a stale guide is a Gate 2
failure.

To keep this cheap and focused, the orchestrator (not the subagent) first
gathers context so the subagent does minimal searching:

1. Extract `Guide.adoc`'s section list (the `==`/`===` headings + their
   line ranges).
2. Map each changed source unit (from Gate 1's scope) to the guide
   section(s) that document it, if any.
3. Hand the Gate 2 subagent: the diff, that mapping, and the full text of
   only the mapped sections (plus the TOC for orientation) — not the whole
   ~4200-line file.

The subagent then either edits those sections so the guide matches
post-merge reality, or, if the change is genuinely invisible at the guide's
level of abstraction (internal refactor, test-only, pure infra), records a
one-line justification: "no Guide.adoc change — <why>". Silent omission of a
needed guide update is a Gate 2 FAIL, exactly like an absent CHANGELOG entry.

### Authoring primitives must stay in sync with the skill + CLAUDE.md

`Guide.adoc` is not the only living doc. Two other surfaces document the
**statechart authoring primitives** and rot the same way when a primitive is
added, renamed, removed, or changes its contract:

- **`.claude/skills/writing-escapement-statecharts/SKILL.md`** — the
  authoring gotchas/cheat-sheet a future agent loads before writing a chart.
- **CLAUDE.md** — the "Statecharts caveats" and "Test conventions" sections.

**Primitive surface = the diff touches any of:**
- `src/escapement/chart/helpers.cljc` — the `h/` authoring API (`tell-llm`,
  `tell-other-llm`, `human-input`, `with-llm-questions`, `llm-conversation`,
  `forward-llm-output`, `render-template`, …): a new/removed/renamed public
  fn, or a changed arglist/contract.
- `params-fn` handling / accepted keys (`:model(s)`, `:needs`, `:auto-cache?`,
  `:temperature`, `:thinking`, `:verdict-schema`, `:max-tokens`, …) in
  `invocation/llm_conversation.clj`, `llm.clj`, `chart/service.cljc`.
- event→tool encoding, reserved tool names (`submit_verdict`), or
  `:allowed-events`/`:real-tools`/`:chart-tools` semantics.
- transition-type / `parallel`-region / `send-after` / event-naming rules
  the skill calls out as traps.

When the diff intersects that surface, the orchestrator hands the Gate 2
subagent the **whole** `SKILL.md` (~50 lines) and the relevant CLAUDE.md
sections (both small — no extraction needed). The subagent edits each to
match post-merge reality, or records a one-line per-surface justification
("no SKILL.md change — internal helper, not part of the `h/` authoring API").
A primitive change that lands with the skill or CLAUDE.md left describing the
old behavior is a Gate 2 **FAIL**, identical to a stale Guide. When the diff
does **not** touch the primitive surface, record "primitive surface
untouched — skill/CLAUDE.md sync N/A" and move on.

**Gate 2 passes** when the CHANGELOG entry is prepended; `Guide.adoc`, the
authoring skill, and CLAUDE.md are each either updated to match post-merge
behavior or explicitly justified as needing no change; and a non-author
could correctly describe the branch's user-visible effect from the CHANGELOG
entry alone.

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
Gate 2 (changelog):   PASS / FAIL                — entry prepended; Guide.adoc + skill + CLAUDE.md synced/justified (or "primitive surface untouched")
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
