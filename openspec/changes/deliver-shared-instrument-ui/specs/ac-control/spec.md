## ADDED Requirements

### Requirement: Live values, drafts, and command feedback are scoped to the active owner and credential
Live readings, local drafts, and command feedback SHALL be presented only for the active owner and ThinQ credential context. Owner change, sign-out, credential removal/replacement or protected-key loss SHALL invalidate the previous context; its readings, drafts and feedback SHALL NOT reappear even after the new context succeeds. Controls SHALL remain unavailable until a successful device-matching live read and verified matching profile belong to the active context, and a failed new-context read SHALL NOT fall back to previous-context values.

#### Scenario: Owner switch before any successful read
- **WHEN** a different account signs in and the owner opens a control surface with no successful read for the new owner
- **THEN** no value, draft, or command feedback from the previous owner SHALL be shown and controls SHALL remain unavailable

#### Scenario: First read after replacement fails
- **WHEN** a credential is replaced and the first read for the new credential fails
- **THEN** the app SHALL present an unavailable state rather than the pre-replacement reading

### Requirement: Results in flight from a superseded context are ignored
A read or command result started under a previous owner, credential, or session SHALL be discarded when it completes after that context has ended. Such a result SHALL NOT establish current state, enable controls, or direct a command for the active context. An operation whose context becomes invalid before dispatch SHALL NOT send a command, and an already dispatched operation or its follow-up requests SHALL NOT borrow a replacement context's credential. A transient failure within the same unchanged context MAY keep a retained old reading visible with its age labeled, with commands disabled.

#### Scenario: Old read completes after replacement
- **WHEN** a read issued before credential replacement returns after the replacement completes
- **THEN** its result SHALL be discarded and SHALL NOT enable controls or drive any command

#### Scenario: Transient failure in the same context
- **WHEN** a read fails while the owner and credential are unchanged and a labeled prior reading exists
- **THEN** that old reading MAY remain visible with its age, and commands requiring current capability knowledge SHALL stay disabled

#### Scenario: Scheduled command is superseded before dispatch
- **WHEN** a command is waiting to dispatch and its originating owner or credential context is invalidated
- **THEN** it SHALL NOT be sent using the replacement credential, and a prior dispatched command SHALL NOT be retried to compensate

### Requirement: Temperature drafts survive same-context navigation
A local temperature draft SHALL be preserved while the owner navigates from Home or Device to Settings or into device details and back under the same owner and credential. A fresh same-context read SHALL update the observed value without overwriting or submitting a still-supported pending draft, and the last confirmed setting SHALL remain identifiable while editing. Persistence across process death SHALL NOT be promised.

#### Scenario: Draft through a Settings round trip
- **WHEN** the owner edits the setpoint, opens Settings or device details, and returns without applying
- **THEN** the unapplied draft SHALL still be present and clearly distinct from the observed temperature

#### Scenario: Fresh read during an unapplied draft
- **WHEN** a successful current read arrives while an unapplied draft exists
- **THEN** the observed value SHALL refresh without overwriting or submitting the draft

### Requirement: Profile and context changes invalidate drafts instead of substituting values
When the verified profile changes so that a pending draft is no longer supported or no longer within bounds, the app SHALL invalidate the draft with an explanation and SHALL NOT silently clamp, substitute, or apply it. A change of owner or credential SHALL discard every draft from the previous context.

#### Scenario: Draft falls outside the new bounds
- **WHEN** the profile's temperature bounds change while an out-of-range draft is pending
- **THEN** the draft SHALL be invalidated with an explanation and no command SHALL be sent for it

#### Scenario: Owner or credential change with a pending draft
- **WHEN** the owner or the credential changes while a draft is pending
- **THEN** the draft SHALL be discarded rather than carried into the new context

### Requirement: Apply sends only temperature and keeps command status honest
Minus and plus setpoint adjustments SHALL change only the local draft. Apply SHALL send a temperature command only, with no coupled mode or fan change, and SHALL be serialized with other control commands so that only one command for the configured device is in flight at a time, including across owner/credential transitions; the submitted value SHALL match the active profile's supported values and current-state requirements. An ambiguous outcome SHALL offer a fresh read and SHALL NOT resend the command. A pending command SHALL NOT be resent or cancelled merely because the Interface detail preference changed, and its status SHALL remain accurate after navigation. If a superseded request still occupies the submission slot, the new context SHALL disable conflicting controls with an explanation or report that no new command was submitted, without exposing the old command details or automatically queuing a retry.

#### Scenario: Adjust and apply
- **WHEN** the owner changes the setpoint with minus or plus and taps Apply
- **THEN** only a temperature command SHALL be sent, with mode and fan untouched

#### Scenario: Ambiguous outcome
- **WHEN** the command response does not establish whether the requested value was applied
- **THEN** the app SHALL present an unconfirmed status with a fresh-read action and SHALL NOT resend automatically

#### Scenario: Preference change while a command is pending
- **WHEN** the owner changes Interface detail while the command is pending
- **THEN** the command SHALL be neither resent nor cancelled, and its status SHALL remain displayed truthfully after navigation

#### Scenario: Previous context still has an in-flight command
- **WHEN** a new context becomes eligible while a superseded command still occupies the device's submission slot
- **THEN** a second command SHALL NOT be sent, the busy condition SHALL be explained without prior-context target/outcome details, and no automatic submission SHALL occur when the slot becomes free
