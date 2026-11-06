---
name: autocmt
description: Guarded autonomous commit-and-push workflow for AirchiveApp Git feature branches. Use when the user invokes `$autocmt on`, `$autocmt checkpoint`, `$autocmt off`, or `$autocmt status`, or asks Codex to make incremental local commits and normal pushes while working on a non-protected branch.
allowed-tools: Bash(git:*)
license: MIT
compatibility: Requires Git. Pushing needs network access, so Codex may pause for command approval; never bypass the sandbox or approval policy to complete a push.
metadata:
  author: airchiveapp
  version: "1.1"
---

# Auto-commit

Autonomous commit-and-push workflow that groups uncommitted changes into small,
coherent increments and pushes them to the current feature branch. This is the
consolidated Codex CLI version: it is self-contained and does not depend on the
Claude, Cline, or OpenCode skill copies.

## Command Modes

| Command | Action |
|---|---|
| `$autocmt on` | Enable auto-commit mode. After each completed work unit, run the checkpoint workflow automatically. |
| `$autocmt checkpoint` | One-time commit-and-push of all current uncommitted changes, grouped into meaningful chunks. |
| `$autocmt off` | Disable auto-commit mode. Stop committing and pushing automatically. |
| `$autocmt status` | Report current mode, branch, upstream state, and pending changes. Read-only. |

If the user invokes `$autocmt` with no argument, run `status`.

## Core Principles

1. **Never commit everything at once.** Group changes into coherent, self-contained increments.
2. **One-line commit messages** in the format: `type: (message here)`.
3. **Only push to non-protected branches.** Never push to `main`, `master`, or any branch the user has designated protected.
4. **Never commit secrets, credentials, or generated artifacts.** Respect `.gitignore` and the repo-specific exclusions below.
5. **Never stage files unrelated to the current chunk.**
6. **Never bypass Codex approvals, the sandbox, or Git hooks** to make a commit or push succeed.

## Commit Types

Use the most specific type that fits the chunk:

| Type | When to use |
|---|---|
| `feat` | New feature or user-facing capability |
| `fix` | Bug fix |
| `refactor` | Code restructuring without behavior change |
| `style` | Formatting, whitespace, lint fixes (no logic change) |
| `docs` | Documentation-only changes |
| `test` | Adding or updating tests |
| `chore` | Maintenance, tooling, dependency updates |
| `build` | Build system or CI configuration changes |
| `perf` | Performance improvements |
| `revert` | Reverting a previous commit |

## Workflow

### 1. Preflight (read-only)

Run from the repository root:

```bash
git status --porcelain
git branch --show-current
git rev-parse --abbrev-ref '@{upstream}'
git remote -v
git rev-parse --git-dir
git rev-parse --git-path MERGE_HEAD
git rev-parse --git-path REBASE_HEAD
```

Then decide:

- **Detached HEAD:** STOP. Report that HEAD is detached and suggest checking out a branch.
- **Merge/rebase/cherry-pick in progress:** STOP. Do not commit.
- **Protected branch** (`main`, `master`, or user-designated): STOP. Report the block and suggest a feature branch.
- **No remote configured:** commit locally only and skip the push. Say so in the report.
- **No changes:** report `No uncommitted changes to commit.` and stop.

For `$autocmt status`, produce the same facts plus the grouped change summary and stop; change nothing.

### 2. Analyze and Chunk

Run `git status --porcelain` and `git diff --stat` (add `git diff --stat --cached` when
there is already a staged set) to understand the scope of changes. Confirm the work is
in a commit-able state: no obvious mid-edit files, no conflict markers, no secrets.

Group changes into **coherent chunks**:

- **What is the logical unit of work?** One feature, one bug fix, one refactor.
- **Which files belong together?** Files changed for the same purpose share a commit.
- **Are there independent changes?** Split them into separate commits.

**Chunking heuristics:**

- New feature files (screen, module, endpoint) → one `feat` commit
- Bug fix plus its test → one `fix` commit, or `fix` + `test` when the test is substantial
- Renames/moves → one `refactor` commit
- Dependency/version bumps → one `chore` or `build` commit
- Docs only → one `docs` commit
- Formatting only → one `style` commit
- Unrelated leftovers → separate commits per concern

**Order** commits from most foundational to most dependent (refactor before feature,
chore before feat).

### 3. Commit Each Chunk

For each chunk, in order:

1. **Stage only the files in this chunk:**
   ```bash
   git add <file1> <file2> ...
   ```
   Never use `git add -A` or `git add .` unless every change belongs to one chunk.

