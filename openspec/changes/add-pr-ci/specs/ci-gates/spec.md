# Spec Delta

## Purpose

Makes every change to `main` pass the same automated, secret-free verification. A change that is failed, skipped, missing, or still running cannot be merged, including by the owner.

## ADDED Requirements

### Requirement: Pull requests are verified without secrets
Every pull request targeting `main`, and every push to `main`, SHALL run verification covering:
- the shared host tests
- assembly of the debug app and its instrumentation test APK
- Android debug lint
- a vulnerability check of the locked dependency set

Verification SHALL use no repository secrets and a read-only repository token. Pull requests from forks SHALL receive the same verification under the same restrictions.

#### Scenario: Pull request from a branch
- **WHEN** a pull request targeting `main` is opened or updated from a branch of this repository
- **THEN** the verification SHALL run against the pull request's merge result and report one aggregate status on the pull request

#### Scenario: Pull request from a fork
- **WHEN** a pull request targeting `main` is opened from a fork
- **THEN** verification SHALL run with no repository secrets and no write permission, and SHALL report the same aggregate status

### Requirement: One aggregate check decides mergeability
Verification SHALL report a single aggregate check that passes only when every verification job it covers succeeded. Any failed, cancelled, or skipped job SHALL make the aggregate check fail. Verification SHALL run for every pull request regardless of which files changed.

#### Scenario: A verification job fails
- **WHEN** any covered job fails or is cancelled
- **THEN** the aggregate check SHALL fail

#### Scenario: A verification job is skipped
- **WHEN** a covered job is skipped by a condition
- **THEN** the aggregate check SHALL fail rather than count the skip as success

#### Scenario: Documentation-only pull request
- **WHEN** a pull request changes only documentation
- **THEN** verification SHALL still run and report the aggregate check, so the check is never left pending

### Requirement: The merge gate binds every actor
Changes SHALL reach `main` only through pull requests whose aggregate check passed. The gate SHALL have no bypass actors, including the repository owner and installed integrations. Pull requests SHALL require zero approving reviews.

#### Scenario: Direct push to main
- **WHEN** anyone, including the owner, pushes a commit directly to `main`
- **THEN** the push SHALL be rejected

#### Scenario: Merge with a failing or pending check
- **WHEN** the owner tries to merge a pull request whose aggregate check failed or has not completed
- **THEN** the merge SHALL be refused

#### Scenario: Emergency override
- **WHEN** the owner must land a change while verification is broken
- **THEN** the only override SHALL be temporarily disabling the gate, which SHALL be recorded in the gate's history, and the gate SHALL be re-enabled afterwards

### Requirement: New lint findings fail verification
Android debug lint SHALL fail verification for any finding not recorded in a committed baseline. Baseline findings SHALL still be reported. Checks whose outcome depends only on newer upstream releases, not on repository contents, SHALL NOT affect the verification result.

#### Scenario: New lint warning
- **WHEN** a pull request introduces a lint warning that is not in the baseline
- **THEN** verification SHALL fail and identify the finding

#### Scenario: Only baseline findings
- **WHEN** lint reports only findings recorded in the baseline
- **THEN** lint SHALL pass and its report SHALL still list those findings

#### Scenario: Upstream publishes a newer dependency
- **WHEN** a newer version of a dependency is published and the repository is unchanged
- **THEN** the verification result SHALL NOT change

### Requirement: Dependency resolution is locked
Dependency versions resolved for the app, its shared module, and their tests SHALL match committed lock state. Verification SHALL fail when resolution differs from the lock state. A pull request that changes a resolved dependency version SHALL show that change in its diff.

#### Scenario: Dependency changed without updating locks
- **WHEN** a pull request changes a dependency declaration but not the lock state
- **THEN** verification SHALL fail and identify the configuration whose resolution differs

#### Scenario: Dependency upgraded with locks
- **WHEN** a pull request upgrades a dependency and updates the lock state
- **THEN** the old and new resolved versions SHALL be visible in the pull request's diff

### Requirement: Vulnerable dependencies fail verification
Verification SHALL fail a pull request that introduces or upgrades to a dependency with a known vulnerability of high or critical severity.

#### Scenario: Vulnerable dependency added
- **WHEN** a pull request adds a dependency version with a known critical vulnerability
- **THEN** verification SHALL fail and name the dependency and advisory

### Requirement: Verification never contacts production services
Automated verification SHALL NOT address the production Firebase project or the LG ThinQ service, SHALL NOT enable the opt-in live verification tests, and SHALL NOT send any AC command. Firebase-backed instrumentation SHALL run only against local Firebase emulators.

#### Scenario: Scheduled instrumentation run
- **WHEN** the instrumentation suite runs in automation
- **THEN** Firebase traffic SHALL go only to local Auth and Firestore emulators, and the live verification tests SHALL be reported as skipped

### Requirement: Instrumentation results are reported truthfully
A scheduled and manually triggerable run SHALL execute the instrumentation suite on an Android emulator. It SHALL report executed, passed, failed, and skipped counts. Any failure SHALL fail the run. A run in which tests were not executed SHALL NOT be reported as passing. The run SHALL NOT be part of the merge gate until its known failures are resolved.

#### Scenario: Instrumentation test fails
- **WHEN** an instrumentation test fails during a scheduled run
- **THEN** the run SHALL fail and name the failing tests

#### Scenario: Emulator unavailable
- **WHEN** the emulator does not start or no tests execute
- **THEN** the run SHALL fail rather than report zero failures as success

### Requirement: Verification evidence is retained
Every verification run SHALL publish its test and lint reports as downloadable evidence tied to the tested commit.

#### Scenario: Reviewing a past run
- **WHEN** the owner opens a completed verification run
- **THEN** its test and lint reports SHALL be downloadable and identify the commit they tested

### Requirement: Secrets are blocked before reaching the repository
The repository SHALL have secret scanning and push protection enabled. A push containing a recognized secret SHALL be rejected before it is stored.

#### Scenario: Push containing a token
- **WHEN** a push contains a recognized credential pattern
- **THEN** the push SHALL be rejected and the secret SHALL NOT appear in the repository

### Requirement: Verification runs with least privilege
Verification workflows SHALL default to a read-only repository token and SHALL reference third-party automation at immutable versions. A job that executes pull-request code SHALL have no access to secrets and no permission to change repository contents, releases, or settings. Its only allowed write is uploading analysis results.

#### Scenario: Workflow changed in a pull request
- **WHEN** a pull request modifies a workflow
- **THEN** that pull request's run SHALL still have no secrets and no permission to change repository contents, releases, or settings

### Requirement: Static code scanning reports findings
Static code scanning SHALL analyse pull requests and `main` and report findings on the pull request. It SHALL join the merge gate only once it analyses the project's Kotlin sources completely.

#### Scenario: Scanner reports a finding
- **WHEN** code scanning finds an issue in a pull request
- **THEN** the finding SHALL be shown on the pull request without blocking the merge while scanning is advisory
