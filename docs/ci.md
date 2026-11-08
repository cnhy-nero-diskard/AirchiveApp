# CI and branch gate

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
