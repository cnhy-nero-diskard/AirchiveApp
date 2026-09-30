## Purpose

Guides the single owner through safe account and ThinQ setup so a fresh installation can read its Airchive history and control the intended AC.

## ADDED Requirements

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
