#!/usr/bin/env bash

# Generates temporary APKs and signing keys to exercise check-apk.sh.
set -e

script_dir=$(cd "$(dirname "$0")" && pwd)
checker="$script_dir/check-apk.sh"
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/build-tools" ]; then
  echo "ANDROID_HOME must point to an Android SDK containing build-tools." >&2
  exit 1
fi

# shellcheck source=scripts/release/check-apk.sh
. "$checker"
build_tools=$(find_build_tools)
aapt="$build_tools/aapt"
apksigner="$build_tools/apksigner"
android_jar=
build_tools_major=${build_tools##*/}
build_tools_major=${build_tools_major%%.*}
if [ -f "$ANDROID_HOME/platforms/android-$build_tools_major/android.jar" ]; then
  android_jar="$ANDROID_HOME/platforms/android-$build_tools_major/android.jar"
else
  for candidate in "$ANDROID_HOME"/platforms/*/android.jar; do
    [ -f "$candidate" ] || continue
    android_jar=$candidate
  done
fi
if [ -z "$android_jar" ]; then
  echo "No Android platform android.jar was found under $ANDROID_HOME/platforms." >&2
  exit 1
fi

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airchive-release-test.XXXXXX")
cleanup() { rm -rf "$temp_dir"; }
trap cleanup EXIT HUP INT TERM

fixture_password=airchive-fixture-only
commit=0123456789abcdef0123456789abcdef01234567
name=9.8.7
code=987

keytool -genkeypair -noprompt \
  -keystore "$temp_dir/fixture-one.jks" -storepass "$fixture_password" \
  -keypass "$fixture_password" -alias fixture-one \
  -dname "CN=Airchive Test Fixture One" -keyalg RSA -keysize 2048 -validity 2 >/dev/null 2>&1
keytool -genkeypair -noprompt \
  -keystore "$temp_dir/fixture-two.jks" -storepass "$fixture_password" \
  -keypass "$fixture_password" -alias fixture-two \
  -dname "CN=Airchive Test Fixture Two" -keyalg RSA -keysize 2048 -validity 2 >/dev/null 2>&1

fixture_pin=$(keytool -exportcert -keystore "$temp_dir/fixture-one.jks" -storepass "$fixture_password" -alias fixture-one 2>/dev/null | openssl dgst -sha256 | awk '{print $NF}' | tr '[:upper:]' '[:lower:]')

make_unsigned_apk() {
  local filename package version_name version_code metadata_commit debuggable manifest
  filename=$1
  package=$2
  version_name=$3
  version_code=$4
  metadata_commit=$5
  debuggable=$6
  mkdir -p "$temp_dir/$filename"
  manifest="$temp_dir/$filename/AndroidManifest.xml"
  cat > "$manifest" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$package" android:versionCode="$version_code" android:versionName="$version_name">
    <application android:label="fixture" android:debuggable="$debuggable">
        <meta-data android:name="cloud.airchive.build.commit" android:value="$metadata_commit" />
    </application>
</manifest>
EOF
  "$aapt" package -f -M "$manifest" -I "$android_jar" -F "$temp_dir/$filename-unsigned.apk" >/dev/null
}

sign_fixture() {
  local unsigned output keystore alias
  unsigned=$1
  output=$2
  keystore=$3
  alias=$4
  "$apksigner" sign --ks "$keystore" --ks-key-alias "$alias" \
    --ks-pass "pass:$fixture_password" --key-pass "pass:$fixture_password" \
    --out "$output" "$unsigned" >/dev/null
}

expect_failure() {
  local label expected output
  label=$1
  expected=$2
  shift 2
  if output=$("$@" 2>&1); then
    echo "FAIL: $label unexpectedly passed" >&2
    exit 1
  fi
  case "$output" in
    *"$expected"*) ;;
    *)
      echo "FAIL: $label did not report '$expected'" >&2
      printf '%s\n' "$output" >&2
      exit 1
      ;;
  esac
  printf 'PASS: %s\n' "$label"
}

make_unsigned_apk good cloud.airchive.personal "$name" "$code" "$commit" false
sign_fixture "$temp_dir/good-unsigned.apk" "$temp_dir/good.apk" "$temp_dir/fixture-one.jks" fixture-one

if output=$(check_apk_with_pin "$temp_dir/good.apk" "$name" "$code" "$commit" "$fixture_pin"); then
  printf '%s\n' "$output" | grep -q '^SHA-256: [0-9a-f]\{64\}$'
  printf '%s\n' "$output" | grep -q '^Size: [0-9][0-9]* bytes$'
  echo "PASS: throwaway-signed non-debuggable APK passes through internal test pin"
else
  echo "FAIL: valid fixture did not pass with the internal test pin" >&2
  printf '%s\n' "$output" >&2
  exit 1
fi
expect_failure "valid fixture rejected by fixed production pin" "signer certificate SHA-256" \
  check_apk "$temp_dir/good.apk" "$name" "$code" "$commit"

make_unsigned_apk debug cloud.airchive.personal "$name" "$code" "$commit" true
sign_fixture "$temp_dir/debug-unsigned.apk" "$temp_dir/debug.apk" "$temp_dir/fixture-one.jks" fixture-one
expect_failure "debuggable APK rejected" "marked debuggable" \
  check_apk_with_pin "$temp_dir/debug.apk" "$name" "$code" "$commit" "$fixture_pin"

make_unsigned_apk two-signers cloud.airchive.personal "$name" "$code" "$commit" false
"$apksigner" sign \
  --v1-signing-enabled false --v3-signing-enabled false --v4-signing-enabled false \
  --ks "$temp_dir/fixture-one.jks" --ks-key-alias fixture-one \
  --v1-signer-name fixture-one \
  --ks-pass "pass:$fixture_password" --key-pass "pass:$fixture_password" \
  --next-signer --ks "$temp_dir/fixture-two.jks" --ks-key-alias fixture-two \
  --v1-signer-name fixture-two \
  --ks-pass "pass:$fixture_password" --key-pass "pass:$fixture_password" \
  --out "$temp_dir/two-signers.apk" "$temp_dir/two-signers-unsigned.apk" >/dev/null
expect_failure "APK with two signers rejected" "exactly one signer" \
  check_apk_with_pin "$temp_dir/two-signers.apk" "$name" "$code" "$commit" "$fixture_pin"

make_unsigned_apk wrong-package cloud.airchive.invalid "$name" "$code" "$commit" false
sign_fixture "$temp_dir/wrong-package-unsigned.apk" "$temp_dir/wrong-package.apk" "$temp_dir/fixture-one.jks" fixture-one
expect_failure "wrong package rejected" "package name is 'cloud.airchive.invalid'" \
  check_apk_with_pin "$temp_dir/wrong-package.apk" "$name" "$code" "$commit" "$fixture_pin"

make_unsigned_apk wrong-code cloud.airchive.personal "$name" 988 "$commit" false
sign_fixture "$temp_dir/wrong-code-unsigned.apk" "$temp_dir/wrong-code.apk" "$temp_dir/fixture-one.jks" fixture-one
expect_failure "wrong version code rejected" "versionCode is '988'" \
  check_apk_with_pin "$temp_dir/wrong-code.apk" "$name" "$code" "$commit" "$fixture_pin"

make_unsigned_apk wrong-commit cloud.airchive.personal "$name" "$code" 1123456789abcdef0123456789abcdef01234567 false
sign_fixture "$temp_dir/wrong-commit-unsigned.apk" "$temp_dir/wrong-commit.apk" "$temp_dir/fixture-one.jks" fixture-one
expect_failure "wrong commit rejected" "embedded commit is '1123456789abcdef0123456789abcdef01234567'" \
  check_apk_with_pin "$temp_dir/wrong-commit.apk" "$name" "$code" "$commit" "$fixture_pin"

keytool -genkeypair -noprompt -keystore "$temp_dir/wrong-release-key.jks" \
  -storetype JKS -storepass "$fixture_password" -keypass "$fixture_password" \
  -alias airchive-release -dname "CN=Wrong Airchive Test Fixture" \
  -keyalg RSA -keysize 2048 -validity 2 >/dev/null 2>&1
mkdir -p "$temp_dir/security-shim"
cat > "$temp_dir/security-shim/security" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$AIRCHIVE_SECURITY_SHIM_LOG"
exit 97
EOF
chmod +x "$temp_dir/security-shim/security"
if PATH="$temp_dir/security-shim:$PATH" \
  AIRCHIVE_SECURITY_SHIM_LOG="$temp_dir/security-calls.log" \
  "$script_dir/setup-signing.sh" --keystore "$temp_dir/wrong-release-key.jks" \
  > "$temp_dir/setup-output.log" 2>&1; then
  echo "FAIL: signing setup accepted a keystore with another certificate" >&2
  exit 1
fi
grep -q 'does not match the pinned Airchive release identity' "$temp_dir/setup-output.log"
if [ -f "$temp_dir/security-calls.log" ]; then
  echo "FAIL: signing setup called security before rejecting the certificate pin" >&2
  cat "$temp_dir/security-calls.log" >&2
  exit 1
fi
echo "PASS: wrong signing certificate rejected before Keychain access"

echo "All release APK checks passed."
