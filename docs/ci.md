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
