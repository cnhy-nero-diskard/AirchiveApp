# Spec Delta

## ADDED Requirements

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
