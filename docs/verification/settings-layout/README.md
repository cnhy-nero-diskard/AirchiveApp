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

Expanded detail follows the same system. Section labels under a row rank below
the row title. Reference data is label/value fact lines that move a long value
beneath its label instead of wrapping it into a narrow column. Expanded blocks
are flush, so dividers no longer stack. Account actions are an aligned, divided
list with sign-out in the error colour, and the credential form's buttons span
the field width. Ages read "1 minute" / "1 day" rather than "1 minutes".

## Validation

- Android debug application and instrumentation APKs assembled successfully.
- All 442 shared host tests passed; no failures, errors or skips. Seven of them
  are new and cover the status, summary, count and age-label mapping.
- Debug lint passed: zero errors, six existing warnings and one hint.
- Native emulator checks passed on the final build: the grouped layout in
  Simple/Detailed and light/dark at 1.0 and 1.3 font scales; the new nested
  walk-through (every Detailed row reached by scrolling at 1.0 and 1.3, plus
  the credential form and Cancel); token-form/dialog preservation across
  detail changes without port calls; account-switch and cancelled-setup draft
  clearing; synthetic-PAT absence from diagnostics; the installation-wide
  interface choice; and confirmed synthetic owner sign-out.
- Run as one long batch, two of these timed out waiting for on-screen content;
  each passed when rerun on its own, so smaller batches were used.
- `productionRoutesPreserveInstrumentHierarchyAndConfiguredTruth` fails at its
  Device-route temperature-control wait, and
  `narrowPhoneWindowKeepsReadingsImeAndDialogReachable` fails on a Home
  energy-value check. The first also fails with the status-mark changes stashed;
  the second fails earlier, in sign-in setup, on the commit before any of this
  work. Neither is attributed to these changes, and the narrow-width IME and
  dialog checks were not validated. The full cross-route disclosure suite was not completed.

The native target was the `Medium_Phone_API_37.0` Android emulator. The font
scale was restored to 1.0 and the window size reset after testing. These checks
do not certify TalkBack, physical-device behavior, tablets or iOS. The debug
shell fixture does not switch status-bar icon contrast with its theme; the
captures verify app content, not production system-bar styling. The nested
captures use the emulator's system dark theme.

## Captures

- [Simple, light, default text](captures/settings-grouped-simple-light-scale-1.0.png)
- [Detailed, light, default text](captures/settings-grouped-detailed-light-scale-1.0.png)
- [Simple, dark, 1.3× text](captures/settings-grouped-simple-dark-scale-1.3.png)
- [Detailed, dark, 1.3× text](captures/settings-grouped-detailed-dark-scale-1.3.png)
- [Nested: account actions and AC connection, 1.0×](captures/settings-nested-account-and-connection-scale-1.0.png)
- [Nested: sync status and diagnostics, 1.0×](captures/settings-nested-history-and-diagnostics-scale-1.0.png)
- [Nested: credential form, 1.3×](captures/settings-nested-token-form-scale-1.3.png)
