---
name: openspec-autoship
description: >-
  Run an OpenSpec change end to end with safe commit and push checkpoints. Use when
  the user invokes /openspec-autoship or requests OpenSpec implementation with commits
  and pushes.
license: MIT
compatibility: Requires openspec CLI and Git.
metadata:
  author: auto-commit-agent
  version: "1.1"
---

# OpenSpec Autoship

One-shot autonomous execution pipeline that combines two existing skills:

- **`openspec-apply-change`** — the task-selection, instruction-reading, and implementation loop
- **`auto-commit-agent`** — the safety gate, staging rules, and conventional commit discipline

and extends both: every coherent checkpoint is committed and, unless
`--no-push` is set, pushed automatically after the safety gate passes. Do not
ask for repeated approval; higher-priority platform controls still apply.

## Autonomy Contract (read first)

This skill exists to remove manual oversight. Follow these rules strictly:

Calling this workflow through its provider-native trigger explicitly authorizes commits
for task-only checkpoints and, unless `--no-push` is set, pushes to the checked-out
branch's configured upstream (or `origin` when the branch has no upstream). Do not ask
for a second approval for normal commits or pushes.

This authorization does not override a higher-priority platform safety or egress
control. If the platform rejects the exact authorized operation, stop and report the
refusal; do not reroute it through another tool, agent, shell, or destination.

1. **Never pause to ask permission to commit.** If the safety gate passes, commit.
2. **Never pause to ask permission to push.** If the safety gate passes, push.
3. **Never pause to ask which branch to use.** Ship to whatever non-detached
   branch is checked out, including `main` and other protected branches. The
   user has explicitly opted in to direct pushes on the current branch.
4. **Never pause to ask "should I push now?"** Push after every coherent
   checkpoint and once more at the end.
