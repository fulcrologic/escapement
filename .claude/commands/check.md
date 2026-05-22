---
description: Run the Escapement Check pre-merge playbook (4 gates, subagent-isolated)
---

Run **the Escapement Check** against the current change set.

The playbook is authoritative — read it in full and follow it exactly:

- Playbook: @ai/escapement-check.md
- Project rules it depends on: @CLAUDE.md
- Changelog target (root, prepend): `CHANGELOG.md`
- Reviewer evidence target (gitignored): `ai/scratch/collabnotes.md`

You are the **orchestrator**. Per the playbook's "Execution model":

1. Establish scope: the total delta vs `main` (cumulative committed
   `git merge-base HEAD main`..HEAD ∪ uncommitted ∪ untracked `src/**`).
   Write the one-sentence goal.
2. **Establish the test baseline yourself, upfront, in an isolated
   worktree** — never inside a subagent, never by mutating the live tree
   (see the playbook's "Establishing the test baseline"):
   `git worktree add --detach /tmp/escapement-check-baseline <merge-base>`,
   run `bb test` + `bb sanity` inside it, capture the numbers, then
   `git worktree remove --force` it. This is a relay, not a judgment — it
   exists so Gate 1 never has to `git stash`/`checkout` (which would race
   the parallel gates editing the tree and orphan stashes). Hand the
   captured baseline result to the Gate 1 subagent as data.
3. Spawn **separate fresh-context subagents**, one per concern, each given
   only the scope + the playbook + its single job. Do **not** perform any
   gate yourself:
   - Gate 1 (coverage & `bb test`/`bb sanity`) — fresh subagent; give it
     the baseline result from step 2 and tell it to compare read-only
     (no stash/checkout/reset).
   - Gate 3 (subjective review) — fresh subagent that did **not** author
     the code; a self-review is an automatic Gate 3 FAIL.
   - Gate 2 (CHANGELOG entry) — fresh subagent, diff only.
   - Gate 4 (branch/commit/PR proposal) — may reuse the Gate 2 agent.
4. Spawn a **collator** subagent that transcribes each concern's returned
   result verbatim into `ai/scratch/collabnotes.md` (create `ai/scratch/`),
   appends the Result block, and never re-judges or softens findings.
5. Report the OVERALL verdict (MERGEABLE / CONDITIONAL / BLOCKED) and, if
   not MERGEABLE, the precise remediation or named external step.

Do **not** commit, push, or run `/commit`. This command only produces
evidence. $ARGUMENTS
