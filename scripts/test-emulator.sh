#!/usr/bin/env bash
# Start, inspect, or stop the headless Android emulator used for instrumented
# tests and captures, with host-GPU rendering enforced.
#
#   scripts/test-emulator.sh start     # reuse a GPU-backed emulator or boot one
#   scripts/test-emulator.sh status    # report which renderer the emulator uses
#   scripts/test-emulator.sh restart   # kill and relaunch (e.g. a software-rendered one)
#   scripts/test-emulator.sh stop
#
# Why: launching headless with `-gpu swiftshader_indirect`, or with no `-gpu` at
# all (headless `auto` resolves to software), renders every frame on the CPU.
# Measured on an M5 (2026-11-07): Settings cold start ~1.6 s vs ~0.26 s, 53%
# janky frames vs 2-4%, and the qemu process idling at ~160% CPU vs ~20%.
# `-gpu host -feature GuestAngle` runs guest ANGLE on host Vulkan (MoltenVK ->
# Metal), keeps the GLES 3.1 that API 37 requires, and was the fastest mode.
#
# Environment overrides:
#   AIRCHIVE_TEST_AVD                    AVD name (default Medium_Phone_API_37.0)
#   AIRCHIVE_TEST_EMULATOR_PORT          console port (default 5554 -> emulator-5554)
#   AIRCHIVE_TEST_EMULATOR_GPU_ARGS      GPU flags (default "-gpu host -feature GuestAngle")
#   AIRCHIVE_TEST_EMULATOR_BOOT_TIMEOUT  seconds to wait for boot (default 300)
#   AIRCHIVE_ALLOW_SOFTWARE_GPU=1        accept a CPU renderer (hosts with no usable GPU)
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${AIRCHIVE_TEST_AVD:-Medium_Phone_API_37.0}"
PORT="${AIRCHIVE_TEST_EMULATOR_PORT:-5554}"
SERIAL="emulator-$PORT"
GPU_ARGS="${AIRCHIVE_TEST_EMULATOR_GPU_ARGS:--gpu host -feature GuestAngle}"
BOOT_TIMEOUT="${AIRCHIVE_TEST_EMULATOR_BOOT_TIMEOUT:-300}"
LOG="${TMPDIR:-/tmp}/airchive-test-emulator-$PORT.log"

die() { echo "test-emulator: $1" >&2; exit "${2:-1}"; }

[ -x "$ADB" ] || die "adb not found at $ADB (set ANDROID_HOME)"
[ -x "$EMULATOR" ] || die "emulator not found at $EMULATOR (set ANDROID_HOME)"

is_online() { [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = "device" ]; }

# Prints "gpu", "software", or "unknown" for the running emulator, judged from
# inside the guest so it also catches emulators this script did not launch.
renderer_class() {
  local sf
  # The GLES line names the GL renderer; with GuestAngle, SurfaceFlinger's
  # Vulkan RenderEngine names the device instead ("Goldfish GFXStream (Apple M5)").
  sf="$("$ADB" -s "$SERIAL" shell dumpsys SurfaceFlinger 2>/dev/null \
    | grep -E 'GLES:|RE Vulkan|^ *Name: ' | head -6 || true)"
  if printf '%s' "$sf" | grep -qiE 'swiftshader|lavapipe|llvmpipe'; then echo software; return; fi
  if printf '%s' "$sf" | grep -qiE 'apple|metal|moltenvk|nvidia|amd|radeon|intel'; then echo gpu; return; fi
  # Fall back to the launch log when the guest does not name the device.
  if [ -f "$LOG" ] && grep -qE 'Selecting Vulkan device: Apple|gles_mode_selected:host' "$LOG" \
      && ! grep -qE 'gles_mode_selected:(swangle|swiftshader)|vulkan_mode_selected:(swiftshader|lavapipe)' "$LOG"; then
    echo gpu; return
  fi
  echo unknown
}

require_gpu() {
  local cls; cls="$(renderer_class)"
  case "$cls" in
    gpu) return 0 ;;
    software)
      [ "${AIRCHIVE_ALLOW_SOFTWARE_GPU:-}" = 1 ] && { echo "test-emulator: WARNING software renderer accepted by AIRCHIVE_ALLOW_SOFTWARE_GPU=1" >&2; return 0; }
      die "$SERIAL renders on the CPU (SwiftShader/lavapipe). It was launched without host GPU.
  If no other agent is using it, run: scripts/test-emulator.sh restart" 3 ;;
    *)
      echo "test-emulator: WARNING could not confirm the renderer of $SERIAL; see: scripts/test-emulator.sh status" >&2 ;;
  esac
}

wait_for_boot() {
  local start=$SECONDS
  until is_online && [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    (( SECONDS - start > BOOT_TIMEOUT )) && die "boot timed out after ${BOOT_TIMEOUT}s; log: $LOG"
    sleep 2
  done
  echo "test-emulator: $SERIAL booted in $(( SECONDS - start ))s"
}

start() {
  if is_online; then
    echo "test-emulator: reusing running $SERIAL"
  else
    echo "test-emulator: launching $AVD on $SERIAL with: $GPU_ARGS (log: $LOG)"
    # shellcheck disable=SC2086  # GPU_ARGS is intentionally word-split.
    nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim \
      -no-snapshot-save $GPU_ARGS >"$LOG" 2>&1 &
    wait_for_boot
  fi
  require_gpu
  echo "test-emulator: ready ($(renderer_class) renderer). Use: export ANDROID_SERIAL=$SERIAL"
}

status() {
  if ! is_online; then echo "$SERIAL: not running"; return 1; fi
  echo "$SERIAL: online, renderer=$(renderer_class)"
  "$ADB" -s "$SERIAL" shell dumpsys SurfaceFlinger 2>/dev/null | grep -E 'GLES:' || true
  [ -f "$LOG" ] && grep -E 'gles_mode_selected|Selecting Vulkan device' "$LOG" || true
}

stop() {
  is_online || { echo "test-emulator: $SERIAL not running"; return 0; }
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  local start=$SECONDS
  while "$ADB" devices | grep -q "^$SERIAL"; do
    (( SECONDS - start > 30 )) && die "$SERIAL did not exit"
    sleep 1
  done
  echo "test-emulator: stopped $SERIAL"
}

case "${1:-start}" in
  start) start ;;
  status) status ;;
  stop) stop ;;
  restart) stop; start ;;
  *) die "usage: $0 [start|status|stop|restart]" 2 ;;
esac
