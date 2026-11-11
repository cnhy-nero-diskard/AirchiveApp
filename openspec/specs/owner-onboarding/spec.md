# Owner Onboarding Specification

## Purpose

Guides the single owner through safe account and ThinQ setup so a fresh installation can read its Airchive history and control the intended AC.

## Requirements

### Requirement: First-run setup distinguishes the two accounts
The app SHALL guide a fresh install through Google sign-in for Airchive history and a separate LG ThinQ Personal Access Token for live status and control. It SHALL explain where to create a control-capable token and SHALL NOT request a Google service-account key or reuse the collector's read-only token automatically.

#### Scenario: Fresh installation
- **WHEN** the app has no configured owner or ThinQ token
- **THEN** it SHALL present first-run setup before device control and remote history are offered

#### Scenario: Owner signed in but not enrolled
- **WHEN** Google sign-in succeeds but Firestore denies the device read because the owner UID is not yet enrolled
- **THEN** the app SHALL show an access-pending state and a retry action without claiming that sign-in alone grants history access

### Requirement: ThinQ setup verifies the intended device
The app SHALL accept a ThinQ token and account country, generate and persist a client ID for this installation, and verify that the token can read the configured AC's identity and profile before completing ThinQ setup. The app SHALL distinguish failed credentials, wrong account or country, unavailable device, and network failure without logging the token. Read verification SHALL NOT be presented as proof of control permission.

#### Scenario: Correct token and AC
- **WHEN** the entered token can read the configured AC's identity and profile
- **THEN** the app SHALL save the token securely and complete ThinQ setup

#### Scenario: Token cannot see the configured AC
- **WHEN** the token is accepted by LG but the expected AC is absent
- **THEN** the app SHALL explain the account or device mismatch and SHALL NOT replace a previously working token

#### Scenario: Network unavailable during verification
- **WHEN** verification cannot reach LG
- **THEN** the app SHALL offer retry without declaring the token invalid or persisting an unverified replacement

### Requirement: Credentials and account data remain private on the device
The app SHALL encrypt the ThinQ token at rest with a platform-protected key, mask it in the interface, omit it from logs and exported diagnostics, and keep it out of the history database. The owner SHALL be able to replace or remove it from Settings. Signing out or changing the Google account SHALL clear owner-bound history and the ThinQ token before another account's data is used.

#### Scenario: Existing owner edits the token
- **WHEN** the owner replaces the ThinQ token in Settings and verification succeeds
- **THEN** the new encrypted token SHALL become active without discarding the same owner's history

#### Scenario: Sign-out or account change
- **WHEN** the owner confirms sign-out or a different Google account
- **THEN** the app SHALL clear cached Airchive history and the local ThinQ token and require setup again

#### Scenario: Protected key unavailable
- **WHEN** the platform can no longer decrypt a saved token
- **THEN** the app SHALL request ThinQ setup again without crashing or exposing ciphertext

### Requirement: Setup and replacement state is scoped to the owner that started it
Pending token entry, account country, replacement-in-progress state, feedback, and confirmation state SHALL be scoped to the owner whose session started them. Sign-out, an account switch, cancelling the setup or replacement form, and removing the credential SHALL clear that pending and transient state so it cannot resurface under another owner or after setup has ended.

#### Scenario: Cancelled form followed by an account switch
- **WHEN** the owner cancels token entry and a different Google account signs in
- **THEN** no pending token, country value, or feedback from the cancelled session SHALL be presented

#### Scenario: Credential removal while a replacement is pending
- **WHEN** the credential is removed while a replacement is awaiting verification
- **THEN** the pending replacement state SHALL be cleared before any further setup is offered

### Requirement: Interface detail selection never disturbs an in-progress setup
Changing the Interface detail preference SHALL preserve any same-owner setup form or confirmation dialog that is open, including its entered values, and SHALL NOT execute, submit, or verify anything as a result of the selection. The selection's own request and mutation restrictions are specified in the mobile-experience capability.

#### Scenario: Preference changed during token entry
- **WHEN** the owner changes Interface detail while entering a token or while a confirmation dialog is open
- **THEN** the form values and dialog SHALL remain intact and no verification, save, or action SHALL run from the selection

### Requirement: Superseded credential verification cannot commit or revive state
When owner/session context changes, credential removal occurs, a setup attempt is cancelled, or a newer attempt supersedes a token verification in flight, its completion SHALL be discarded: it SHALL NOT save a token, revive a device profile, or present feedback for the active context. A result SHALL commit only if its originating owner, credential context and setup attempt are still current at the protected-store save boundary.

#### Scenario: Account switched during verification
- **WHEN** verification returns after a different Google account signs in
- **THEN** no token SHALL be persisted and no profile or feedback SHALL appear for the new owner

#### Scenario: Credential is removed while the same owner remains signed in
- **WHEN** verification returns after credential removal or setup cancellation without an owner change
- **THEN** the superseded candidate SHALL NOT be saved or repopulate live/profile state

