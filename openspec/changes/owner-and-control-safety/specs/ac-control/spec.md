# Spec Delta

## MODIFIED Requirements

### Requirement: Apply sends only temperature and keeps command status honest
Minus and plus setpoint adjustments SHALL change the local draft immediately. The draft SHALL be applied by sending its final value once after one second passes with no further adjustment; each such pause SHALL send at most one command. The send SHALL be a temperature command only, with no coupled mode or fan change, and SHALL be serialized with other control commands so that only one command for the configured device is in flight at a time, including across owner/credential transitions; the submitted value SHALL match the active profile's supported values and current-state requirements. A draft whose send was not started because the device slot was busy or the context changed SHALL say it was not sent. An ambiguous outcome SHALL offer a fresh read and SHALL NOT resend the command. A pending command SHALL NOT be resent or cancelled merely because the Interface detail preference changed, and its status SHALL remain accurate after navigation. If a superseded request still occupies the submission slot, the new context SHALL disable conflicting controls with an explanation or report that no new command was submitted, without exposing the old command details or automatically queuing a retry.

#### Scenario: Adjust and apply
- **WHEN** the owner taps plus twice within one second and then stops
- **THEN** exactly one temperature command SHALL be sent, carrying the final draft value, with mode and fan untouched

#### Scenario: Send not started
- **WHEN** the one-second pause ends while the device slot is busy or the context has changed
- **THEN** no command SHALL be sent and the draft SHALL be labeled as not sent

#### Scenario: Ambiguous outcome
- **WHEN** the command response does not establish whether the requested value was applied
- **THEN** the app SHALL present an unconfirmed status with a fresh-read action and SHALL NOT resend automatically

#### Scenario: Preference change while a command is pending
- **WHEN** the owner changes Interface detail while the command is pending
- **THEN** the command SHALL be neither resent nor cancelled, and its status SHALL remain displayed truthfully after navigation

#### Scenario: Previous context still has an in-flight command
- **WHEN** a new context becomes eligible while a superseded command still occupies the device's submission slot
- **THEN** a second command SHALL NOT be sent, the busy condition SHALL be explained without prior-context target/outcome details, and no automatic submission SHALL occur when the slot becomes free

## ADDED Requirements

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
