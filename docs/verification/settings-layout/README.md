# Grouped Settings layout

Local working-tree verification on 2026-11-06. Captures show the production
Compose shell over debug-only synthetic owner/history/ThinQ ports, with no
saved ThinQ credential. No production account or physical phone was used.

Settings now groups compact status rows under Connections, History and
Preferences. Account actions stay collapsed in both modes. Informational
sections follow Simple/Detailed defaults, while credential entry, feedback,
unlock recovery and confirmation dialogs remain independent of those sections.
Sync timestamps appear in History rather than repeating in the account row.

## Validation

- Android debug application and instrumentation APKs assembled successfully.
- All 435 shared host tests passed; no failures, errors or skips.
- Debug lint passed: zero errors, six existing warnings and one hint.
- Native emulator checks passed for the grouped layout in Simple/Detailed and
  light/dark at 1.0 and 1.3 font scales, token-form/dialog preservation across detail
  changes without port calls, and confirmed synthetic owner sign-out.
- One repeated token-form test timed out during fixture sign-in before opening
  Settings; its isolated rerun passed. The full cross-route disclosure suite
  was not completed in this pass.
- The scoped layout detector returned no findings; `git diff --check` passed.

The native target was the `Medium_Phone_API_37.0` Android emulator. The font
scale was restored to 1.0 after testing. These checks do not certify TalkBack,
physical-device behavior, tablets or iOS. The debug shell fixture does not
switch status-bar icon contrast with its theme; the captures verify app content,
not production system-bar styling.

## Captures

- [Simple, light, default text](captures/settings-grouped-simple-light-scale-1.0.png)
- [Detailed, light, default text](captures/settings-grouped-detailed-light-scale-1.0.png)
- [Simple, dark, 1.3× text](captures/settings-grouped-simple-dark-scale-1.3.png)
- [Detailed, dark, 1.3× text](captures/settings-grouped-detailed-dark-scale-1.3.png)
