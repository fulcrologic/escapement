---
description: Commit using the branch/commit proposal recorded by /check in collabnotes.md
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git branch:*), Bash(git checkout:*), Bash(git switch:*), Bash(git add:*), Bash(git commit:*), Read
---

Make the commit using the proposal the Escapement Check already recorded.

Source of truth: @ai/scratch/collabnotes.md

Steps:

1. Read `ai/scratch/collabnotes.md`. Locate the **Result block** and
   **Gate 4** proposal (branch name, commit message, PR summary).
2. **Refuse to proceed** if any of these hold — tell the user to run
   `/check` first instead:
   - `ai/scratch/collabnotes.md` is missing or has no Gate 4 proposal.
   - The Result block OVERALL is `BLOCKED`.
   - The notes are stale: `git status` / `git diff` no longer match the
     change set the notes describe (spot-check changed files). A commit
     must reflect the reviewed state, not drift.
   If OVERALL is `CONDITIONAL`, surface the named external step and ask the
   user to confirm they accept committing before it is satisfied.
3. Show the user the exact branch name and full commit message from the
   notes and get explicit confirmation before mutating the repo.
4. On confirmation:
   - If the proposed branch differs from the current branch and the current
     branch is `main`, create and switch to it
     (`git checkout -b <branch>`). Never commit the feature work directly
     to `main` unless the user explicitly says to.
   - `git add` the reviewed change set (the files the notes cover; do not
     blanket-add unrelated cruft — and never add `ai/scratch/`).
   - `git commit` with the recorded message **verbatim**. Per repo session
     rules: **never** add a `Co-Authored-By` trailer.
5. Do **not** push. Print the resulting `git log -1 --stat` and the PR
   summary from the notes so the user can open the PR. $ARGUMENTS
