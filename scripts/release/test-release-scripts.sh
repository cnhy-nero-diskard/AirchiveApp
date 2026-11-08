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

make_release_test_repo() {
  local label release_repo origin_repo shim_dir home_dir
  label=$1
  release_repo="$temp_dir/release-repo-$label"
  origin_repo="$temp_dir/release-origin-$label.git"
  shim_dir="$temp_dir/release-shims-$label"
  home_dir="$temp_dir/release-home-$label"
  mkdir -p "$release_repo" "$shim_dir" "$home_dir/.airchive" "$release_repo/config" "$release_repo/scripts/release" "$release_repo/androidApp"
  git init --bare --initial-branch=main "$origin_repo" >/dev/null
  git -C "$release_repo" init --initial-branch=main >/dev/null
  git -C "$release_repo" config user.name "Airchive Release Fixture"
  git -C "$release_repo" config user.email "airchive-fixture@example.invalid"
  printf 'versionName=0.1.0\nversionCode=3\n' > "$release_repo/config/airchive-version.properties"
  cp "$script_dir/release.sh" "$release_repo/scripts/release/release.sh"
  cat > "$release_repo/scripts/release/check-apk.sh" <<'EOF'
#!/usr/bin/env bash
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  test -f "$1"
  printf 'fixture APK check passed\n'
fi
EOF
  cat > "$release_repo/gradlew" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$AIRCHIVE_RELEASE_TEST_ARGS"
printf '%s\n%s\n' "$AIRCHIVE_RELEASE_STORE_PASSWORD" "$AIRCHIVE_RELEASE_KEY_PASSWORD" > "$AIRCHIVE_RELEASE_TEST_ENV"
mkdir -p androidApp/build/outputs/apk/release
printf 'fixture APK bytes\n' > androidApp/build/outputs/apk/release/androidApp-release.apk
EOF
  cat > "$shim_dir/security" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *airchive-release-store-password*) printf 'fixture-store-password\n' ;;
  *airchive-release-key-password*) printf 'fixture-key-password\n' ;;
  *) exit 1 ;;
esac
EOF
  cat > "$shim_dir/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$AIRCHIVE_RELEASE_TEST_GH_ARGS"
EOF
  chmod +x "$release_repo/scripts/release/release.sh" "$release_repo/scripts/release/check-apk.sh" "$release_repo/gradlew" "$shim_dir/security" "$shim_dir/gh"
  : > "$home_dir/.airchive/airchive-release.jks"
  printf 'Fixture release notes.\n' > "$temp_dir/release-notes-$label.md"
  git -C "$release_repo" add config scripts gradlew
  git -C "$release_repo" commit -m "test: prepare release fixture" >/dev/null
  git -C "$release_repo" remote add origin "$origin_repo"
  git -C "$release_repo" push -u origin main >/dev/null 2>&1
  RELEASE_TEST_REPO=$release_repo
  RELEASE_TEST_ORIGIN=$origin_repo
  RELEASE_TEST_SHIMS=$shim_dir
  RELEASE_TEST_HOME=$home_dir
  RELEASE_TEST_LABEL=$label
}

run_release_test() {
  local output
  if output=$(cd "$RELEASE_TEST_REPO" && \
    PATH="$RELEASE_TEST_SHIMS:$PATH" \
    HOME="$RELEASE_TEST_HOME" USER=fixture \
    AIRCHIVE_RELEASE_TEST_ARGS="$temp_dir/gradle-args-$RELEASE_TEST_LABEL" \
    AIRCHIVE_RELEASE_TEST_ENV="$temp_dir/gradle-env-$RELEASE_TEST_LABEL" \
    AIRCHIVE_RELEASE_TEST_GH_ARGS="$temp_dir/gh-args-$RELEASE_TEST_LABEL" \
    ./scripts/release/release.sh "$@" 2>&1); then
    printf '%s\n' "$output"
    return 0
  fi
  printf '%s\n' "$output"
  return 1
}

expect_release_refusal() {
  local label expected output
  label=$1
  expected=$2
  shift 2
  if output=$(run_release_test "$@" 2>&1); then
    echo "FAIL: release preflight '$label' unexpectedly passed" >&2
    exit 1
  fi
  case "$output" in
    *"$expected"*) ;;
    *)
      echo "FAIL: release preflight '$label' did not report '$expected'" >&2
      printf '%s\n' "$output" >&2
      exit 1
      ;;
  esac
  printf 'PASS: release preflight %s\n' "$label"
}

make_release_test_repo dirty
printf 'uncommitted fixture change\n' > "$RELEASE_TEST_REPO/untracked.txt"
expect_release_refusal "dirty tree" "Working tree is dirty" 0.1.0 --notes-file "$temp_dir/release-notes-dirty.md"

make_release_test_repo outside-main
git -C "$RELEASE_TEST_REPO" commit --allow-empty -m "test: create unmerged release head" >/dev/null
expect_release_refusal "HEAD outside origin/main" "HEAD is not contained in origin/main" 0.1.0 --notes-file "$temp_dir/release-notes-outside-main.md"

make_release_test_repo local-tag
git -C "$RELEASE_TEST_REPO" tag v0.1.0
expect_release_refusal "local tag exists" "already exists locally" 0.1.0 --notes-file "$temp_dir/release-notes-local-tag.md"

make_release_test_repo remote-tag
git --git-dir="$RELEASE_TEST_ORIGIN" tag v0.1.0 refs/heads/main
expect_release_refusal "remote tag exists" "already exists on origin" 0.1.0 --notes-file "$temp_dir/release-notes-remote-tag.md"

make_release_test_repo version-mismatch
expect_release_refusal "version file mismatch" "does not match config/airchive-version.properties" 9.9.9 --notes-file "$temp_dir/release-notes-version-mismatch.md"

make_release_test_repo successful-build
if ! run_release_test 0.1.0 --notes-file "$temp_dir/release-notes-successful-build.md" >/dev/null; then
  echo "FAIL: valid release fixture did not complete" >&2
  exit 1
fi
if grep -E 'fixture-(store|key)-password' "$temp_dir/gradle-args-successful-build"; then
  echo "FAIL: signing password appeared in Gradle arguments" >&2
  exit 1
fi
grep -q '^fixture-store-password$' "$temp_dir/gradle-env-successful-build"
grep -q '^fixture-key-password$' "$temp_dir/gradle-env-successful-build"
grep -q -- '--draft' "$temp_dir/gh-args-successful-build"
grep -q -- '--notes-file' "$temp_dir/gh-args-successful-build"
echo "PASS: release Gradle receives signing passwords only through its environment"

echo "All release APK checks passed."
