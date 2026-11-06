# Grouped Settings layout

Local working-tree verification on 2026-11-06. Captures show the production
Compose shell over debug-only synthetic owner/history/ThinQ ports, with no
saved ThinQ credential. No production account or physical phone was used.

Settings now groups compact status rows under Connections, History and
Preferences. Account actions stay collapsed in both modes. Informational
sections follow Simple/Detailed defaults, while credential entry, feedback,
unlock recovery and confirmation dialogs remain independent of those sections.
Sync timestamps appear in History rather than repeating in the account row.
Each row leads with a status mark (check, exclamation or dash) driven by real
account, credential, access and sync state, and History and storage states the
cached count with the age of the last successful sync. The History access row no
longer repeats its own title as a heading inside, and the filled button is kept
for a missing or unreadable ThinQ token while replacing a working one is outlined.

## Validation

- Android debug application and instrumentation APKs assembled successfully.
- All 440 shared host tests passed; no failures, errors or skips. Five of them
  are new and cover the status and summary mapping.
- Debug lint passed: zero errors, six existing warnings and one hint.
- Native emulator checks passed for the grouped layout in Simple/Detailed and
  light/dark at 1.0 and 1.3 font scales, token-form/dialog preservation across
  detail changes without port calls, account-switch and cancelled-setup draft
  clearing, synthetic-PAT absence from diagnostics, the installation-wide
  interface choice, and confirmed synthetic owner sign-out.
- `productionRoutesPreserveInstrumentHierarchyAndConfiguredTruth` fails at its
  Device-route temperature-control wait (`SharedInstrumentUiTest.kt:1391`). It
  fails identically with this pass's Settings changes stashed, so it is not
  attributed to them. The full cross-route disclosure suite was not completed.

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
