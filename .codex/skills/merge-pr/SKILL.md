---
name: merge-pr
description: Merge an AirchiveApp pull request into main after syncing and archiving its associated OpenSpec change, or make an explicitly requested Android version bump. Use when the user asks to merge a PR, select an open PR, or bump a version/release.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(openspec:*), Bash(powershell:*)
license: MIT
compatibility: Requires authenticated gh CLI and Git; OpenSpec operations require openspec CLI.
metadata:
  author: cline
  version: "1.1"
---

# Merge PR — AirchiveApp

Merging a PR, bumping a version, building a signed APK, installing it, and publishing
are separate operations. Do only what the user requested.

## Mode A — Merge a PR

### 1. Resolve and inspect

- Use the supplied PR number or URL. Otherwise run
  `gh pr list --state open --json number,title,headRefName,baseRefName` and ask the
  user to choose; never guess.
- Read PR metadata and confirm it is open and targets `main`. If its base differs,
  report that and ask rather than silently retargeting.
- Inspect status and worktrees. Use the existing PR-head worktree when available;
  never overwrite dirty files. Fetch and fast-forward the head before edits with
  `git fetch origin` and `git pull --ff-only`. Stop on divergence.

### 2. OpenSpec sync/archive gate

- Identify the associated change from the PR description, branch, and artifacts;
  a stripped `feat/` or `fix/` prefix is a hint, not proof.
- Inspect `openspec list --json` and archived changes. If no associated change can
  be established, ask whether this is an intentional no-OpenSpec merge.
- If the user explicitly says the unarchive was deliberate or requests skipping
  sync/archive, honor that exception and report it.
- Otherwise run `openspec status --change "<name>" --json`. Follow resolved paths
  and store context from the CLI rather than assuming all planning is repo-local.
- If active with delta specs, use `$openspec-sync-specs`, then commit and push
  only the specific changed spec paths. Use `$openspec-archive-change`, then
  separately commit and push only its affected paths. If active without deltas,
  archive directly. If already archived, no edit is needed.
- Review each diff, run `git diff --cached --check`, and exclude unrelated work.
  Use `chore: sync specs for <name>` and `chore: archive <name> change` commits.
  Never stage all of `openspec/` blindly or force-push.

### 3. Check and merge

1. Run `gh pr checks <number>` and inspect the current mergeability/review state.
   Do not assume AirchiveApp has branch protection, required checks,
   or auto-merge configuration. Report absent checks honestly.
2. Prefer `gh pr merge <number> --auto --merge` when auto-merge is available.
   Failed checks or unmet required reviews are blockers; never use `--admin`.
3. If auto-merge is unavailable, report it. Only use a normal
   `gh pr merge <number> --merge` when the observed checks/reviews permit the
   user-authorized merge; do not use it to bypass pending or failing checks.
4. Use squash/rebase only if explicitly requested. Do not delete local or remote
   branches unless separately requested.
5. Verify whether the PR is actually merged or merely queued, and report that
   distinction with URL, base/head, sync/archive commits, and check results.

## Mode B — Android version bump

1. Resolve the requested version. If the user only says "bump" or "release", ask
   for the target version or major/minor/patch intent; do not invent a release.
2. Use a clean `main` checkout (or its existing worktree), fetch and fast-forward.
   Do not switch another worktree's branch or discard user changes.
3. Inspect `androidApp/build.gradle.kts`: the default version code is the fallback
   on `releaseVersionCode` (overridable by `airchive.versionCode`), while version
   name is in `android.defaultConfig`. Increase the default code monotonically and
   update `versionName` for the requested release. Do not modify the validation
   logic or assume the override increments the checked-in default.
4. Run `.\gradlew.bat :shared:testAndroidHostTest :androidApp:assembleDebug` and
   review `git diff --check`. Stage only the version file and any explicitly
   requested release notes, then commit `chore: bump version to <version>`.
   Report the commit; push only if the user authorized publishing that bump.
5. There is no `scripts/bump-tag.ps1`/`.sh` workflow here. Do not invent one,
   create/push tags, or claim a CI release was triggered.

For an explicitly requested signed build or same-key phone update, read
`docs/android-release.md` and use `scripts/release/Invoke-AirchiveRelease.ps1`.
Keep keystores and DPAPI credentials outside Git. Do not collect passwords in
chat, print secrets, uninstall the app, clear data, or send AC commands.