#### Scenario: Current verification succeeds
- **WHEN** the current attempt verifies successfully and its owner/context still match at commit
- **THEN** only that candidate SHALL replace the credential under the existing verification rules, and success feedback SHALL belong to the resulting active context

### Requirement: A failed or abandoned replacement leaves the working credential intact
For the same owner, a replacement that fails verification, cannot reach LG, or is abandoned SHALL NOT overwrite, remove, or disable the previously working token and SHALL NOT leave a partially applied credential state. The old token SHALL become fully superseded only after the replacement verifies successfully.

#### Scenario: Replacement verification fails
- **WHEN** a same-owner replacement fails verification or cannot reach LG
- **THEN** the previously working token SHALL remain active and usable and the failed candidate SHALL NOT be persisted

#### Scenario: Replacement abandoned after entry
- **WHEN** the owner leaves the replacement form without completing verification
- **THEN** the existing credential SHALL remain unchanged and no feedback about the abandoned candidate SHALL be shown

### Requirement: Plaintext tokens never enter history, preferences, logs, or evidence
The plaintext ThinQ token SHALL NOT be written to history, preferences, saved-instance state, logs, exported diagnostics or acceptance evidence. Durable credential material SHALL exist only as encrypted data in the protected credential store; transient setup input SHALL be cleared when its operation/form/context ends.

#### Scenario: Inspect local stores after setup
- **WHEN** setup completes and the local history, preference, and log stores are inspected
- **THEN** none of them SHALL contain the plaintext token while the protected credential store holds only encrypted credential material

### Requirement: Display preference is independent of account and credential state
The Interface detail choice SHALL be retained across sign-out, history cache removal, and credential removal, and SHALL remain available before history access or ThinQ access exists. Its selection, storage, and immediate-effect semantics are specified in the mobile-experience capability and SHALL NOT be conditioned on owner or credential state.

#### Scenario: No credential and no cached history
- **WHEN** an installation has no ThinQ credential and no cached history
- **THEN** Interface detail SHALL still be available and its saved choice SHALL remain in effect

### Requirement: The ThinQ credential belongs to the owner who saved it
A saved ThinQ token SHALL be bound to the Firebase owner UID that saved it. The app SHALL treat a token bound to a different owner, or to no owner, as absent and SHALL delete it before any live read or command for the current owner.

#### Scenario: Different owner finds a saved token
- **WHEN** owner B is signed in and the stored token was saved by owner A
- **THEN** the app SHALL report no ThinQ credential for B, delete A's token, and send no ThinQ request with it

#### Scenario: Same owner restarts the app
- **WHEN** owner A reopens the app with A's token saved
- **THEN** the token SHALL be available to A as before

### Requirement: Every owner transition clears the previous owner's local data
Whenever the signed-in owner changes or the session ends, the app SHALL clear the previous owner's ThinQ token, cached history partition, and Firebase SDK document cache before another owner's data or controls are used. This SHALL hold for confirmed sign-out, account change, sign-in started outside Settings, and a session ended by Firebase.

#### Scenario: Sign-in from Home replaces the account
- **WHEN** owner A's session is replaced by owner B through any sign-in entry point
- **THEN** A's token, history partition, and SDK cache SHALL be cleared before B sees live state or controls

#### Scenario: Firebase ends the session
- **WHEN** Firebase reports that A's session ended without a user action
- **THEN** A's token and cached history SHALL be cleared, and a later sign-in by any account SHALL start without A's credential

### Requirement: Sign-in is not offered while a session is being verified
While a Firebase session exists and its access check is pending, the app SHALL show a checking state and SHALL NOT offer Google sign-in. Starting a sign-in while a session exists SHALL go through the confirmed account-change flow. A later successful checked refresh SHALL clear a previous access-check failure.

#### Scenario: Cold start with a saved session
- **WHEN** the app starts with a signed-in owner and the access check has not completed
- **THEN** Home SHALL show that access is being checked and SHALL NOT show "Continue with Google"

#### Scenario: Check failed offline, sync later succeeds
- **WHEN** the start-up access check failed offline and a later checked background refresh succeeds
- **THEN** the access-failure message SHALL be cleared and range loading SHALL be re-enabled

### Requirement: Credential removal is verified
Removing the ThinQ credential SHALL delete both the encrypted token and its platform key. If either remains, removal and any sign-out or account change that depends on it SHALL report failure and SHALL NOT proceed as if the token were gone.

#### Scenario: Ciphertext cannot be deleted
- **WHEN** the encrypted token file cannot be deleted during removal
- **THEN** removal SHALL report failure, the owner SHALL remain signed in, and the token SHALL NOT be reported as removed

#### Scenario: Successful removal
- **WHEN** removal completes
- **THEN** neither the encrypted token nor its key SHALL exist, and no later launch SHALL load a token
