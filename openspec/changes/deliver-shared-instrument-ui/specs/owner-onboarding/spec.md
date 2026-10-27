## ADDED Requirements

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
