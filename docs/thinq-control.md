# Android ThinQ control path

## Implementation status

The Android production graph now composes `AndroidEncryptedThinQCredentialStore`,
`KtorThinQClient`, the shared verify-before-save coordinator, live-state
controller, and one-at-a-time command controller. Production Settings accepts a
PAT only through a password-masked runtime field, verifies the exact configured
AC and profile before saving, and stores it through the Android Keystore-backed
store. The UI receives only masked credential state, the country, and
nonsecret client ID, not the store's raw-token read method. Sign-out and
account change clear both the active owner's Room partition and the PAT; a
cache-only clear preserves the PAT.

Opening Device performs an explicit ThinQ device/profile check and a live-state
read. Controls are offered only when the configured device profile permits the
value and the live-state/profile gate is current. Each command is an explicit
UI action; the Ktor adapter validates against a fresh profile and confirms with
a bounded live read. Ambiguous outcomes are not resent automatically. ThinQ
uses its local protected credential independently from Firestore history sync.
If a device-list response omits its `online` field, that status stays unknown;
setup still requires the exact configured profile, and control enablement still
requires a successful live-state read. An explicit offline report continues to
block setup.

## PAT handling

`local.properties` is not read for a ThinQ PAT, and the token is not put into
BuildConfig, Gradle properties, assets, or an APK. Do not leave a PAT in
`local.properties`: it is plain local configuration, not the app's protected
credential store. Enter the token in the signed-in app's **Settings → ThinQ
credential** flow; the app verifies it before saving in Android Keystore. Do
not paste it into chat, logs, test fixtures, or the repository.

## Acceptance still pending

The production UI and secure storage path are wired, but the real token has not
been loaded or read from any local properties file in this work. No live LG
request or AC command has been sent. Tasks 4.6 and 6.4 still require deliberate
manual validation on the configured AC, including the requested values, API
outcomes, and observed state. Emulator/fixture results do not count as that
evidence. The target-device release/signing tasks remain separate.
