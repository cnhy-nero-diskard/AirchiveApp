# AC Control Specification

## Purpose

Lets the owner view the AC's current ThinQ state and safely perform the four everyday controls supported by this specific model.

## Requirements

### Requirement: Live device state is distinct from sampled history
The Device screen SHALL obtain initial live state directly from LG and show its observation time separately from sampled history. Home and Device SHALL reuse an existing same-context live state on navigation; explicit refresh and eligible foreground scheduling SHALL obtain new state. If LG is unreachable, retained state SHALL be labeled old and commands requiring current capability knowledge SHALL be disabled.

#### Scenario: Open Device screen online
- **WHEN** the owner opens the Device screen with valid ThinQ access and no matching live read has been attempted
- **THEN** the app SHALL request current state and present its observation time separately from telemetry sync time

#### Scenario: Return to a previously read control surface
- **WHEN** the owner returns to Home or Device with an existing same-context live reading or read failure
- **THEN** navigation SHALL reuse that state without issuing or retrying a read, while explicit refresh and independent eligible foreground scheduling remain available

#### Scenario: LG unavailable
- **WHEN** a live state request fails
- **THEN** the app SHALL show a clear unavailable or stale state without claiming the AC's current settings are known

### Requirement: Four core controls follow the device profile
The app SHALL offer power, target temperature, operating mode, and fan strength only where the verified profile marks the relevant property writable. It SHALL use the profile's allowed values and temperature bounds, including model-specific units and steps, and SHALL omit or disable unsupported choices.

#### Scenario: Supported control
- **WHEN** the profile permits a requested value for one of the four controls
- **THEN** the app SHALL allow the owner to submit that command

#### Scenario: Unsupported control or value
- **WHEN** the profile does not permit a control or its proposed value
- **THEN** the app SHALL prevent the command and explain the model limitation

### Requirement: Commands show verified outcomes
Each command SHALL follow an explicit owner action, prevent accidental duplicate submission while pending, and show sending, accepted, confirmed, failed, or unconfirmed status. An accepted API response SHALL trigger a fresh state read; only observed matching state SHALL be called confirmed. The app SHALL NOT silently retry a command with an uncertain outcome or alter historical telemetry to make it appear successful.

#### Scenario: Command takes effect
- **WHEN** LG accepts a command and a subsequent state read matches the requested setting
- **THEN** the app SHALL show the control as confirmed

#### Scenario: Command accepted but state differs
- **WHEN** LG accepts a command but a bounded follow-up state check does not confirm the setting
- **THEN** the app SHALL show an unconfirmed outcome and offer a fresh read without automatically resending the command

#### Scenario: No control authority
- **WHEN** LG reports that the token lacks control authority
- **THEN** the app SHALL explain that a control-capable ThinQ token is required and link to credential replacement

### Requirement: Control and telemetry failures are independent
ThinQ controls SHALL remain available when Firestore history access or synchronization fails, provided LG can be reached and the local ThinQ credential and profile are valid. Offline history SHALL remain viewable when ThinQ control is unavailable.

#### Scenario: Firestore unavailable
- **WHEN** Firestore synchronization fails but LG status and control requests succeed
- **THEN** the owner SHALL still be able to operate the AC while history shows its stale status

### Requirement: Live values, drafts, and command feedback are scoped to the active owner and credential
Live readings, local drafts, and command feedback SHALL be presented only for the active owner and ThinQ credential context.

#### Scenario: Context invalidation clears retained presentation
- **WHEN** the active owner or credential context changes
- **THEN** Owner change, sign-out, credential removal/replacement or protected-key loss SHALL invalidate the previous context; its readings, drafts and feedback SHALL NOT reappear even after the new context succeeds. Controls SHALL remain unavailable until a successful device-matching live read and verified matching profile belong to the active context, and a failed new-context read SHALL NOT fall back to previous-context values.

#### Scenario: Owner switch before any successful read
- **WHEN** a different account signs in and the owner opens a control surface with no successful read for the new owner
- **THEN** no value, draft, or command feedback from the previous owner SHALL be shown and controls SHALL remain unavailable

#### Scenario: First read after replacement fails
- **WHEN** a credential is replaced and the first read for the new credential fails
- **THEN** the app SHALL present an unavailable state rather than the pre-replacement reading

### Requirement: Results in flight from a superseded context are ignored
A read or command result started under a previous owner, credential, or session SHALL be discarded when it completes after that context has ended.

#### Scenario: Late work cannot borrow or publish a replacement context
- **WHEN** work captured before a context change completes afterward
- **THEN** Such a result SHALL NOT establish current state, enable controls, or direct a command for the active context. An operation whose context becomes invalid before dispatch SHALL NOT send a command, and an already dispatched operation or its follow-up requests SHALL NOT borrow a replacement context's credential. A transient failure within the same unchanged context MAY keep a retained old reading visible with its age labeled, with commands disabled.

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
- **WHEN** the owner edits the setpoint, leaves for Settings or device details before the paused send dispatches, and returns in the same context
- **THEN** the unsent draft SHALL remain distinct from the observed temperature, leaving the control surface SHALL cancel its unsent countdown, and restoring that draft SHALL NOT arm another send

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
Minus and plus setpoint adjustments SHALL change the local draft immediately.

