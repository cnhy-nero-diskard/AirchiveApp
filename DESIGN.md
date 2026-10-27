---
name: Airchive Home Instrument
description: A precise personal instrument for one AC, giving comfort and recorded energy equal prominence.
colors:
  cobalt: "#315EAA"
  on-cobalt: "#FFFFFF"
  cobalt-container: "#E2EBFA"
  on-cobalt-container: "#16396F"
  slate-secondary: "#53666F"
  slate-container: "#E5ECF0"
  porcelain-ground: "#F1F4F5"
  porcelain-panel: "#FFFFFF"
  slate-ink: "#202D34"
  quiet-surface: "#E8EEF2"
  slate-outline: "#71838E"
  quiet-rule: "#D4DDE3"
  coverage-amber: "#8C4B00"
  coverage-wash: "#FFF4E4"
  dark-cobalt: "#ABC7FF"
  dark-on-cobalt: "#092A60"
  dark-cobalt-container: "#244675"
  dark-on-cobalt-container: "#D9E6FF"
  dark-slate-secondary: "#B4C3CD"
  dark-ground: "#111A21"
  dark-panel: "#1A252D"
  dark-ink: "#E6EDF2"
  dark-quiet-surface: "#2C3D49"
  dark-outline: "#8C9CA7"
  dark-quiet-rule: "#40515D"
  dark-coverage-amber: "#F2BE71"
  dark-coverage-wash: "#3D2E18"
typography:
  reading:
    fontFamily: "Android system sans-serif (Roboto-compatible); Compose FontFamily.SansSerif"
    fontSize: "76sp"
    fontWeight: 600
    lineHeight: "82sp"
    letterSpacing: "-1sp"
    fontFeature: "tnum"
rounded:
  coverage: "8dp"
  small: "12dp"
  medium: "16dp"
  large: "20dp"
spacing:
  chart-gap: "4dp"
  compact: "8dp"
  related: "12dp"
  screen-inline: "16dp"
  panel-inline: "20dp"
  secondary-screen-inline: "24dp"
components:
  shared-panel:
    backgroundColor: "{colors.porcelain-panel}"
    textColor: "{colors.slate-ink}"
    rounded: "{rounded.large}"
    padding: "16dp 20dp"
  apply:
    backgroundColor: "{colors.cobalt}"
    textColor: "{colors.on-cobalt}"
    rounded: "{rounded.small}"
    height: "48dp minimum"
  temperature-step:
    rounded: "{rounded.small}"
    size: "48dp"
  choice-row:
    textColor: "{colors.slate-ink}"
    padding: "0dp"
    height: "48dp minimum"
  coverage-incomplete:
    backgroundColor: "{colors.coverage-wash}"
    textColor: "{colors.coverage-amber}"
    rounded: "{rounded.coverage}"
    padding: "6dp 10dp"
  coverage-complete:
    backgroundColor: "{colors.quiet-surface}"
    textColor: "{colors.slate-secondary}"
    rounded: "{rounded.coverage}"
    padding: "6dp 10dp"
  navigation:
    backgroundColor: "{colors.porcelain-panel}"
  instrument-reading:
    typography: "{typography.reading}"
---

# Design System: Airchive

## Overview

**Creative North Star: "Home Instrument"**

Airchive is a precise personal instrument for one AC. Porcelain surfaces, slate text and restrained cobalt make live comfort controls and recorded energy equally legible. The owner selected the Shared Instrument Home layout: paired readings live within one quiet panel, with familiar native controls and clear measurement labels.

This document records the implemented Android Compose system, not a web translation. Layout uses density-independent `dp`; type uses scalable `sp`. The frontmatter records explicit source tokens; uncustomized Material typography, component states and unspecified color roles remain library defaults. Light and dark schemes are implemented. `AirchiveTheme` wraps production destinations and configuration-blocked states; the legacy `AirchiveApp` demo still uses default Material schemes. Secondary production routes inherit the shared theme but retain their existing layouts.

**Key Characteristics:**

- Equal comfort and energy readings.
- Flat native surfaces and quiet structural rules.
- Cobalt actions, slate context and explicit coverage status.
- Native controls, scalable text and honest data states.

Evidence: `AirchiveTheme.kt`, `HomeInstrument.kt`, `ProductionAirchiveApp.kt`, `AirchiveApp.kt`, and the real emulator captures in `.impeccable/review/`. Those captures are evidence only; no concept image or screenshot ships as UI. The verification record reports 313 shared tests, Android debug and instrumentation builds, debug lint, explicit-Apply/stale-gating tests, and light/dark screenshot runs including 1.3 text scale. The independent reviewer resolved all six application findings and observed no defect requiring another batch within the scored scope. The automated pixel gate remains failed/open at approximately 75% with hard region/missing classifications; this system is not certified pixel-perfect. Physical-phone signed-in acceptance, tablets, landscape and maximum system text scale remain outside that verification.