5. The ONLY pause conditions are the hard blockers listed in
   [Pause Conditions](#pause-conditions). Everything else runs to completion.

## Input

```
/openspec-autoship [change-name] [--dry-run] [--no-push]
```

- `change-name` (optional): the OpenSpec change to apply. If omitted, infer from
  conversation context or auto-select when exactly one active change exists. If
  truly ambiguous, run `openspec list --json` and ask once — this is the only
  sanctioned early pause.
- `--dry-run`: run the full workflow but stop short of `git commit` / `git push`;
  print the commits and pushes that would have been made.
- `--no-push`: commit locally at checkpoints but skip all pushes.

## Phase 1 — Select the Change

Follow `openspec-apply-change` step 1 exactly:

- If a name is provided, use it.
- Otherwise infer from context, or auto-select if only one active change exists.
- Only if ambiguous: `openspec list --json` and ask the user once.

Always announce: `"Autoshipping change: <name> on branch <branch> — commits and pushes will happen automatically."`

## Phase 2 — Preflight Safety Gate

Run once before the first task, and re-verify cheaply before each checkpoint.

### 1. Repository root

```bash
git rev-parse --show-toplevel
```

If this fails, stop: "Not in a Git repository." (hard blocker)

### 2. Branch check

```bash
git branch --show-current
```

- **Refuse only on detached HEAD** (empty output): hard blocker.
- **Do NOT refuse any named branch.** `main`, `master`, `release/*`, etc. are
  all allowed for this skill; the user opted in. Still announce the branch in
  the plan so it is visible.

### 3. Repository state

```bash
git status --short --branch
```

Note any **pre-existing dirty files that are unrelated to the change**. These
belong to the user — never stage, commit, or push them. If a pre-existing
modification overlaps a file a task must edit, record that file in the report
and stage only the task-specific hunks via explicit paths (or, if hunks are
inseparable, treat it as a hard blocker for that task only).

### 4. Upstream

- If the branch has no upstream, the first push uses `git push -u origin <branch>`. This is autonomous — do not ask.
- If the branch has an upstream, plain `git push` is used.

### 5. Secret scan (before every commit)

Review the to-be-staged diff for secrets and credentials: API keys, tokens,
passwords, private keys, connection strings, `.env` contents. If found in
files not intended to hold them, that task is a hard blocker. Legitimate
fixture/test credentials clearly marked as such in test fixtures are allowed.

## Phase 3 — Apply Loop (openspec-apply-change steps 2–6)

Follow `openspec-apply-change` exactly for the mechanics:

1. `openspec status --change "<name>" --json` — understand the schema.
2. `openspec instructions apply --change "<name>" --json` — get contextFiles,
   progress, task list, `context`, and `operationGuidance`. Handle
   `blocked` / `all_done` states the same way that skill does.
3. Read every context file listed in `contextFiles` before implementing.
4. For each pending task:
   - Announce which task is being worked on.
   - Make the code changes — minimal and scoped to the task.
   - Run available project checks (see Phase 4).
   - Mark the checkbox in the tasks file: `- [ ]` → `- [x]`.
   - Commit AND push the checkpoint (Phase 4).
   - Continue to the next task. **Do not stop between tasks.**

Keep the apply-loop guardrails: only mark `- [x]` when the task's behavior is
fully implemented; never silently narrow or defer specified behavior.

## Phase 4 — Checkpoint Commit + Push

After each completed task (or tightly-coupled task group), run the commit-push
sequence. This replaces `auto-commit-agent`'s "ask when in doubt" with
"proceed when the gate passes".

When this skill runs in OpenCode, delegate the commit and push operations to
the `shipper` subagent after the parent has staged and verified the checkpoint.
Pass the requested mode (`commit-only`, `commit-and-push`, or `push-only`),
current branch, upstream state, and the `$openspec-autoship` authorization
context. For commit modes, also pass the exact staged path allowlist and exact
commit message. The parent owns implementation, task checkboxes, checks,
staging, and scope review; the shipper only verifies staged checkpoints,
commits them, and pushes when requested. Wait for the shipper's commit hash
and push result before continuing. If `shipper` is unavailable, or stops
because its checks fail, report the blocker instead of bypassing its boundary.
The parent handles push rejections under step 5.

### 1. Stage explicit paths only

```bash
git add <explicit-file-paths-from-this-task>
```

- **Never** `git add -A` or `git add .`.
- Stage only files touched for this task, plus the tasks-artifact checkbox update.
- Never stage: `local.properties`, `.env*`, `node_modules/`, `dist/`, `build/`,
  `out/`, `.gradle/`, `.idea/workspace.xml`, or anything ignored by `.gitignore`.
- Checkbox updates in the tasks file ARE staged — they belong to the change.

### 2. Verify staged diff

```bash
git diff --cached --check
git diff --cached --stat
```

`--check` failures (whitespace/conflict markers) must be fixed before committing.

### 3. Commit

In OpenCode, ask `shipper` to commit the already staged files using the exact
message and path allowlist. The parent must not run `git commit` directly.

Conventional, lowercase imperative. Derive the type from the task:

- `feat: add reaction ingestion to orchestrator`
- `fix: handle monitor retry cancellation`
- `test: cover sync status failure`
- `docs: update openspec task notes`
- `refactor: simplify error handling`
- `chore: update dependencies`

Scope is optional: `feat(orchestrator): add reaction ingestion`.

### 4. Push immediately

For OpenCode, tell `shipper` whether to push to the configured upstream or set
`origin` as upstream for the supplied current branch. The parent must not run
`git push` directly.

With `--no-push`, ask `shipper` for `commit-only`. With `--dry-run`, do not call
`shipper`; skip both commit and push and report intent.

### 5. Push rejection handling

If `shipper` reports that `git push` was rejected because the remote is ahead:

1. Run `git pull --no-rebase` (merge, never rebase — history must not be rewritten).
2. If the merge auto-resolves cleanly: re-run any affected checks, commit the
   merge, stage and verify the merge checkpoint, then delegate its commit and
   push to `shipper` again. Continue autonomously.
3. If conflicts require judgment: hard blocker — report exactly which files
   conflict and stop. **Never** `git push --force` or `--force-with-lease`.

## Phase 5 — Completion

After the last task (or when `instructions apply` reports `all_done`):

1. Ensure the working tree contains no unstaged task work (unrelated
   pre-existing files may remain untouched).
2. If a push is still needed and `--no-push` or `--dry-run` was not specified,
   delegate a `push-only` request to `shipper` (a no-op if already pushed at
   the last checkpoint).
3. Report using the template below, then suggest
   `openspec-archive-change` — do **not** archive automatically; archiving is a
   separate user decision.

```
## Autoship Complete

**Change:** <change-name>
**Branch:** <branch> → origin/<branch>
**Progress:** N/N tasks complete ✓

### Shipped Checkpoints
- <hash> <type>: <message>
- <hash> <type>: <message>

### Left Untouched (pre-existing)
- <file>: <why>

Next: archive with `/opsx-archive` when ready.
```

## Pause Conditions (exhaustive)

Pause ONLY for:

- Change selection genuinely ambiguous (ask once, then continue).
- Task unclear, implementation reveals a design issue, or a task needs work
  beyond the spec — same pause rules as `openspec-apply-change`.
- Secrets detected in a task's diff.
- Pre-existing user changes inseparably overlapping a task's files.
- Merge conflicts after a push rejection that cannot auto-resolve.
- The execution platform blocks an authorized commit or push. Report the exact refusal
  and do not retry through another route.
- openspec CLI reports `blocked` and the missing artifact is not part of this run.
- User interrupts.

A pause must always state: what blocked, what was already committed/pushed
(with hashes), and the exact remaining tasks. **Anything not listed above is not
a valid reason to pause.** In particular, never pause for commit permission,
push permission, branch selection, or "is it a good time to push?"

## Guardrails

- **Never** force-push, delete branches, or push tags.
- **Never** amend, squash, or rebase committed work.
- **Never** stage unrelated or pre-existing changes.
- **Never** stage secrets, credentials, or ignored build artifacts.
- **Never** rewrite history, including via rebase during pull.
- **Always** review the diff content before staging.
- **Always** run available quick checks (`build`, `test`, `lint` scripts from
  `package.json` or equivalent) before committing; on failure, fix forward if
  the fix is within the task's scope, otherwise pause as a hard blocker.
- **Always** push after each checkpoint commit.
- **Always** report hashes and the final summary.

## Fluid Workflow Integration

Like `openspec-apply-change`, this skill can be invoked on a partially applied
change: it picks up remaining tasks, checkpoints-and-pushes each one, and
finishes with a final push. Prior commits by other skills are left untouched.
