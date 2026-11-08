#!/usr/bin/env bash
set -euo pipefail

readonly results_dir="androidApp/build/outputs/androidTest-results/connected"
readonly archive_dir="androidApp/build/instrumentation-batch-results"
readonly logcat_file="androidApp/build/outputs/instrumentation-logcat.txt"

batch_names=(
  auth-and-task-units
  local-storage
  firebase-emulators
  process-restart
  live-verification-skips
  shared-instrument-ui
)

batch_classes=(
  "cloud.airchive.personal.data.auth.GoogleSignInRequestTest,cloud.airchive.personal.data.auth.FirebaseOwnerIdentityExchangeTest,cloud.airchive.personal.data.tasks.FirebaseTaskAwaitTest"
  "cloud.airchive.personal.data.credentials.AndroidEncryptedThinQCredentialStoreTest,cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferencesTest"
  "cloud.airchive.personal.data.auth.FirebaseOwnerIdentityEmulatorTest,cloud.airchive.personal.data.firebase.FirebaseAirchiveTelemetryEmulatorTest"
  "cloud.airchive.personal.preview.SharedInstrumentProcessRestartTest"
  "cloud.airchive.personal.data.auth.LiveOwnerAccessVerificationTest,cloud.airchive.personal.data.firebase.LiveHistoryCacheVerificationTest"
  "cloud.airchive.personal.preview.SharedInstrumentUiTest"
)

capture_logcat() {
  adb logcat -d -v threadtime > "$logcat_file" 2>&1 || true
}

archive_current_results() {
  local batch_name="$1"
  local batch_dir="$archive_dir/$batch_name"
  local -a reports
  mkdir -p "$batch_dir"
  mapfile -t reports < <(find "$results_dir" -type f -name '*.xml' -print | sort)
  if [[ ${#reports[@]} -eq 0 ]]; then
    echo "No JUnit XML was produced for instrumentation batch $batch_name." >&2
    return 1
  fi
  if ! grep -Eiq '<testcase([[:space:]/>])' "${reports[@]}"; then
    echo "Instrumentation batch $batch_name produced no testcase records." >&2
    return 1
  fi
  for report in "${reports[@]}"; do
    cp "$report" "$batch_dir/$(basename "$report")"
  done
}

publish_aggregate_results() {
  local batch_name
  local report
  rm -rf "$results_dir"
  mkdir -p "$results_dir/batches"
  for batch_name in "${batch_names[@]}"; do
    for report in "$archive_dir/$batch_name"/*.xml; do
      [[ -f "$report" ]] || continue
      cp "$report" "$results_dir/batches/$batch_name-$(basename "$report")"
    done
  done
}

mkdir -p "$(dirname "$logcat_file")"
rm -rf "$results_dir" "$archive_dir"
trap capture_logcat EXIT

timeout 60s adb wait-for-device
adb logcat -c

for index in "${!batch_names[@]}"; do
  batch_name="${batch_names[$index]}"
  batch_class_list="${batch_classes[$index]}"
  echo "Running instrumentation batch $((index + 1))/${#batch_names[@]}: $batch_name"
  rm -rf "$results_dir"

  if timeout --signal=TERM 25m ./gradlew :androidApp:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$batch_class_list"; then
    gradle_status=0
  else
    gradle_status=$?
  fi

  archive_current_results "$batch_name" || {
    publish_aggregate_results
    exit 1
  }
  if [[ "$gradle_status" -ne 0 ]]; then
    publish_aggregate_results
    exit "$gradle_status"
  fi
done

publish_aggregate_results
