---
name: merge-pr
description: Merge a GitHub pull request into main, gated on its associated OpenSpec change being synced and archived (skippable when the unarchive was deliberate). Also handles version bumps (versionCode/versionName) by checking out main and committing the bump. Use when the user asks to merge a PR, wants to pick from open PRs, or requests a version bump/release.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(openspec:*), Bash(powershell:*)
license: MIT
compatibility: Requires gh CLI (authenticated), openspec CLI, and Git.
metadata:
  author: cline
  version: "1.0"
---

# Merge PR

Merge a GitHub pull request into `main`, after making sure its associated OpenSpec
change is synced and archived. Also handles version bumps.

## Overview

Two independent operations:

- **Merge a PR** — resolve the PR, gate on OpenSpec sync + archive (skippable when the
  unarchive was deliberate), then merge into `main`.
- **Bump the app version** — check out `main`, bump `versionName` / `versionCode`,
  commit and push (signed release builds are produced separately via
  `scripts/release/Invoke-AirchiveRelease.ps1`).

## Mode A — Merge a PR

### 1. Resolve the PR

- If the user gave a PR number or URL, use it.
- Otherwise run:
  ```bash
  gh pr list --state open --json number,title,headRefName,baseRefName
  ```
  and present the open PRs (number, title, branch) for the user to choose.
  **Do not guess or auto-select a PR.**

### 2. Resolve the associated OpenSpec change

- Derive the change name from the PR's head branch: strip the leading `type/` prefix
  (e.g. `fix/auditfix-day-attribution` → `auditfix-day-attribution`).
- Confirm it exists: `openspec list --json` for active changes, or look under
  `openspec/changes/archive/` for an already-archived change.
- If no change can be matched, report it and ask whether to merge anyway or stop.

### 3. Sync + archive gate

**Deliberate-unarchive skip:** If the prompt explicitly says the unarchive was
deliberate (e.g. "the unarchive was deliberate", "skip sync/archive", "merge without
archiving"), skip this gate entirely and go straight to merge.

Otherwise:

1. Check out the PR's head branch so the sync/archive edits land on the branch being
   merged. If that branch is already checked out in a separate worktree, switch to
   that worktree directory instead. Before editing, fetch and fast-forward the branch
   so the sync/archive edits land on top of the latest remote state — do not proceed
   on a stale branch:
   ```bash
   git checkout <head-branch>
   git fetch origin
   git pull --ff-only
   # or, when using a worktree:
   cd <path-to-worktree>
   git fetch origin
   git pull --ff-only
   ```
   If `git pull --ff-only` fails (local and remote diverged), stop and report before
   continuing.
2. Run `openspec status --change "<name>" --json` to inspect delta specs.
3. If the change is still active and has delta specs → run `openspec-sync-specs`
   (agent-driven) to sync them into `openspec/specs/`, then commit and push; then run
   `openspec-archive-change` to archive the change, then commit and push again.
4. If the change is still active but has no delta specs → archive it directly, then
   commit and push.
5. If the change is already archived → nothing to do.

Commit and push after **each** sync and **each** archive occurrence — they are separate
operations and each must be recorded on the branch:
```bash
git add openspec/
git commit -m "chore: sync specs for <name>"
git push
# ...then, after archiving:
git commit -m "chore: archive <name> change"
git push
```

### 4. Merge (queue behind CI)

`main` enforces required CI status checks with auto-merge enabled, so queue the
merge rather than merging immediately — GitHub then waits for every required check:

```bash
gh pr merge <number> --auto --merge --delete-branch
```

- Use `--squash` or `--rebase` instead of `--merge` only if the user asks.
- The base branch is `main` (this repo's default branch).
- Before queueing, review CI state with `gh pr checks <number>`; after queueing,
  report that auto-merge is pending — GitHub merges automatically once every
  required check passes.
- If any required check fails, the PR stays blocked: report the failure instead of
  retrying with an immediate (non-`--auto`) merge.

## Mode B — Bump the app version

1. Ensure a clean working tree, then:
   ```bash
   git checkout main
   git pull --ff-only
   ```
2. Bump the version: edit `versionName` in `androidApp/build.gradle.kts` and/or
   raise the `airchive.versionCode` default (currently `2`) so the next build
   carries the new code. Commit and push:
   ```bash
   git add androidApp/build.gradle.kts
   git commit -m "chore: bump version to <name> (<code>)"
   git push
   ```
3. Report the new version. Signed release builds are produced separately via
   `scripts/release/Invoke-AirchiveRelease.ps1` (requires the private signing
   setup from `Setup-AirchiveSigning.ps1`); the bump only versions the code.

## Guardrails

- Never merge without resolving which PR (prompt when ambiguous).
- Never sync/archive or merge on a stale head branch; always fetch + `--ff-only` first, and stop if the branch diverged.
- Do not skip the sync + archive gate unless the prompt explicitly marks the unarchive
  as deliberate.
- Always target `main`; never merge into a feature branch.
- Do not force-push, delete remote branches, or rewrite history.
- If the working tree is dirty, stop and ask before checking out `main`.
- Report clearly what was synced, archived, merged, or tagged.
- Always queue merges with `--auto` — it merges at once when CI is already green;
  never merge without it to sidestep pending CI.
