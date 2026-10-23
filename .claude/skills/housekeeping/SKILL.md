---
name: housekeeping
description: Repository housekeeping for Backlogium — audit and remove workspace clutter (stray logs, screenshots, scratch dirs), verify .gitignore coverage, perform safe Git hygiene (prune merged branches, report stale worktrees), and refresh front-facing markdown docs on an ordinal-named chore branch. Use when the user invokes $housekeeping or asks to clean up the repository, tidy workspace artifacts, or refresh project status docs.
allowed-tools: Bash(git:*), Bash(powershell:*)
license: MIT
compatibility: Requires Git and PowerShell (Windows).
metadata:
  author: cline
  version: "1.0"
---

# Housekeeping

Safe, scan-first repository housekeeping for Backlogium. Every mode begins with a
read-only audit; nothing is deleted or changed without an explicit, confirmed plan.

## Command Modes

- `$housekeeping` or `$housekeeping scan` — **(default)** read-only audit. Report workspace clutter, `.gitignore` gaps, untracked files, merged local branches, and stale worktrees. Change nothing.
- `$housekeeping clean` — delete only the artifact targets confirmed against the scan results (with user approval).
- `$housekeeping git` — Git hygiene: list/prune merged local branches (guarded) and report stale worktrees under `.worktrees/`.
- `$housekeeping docs [focus notes]` — refresh front-facing markdown status docs on an ordinal-named chore branch (`chore/<topic>-<ordinal>`).
- `$housekeeping all` — scan → clean → git → docs, pausing for confirmation between destructive steps.

**Always run the scan before any mutation, even for `$housekeeping all`.**

## Workflow

### 1. Scan (read-only — always first)

If `scripts/Invoke-HousekeepingScan.ps1` exists, prefer it for deterministic checks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-HousekeepingScan.ps1 -RepoRoot <repo-root>
```

Otherwise collect the same data manually:

```powershell
# Known Backlogium clutter patterns (all currently expected to be ignored/untracked)
Get-ChildItem -Path <repo-root> -File -Filter *.log          # root-level build/test logs
Test-Path <repo-root>\.scratch_screens                       # emulator screenshot scratch dir
Get-ChildItem -Path <repo-root> -Directory -Filter '.gradle-project-cache*'
git -C <repo-root> status --porcelain                        # ?? = untracked non-ignored clutter
git -C <repo-root> check-ignore -v <path>                    # verify .gitignore coverage of each target
git -C <repo-root> branch --format '%(refname:short)'        # ordinal scan for chore branches
```

Classify every finding into exactly one bucket and report sizes:

| Bucket | Examples in this repo | Action |
|---|---|---|
| Ignored artifact | `build_install*.log`, `instr_test*.log`, `build/` outputs | deletable |
| Untracked clutter | `.scratch_screens/`, one-off screenshots | deletable if truly transient |
| Gitignore gap | pattern recurring but not ignored (e.g. `.scratch_screens/`) | propose adding to `.gitignore` — do not edit silently |
| Tracked file | anything matching `git ls-files` | **never touched** |

Verify .gitignore coverage: for each clutter path run `git check-ignore -v <path>`.
If a recurring artifact pattern is **not** ignored, propose adding it to `.gitignore`
as part of the docs/chore commit rather than leaving the gap silent.

### 2. Clean

Before deleting anything:

1. Re-present the full deletion list with sizes and get explicit user approval.
2. For each target, confirm it is not tracked: `git ls-files --error-unmatch <path>` must fail.
3. Delete only then, e.g. `Remove-Item -LiteralPath <path> -Recurse -Force`.
4. Re-run `git status --porcelain` afterward and report that no tracked content changed.

**Never delete:** tracked sources/docs, `local.properties`, `keystore/`, `.worktrees/`
(that belongs to worktree handling below), agent config dirs (`.cline/`, `.claude/`,
`.codex/`, `.agents/`), or `openspec/`.

### 3. Git Hygiene

Run checks in order and stop at the first guardrail violation:

1. Confirm repository root: `git rev-parse --show-toplevel`.
2. Report current branch. **Refuse to delete branches while HEAD is detached.**
3. Protected branches are never candidates for deletion: `master`, `main`, `release/*`, `hotfix/*`, `prod/*`.
4. List safe-to-prune locals: `git branch --merged master`, excluding protected branches and the current branch.
5. Present the list and get explicit approval, then delete with `-d` only (**never `-D`**, never force).
6. Worktrees: `git worktree list`. Flag entries whose directory is missing, whose branch
   is gone, or whose branch is fully merged. **Propose** `git worktree remove <path>`;
   require approval, and never `--force` unless the user explicitly asks twice.
7. Optional, only with approval (slow): `git fetch --prune` and `git gc`.

### 4. Docs Refresh (chore branch workflow)

Updates front-facing markdown (`README.md`, `CLAUDE.md`, `SECURITY.md` — plus other
root-level `*.md` if present) so stated facts match current reality: module list,
build commands, `ls openspec/specs/` capability list, and recent
`openspec/changes/archive/` entries worth surfacing.

1. **Preconditions:** working tree must be clean (`git status --porcelain` empty);
   current branch must have upstream or fall back to `origin/master`.
2. **Branch naming convention:** `chore/<topic>-<ordinal>` where `<ordinal>` starts at
   `1` and increments per invocation across sessions. Derive it deterministically:
   filter `git branch --format '%(refname:short)'` to `chore/<topic>-*`, take the max
   numeric suffix, add 1. Example progression: `chore/docs-refresh-1`, then later
   `chore/docs-refresh-2`, and so on. Default topic is `docs-refresh`; use the
   provided argument as topic when the user names one.
3. Create the branch: `git fetch origin` then `git switch -c chore/<topic>-<N> origin/master`.
4. Make **minimal factual updates** to front-facing markdown — correct stale numbers,
   commands, lists, paths. No tone/style rewrites; preserve each file's structure.
5. Commit only those files: `docs: refresh front-facing project status` (conventional
   lowercase imperative). Never stage unrelated changes.
6. Push: `git push -u origin chore/<topic>-<N>`. Do **not** open a PR unless asked;
   offer the compare URL instead.
7. Return to the previous branch only if the user asks; otherwise stay put and say so.

## Guardrails

- **Scan first, mutate second.** No deletion, branch removal, or commit without a presented plan and user approval (the docs-refresh commit itself is the approved plan output).
- **Never touch tracked files** during cleanup. Deletion candidates must fail `git ls-files --error-unmatch`.
- **Never modify openspec/** as part of housekeeping — behaviour specs go through opsx skills.
- **Never force:** no `git branch -D`, no `git push --force`, no `git worktree remove --force`, no history rewrite.
- **Protected branches are off-limits**: `master`, `main`, `release/*`, `hotfix/*`, `prod/*`.
- **Keep deletes narrow.** When a path's classification is ambiguous, leave it and mark it "needs human review".
- **Docs updates are factual only.** If a doc statement can't be verified against the repo, fix it or remove it — do not embellish.
- **Report everything.** Finish with what was scanned, what was changed, what was skipped and why, and the exact chore branch name used (if any).

## Troubleshooting

- **"Not a Git repository"** — run from the repo root or pass `-RepoRoot`.
- **"Working tree not clean" during docs refresh** — finish or stash pending work first; housekeeping must not mix unrelated changes into the chore commit.
- **".gitignore gap" repeats every session** — land the `.gitignore` addition inside the next `chore/…` docs commit instead of manually ignoring the reminder.
