---
name: followup
description: Resolve review feedback on a GitHub pull request fully. Resolves the PR from a link or number (or asks the user to pick from open PRs), reads the review comments on the current head, implements every requested change on the PR branch, and commits and pushes with its own discretion. Use when the user asks to follow up on a PR review, address or resolve PR feedback/comments, or work through review comments on a pull request.
allowed-tools: Bash(git:*, gh:*, ./gradlew:*, npm:*, powershell:*)
license: MIT
compatibility: Requires gh CLI (authenticated) and Git.
metadata:
  author: cline
  version: "1.0"
---

# Follow up on PR Review Feedback

Take a PR (link or number), find the review/feedback comments on it, and resolve them
fully on the PR's head branch â€” the agent commits and pushes on its own discretion.

## Overview

1. **Resolve the PR** â€” from the given link/number, or pick from the open PRs.
2. **Move to the PR head branch** â€” fixes land where they are being reviewed.
3. **Read the feedback** â€” unresolved review threads on the current head.
4. **Gate on emptiness** â€” nothing on head? Reject ("there's no comment in the PR
   thread") â€” unless the user explicitly pointed at a thread that is not on the head,
   in which case scour the full history and let them pick.
5. **Resolve** â€” implement each piece of feedback to completion.
6. **Verify, commit, push** â€” autonomously, in coherent increments.
7. **Report** â€” feedback item â†’ action â†’ commit SHA.

### 1. Resolve the PR

- If the user gave a PR number (`#87`, `87`) or URL
  (`https://github.com/<owner>/<repo>/pull/<n>`), use it.
- Otherwise run:
  ```bash
  gh pr list --state open --json number,title,headRefName,baseRefName
  ```
  and present the active PRs (number, title, branch) for the user to choose which
  one they meant. **Do not guess or auto-select a PR.**
- Confirm the PR and read its metadata:
  ```bash
  gh pr view <number> --json number,title,state,headRefName,baseRefName,reviewDecision,url
  ```
  If the PR is not `OPEN`, report that and stop.

### 2. Move to the PR head branch

Check out the head branch so the fixes land on the branch being reviewed. If that
branch is already checked out in a separate worktree, switch to that worktree
directory instead:

```bash
git fetch origin
git checkout <head-branch>     # or: cd <path-to-worktree>
git pull --ff-only             # align with the remote PR head
```

If the local branch has diverged and cannot fast-forward, stop and ask â€” never
rewrite or reset it on your own.

### 3. Read the review feedback (on-head scope first)

Get the base repo owner/name (needed for the GraphQL query):

```bash
gh repo view --json nameWithOwner -q .nameWithOwner
```

Dump every review thread with its resolution and freshness flags:

```bash
gh api graphql -f query='
  query($owner: String!, $repo: String!, $number: Int!) {
    repository(owner: $owner, name: $repo) {
      pullRequest(number: $number) {
        reviewThreads(first: 100) {
          nodes {
            id
            isResolved
            isOutdated
            comments(first: 100) {
              nodes { author { login } body path line createdAt }
            }
          }
        }
      }
    }
  }' -f owner=<owner> -f repo=<repo> -F number=<n>
```

Also cross-check review bodies and top-level conversation comments:

```bash
gh pr view <number> --json reviews
gh pr view <number> --comments
```

**Feedback in scope (default):**

- Review threads with `isResolved: false` on the current head â€” the outstanding
  review comments.
- Review bodies (e.g. `CHANGES_REQUESTED`) and conversation comments that ask for
  concrete changes.
- Ignore thanks/acknowledgement-only comments â€” they are not actionable feedback.

### 4. Gate: no comments on the head

If step 3 yields no actionable feedback, **reject the prompt**: report that there's no
comment in the PR thread (include PR number, title, URL) and stop. Make no changes.

**Exception â€” the user explicitly said the feedback is in a thread that is not on
the current head** (wording like "it's in an old review", "before the last push",
"an outdated thread", "the comment is gone from the current diff"). Only then scour
the full history:

1. Use the step-3 thread dump, but include **all** threads regardless of
   `isResolved`/`isOutdated` â€” also resolved ones and ones anchored to superseded
   commits.
2. Present every feedback comment as a numbered list: author, date, `path:line`
   (when present), outdated/head marker, and the first lines of the comment body.
3. **Let the user pick which one they meant. Never auto-select.**

Continue to step 5 with the picked comment(s) only.

### 5. Resolve the feedback fully

For each in-scope or picked comment:

1. Read the comment, the code it references (`path`, `line`), and the surrounding
   context. When behaviour is in question, read the normative spec under
   `openspec/specs/` first â€” specs win over implementation.
2. Implement the requested change **completely**. No partial fixes, no leftover
   `TODO`s, no "left for later". If the comment asks for a test, add the test.
3. Keep the change in the scope of the feedback â€” no drive-by refactors or
   unrelated edits.
4. Verify before committing:
   ```bash
   ./gradlew :gamification:test :app:testDebugUnitTest   # when the Kotlin side changed
   ./gradlew assembleDebug                               # compilation sanity, when warranted
   npm --prefix functions run build                      # when functions/ changed
   ```

If a comment demands behaviour that conflicts with a normative spec in
`openspec/specs/`, stop and ask â€” spec-behaviour changes go through an OpenSpec
change (`openspec-propose`), never a silent spec edit.

### 6. Commit and push (agent discretion)

Exercise commit/push autonomy: the agent decides, without asking, how to group the
fixes into coherent commits and pushes them when a coherent unit is verified.

1. Stage only the files that belong to the addressed feedback:
   ```bash
   git add <files-for-one-feedback-item>
   git commit -m "<type>: <lowercase imperative summary>"
   git push
   ```
2. One commit per feedback item, or one per coherent group when several comments
   concern the same issue; every commit must stand alone as the answer to review
   feedback. Nothing unrelated gets staged.
3. Use the repo's conventional-commit prefixes â€” `feat`, `fix`, `refactor`, `test`,
   `docs`, `chore` â€” lowercase imperative, explaining *why*:
   ```
   fix: wrap session writes in one room transaction (review)
   test: cover family shared last-played mapping (review)
   ```
4. Push normally to the PR head branch. Never force-push, never amend pushed
   commits, never rewrite history, never touch `master`. If a push is rejected
   because the remote moved, stop and ask.

Pushed commits appear on the PR automatically â€” nothing else needs to be attached.

### 7. Reply and report

1. Reply to each addressed review thread with the commit that resolved it (thread
   `id`s come from step 3):
   ```bash
   gh api graphql -f query='
     mutation($threadId: ID!, $body: String!) {
       addPullRequestReviewThreadReply(
         input: {pullRequestReviewThreadId: $threadId, body: $body}
       ) { comment { url } }
     }' -f threadId=<id> -f body='Addressed in <short-sha> â€” <one-line summary>'
   ```
   Leave threads **unresolved**: in this repo a thread is resolved by the reviewer
   after re-review. Only resolve (via the `resolveReviewThread` mutation) when the
   user explicitly asks for it.
2. Final report:
   ```
   ## Follow-up complete

   **PR:** #<n> â€” <title> (<url>)

   | Feedback | Action | Commit |
   |---|---|---|
   | "<comment summary>" | what changed and why | <sha> |

   **Verification:** <tests/build commands and results>
   **Pushed:** âœ“ / âœ— (reason)
   ```

## Guardrails

- Never guess which PR â€” ambiguous prompts always get the open-PR picker.
- Never auto-select a historical/outdated comment; the user picks, and only after
  they explicitly directed the skill off the current head.
- No actionable comments on head and no explicit off-head direction â†’ reject with
  "there's no comment in the PR thread". No code changes, no commits.
- Stay on the PR head branch; never commit or push to `master` or another branch.
- Never force-push, amend pushed commits, or rewrite history.
- Never stage files unrelated to addressed feedback; never commit secrets or build
  artifacts (respect `.gitignore`).
- Never push code that fails verification; commit/push autonomy covers *how* and
  *when* to push, never pushing broken work. If verification cannot be made green,
  report and stop.
- Normative specs win: when feedback conflicts with `openspec/specs/`, ask before
  implementing.
- Report clearly what was addressed, how it was verified, and what was pushed.