#### Scenario: Temperature submission preserves serialization and honest outcomes
- **WHEN** the owner submits a temperature draft or an earlier command remains in flight
- **THEN** The draft SHALL be applied by sending its final value once after one second passes with no further adjustment; each such pause SHALL send at most one command. The send SHALL be a temperature command only, with no coupled mode or fan change, and SHALL be serialized with other control commands so that only one command for the configured device is in flight at a time, including across owner/credential transitions; the submitted value SHALL match the active profile's supported values and current-state requirements. A draft whose send was not started because the device slot was busy or the context changed SHALL say it was not sent. An ambiguous outcome SHALL offer a fresh read and SHALL NOT resend the command. A pending command SHALL NOT be resent or cancelled merely because the Interface detail preference changed, and its status SHALL remain accurate after navigation. If a superseded request still occupies the submission slot, the new context SHALL disable conflicting controls with an explanation or report that no new command was submitted, without exposing the old command details or automatically queuing a retry.

#### Scenario: Adjust and apply
- **WHEN** the owner taps plus twice within one second and then stops
- **THEN** exactly one temperature command SHALL be sent, carrying the final draft value, with mode and fan untouched

#### Scenario: Ambiguous outcome
- **WHEN** the command response does not establish whether the requested value was applied
- **THEN** the app SHALL present an unconfirmed status with a fresh-read action and SHALL NOT resend automatically

#### Scenario: Preference change while a command is pending
- **WHEN** the owner changes Interface detail while the command is pending
- **THEN** the command SHALL be neither resent nor cancelled, and its status SHALL remain displayed truthfully after navigation

#### Scenario: Previous context still has an in-flight command
- **WHEN** a new context becomes eligible while a superseded command still occupies the device's submission slot
- **THEN** a second command SHALL NOT be sent, the busy condition SHALL be explained without prior-context target/outcome details, and no automatic submission SHALL occur when the slot becomes free

#### Scenario: Send not started
- **WHEN** the one-second pause ends while the device slot is busy or the context has changed
- **THEN** no command SHALL be sent and the draft SHALL be labeled as not sent

### Requirement: Context is checked immediately before a command is sent
The app SHALL confirm that the owner and credential context that started a command is still current immediately before sending the control request, after any profile read the send depends on. A command whose context changed before that point SHALL NOT be sent and SHALL be reported as not sent.

#### Scenario: Token removed during the profile read
- **WHEN** the owner removes the ThinQ token while the command's profile read is in progress
- **THEN** no control request SHALL be sent and the command SHALL be reported as not sent

### Requirement: Command tracking survives configuration changes
An in-flight command, its device slot, and its pending, unconfirmed, or confirmed outcome SHALL survive Activity recreation from rotation, theme, font-scale, or window changes. Recreation SHALL NOT cancel a command or re-enable controls that its outcome should keep disabled.

#### Scenario: Rotation while a command is in flight
- **WHEN** the device rotates while a command is in flight
- **THEN** the command SHALL continue, the recreated screen SHALL show its pending status, and a second command SHALL NOT be sent until the first resolves

### Requirement: Unconfirmed outcomes never invite a retry
When a command was accepted or possibly delivered but its result was not confirmed, the app SHALL say the change may have been applied and offer a fresh read. It SHALL NOT tell the owner to send the command again, whatever caused the confirmation to fail.

#### Scenario: Confirmation read rate-limited
- **WHEN** LG accepts a command and the confirmation read is rate-limited
- **THEN** the status SHALL say the change was sent but not confirmed and offer a fresh read, without retry wording

### Requirement: Read failures name their cause and recovery
A failed live read SHALL be reported by cause: token rejected or expired, no control authority, rate limited, AC offline, or network unavailable. A token or authority problem SHALL offer credential replacement. The cause and its recovery action SHALL be visible in both Simple and Detailed.

#### Scenario: Revoked token
- **WHEN** the device list or state read fails because LG rejects the token
- **THEN** Home and Device SHALL say the token was rejected and offer to replace it, in Simple and Detailed

#### Scenario: AC offline
- **WHEN** the device reports it is offline
- **THEN** the app SHALL say the AC is offline rather than suggest checking the phone's connection

### Requirement: Selectors never imply an unsettable current value
When the AC's current mode or fan speed is unknown or is not among the writable options, the selector SHALL show the current value as unknown or not settable and SHALL NOT position itself on any option. No command SHALL be sent until the owner deliberately chooses an option.

#### Scenario: Fan AUTO with only LOW and HIGH writable
- **WHEN** the AC reports fan AUTO and the profile allows only LOW and HIGH
- **THEN** the selector SHALL show AUTO as the current, not settable value, sit on no option, and send nothing until LOW or HIGH is chosen
