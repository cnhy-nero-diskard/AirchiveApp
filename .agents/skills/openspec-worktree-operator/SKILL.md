---
name: openspec-worktree-operator
description: Create and manage isolated Git worktrees for autonomous OpenSpec implementation with autoship commit-and-push. Use when the agent should start a branch worktree, apply an OpenSpec proposal in an isolated worktree, inspect worktree status, prepare a handoff, create a draft PR, or clean up a completed OpenSpec worktree.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(openspec:*), Bash(powershell:*)
license: MIT
compatibility: Requires Git and openspec CLI; gh CLI optional.
metadata:
  author: openspec
  version: "1.1"
---

# OpenSpec Worktree Operator Skill

## Overview
This skill manages isolated Git worktrees for applying OpenSpec proposals. All implementation
work is isolated to the worktree, protecting the original repository state.

It embeds the `openspec-autoship` pipeline (`openspec-apply-change` + auto-commit
mechanics) scoped to the worktree: every coherent checkpoint is committed **and pushed**
to `origin/<branch>` immediately, with no permission prompts. The only deliberate
divergence from autoship is branch policy: autoship ships to whatever branch is checked
out including `main`; this skill refuses to autoship to protected branches
(`master`, `main`, `release/*`, `hotfix/*`, `prod/*`) because the whole point is
isolated feature-branch work. To ship directly to `main`, invoke
`$openspec-autoship` (Codex) or `/openspec-autoship` (other agents)
in the original repo instead.

## Supported Commands

### 1. Create & Autoship in a Worktree
**Syntax:**
- `start branch <branch> apply proposal <proposal> [--dry-run] [--no-push]`
- `start branch <branch> from current apply proposal <proposal> [--dry-run] [--no-push]`
- `start branch <branch> from <base> [--dry-run] [--no-push]`

- `--dry-run`: run the full workflow but stop short of `git commit` / `git push`;
  print the commits and pushes that would have been made.
- `--no-push`: commit locally at checkpoints but skip all pushes.

**Flow:**
1. Run planning gate (detect repo-root, current branch, worktree list, status)
2. Refuse if `<branch>` is protected (`master`, `main`, `release/*`, `hotfix/*`, `prod/*`) or detached HEAD base
3. Create isolated worktree at sibling path: `<parent>/<repo-name>-<sanitized-branch>`
4. Provision local environment (report outcome)
5. If proposal specified: run the autoship apply loop **inside the worktree**
   (select change, preflight safety gate, task-by-task implement + checkpoint commit + push)
6. Announce on entry: `"Autoshipping proposal <proposal> in worktree <worktree-path> on branch <branch> — commits and pushes will happen automatically."`
7. Report worktree path, branch, base, shipped checkpoints, and push status clearly

### 2. Status Check
**Syntax:** `status`

**Actions:**
- Detect repo root (`git rev-parse --show-toplevel`)
- Show current branch (`git branch --show-current`)
- List worktrees (`git worktree list --porcelain`)
- Show status (`git status --short --branch`)
- Do NOT mutate anything

### 3. Handoff Prompt
**Syntax:** `handoff branch <branch>`

**Output format:**
```
Open <worktree-path>. Autoship proposal <proposal> on branch <branch>.
Stay inside this worktree. Checkpoints commit and push to origin/<branch>
automatically after each task (no prompts). Do not switch branches in the
original repo. Flags: [--dry-run] [--no-push] if requested.
```

### 4. Draft PR Creation
**Syntax:**
- `pr branch <branch>`
- `create pr branch <branch>`
- `ready pr branch <branch>`

**Before creating:**
1. Verify branch is NOT master/main
2. Verify branch is pushed to `origin/<branch>` (expected already — autoship pushes per checkpoint; push now if `--no-push` / `--dry-run` was used)
3. Inspect PR templates in `.github/`
4. Summarize changes from commits and diff
5. Include proposal name if known
6. Include actual validation results only
7. Create draft PR (unless user explicitly says ready-for-review)
8. Report PR URL, title, base, head, draft status

### 5. Cleanup Worktree
**Syntax:**
- `cleanup branch <branch>`
- `cleanup branch <branch> force`

**Flow:**
1. Fetch origin for fresh remote-tracking refs
2. Verify branch is merged into origin/master or origin/main
3. Check `git status --short --branch` in worktree
4. **Refuse** if unmerged or dirty (unless force)
5. Remove worktree: `git worktree remove <worktree-path>`
6. Delete local branch: `git branch -d <branch>`
7. Refresh remote base refs with `git fetch`

## Planning Gate (Before Any Mutation)

Before creating, inspecting, or removing a worktree, always:

1. **Detect repo-root:**
   ```bash
   git rev-parse --show-toplevel
   ```
2. **Detect current branch:**
   ```bash
   git branch --show-current
   ```
