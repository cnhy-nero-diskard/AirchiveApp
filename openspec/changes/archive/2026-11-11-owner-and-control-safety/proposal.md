## Why

The Phase 0 audit of `main` @ `f07764e` (issue #4) found one P0 and six P1 defects in owner isolation and AC control.
- **The P0:** changing Google account anywhere other than Settings leaves the previous owner's ThinQ token active. The new account can read and command the AC.
- **The P1s:**
  - a command can still be sent after the token is removed or the account changes
  - recreating the Activity loses track of an in-flight command
  - unconfirmed commands are shown with copy that invites a retry
  - every read failure is reported as "check the connection"
  - the mode/fan selector can show, and then send, a value the AC is not on

None of these can wait for a production release. The temperature control also contradicts its spec. The owner chose to keep the shipped one-second send and update the spec.

## What Changes

- **The ThinQ token is bound to the owner who saved it.** Any owner change discards the previous owner's token, Room partition, and Firestore SDK cache, whichever path caused the change. That includes Settings, a sign-in from another screen, and Firebase ending the session.
- **A signed-in owner is never offered "Continue with Google".** While the access check runs, the app shows a checking state. Starting a sign-in while a session exists goes through the confirmed account-change flow.
- **A failed access check is cleared** by a later successful checked refresh.
- **Token removal deletes the Keystore key and the ciphertext,** and reports failure if either remains. The internal type that holds the token no longer prints it.
- **Context is rechecked immediately before the control POST,** after the profile pre-read. A context that changed during that read sends nothing.
- **Command work and its pending or unconfirmed state live in a process-scoped holder,** so rotation, theme, and font-scale changes neither cancel an in-flight command nor forget its outcome.
- **Unconfirmed outcomes always say the command may have been applied, and never suggest retrying.** They offer a fresh read.
- **Read failures name their cause:** token rejected, rate limited, AC offline, no control authority, or network. A token problem offers credential replacement. This is visible in Simple mode.
- **The mode and fan selectors don't imply a current value they can't represent.** An unknown or unsettable value is shown as such, and no option is sent until the owner deliberately selects one.
- **Temperature spec (BREAKING):** +/− edits the draft, and the final value is sent once after one second without further adjustment. This replaces the explicit-Apply wording in `ac-control` and `mobile-experience`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `owner-onboarding`: the credential is bound to its owner; every owner transition cleans up; sign-in is not offered while a session is being checked; removal is verified.
- `ac-control`: the one-second temperature send replaces Apply; commands are fenced until the POST; command tracking survives Activity recreation; unconfirmed copy never invites a retry; read failures name their cause and recovery; selectors never imply an unsettable current value.
- `mobile-experience`: Home and Device describe the setpoint send without an Apply button.

## Impact

- **`androidApp`:**
  - a new process-scoped holder for the ThinQ graph (an `Application` subclass or an equivalent singleton), and `MainActivity` binding to it
  - the credential store's owner binding and verified clear
  - Firestore cache handling on owner change
- **`shared` ThinQ code:** `ThinQContextCoordinator`, `KtorThinQClient`, `AcCommandController`, and the error-copy and selector UI in `HomeInstrument.kt` / `DeviceControlsCard.kt`.
- **`shared` owner flow:** `ProductionAirchiveApp` access-state handling, and `ProductionAccountController` / `OwnerAccessCoordinator` sign-in routing.
- **Tests:** host tests for each defect. Instrumented tests that currently assert the old behaviour (`assertTextAbsent("Apply")`, the generic read-failure text) are updated to the new specs.
- **Unchanged:** release signing, CI, and history sync, apart from clearing the cache on owner change.
