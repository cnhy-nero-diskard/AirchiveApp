# Agent instructions

## Android emulator for instrumented tests and captures

Start the emulator only with `scripts/test-emulator.sh start`, then pass the
serial it prints (`ANDROID_SERIAL=emulator-5554`) to Gradle and `adb`. Don't
run `emulator -avd …` yourself.

- The script launches headless with `-gpu host -feature GuestAngle`, so the
  guest renders through the Mac GPU (Vulkan → MoltenVK → Metal). It exits with
  code 3 if the running emulator renders on the CPU.
- Never launch with `-gpu swiftshader_indirect`, `swiftshader`, `swangle`,
  `lavapipe`, `software`, or without `-gpu` at all. A headless `auto` resolves
  to software. On an M5, CPU rendering made app launches ~6× slower, made half
  the frames janky, and kept the emulator at ~160% CPU while idle, which
  starves Gradle.
- If `start` exits with code 3, another session left a CPU-rendered emulator
  running. Run `scripts/test-emulator.sh restart` only when no other agent is
  mid-test on it; otherwise wait or ask the owner.
- `scripts/test-emulator.sh status` reports the renderer. `stop` shuts it down.
- The owner's phone is often attached over wireless ADB. Always target the
  emulator serial so test APKs never land on the phone.