3. **List active worktrees:**
   ```bash
   git worktree list --porcelain
   ```
4. **Check repo status:**
   ```bash
   git status --short --branch
   ```
5. **Verify safety:**
   - Is `<branch>` already checked out elsewhere? (REFUSE if yes)
   - Does `<worktree-path>` already exist? (REFUSE if yes)
   - Can base be verified? (REFUSE if ambiguous)
   - Is `<branch>` protected (`master`, `main`, `release/*`, `hotfix/*`, `prod/*`)? (REFUSE autoship if yes)

Never switch branches in the original worktree.

## Worktree Creation

**Path derivation:**
```
<parent-of-repo-root>/<repo-name>-<sanitized-branch>
```
Sanitize `<branch>` for folder names: replace `/`, `\`, `:`, whitespace with `-`.

**Fetch before creating:**
```bash
git fetch origin
```

**Creation paths (choose exactly one):**

New local branch:
```bash
git worktree add <worktree-path> -b <branch> <base>
```
Existing local branch (not checked out elsewhere):
```bash
git worktree add <worktree-path> <branch>
```
Existing origin branch (when clearly intended):
```bash
git worktree add <worktree-path> -b <branch> origin/<branch>
```

## Provision Local Environment

After creating the worktree, provision safe local files (e.g., `local.properties`, `android/local.properties`):

1. Copy from original repo only if safe:
   - File must be git-ignored or untracked
   - Never copy `.env`, keystores, credentials, signing files
2. Verify ignored status:
   ```bash
   git -C <worktree-path> status --short --ignored
   ```
3. SDK provisioning (Android projects):
   - If `local.properties` exists in original and repo is Android project, preserve `sdk.dir` value
   - If SDK path unavailable, set `ANDROID_HOME` environment variable
   - Report outcome clearly (copied/created/provided via env/failed)

Report provisioning status before applying changes.

## Autoship Autonomy Contract (inside the worktree)

Once inside `<worktree-path>`, this skill removes manual oversight. Follow strictly:

1. **Never pause to ask permission to commit.** If the safety gate passes, commit.
2. **Never pause to ask permission to push.** If the safety gate passes, push to `origin/<branch>`.
3. **Never pause to ask which branch to use.** Ship to the worktree branch `<branch>`. Protected branches were already refused at creation — do not switch branches mid-run.
4. **Never pause to ask "should I push now?"** Push after every coherent checkpoint and once more at the end.
5. The ONLY pause conditions are the hard blockers in [Pause Conditions](#pause-conditions). Everything else runs to completion.

With `--dry-run`, skip `git commit` / `git push` and report intent. With `--no-push`, commit locally but skip pushes.

## OpenSpec Apply Loop (inside the worktree)

All commands run with `git -C <worktree-path>` or with cwd set to `<worktree-path>`. Never run task work in the original repo.

1. Inspect proposal: `openspec/changes/<proposal>`. If the name is ambiguous, run `openspec list --json` and ask once — the only sanctioned early pause.
2. `openspec status --change "<proposal>" --json` — understand the schema.
3. `openspec instructions apply --change "<proposal>" --json` — get contextFiles, progress, task list, `context`, and `operationGuidance`. Handle `blocked` / `all_done` the same way `openspec-apply-change` does.
4. Read every context file listed in `contextFiles` before implementing.
5. Worktree preflight safety gate (run once before the first task, re-verify cheaply per checkpoint):
   - Repo root: `git -C <worktree-path> rev-parse --show-toplevel` (fail = hard blocker)
   - Branch: `git -C <worktree-path> branch --show-current` (must equal `<branch>`; detached HEAD or mismatch = hard blocker)
   - State: `git -C <worktree-path> status --short --branch` — note pre-existing dirty files unrelated to the proposal; never stage/commit/push them. If a pre-existing modification overlaps a task file, stage only task hunks via explicit paths, or treat as hard blocker for that task if inseparable.
   - Upstream: no upstream yet → first push is `git push -u origin <branch>` (autonomous, do not ask); otherwise plain `git push`.
   - Secret scan before every commit (see below).
6. For each pending task:
   - Announce which task is being worked on.
   - Make minimal code changes scoped to the task.
   - Run available project checks before committing (Gradle / npm / dataset gate as applicable to the repo).
   - Mark the checkbox in the tasks file: `- [ ]` → `- [x]`.
   - Checkpoint commit + push (next section).
   - Continue to the next task. **Do not stop between tasks.**
7. Fluid continuation: on a partially applied proposal, pick up remaining tasks, checkpoint-and-push each one, and leave prior commits untouched.

Guardrail: only mark `- [x]` when the task's behavior is fully implemented; never silently narrow or defer specified behavior.

## Checkpoint Commit + Push (inside the worktree)

After each completed task (or tightly-coupled task group), run this sequence. This replaces auto-commit "ask when in doubt" with "proceed when the gate passes".

### 1. Stage explicit paths only

```bash
git -C <worktree-path> add <explicit-file-paths-from-this-task>
```

- **Never** `git add -A` or `git add .`.
- Stage only files touched for this task, plus the tasks-artifact checkbox update.
- Never stage: `local.properties`, `.env*`, keystores, signing files, `node_modules/`, `dist/`, `build/`, `out/`, `.gradle/`, `.idea/workspace.xml`, or anything ignored by `.gitignore`.
- Checkbox updates in the tasks file ARE staged — they belong to the change.
- Always review the diff content before staging.

### 2. Verify staged diff + secret scan

```bash
git -C <worktree-path> diff --cached --check
git -C <worktree-path> diff --cached --stat
```

- `--check` failures (whitespace/conflict markers) must be fixed before committing.
- Scan the to-be-staged diff for secrets: API keys, tokens, passwords, private keys, connection strings, `.env` contents. If found in files not intended to hold them, that task is a hard blocker. Legitimate fixture/test credentials clearly marked as such in test fixtures are allowed.

### 3. Commit

```bash
git -C <worktree-path> commit -m "<type>: <lowercase imperative description>"
```

Conventional, lowercase imperative. Derive the type from the task:

- `feat: add reaction ingestion to orchestrator`
- `fix: handle monitor retry cancellation`
- `test: cover sync status failure`
- `docs: update openspec task notes`
- `refactor: simplify error handling`
- `chore: update dependencies`

Scope is optional: `feat(orchestrator): add reaction ingestion`.

### 4. Push immediately

```bash
git -C <worktree-path> push                      # upstream exists
git -C <worktree-path> push -u origin <branch>   # no upstream yet
```

With `--no-push`, skip. With `--dry-run`, skip both commit and push and report intent.

### 5. Push rejection handling

If push is rejected because the remote is ahead:

1. Run `git -C <worktree-path> pull --no-rebase` (merge, never rebase — history must not be rewritten).
2. If the merge auto-resolves cleanly: re-run affected checks, commit the merge, push again. Continue autonomously.
3. If conflicts require judgment: hard blocker — report exactly which files conflict and stop. **Never** `git push --force` or `--force-with-lease`.

### 6. Completion

After the last task (or when `instructions apply` reports `all_done`):

1. Ensure the worktree contains no unstaged task work (unrelated pre-existing files may remain untouched).
2. Final `git -C <worktree-path> push` (no-op if already pushed; skip with `--no-push` / `--dry-run`).
3. Report:

```
## Worktree Autoship Complete

