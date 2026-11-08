---
name: openspec-worktree-operator
description: Create and manage isolated Git worktrees for autonomous OpenSpec implementation with autoship commit-and-push. Use when Codex should start a branch worktree, apply an OpenSpec proposal in an isolated worktree, inspect worktree status, prepare a handoff, create a draft PR, or clean up a completed OpenSpec worktree.
license: MIT
compatibility: Requires Git; examples assume Windows PowerShell, with optional gh and openspec CLI support.
metadata:
  author: openspec
  version: "1.1"
  provider: codex
---

# OpenSpec Worktree Operator (Codex)

Invoke this skill explicitly with `$openspec-worktree-operator`, or let Codex
select it from the skill description. The user's remaining text is the command
payload. Use the Codex skill interface for related workflows:

- Apply a proposal inside the target worktree with
  `$openspec-apply-change <proposal>`.
- Autoship checkpoints inside that worktree: commit AND push each coherent
  increment to `origin/<branch>` without further prompts once a
  `start branch <branch> apply proposal <proposal>` payload was given.
- For PR creation, prefer the available GitHub connector/app. Fall back to an
  authenticated `gh` CLI when no connector is available.

Loading this skill alone is not permission to commit, push, create a PR, or
remove anything. A `start branch <branch> apply proposal <proposal>` payload
IS permission to commit and push inside the new worktree to `origin/<branch>`
only — never in the original checkout, never to a protected branch, never
without the checkpoint gates below.

## Command Payloads

- `start branch <branch> apply proposal <proposal> [--dry-run] [--no-push]`: create a sibling worktree
  from the default base, then autoship the proposal there.
- `start branch <branch> from current apply proposal <proposal> [--dry-run] [--no-push]`: use the
  original checkout's `HEAD` as the base.
- `start branch <branch> from <base> [--dry-run] [--no-push]`: create the worktree without assuming a
  proposal.
- `status`: inspect the repository and all known worktrees without mutation.
- `handoff branch <branch>`: print a paste-ready Codex handoff prompt.
- `pr branch <branch>`: create a draft PR after the branch and template checks.
- `cleanup branch <branch>` or `cleanup branch <branch> force`: remove a
  completed worktree subject to the safeguards below.

`--dry-run` prints would-be commits/pushes without running them. `--no-push`
commits locally but skips pushes.

Use placeholders literally in examples: `<branch>`, `<proposal>`, `<base>`,
`<repo-root>`, `<repo-name>`, and `<worktree-path>`.

## Planning Gate

