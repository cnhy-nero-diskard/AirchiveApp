# Git scoping techniques for under-the-hood analysis

Provider-neutral reference. Every command below is read-only unless explicitly marked otherwise.

## 1. Establish identity and provenance first

- Confirm you are in the intended repo: `git rev-parse --show-toplevel` and `git remote -v` (if remotes exist).
- Record provenance up front: resolved HEAD (`git rev-parse HEAD`), the requested ref, `remote`/branch context, and whether the working tree is dirty.
- Check dirty status immaterially: `git status --porcelain` (empty = clean). State explicitly in the deliverable whether uncommitted changes were included.
- Resolve a loose version to a commit: `git tag --list`; `git show-ref --tags`; `git describe --tags --always`; for manifests declaring versions, cross-check the manifest rather than trusting it.

## 2. Reading non-HEAD revisions without switching branches

Never check out the analyzed ref. Prefer:

- `git show <ref>:<path>` — a single file's content at that revision.
- `git ls-tree -r <ref>` — inventory of what exists at that revision (catches files added/removed since).
- `git grep -n <pattern> <ref>` — search within the revision, independent of the working tree.
- `git log --oneline -20 <ref>` — recent history context for the ref.
- `git diff <base>...<target>` — *three* dots diff from merge-base to target (the usual "what this branch changed" semantic); `git diff <base>..<target>` with two dots compares the two tips directly. State which you used, because results differ when the branch is behind its base.
- Add `-M` (rename detection) so moved files are read as moves, not mass deletions plus creations.
- `git diff <base>...<target> -- <path>` to constrain to a feature area; `--stat` first for a map, full hunks second.

## 3. Temporary worktree for heavy revision analysis

For repeated reads across a large revision, isolate instead of repeatedly calling `git show`:

```sh
git worktree add --detach <temp-dir> <ref>   # do not touch the user's checkout
# ... inspect files inside <temp-dir> ...
git worktree remove <temp-dir>               # always clean up, even on failure paths
```

Record in the report that the snapshot lived in a temporary worktree at `<ref>`. Never perform installs, builds, or network access inside it.

## 4. Feature-level scoping (two directions at once)

A feature is rarely in one file. Search it from both ends:

1. **Trigger-to-implementation**: user-facing entry (route, command, button/onClick, CLI flag, scheduled job) -> handler/state -> domain logic -> persistence.
2. **Schema-to-surface**: tables/migrations, queues, or external contracts -> services -> exposed behavior.

Techniques:

- Grep for distinctive identifiers (table names, flags, error codes, endpoint fragments) before trusting naming guesses.
- Find callers with identifier grep across the whole repo (including tests) — later callers reveal real usage; tests reveal intended contracts.
- Check config defaults and feature flags: the shipped behavior may be gated off by default.
- Diff-driven discovery: when the feature has a change proposal, PR, or known commit range, inspect `git log/diff` for that range to bound the search, but *verify current behavior in the working tree* before describing it.

## 5. Comparison discipline

- Identify each analyzed side's exact revision; record both SHAs.
- Classify each change: **added**, **deleted**, **moved** (renamed), **behaviorally changed**, **cosmetically changed**, **unchanged**. Only "behaviorally changed" deserves flow-level re-tracing.
- Read changed hunks on *both* sides when semantics are unclear rather than inferring intent from a diff alone.
- Disclose merge-base drift (`git merge-base <base> <target>`) when the base has moved since the branch forked — the comparison is against the stated refs, not an idealized fork point.

## 6. Guardrails

- Read-only inspection first; do not fetch, install, run tests, or execute project scripts just to explain code.
- Never switch/modify branches, rewrite history, or mutate refs; `git worktree` writes are limited to the temporary directory pattern above.
- If the revision contains secret-looking data (credentials, tokens, keys), do not transcribe values.
- Submodules and generated artifacts: note when code of interest lives outside the parent repo; resolve submaterial only via `git submodule status` inspection unless the user permits deeper access.
