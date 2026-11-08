# CI and branch gate

## Pull request verification

`verify.yml` runs on every pull request targeting `main` and every push to
`main`, with no path filters. It runs `build` and `dependencies` in parallel.
The `build` job runs shared host tests, assembles the debug app and test APK,
and runs Android debug lint. The `dependencies` job scans the committed
lockfiles. The only required status check is `required`, which passes only when
both jobs succeed; a failure, cancellation, or skip fails the aggregate check.
The workflow uses no repository secrets and grants only `contents: read`.

Run the same build locally with:

```sh
./gradlew --continue \
  :shared:testAndroidHostTest \
  :androidApp:assembleDebug \
  :androidApp:assembleDebugAndroidTest \
  :androidApp:lintDebug
```

The Gradle Wrapper allows 60 seconds for distribution downloads and retries
four times with a 5-second initial backoff that doubles between attempts.

The PR run uploads test and lint reports as an artifact named for the commit
it tested.

## Dependency lockfiles

CI dependency versions are recorded in the module `gradle.lockfile` files.
Strict locking is enabled for the configurations listed below. To refresh the
lock state after an intentional dependency change, run:

```sh
./gradlew --no-configuration-cache resolveCiDependencies --write-locks
```

The root task resolves the Android compile and runtime classpaths for debug,
release, and instrumentation tests, plus the shared Android and host-test
classpaths. It does not resolve the shared iOS targets, which CI does not build.
Commit the resulting lockfile changes with the dependency change.

## Dependency vulnerability gate

The `dependencies` job runs OSV-Scanner against both committed Gradle lockfiles.
High and critical findings fail the job and name the dependency and advisory ID;
low and medium findings do not block verification. `osv-scanner.toml` is the
exception list. Each `[[IgnoredVulns]]` entry must include an advisory `id`, a
non-empty `reason`, and a future `ignoreUntil` date. CI rejects expired or
incomplete entries and does not allow package-wide ignore overrides. The
transitive Guava dependency is constrained to `33.7.2-android` to resolve
`GHSA-xxph-c9ww-hj94` without an exception.

## Scheduled instrumentation

`instrumentation.yml` runs daily at 02:00 UTC and supports manual dispatch. It
starts an x86_64 API 36 emulator and runs Firebase-backed tests only against the
Auth and Firestore emulators configured in `firebase.test.json`. The test
classes run in these batches:

| Batch | Instrumentation classes |
| --- | --- |
| `auth-and-task-units` (3 classes) | `GoogleSignInRequestTest`, `FirebaseOwnerIdentityExchangeTest`, `FirebaseTaskAwaitTest` |
| `local-storage` (2 classes) | `AndroidEncryptedThinQCredentialStoreTest`, `AndroidInterfaceDetailPreferencesTest` |
| `firebase-emulators` (2 classes) | `FirebaseOwnerIdentityEmulatorTest`, `FirebaseAirchiveTelemetryEmulatorTest` |
| `process-restart` (1 class) | `SharedInstrumentProcessRestartTest` |
| `live-verification-skips` (2 classes) | `LiveOwnerAccessVerificationTest`, `LiveHistoryCacheVerificationTest` |
| `shared-instrument-ui` (1 class) | `SharedInstrumentUiTest` |

The six batches cover all 11 instrumentation classes. The workflow does not
pass the live verification runner arguments. A guard fails if any workflow
file mentions either opt-in argument. Each batch has a 25-minute timeout. The
JUnit summary reports executed, passed, failed, and skipped test counts, names
failures, and fails if any test failed or no tests ran.
JUnit XML and logcat are uploaded as an artifact. The live verification classes
must appear as skipped. After seven consecutive green scheduled runs, a
follow-up change can promote instrumentation to a pre-merge check.

## Static code analysis

`codeql.yml` is an advisory Kotlin and Java scan on pull requests, `main`, and
a weekly schedule. It is not part of the `required` merge check. Its manual
`assembleDebug` build supports Kotlin source extraction. The successful run on
2026-11-08 compiled `:shared:compileAndroidMain` and
`:androidApp:compileDebugKotlin` under CodeQL's Java/Kotlin tracer, and analysis
completed without extraction errors. Kotlin extraction is complete for the
Android debug build.

## Repository gate and emergency procedure

The `main-gate.json` ruleset targets the default branch through GitHub's
REST API ref selector (`target: "branch"` with
`conditions.ref_name.include: ["~DEFAULT_BRANCH"]`). It requires a pull request
and the `required` check with zero approvals and no bypass actors. The existing
`default` ruleset is unchanged. Integration `1144995` is the OpenAI
**ChatGPT Codex Connector** (`chatgpt-codex-connector`) installation identified
on the existing ruleset; it is not a bypass actor on the new gate.

The `main-gate` ruleset is active as ruleset ID `26794798`. Secret scanning
and push protection are enabled in repository settings. Non-provider
patterns remain disabled because this repository is user-owned; GitHub
currently lists generic-pattern scanning as available for organization-owned
repositories on GitHub Team with GitHub Secret Protection. See [GitHub's
eligibility documentation](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enabling-secret-scanning-for-generic-patterns).
If the repository moves to an eligible organization, enable that setting
and update this note.

If verification must be bypassed during an outage, disable the `main` gate
ruleset, land the change through a pull request, then re-enable the ruleset.
Record the disable and re-enable window in that pull request.

## Android debug lint

`:androidApp:lintDebug` uses `androidApp/lint-baseline.xml`. Baseline findings
remain in the lint report; warnings are treated as errors, so a finding not in
the committed baseline fails the build. The baseline currently records:

- `OldTargetApi` — the app targets SDK 36.
- `DataExtractionRules` — the manifest sets `android:allowBackup` without a
  data extraction rules resource.
- `ObsoleteLintCustomCheck` — the Lifecycle lint checks use an API not
  available in this lint environment.
- `MissingApplicationIcon` — the application manifest has no explicit icon.
- `UseKtx` — a `SharedPreferences` call can use the KTX extension.
- `AutoboxingStateCreation` — the preview activity stores an `Int` with
  `mutableStateOf` instead of `mutableIntStateOf`.

Three checks are disabled because their result depends on what upstream has
published, rather than only on this repository's contents:

- `GradleDependency` reports when newer dependency versions are available.
  Dependency versions are instead reviewed through the committed lockfiles;
  OSV-Scanner handles known high and critical advisories.
- `NewerVersionAvailable` also checks for newer upstream dependency releases,
  so the same commit could change status over time.
- `AndroidGradlePluginVersion` checks whether a newer AGP release is available.
  AGP is pinned in the version catalog, so this check would make lint depend on
  release timing instead of the repository state.