## Colors

The palette uses porcelain and slate as the reading field, cobalt for active meaning, and amber for incomplete coverage. Frontmatter values are authoritative; the native role mapping below explains their application.

### Primary

- **Restrained Cobalt** (`cobalt`): Material `primary` and `surfaceTint`; the panel's slim index band, active actions, selector cues and recorded-energy bars. `on-cobalt`, `cobalt-container` and `on-cobalt-container` supply the corresponding Material contrast roles.
- **Dark Cobalt** (`dark-cobalt`): the lighter primary in dark mode, paired with `dark-on-cobalt` and its container/on-container roles.

### Secondary

- **Slate Secondary** (`slate-secondary`): Material `secondary` and `onSurfaceVariant`, used for contextual labels, units and freshness. `slate-container` is `secondaryContainer`, with slate ink as its foreground; `onSecondary` is white.
- **Dark Slate Secondary** (`dark-slate-secondary`): the dark equivalent. Its container is `dark-quiet-surface`, its foreground is `dark-ink`, and `onSecondary` is `dark-panel`.

### Tertiary

- **Coverage Amber / Coverage Wash** (`coverage-amber`, `coverage-wash`): Material `tertiary`, `tertiaryContainer` and `onTertiaryContainer`, used for incomplete or absent usable coverage, including a visible border and text.
- **Dark Coverage Amber / Wash**: the corresponding dark roles retain a readable warning label without turning the whole panel into an alert.

### Neutral

- **Porcelain Ground**: `background` and `surfaceContainer`.
- **Porcelain Panel**: `surface` and `surfaceContainerLow`; also the navigation background.
- **Slate Ink**: `onBackground`, `onSurface` and `onSecondaryContainer`.
- **Quiet Surface**: `surfaceVariant` and `surfaceContainerHighest`; complete coverage uses it with slate contextual text.
- **Slate Outline / Quiet Rule**: `outline` and `outlineVariant`; quiet rules separate reading and control groups.
- **Dark Ground / Panel / Ink**: the equivalent dark backgrounds, surfaces and foregrounds. `dark-quiet-surface` also supplies dark `surfaceVariant`, `surfaceContainerHighest` and `secondaryContainer`; dark outline tokens keep divisions visible.

**The Semantic Color Rule.** Resolve colors through Material roles so the same hierarchy survives light, dark, disabled and selected states; never use color as the only indication of status.

## Typography

**Display and body family:** native system sans-serif, Roboto-compatible on Android. The reading explicitly uses Compose `FontFamily.SansSerif`; ordinary UI uses the unmodified Material `Typography()` scale. No custom font asset or separate mono face is installed.

The character is clear, compact and instrument-like. The signature reading is the single custom style in the frontmatter: semibold, tabular figures with tight tracking. Units and explanations sit below or beside the number as real text, not baked-in graphics.

### Hierarchy

- **Reading:** `InstrumentReading`, shared identically by set temperature and sampled-today energy. Tabular figures stabilize comparison as values change.
- **Headline:** Material `headlineLarge` with a semibold override for the production Home title; other route headings retain their own Material roles.
- **Title:** Material `titleLarge` with a semibold override for the device name and “Today’s record”; `titleSmall` for Temperature and finalized energy; `titleMedium` for reading units.
- **Body:** `bodyMedium` for readout labels and setup guidance; `bodySmall` for device/sample/sync age, measurement context and command outcomes.
- **Label:** Material label roles for controls, navigation, coverage and chart values. Chart values use `labelSmall` and one line; no special uppercase or eyebrow system is introduced.

**The Equal Reading Rule.** Temperature and energy use the same reading style and equal-width columns when space allows; enlarged text stacks them instead of shrinking their hierarchy.

## Layout

Production Home is a vertically scrollable column, centered with a maximum width of 640dp, horizontal padding of 16dp, vertical padding of 12dp and 12dp between major children. The Scaffold reserves space for the persistent native bottom navigation. The compact header precedes the grouped panel; separate history-sync context follows it. Access/setup guidance appears when the actual state requires it.

The panel uses 20dp horizontal and 16dp vertical padding with 8dp vertical spacing. Its 3dp cobalt index band follows the clipped panel edge. The device header and power sit above the paired readings. Readings use equal weights with 12dp separation and a 132dp vertical divider. Temperature steps and Apply follow, then separate mode/fan rows, recorded-energy chart, coverage/freshness and, when present, yesterday's finalized LG total.

**The Stack Before Shrink Rule.** Within the panel, available width below 300dp **or** font scale above 1.2 stacks both readouts and the coverage/freshness row. The readout divider becomes horizontal. At captured 1.3 text scale, lower energy content requires scrolling; native navigation remains accessible.