2. **Verify the staged set:**
   ```bash
   git diff --cached --stat
   ```

3. **Commit** with a one-line message:
   ```bash
   git commit -m "feat: (add shared instrument integration)"
   ```
   Format: lowercase type, colon, space, parenthesized imperative message.

4. **Confirm the commit succeeded** before starting the next chunk. If a hook fails,
   STOP: report the hook output and do not retry with `--no-verify`.

### 4. Push

After all chunks are committed:

```bash
git push
```

- Push to the current branch's upstream.
- If no upstream is set:
  ```bash
  git push -u origin <current-branch>
  ```
- If Codex pauses for approval or the sandbox blocks network access, request the push
  and wait. Do not re-run the command with `--dangerously-bypass-approvals-and-sandbox`,
  `--full-auto`, or any other bypass flag, and do not switch to `gh` or a raw HTTP call
  to work around the block.
- If the push is rejected, do NOT force-push. Report the reason and ask whether to
  pull, rebase, or merge.

### 5. Report

```
## Auto-commit complete

**Branch:** <branch>
**Commits created:** N

- `feat: (add shared instrument integration)` — 3 files
- `fix: (fix live status detection)` — 2 files
- `chore: (bump gradle dependencies)` — 1 file

**Pushed:** yes / no (reason)
**Skipped:** <files intentionally excluded and why>
```

## Auto-commit Mode (`on` / `off`)

When auto-commit mode is **on**:

- After each meaningful work unit (feature finished, bug fixed, refactor done), run the
  checkpoint workflow without being asked again.
- Do NOT interrupt the user mid-task. Wait for a natural stopping point.
- If a file is clearly mid-edit or incomplete, exclude it and note it in the report.
- Keep mode state in the conversation context. If the conversation is reset, default to
  `off` and say so.

## Guardrails

- **Protected branches:** `main`, `master`, and anything the user designates. Stop and
  suggest a feature branch instead of committing.
- **No force-push:** Never use `--force` or `--force-with-lease` unless the user
  explicitly asks for that exact operation.
- **No secrets:** Never commit credentials, API keys, tokens, `.env` files, keystores,
  or service-account JSON.
- **AirchiveApp generated artifacts — never commit:** `build/`, `androidApp/build/`,
  `shared/build/`, `androidApp/release/`, `.gradle/`, `.kotlin/`, `local.properties`,
  `.idea/`, `**/xcuserdata/`, `**/DerivedData/`, `.impeccable/`, `__pycache__/`,
  `firebase-debug.log`, `firestore-debug.log`, `ui-debug.log`,
  `androidApp/google-services.json`, `iosApp/iosApp/GoogleService-Info.plist`,
  `*.jks`, `*.keystore`, `*.p12`.
- **No unrelated files:** Each commit contains only its chunk's files.
- **No empty commits:** Never commit with an empty staged set.
- **No amend:** Never amend a commit that has already been pushed.
- **No merge commits:** Do not create merge commits; stop and ask when a push is rejected.
- **No interactive Git:** Avoid `git add -p`, `git rebase -i`, `git commit` with no `-m`,
  and any command needing a TTY.
- **No Git config changes:** Do not change user identity, hooks, signing, or remotes.
- **Verify before proceeding:** After each `git add` and `git commit`, confirm success
  before continuing. On failure, stop and investigate.
- **Incomplete work:** Exclude files that look half-finished and report them.

## Edge Cases

| Situation | Handling |
|---|---|
| No changes | Report `No uncommitted changes.` and stop. |
| Only untracked files | Treat them as one or more chunks and commit them, after checking they are not artifacts or secrets. |
| Deleted files | Stage with `git add -u <path>` or `git rm <path>`. |
| Renamed files | `git add -A <old-path> <new-path>` to stage the rename. |
| Large change set | Group by directory/concern; avoid one giant commit. |
| Merge/rebase/cherry-pick in progress | STOP. Do not commit. |
| Detached HEAD | STOP. Suggest checking out a branch. |
| Push rejected | STOP. Report and ask; never force-push. |
| Push blocked by sandbox/approval | Request approval and wait; never bypass. |
| No remote | Commit locally, skip push, report. |
| Protected branch | STOP. Suggest a feature branch. |
| Pre-commit hook failure | STOP. Report hook output; do not use `--no-verify`. |
| Mid-edit / incomplete file | Exclude and report. |
| Binary/asset files | Commit only when intentional; note them in the report. |
