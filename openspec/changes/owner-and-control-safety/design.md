# Design

## Context

See proposal.md for motivation and the issue #4 audit comment for evidence.

**Current state of `main` (after PR #8):**
- **Owner changes.** Owner cleanup happens only in `ProductionAccountController.signOutConfirmed` / `changeAccountConfirmed`. Other owner changes go through `MainActivity`'s `session.collect` → `ThinQContextCoordinator.onOwnerChanged`, which only clears the on-screen state.
- **The credential store** (`AndroidEncryptedThinQCredentialStore`) keeps a single `noBackupFilesDir/thinq/thinq_credential.enc` per install. `clear()` ignores the result of `File.delete()` and never deletes the Keystore alias.
- **Access state.** `ProductionAirchiveApp` starts each session with `rawAccessState = SignedOut`, and `productionAccessForSession` passes `SignedOut` through. That is why "Continue with Google" is shown during `restore()`.
- **The control send.** `KtorThinQClient.setStateWithContext` reads the profile and then POSTs. The last context check is in `AcCommandController` (`:247`), before the port call.
- **Graph lifetime.** `AndroidProductionThinQGraph` is built in `MainActivity.onCreate` and closed in `onDestroy`, which cancels its `commandScope`. There is no `Application` subclass and no `configChanges`.
- **Firestore** is created with default settings (`MainActivity.kt:209`), so it uses a persistent disk cache.

## Goals / Non-Goals

**Goals:**
- One choke point that owner changes cannot get around.
- Nothing can send with a context that has gone stale.
- Command state lives as long as the process, not the Activity.

**Non-Goals:**
- Surviving process death for an in-flight command. A killed process loses the outcome. Only Activity recreation is covered.
- Rate-limit backoff, and the other ThinQ P2s from the audit.
- History truthfulness fixes, which belong to a separate change.
- Production Firestore rules.

## Decisions

### 1. The owner binding lives in the credential record

- The encrypted record stores the owner UID next to the token. That is authenticated data in the GCM record, so it can't be swapped without failing decryption.
- `readToken(ownerUid)` returns the token only for a matching UID. A mismatch, or a legacy record without a UID, makes the store clear itself and report absent.
- The owner's own pre-change token is migrated once. If the first read after upgrade comes from a signed-in owner whose access check passes, the legacy record is re-saved bound to that UID. This keeps the current phone's token across the update, so no re-entry is needed.

*Alternative considered:* clearing the token on every owner change, without binding. Rejected on its own: it relies on catching every change event. Binding fails closed even if an event is missed. Both are done (§2).

### 2. One owner-transition path

`MainActivity`'s session collector calls one suspend function, `OwnerTransition.onSessionChanged(previous, next)`, which runs under the account controller's `operationMutex`. When the UID changes or becomes null, it:
1. invalidates the ThinQ context
2. clears the previous partition
3. clears the credential, verifying the result
4. clears the Firestore cache with `terminate()` + `clearPersistence()`, then re-creates the instance lazily

Confirmed sign-out and account change already perform steps 1–3 first, so for them this is a no-op re-check. Any cleanup failure is shown on Settings and blocks live controls until resolved.

*Alternative considered:* switching Firestore to an in-memory cache. Rejected because `clearPersistence` keeps the SDK's offline persistence for the same owner. Every read is `Source.SERVER` anyway, so either works. If `clearPersistence` proves unreliable in tests, switch to `MemoryCacheSettings`.

### 3. Access state while checking

`rawAccessState` starts as `AccessPending(session)` whenever a session exists. Home renders that as "Checking history access…" with no sign-in button. `signIn()` with an existing session routes to the account-change confirmation.

The loop's `checkedRefresh` result is published to `rawAccessState`, so a later success clears an earlier `AccessCheckFailed`.

### 4. Fence before the POST

The port API changes from `setState(command)` to `setState(command, guard)`. The guard is `suspend () -> Boolean`, supplied by `AcCommandController` from `lifecycle.isCurrentContext(captured)`.

`KtorThinQClient` calls the guard after the profile read and immediately before the POST. If it returns false, the result is `NotSent(StaleContext)` and the POST is never made. Fakes implement the same contract, so the existing invalidation tests exercise it.

### 5. Process-scoped command holder

A new `AirchiveApplication` (registered in the manifest) owns `AndroidProductionThinQGraph` and its `commandScope`. `MainActivity` gets the graph from the application and no longer closes it in `onDestroy`.

The graph is closed only when the owner transition (§2) invalidates it. Its outcome `StateFlow`s are already observable, so a recreated Home binds to the same pending, unconfirmed, or confirmed state. The credential store and the CIO engine move with it, so they are no longer rebuilt on every recreation.

*Alternative considered:* `configChanges` in the manifest. Rejected because it doesn't cover window or multi-window changes, and it fights Compose.

### 6. Outcome and failure copy

- **`Unconfirmed`** always says "Sent, but LG didn't confirm the change. It may have been applied." with a "Read current state" action, whatever the attached failure.
- **Read failures** keep their `ThinQClientException` kind through `ThinQEntryResult`. `HomeInstrument` maps each kind to text and an action:
  - token rejected or no authority → "Replace token" (opens Settings)
  - rate limited → wait text
  - offline → "AC is offline"
  - network → connection text

  The text is shown outside disclosures in both modes.
- **A send that didn't start** (busy slot or stale context) labels the draft "Not sent".

### 7. Selector with an unrepresentable current value

When `current` is null or not in `options`, `SteppedChoice` renders the track with no thumb (no selected position) and a label "Current: AUTO · not settable here" (or "unknown"). Only a tap or drag that lands on an option and is released sends a command. The slider's `onValueChangeFinished` sends only when a deliberate selection exists.

### 8. Token type hygiene

`CredentialContext` becomes a regular class with a `toString()` that masks the token, and a host test asserts the mask.

## Risks / Trade-offs

- **Migration of the legacy credential record could fail.** → It falls back to "token absent", and the owner re-enters the token. That costs a re-entry, never a leak. A host test covers both the migrate and the fallback branch.
- **`clearPersistence()` requires a terminated instance**, and any reference to the old instance throws afterwards. → The telemetry adapter obtains Firestore through a provider that re-creates the instance after termination. An instrumented test runs sign-out followed by a fresh read.
- **An application-scoped graph outlives screens**, so a leak there lives for the whole process. → The graph holds no Activity reference. Its `Context` is the application.
- **Changing the port signature touches every fake.** → It is a mechanical change, covered by the existing suites.

## Migration Plan

Ship in the next release. Nothing on the server changes. The owner's current token survives through the one-time binding in §1. Rollback means reverting the PR. A record bound to an owner UID is not readable by an older build, which would ask for the token again.