**Proposal:** <proposal>
**Worktree:** <worktree-path>
**Branch:** <branch> → origin/<branch>
**Progress:** N/N tasks complete ✓

### Shipped Checkpoints
- <hash> <type>: <message>
- <hash> <type>: <message>

### Left Untouched (pre-existing)
- <file>: <why>

Next: `pr branch <branch>` for a draft PR, or archive with `$openspec-archive-change` (Codex) or `/openspec-archive-change` (other agents) when ready.
```

## Pause Conditions (exhaustive)

Pause ONLY for:

- Proposal selection genuinely ambiguous (ask once, then continue).
- Task unclear, implementation reveals a design issue, or a task needs work beyond the spec — same pause rules as `openspec-apply-change`.
- Secrets detected in a task's diff.
- Pre-existing worktree changes inseparably overlapping a task's files.
- Merge conflicts after a push rejection that cannot auto-resolve.
- openspec CLI reports `blocked` and the missing artifact is not part of this run.
- User interrupts.

A pause must always state: what blocked, what was already committed/pushed (with hashes), and the exact remaining tasks. **Anything not listed above is not a valid reason to pause.** In particular, never pause for commit permission, push permission, branch selection, or "is it a good time to push?"

## Hard Safety Rules

- Never mutate another active worktree
- Never switch branches in the original repo for task execution
- Never run task implementation, commits, or pushes outside `<worktree-path>`
- Never overwrite an existing folder
- Never delete unmerged work unless explicitly forced
- Never force-push, push tags, auto-rebase, auto-amend, squash, delete remote branches, or rewrite history (including via rebase during pull)
- Never stage unrelated or pre-existing changes; never stage secrets, credentials, or ignored build artifacts
- Never commit or push on a protected branch (`master`, `main`, `release/*`, `hotfix/*`, `prod/*`) or detached HEAD — this skill refuses those at creation
- Always run available quick checks (`build`, `test`, `lint`, dataset gate) before committing; on failure, fix forward if in task scope, otherwise pause as hard blocker
- Always push after each checkpoint commit (unless `--no-push` / `--dry-run`)
- Always report hashes and the final summary
- Treat `.gitignore` as ignore policy, not proof a file is safe to commit
