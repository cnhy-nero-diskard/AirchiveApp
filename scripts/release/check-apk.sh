#!/usr/bin/env bash

# Shared release APK verification for macOS and Linux (Bash 3.2 compatible).

AIRCHIVE_SIGNING_CERT_SHA256="2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202"

newer_version() {
  local left right left_part right_part index
  local -a left_parts right_parts
  left=${1%%-*}
  right=${2%%-*}
  IFS=. read -r -a left_parts <<< "$left"
  IFS=. read -r -a right_parts <<< "$right"
  for index in 0 1 2 3; do
    left_part=${left_parts[$index]:-0}
    right_part=${right_parts[$index]:-0}
    left_part=${left_part%%[^0-9]*}
    right_part=${right_part%%[^0-9]*}
    left_part=${left_part:-0}
    right_part=${right_part:-0}
    if [ "$left_part" -gt "$right_part" ]; then return 0; fi
    if [ "$left_part" -lt "$right_part" ]; then return 1; fi
  done
  return 1
}

find_build_tools() {
  local sdk_dir version candidate best_version best_dir
  sdk_dir=${ANDROID_HOME:-}
  if [ -z "$sdk_dir" ] || [ ! -d "$sdk_dir/build-tools" ]; then
    echo "ANDROID_HOME must point to an Android SDK containing build-tools." >&2
    return 1
  fi
  best_version=
  best_dir=
  for candidate in "$sdk_dir"/build-tools/*; do
    [ -d "$candidate" ] || continue
    version=${candidate##*/}
    if [ -z "$best_version" ] || newer_version "$version" "$best_version"; then
      best_version=$version
      best_dir=$candidate
    fi
  done
  if [ -z "$best_dir" ]; then
    echo "No Android build-tools were found under $sdk_dir/build-tools." >&2
    return 1
  fi
  printf '%s\n' "$best_dir"
}

check_apk_with_pin() {
  local apk expected_name expected_code expected_commit certificate_pin build_tools
  local aapt apksigner badging manifest signer_output signer_count certificate_count
  local observed_package observed_code observed_name observed_cert observed_commit
  local apk_sha256 apk_size
  local -a failures

  apk=$1
  expected_name=$2
  expected_code=$3
  expected_commit=$4
  certificate_pin=$(printf '%s' "$5" | tr '[:upper:]' '[:lower:]')
  failures=()

  if [ ! -f "$apk" ]; then
    failures[${#failures[@]}]="APK does not exist: $apk"
  fi

  build_tools=$(find_build_tools) || return 1
  aapt="$build_tools/aapt"
  apksigner="$build_tools/apksigner"
  if [ ! -x "$aapt" ]; then failures[${#failures[@]}]="aapt is missing or not executable: $aapt"; fi
  if [ ! -x "$apksigner" ]; then failures[${#failures[@]}]="apksigner is missing or not executable: $apksigner"; fi
  if [ ! -f "$apk" ] || [ ! -x "$aapt" ] || [ ! -x "$apksigner" ]; then
    printf 'APK check failed: %s\n' "${failures[@]}" >&2
    return 1
  fi

  if ! badging=$("$aapt" dump badging "$apk" 2>&1); then
    failures[${#failures[@]}]="aapt could not read APK badging"
  fi
  observed_package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
  observed_code=$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -1)
  observed_name=$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)
  [ "$observed_package" = "cloud.airchive.personal" ] || failures[${#failures[@]}]="package name is '$observed_package', expected 'cloud.airchive.personal'"
  [ "$observed_name" = "$expected_name" ] || failures[${#failures[@]}]="versionName is '$observed_name', expected '$expected_name'"
  [ "$observed_code" = "$expected_code" ] || failures[${#failures[@]}]="versionCode is '$observed_code', expected '$expected_code'"
  case "$badging" in
    *application-debuggable*) failures[${#failures[@]}]="APK is marked debuggable" ;;
  esac

  if ! manifest=$("$aapt" dump xmltree "$apk" AndroidManifest.xml 2>&1); then
    failures[${#failures[@]}]="aapt could not read APK manifest"
  fi
  observed_commit=$(printf '%s\n' "$manifest" | awk '
    /android:name[^=]*="cloud\.airchive\.build\.commit"/ { want_value = 1; next }
    want_value && /android:value/ { if (match($0, /"[^"]+"/)) { value = substr($0, RSTART + 1, RLENGTH - 2); print value; exit } }
  ')
  [ "$observed_commit" = "$expected_commit" ] || failures[${#failures[@]}]="embedded commit is '$observed_commit', expected '$expected_commit'"

  if ! signer_output=$("$apksigner" verify --print-certs "$apk" 2>&1); then
    failures[${#failures[@]}]="apksigner verification failed"
  fi
  signer_count=$(printf '%s\n' "$signer_output" | grep -c 'Signer #[0-9][0-9]* certificate SHA-256 digest:' || true)
  certificate_count=$(printf '%s\n' "$signer_output" | grep -c 'certificate SHA-256 digest:' || true)
  if [ "$signer_count" -ne 1 ] || [ "$certificate_count" -ne 1 ]; then
    failures[${#failures[@]}]="APK must have exactly one signer (found $signer_count)"
  fi
  observed_cert=$(printf '%s\n' "$signer_output" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')
  [ "$observed_cert" = "$certificate_pin" ] || failures[${#failures[@]}]="signer certificate SHA-256 is '$observed_cert', expected '$certificate_pin'"

  if [ "${#failures[@]}" -gt 0 ]; then
    printf 'APK check failed:\n' >&2
    for failure in "${failures[@]}"; do printf ' - %s\n' "$failure" >&2; done
    return 1
  fi

  if command -v shasum >/dev/null 2>&1; then
    apk_sha256=$(shasum -a 256 "$apk" | awk '{print $1}')
  else
    apk_sha256=$(sha256sum "$apk" | awk '{print $1}')
  fi
  apk_size=$(stat -f%z "$apk" 2>/dev/null || stat -c%s "$apk")
  printf 'SHA-256: %s\nSize: %s bytes\n' "$apk_sha256" "$apk_size"
}

check_apk() {
  check_apk_with_pin "$1" "$2" "$3" "$4" "$AIRCHIVE_SIGNING_CERT_SHA256"
}

main() {
  if [ "$#" -ne 4 ]; then
    echo "Usage: check-apk.sh <apk> <versionName> <versionCode> <commit>" >&2
    return 2
  fi
  check_apk "$1" "$2" "$3" "$4"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
