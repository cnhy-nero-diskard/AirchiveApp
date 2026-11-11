# Tasks

## 1. Owner-bound, verifiably removable credential

- [x] 1.1 Store the owner UID in the encrypted credential record (GCM-authenticated). Make reads owner-scoped: a mismatched or unbound record clears itself and reports absent. Verify with host/instrumented store tests for: same owner reads, other owner gets absent and the record is deleted, legacy record migration, and the legacy fallback.
- [x] 1.2 Make `clear()` delete the ciphertext and the Keystore alias, and fail if either remains. Propagate failure through removal, sign-out, and account change. Verify with a test where the file cannot be deleted (removal reports failure, owner stays signed in) and a test that the alias is gone after clear.
- [x] 1.3 Replace the `CredentialContext` data class with a class whose `toString()` masks the token. Verify a host test asserts the PAT never appears in its `toString()`.

## 2. Single owner-transition path

- [x] 2.1 Add `OwnerTransition.onSessionChanged(previous, next)` under the account `operationMutex`. On a UID change or session end it invalidates the ThinQ context, clears the previous partition and credential, and clears the Firestore cache (`terminate` + `clearPersistence`, with the instance re-created lazily). Wire `MainActivity`'s session collector to it. Verify with a host test where emitting owner B from the identity port without the controller leaves A's token absent, A's partition empty, and `onDeviceOpened()` Unavailable.
- [x] 2.2 Surface cleanup failure on Settings and block live controls until it is resolved. Verify with a host test using a failing clear.
- [x] 2.3 Add an emulator test: seed and read a document, sign out, then assert `get(Source.CACHE)` finds nothing and a new server read works. Verify it passes on `ANDROID_SERIAL=emulator-5554`.

## 3. Access state during checks

- [x] 3.1 Start `rawAccessState` as `AccessPending(session)` when a session exists. Render "Checking history access…" with no sign-in button. Route `signIn()` with an existing session to the account-change confirmation. Verify with a Compose host test: a signed-in fake with `restore()` suspended shows no "Continue with Google".
- [x] 3.2 Publish the loop's `checkedRefresh` result to `rawAccessState`. Verify with a test where the first check fails offline, a later checked refresh succeeds, and the failure card clears with range loading re-enabled.

## 4. Command send safety

- [x] 4.1 Add a pre-send guard to the operation port (`setState(command, guard)`). Call it in `KtorThinQClient` after the profile read and before the POST, and update the fakes. Verify with a coordinator + MockEngine test that suspends `/profile`, removes the credential, and asserts zero `/control` requests and a "not sent" outcome.
- [x] 4.2 Add `AirchiveApplication`, owning `AndroidProductionThinQGraph`, the credential store, and the HTTP engine. `MainActivity` binds to it and no longer closes it in `onDestroy`; the graph is closed only by the owner transition. Verify with an instrumented test: dispatch against a suspended fake port, `scenario.recreate()`, then assert the pending status is shown and a second tap sends nothing.
- [x] 4.3 Label a temperature draft whose one-second send did not start (busy slot or stale context) as "Not sent". Verify with a host test that occupies the slot during the pause.

## 5. Truthful outcomes, failures, and selectors

- [x] 5.1 Make `Unconfirmed` copy failure-independent ("may have been applied", plus a fresh-read action, no retry wording). Verify with a host test over every `CommandFailure` that no retry wording appears.
- [x] 5.2 Carry the read-failure kind through `ThinQEntryResult` and live state, and map it to cause text and an action (token → Replace token; offline; rate limited; network), outside disclosures in both modes. Verify with host tests for a revoked token, offline, and rate limit in Simple and Detailed. Update `SharedInstrumentUiTest`'s generic read-failure assertion.
- [x] 5.3 When `current` is unknown or not writable, render `SteppedChoice` with no thumb position and a "not settable / unknown" label, and send only after a deliberate selection. Verify with a test `SteppedChoice(options=[LOW,HIGH], current=AUTO)` where there is no selected position and no command until LOW or HIGH is chosen.

## 6. Spec-aligned temperature behaviour and suite

- [x] 6.1 Update the tests and docs that encode the old Apply wording (`docs/home-instrument-refinement.md`, `docs/shared-instrument-screens.md`, `docs/interface-detail.md`) to the one-second paused send. Keep `SharedInstrumentUiTest`'s send-after-one-second test. Verify with `grep -rn "Apply" docs/` that the only hits are unrelated.
- [x] 6.2 Run `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`, then the affected `SharedInstrumentUiTest` classes in small batches on the emulator started by `scripts/test-emulator.sh start`. Verify everything is green and record counts and the commit in the PR.

## Workflow follow-up

- Open the PR referencing #4, then sync and archive after merge.
