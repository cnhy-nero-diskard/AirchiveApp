# Spec Delta

## MODIFIED Requirements

### Requirement: Private Android release and iOS preparation
The first release SHALL produce a signed Android APK that can be installed privately and updated without clearing local data. "Installed privately" means installed directly, outside any app store; release APKs MAY be publicly downloadable from the repository's GitHub Releases, signed with the pinned Airchive release identity. The project SHALL contain shared Kotlin Multiplatform UI/domain code and an iOS app scaffold wired to that shared code; iOS distribution and device validation are outside this first release.

#### Scenario: Upgrade private APK
- **WHEN** the owner installs a newer release signed with the same release identity
- **THEN** the app SHALL retain compatible local history and protected settings

#### Scenario: Open iOS project
- **WHEN** a developer opens the iOS scaffold on a supported Mac
- **THEN** it SHALL reference the shared app code and identify the remaining platform setup needed for iOS execution
