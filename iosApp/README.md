# iOS scaffold and Mac-side handoff

## Current status

The iOS target is a scaffold only. Its SwiftUI entry point embeds the shared
Compose controller, and the Xcode build phase invokes Gradle to build/embed the
`Shared` framework. `shared/build.gradle.kts` declares `iosArm64` and
`iosSimulatorArm64`; `shared/src/iosMain/.../DatabaseBuilder.ios.kt` supplies
the Room database builder. The iOS launcher intentionally shows a setup-required
screen instead of the in-memory demo: no iOS identity, Firestore, or Keychain
adapter is connected yet.

The following shared contracts are the platform adapter boundaries; implement
native iOS adapters against these contracts rather than adding Firebase or
Keychain details to shared UI/domain code:

| Shared contract | iOS implementation required on a Mac |
| --- | --- |
| `OwnerIdentityPort` | Firebase Auth / Google sign-in; expose the Firebase UID and typed sign-in/session outcomes. |
| `AirchiveTelemetryPort` | Firebase Firestore reads with the signed-in owner's identity; only the approved device paths, pages no larger than 250, and no write/listener API. Normalize through the shared parsers. |
| `LocalHistoryPort` | Room KMP storage in the app container, partitioned by project, UID, and configured device. Use an absolute database path and preserve the shared migrations. |
| `ThinQCredentialStore` | Keychain-backed PAT and stable per-install client ID. Keep token bytes out of Room, logs, backups, and diagnostics; expose only masked credential state. |

The Android implementations are reference behavior, not iOS code. Do not enable
the production experience until all required iOS adapters and the production
composition are connected and verified. Do not use fake providers as an iOS
fallback.

## Mac-side setup and verification

1. On a supported Mac, install Xcode and the repository's supported JDK, then
   open `iosApp/iosApp.xcodeproj`. Confirm the `Airchive` target's build phase
   completes `:shared:embedAndSignAppleFrameworkForXcode` and links `Shared`.
   The project bundle identifier is `cloud.airchive.personal.ios`.
2. Register/configure the iOS app in the intended Firebase project and enable
   the Google sign-in provider. Add the downloaded `GoogleService-Info.plist`
   to the local Xcode app target; it is ignored by Git. Verify the project ID
   matches the non-secret app configuration and that no service-account key is
   added to the app.
3. Implement the native adapters listed above. The Firestore adapter must use
   only the enrolled owner's Firebase session, the configured device ID, the
   four authorized read path shapes, and pages of at most 250. Keep all client
   Firestore operations read-only. Store the Room database inside the app's
   container, use the existing schema/migrations, and never persist raw
   Firestore payloads. Use Keychain for the ThinQ credential; do not log or
   export it.
4. Connect those adapters to the shared production history composition. Keep
   the Device controls disabled until a separate control-capable PAT and the
   four deliberate real-device command checks have been completed. Automated
   tests must not actuate the AC.
5. Build and run the `Airchive` target in an iOS simulator, then on the target
   device with the intended signing identity. Verify sign-in/session restore,
   exact enrolled UID and configured device, bounded first and incremental
   reads, read-denial behavior, offline Room restart, migration behavior,
   Keychain save/read/replacement/removal, account-change cleanup, all five
   routes, and the separately approved manual control checks. Record simulator
   and physical-device results separately; do not infer device behavior from a
   simulator run.

For a command-line simulator framework build, run from the repository root on
the Mac:

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Then open the Xcode project and build/run the `Airchive` scheme for the selected
simulator or signed device. iOS compilation, signing, Firebase setup, and device
verification have **not been tested on Windows**.