Before creating, inspecting, or removing a worktree, run these checks in the
original checkout:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git worktree list --porcelain
git status --short --branch
```

Record the repository root, current branch, dirty files, every active worktree,
and a verified base ref. AirchiveApp uses `origin/main` by default; verify that
ref exists and honor an explicitly requested base rather than assuming `master`.

Refuse when the requested branch is already checked out elsewhere, the sibling
destination exists, the base is ambiguous, the requested branch is protected
(`master`, `main`, `release/*`, `hotfix/*`, `prod/*`), or continuing would require
switching the original checkout. Codex must leave the original checkout on its
current branch for the entire task.

## Worktree Creation

Fetch `origin` when remote access is available. Derive the destination as a
sibling of `<repo-root>`:

```text
<parent-of-repo-root>\<repo-name>-<sanitized-branch>
```

Replace `/`, `\`, `:`, and whitespace in `<branch>` with `-`. Never overwrite
an existing folder. After the planning gate, choose exactly one command:

```powershell
git worktree add <worktree-path> -b <branch> <base>
git worktree add <worktree-path> <branch>
git worktree add <worktree-path> -b <branch> origin/<branch>
```

Use the first form for a new local branch, the second only for an existing local
branch not checked out elsewhere, and the third only when the user clearly
requested an origin-only branch. Report the resolved path, branch, and base
before doing any implementation work. Announce:
`"Autoshipping proposal <proposal> in worktree <worktree-path> on branch <branch> — commits and pushes will happen automatically."`

## Local Environment Provisioning

Provision immediately after creation, before `$openspec-apply-change` or any
validation:

1. Copy only `local.properties` from the original checkout, and only when Git
   reports it as ignored or untracked. Never copy arbitrary ignored files,
   `.env` files, credentials, keystores, signing files, or build output.
2. Preserve an existing `sdk.dir`. If no safe source exists, use the common
   Windows Android SDK path if present or expose `ANDROID_HOME` to the current
   Codex process. Do not invent a path.
3. Verify the target file:

   ```powershell
   git -C <worktree-path> status --short --ignored -- local.properties
   ```

   A copied or generated file must be `!!` or `??`; remove it if it is tracked
   or fails that check.
4. Report copied, already present, created, environment-provided, skipped, or
   failed provisioning. Do not apply or validate until that result is reported.

AirchiveApp-specific validation and safety:

- Read `PRODUCT.md`, `DESIGN.md`, and applicable OpenSpec artifacts before changes.
- For Kotlin/Android changes use
  `.\gradlew.bat :shared:testAndroidHostTest :androidApp:assembleDebug` inside the worktree.
- For release-script changes also run
  `shellcheck scripts/release/*.sh` and
  `scripts/release/test-release-scripts.sh`.
- Do not copy private signing files, DPAPI credential stores, Firebase service
  configuration, or ThinQ credentials into the worktree. Consult `docs/android-release.md`
  only when an explicitly requested release operation needs it.
- Do not install builds or send real AC commands as an automatic checkpoint check.
- Debug and release share `cloud.airchive.personal`; never uninstall/clear app data
  to work around a signing conflict.

## Autoship Apply Loop (inside the worktree)

Keep all implementation work inside `<worktree-path>`:

1. Read `openspec/changes/<proposal>` and every context artifact needed by the
   change, including proposal, design, specs, and tasks files.
2. Run `openspec status --change "<proposal>" --json` and
   `openspec instructions apply --change "<proposal>" --json` when the CLI is
   available in this Codex process to get contextFiles, progress, tasks,
   `context`, and `operationGuidance`. If the `openspec` command is absent,
   follow the checked-in artifacts directly and state that the CLI is
   unavailable in this process; do not claim it is uninstalled.
3. Read every context file before implementing. Handle `blocked` / `all_done`
   the same way `$openspec-apply-change` does.
4. Worktree preflight (once before the first task, cheaply re-verified per
   checkpoint): `git -C <worktree-path> rev-parse --show-toplevel` must succeed;
   `git -C <worktree-path> branch --show-current` must equal `<branch>`;
   note pre-existing dirty files unrelated to the proposal via
   `git -C <worktree-path> status --short --branch` and never stage/commit/push
   them (stage only task hunks via explicit paths, or block that task if
   inseparable); no upstream yet means the first push is `push -u origin <branch>`.
5. For each pending task: announce it, make minimal scoped changes, run
   validation from inside the target worktree only, mark `- [ ]` → `- [x]`,
   run the checkpoint below, and continue without stopping between tasks.
6. On a partially applied proposal, pick up remaining tasks and leave prior
   commits untouched. Only mark `- [x]` when the behavior is fully implemented.

## Checkpoint Commit + Push (inside the worktree)

After each completed task (or tightly-coupled group):

1. Stage explicit paths only: `git -C <worktree-path> add <files-for-this-task>`.
   Never `git add -A` / `git add .`. Always include the tasks-checkbox update;
   never include `local.properties`, `.env*`, keystores, build outputs, or
   ignored files. Review the diff before staging.
2. Verify: `git -C <worktree-path> diff --cached --check` and
   `git -C <worktree-path> diff --cached --stat`. Fix `--check` failures first.
   Scan for secrets (keys, tokens, passwords, connection strings); a leak is a
   hard blocker for that task.
3. Commit conventionally: `git -C <worktree-path> commit -m "<type>: <lowercase imperative>"`
   (`feat`, `fix`, `test`, `docs`, `refactor`, `chore`; scope optional).
4. Push immediately: `git -C <worktree-path> push` (or `push -u origin <branch>`
   on first push). Skip with `--no-push`; skip commit+push with `--dry-run`
   and report intent instead.
5. On push rejection (remote ahead): `git -C <worktree-path> pull --no-rebase`
   (merge, never rebase). If clean, re-run checks, commit the merge, push again
   and continue. On conflicts needing judgment: hard blocker — report files and
   stop. Never force-push.

After the last task (or `all_done`): final `git -C <worktree-path> push`,
then report shipped checkpoint hashes, branch `→ origin/<branch>`, progress,
untouched pre-existing files, and next step (`pr branch <branch>` or archive).

## Pause Conditions (exhaustive)

Pause ONLY for: genuinely ambiguous proposal selection (ask once); unclear task
or spec超越 needing design input; secrets in the diff; inseparable overlap with
pre-existing worktree changes; unresolvable merge conflicts after push
rejection; openspec CLI `blocked` on out-of-scope artifacts; user interrupt.
Every pause states what blocked, committed/pushed hashes, and remaining tasks.
Never pause for commit/push/branch permission or "should I push now?".

## Handoff

For `handoff branch <branch>`, print this with resolved values:

```text
Open <worktree-path> as a Codex task. Autoship <proposal> on branch <branch>.
Stay inside this worktree. Checkpoints commit and push to origin/<branch>
automatically after each task. Do not switch branches in the original repo.
```

If the proposal is unknown, omit only its instruction and identify the missing
value.

## Draft PR

Only create a PR for an explicit `pr branch <branch>` request. Before using the
GitHub connector or `gh pr create`:

1. Refuse `master`, `main`, `release/*`, `hotfix/*`, and `prod/*`.
2. Verify the branch exists on `origin/<branch>` and that any configured
   upstream is exactly `origin/<branch>` (autoship pushes per checkpoint; push
   now if `--no-push` / `--dry-run` was used).
3. Inspect `.github/pull_request_template.md`,
   `.github/PULL_REQUEST_TEMPLATE.md`, and matching files under
   `.github/PULL_REQUEST_TEMPLATE/` when present.
4. Summarize commits and the branch diff, include the proposal when known, and
   report only validation actually run.
5. Prefer a draft PR unless the user explicitly requests ready-for-review.
   Verify and report the URL, title, base, head, and draft status.

Never force-push, push tags, delete remote branches, or silently change the PR
base.

## Cleanup

For normal cleanup, fetch `origin`, locate `<worktree-path>` from
`git worktree list --porcelain`, and then:

1. Verify `<branch>` is an ancestor of the fresh remote base.
2. Inspect `git -C <worktree-path> status --short --branch`.
3. Refuse if the branch is unmerged or the worktree is dirty.
4. Run `git worktree remove <worktree-path>` and then `git branch -d <branch>`.
5. Refresh the remote base ref after successful removal.

For `force`, inspect and report merge and dirty status first. Never remove a
dirty worktree or force-delete an unmerged branch without explicit confirmation.
Never delete the remote branch unless separately requested.

## Safety Rules

- Never mutate another active worktree.
- Never switch branches in the original checkout; never run task work, commits,
  or pushes outside `<worktree-path>`.
- Never overwrite an existing folder.
- Never delete unmerged work unless the user explicitly forces that exact action.
- Treat `.gitignore` as ignore policy, not proof that a file is safe to commit.
- Never stage unrelated, pre-existing, secret, or ignored build-artifact files.
- Keep autoship commits/pushes inside the target worktree on `origin/<branch>`.
- Never force-push, amend, squash, rebase automatically, push tags, or delete
  remote branches.
- Always run available quick checks before committing; always push after each
  checkpoint (unless `--no-push` / `--dry-run`); always report hashes.
