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

The profile parser accepts both the legacy SDK `writable` / `write_values`
shape and the current LG REST `mode` / `value.w` shape documented in the
[official AC profile reference](https://thinq.developer.lge.com/en/cloud/docs/thinq-connect/device-profile/air-conditioner).
Temperature writes retain the resource key from the profile; unsupported or
unparsed properties remain disabled rather than inferred.

## PAT handling

`local.properties` is not read for a ThinQ PAT, and the token is not put into
BuildConfig, Gradle properties, assets, or an APK. Do not leave a PAT in
`local.properties`: it is plain local configuration, not the app's protected
credential store. Enter the token in the signed-in app's **Settings → ThinQ
credential** flow; the app verifies it before saving in Android Keystore. Do
not paste it into chat, logs, test fixtures, or the repository.

## Real-device acceptance

The owner entered the PAT through the protected Settings flow; no value was
read from local properties. On the physical phone, the app successfully read
the configured device profile and current live state after the REST-profile
parser was updated. The profile exposes writable power, temperature, FAN/DRY
mode, and LOW/HIGH fan choices. The REST profile advertised 0.5 °C steps, but
the owner confirmed this AC accepts whole-degree targets; the nonsecret
`thinq.temperatureStepOverride=1.0` now restricts the UI and command validation
to integer Celsius values.

### Partial real-device control check

- Requested 25.5 °C. The app reported the outcome as **unconfirmed**; it did not
  resend. A separate fresh read returned 25.0 °C. Per the owner's instruction,
  the setpoint was left at 25.0 °C; no restoration command was sent. The integer
  step override has been added; another temperature command awaits a new
  owner-selected integer setpoint.
- Requested fan HIGH. The app reported **confirmed**, and the fresh observed
  state showed fan HIGH.
- The owner reports that power off/on were manually verified. The interface and
  API outcome were not specified; no power command was sent from Airchive here.
- Mode commands were skipped at the owner's direction. The app profile offers
  FAN/DRY and LOW/HIGH options.

### Owner-confirmed final acceptance

After the signed release 1 → 2 upgrade, the owner explicitly confirmed the
remaining real-AC verification (whole-degree temperature, mode, power, and fan)
and release navigation/control checks complete. Task 4.6 is closed on this
owner-reported acceptance. Exact requested values and API responses from those
final manual checks were not supplied, so the earlier direct observations above
are retained rather than replaced with invented results. No additional control
command was issued by the assistant.

The owner separately reported task 6.4 verified before release signing, including
its offline/Firestore-unavailable acceptance. That is owner-reported device
evidence, not a new instrumentation run. Earlier emulator/fixture results remain
separate and do not themselves establish physical-device behavior.