Secondary production screens commonly retain their existing 640dp maximum, 24dp horizontal/20dp vertical padding and 16dp rhythm. These are observed route patterns, not a claim that every secondary layout was rebuilt. No new tablet or landscape composition is documented.

## Elevation & Depth

The signature Home panel is flat: a clipped background, tonal contrast, index band and thin rules supply structure; it has no explicit shadow or elevation. Native Material menus, dialogs, buttons, cards and navigation retain their library state/elevation behavior where not overridden. There is no custom shadow or animation vocabulary to export. The time-age label updates locally; it does not create continuous device polling. No decorative motion is added.

**The Flat Panel Rule.** Keep Home's grouped instrument structure legible through surface roles and dividers; do not add a bespoke shadow to recreate preview pixels.

## Shapes

Shared theme shapes are gently rounded: small controls use the small token, medium is available to Material consumers, and the Home panel uses large. Coverage badges use their own smaller radius. The control step buttons are square touch areas with rounded corners, while the real Material power switch and navigation indicator retain their native silhouettes. Quiet row dividers and the vertical readout rule are structural, not a decorative card grid.

## Components

### Shared instrument and readings

One grouped native surface holds comfort and energy. Labels, numbers and units are semantic text; each reading merges its descendants for accessibility. Device name and record title are headings. Missing numeric values render an em dash. The current setpoint stays identifiable when a local draft differs from the last observed value.

### Buttons and temperature controls

Apply is a native filled Material button using the shared small shape and a minimum 48dp height. It expands into the remaining temperature-row width. Minus/plus are 48dp tonal icon buttons with simple Canvas strokes and explicit accessibility labels. They change a local draft; only Apply submits the temperature command. Apply is disabled until the draft differs and live/profile gating permits a command. Step boundaries follow the supported device profile. Native disabled/ripple/focus behavior remains in effect; no web hover rules are invented.

Text buttons handle Energy, device-state retry, setup and history refresh. Native power is a real Switch with readable On/Off/Unknown context and an “AC power” description. Unsupported, stale, unverified or sending states disable commands; ambiguous outcomes never trigger automatic resend.

### Mode and fan selectors

Each is a full-width native text-button row with a minimum 48dp height, no content padding, a quiet top divider, left-aligned field name, current value and right chevron. The DropdownMenu lists profile-supported options; the current entry is identified and disabled. Controls do not fabricate writable modes or fan settings.

### Coverage status and energy chart

Coverage is informational, not an interactive filter chip. Incomplete or absent usable samples use amber text, wash and a 1dp border; complete coverage uses a quiet neutral surface. Badges use labelLarge text and the frontmatter padding. Readable coverage text accompanies color.

The native chart groups five-minute source intervals into eight three-hour columns, labeled in Wh. Its Canvas is 76dp tall, with text values above and hours below. Filled cobalt bars are fully measured groups; dashed outlines mark partial groups, and explicit “Gap” text marks missing measured intervals. Future groups remain empty; measured zero is distinct from missing. A merged chart description states values and measured interval counts and directs users to Energy for five-minute detail. “Sampled today” is usable recorded energy in kWh, not instantaneous power; finalized LG totals remain separately labeled. Day boundaries use Asia/Manila.

### Navigation

Native Material NavigationBar presents Home, Device, Energy, History and Settings with visible text and Canvas destination glyphs. Selected state uses Material roles/indicator. The bar sits outside Home's scroll container and respects Scaffold/system insets; it is not an image overlay.

### Existing cards and fields

Secondary and access/setup surfaces retain native Material cards, buttons, outlined text fields and dialogs with the shared production theme. `SummaryCard` retains 20dp internal padding and 8dp child spacing. Existing credential fields remain labeled, single-line native fields with password transformation where appropriate; focus, error and disabled treatments are library-owned. This documentation does not assert a custom field radius or new secondary-screen composition that the code does not implement.

## Do's and Don'ts

### Do:

- **Do** keep comfort and energy readings equally prominent and apply the native stacking rule.
- **Do** preserve scalable text, readable status text, native semantics and at least 48dp interactive targets.
- **Do** distinguish live device age, latest usable sample age and history-sync age wherever they affect a decision.
- **Do** show gaps, partial coverage, source and units honestly; keep sampled sums separate from finalized totals.
- **Do** preserve local temperature drafts, explicit Apply and supported/current-state command gating.
- **Do** reuse production Material roles in both implemented themes and keep native control states visible.

### Don't:

- **Don't** ship preview rasters as controls, charts, backgrounds or production data.
- **Don't** infer room temperature, humidity, cost, savings, automation or unsupported writable capabilities.
- **Don't** turn unavailable measurements into zero or sampled energy into instantaneous power.
- **Don't** disguise disabled controls or uncertain command outcomes to match a generated comp.
- **Don't** treat inherited secondary layouts or the legacy demo as a completed Home-style recomposition.
- **Don't** describe the open failed pixel gate as passed or the implementation as pixel-perfect.
