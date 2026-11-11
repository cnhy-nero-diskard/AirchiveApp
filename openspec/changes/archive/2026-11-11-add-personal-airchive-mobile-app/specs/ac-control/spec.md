## Purpose

Lets the owner view the AC's current ThinQ state and safely perform the four everyday controls supported by this specific model.

## ADDED Requirements

### Requirement: Live device state is distinct from sampled history
The Device screen SHALL obtain current state directly from LG when opened or explicitly refreshed and SHALL show the time of that live read. It SHALL NOT describe a five-minute Firestore observation as live state. If LG is unreachable, the app SHALL label any retained state as old and disable commands requiring current capability knowledge.

#### Scenario: Open Device screen online
- **WHEN** the owner opens the Device screen with valid ThinQ access
- **THEN** the app SHALL request current state and present its observation time separately from telemetry sync time

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
