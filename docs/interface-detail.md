# Interface detail

Handoff: 2026-10-27. Local extension of the existing Home Instrument design; [DESIGN.md](../DESIGN.md), its token sidecar and [PRODUCT.md](../PRODUCT.md) remain the visual and product authorities.

Settings > **Interface detail** offers **Simple** (the default, including absent or unrecognized saved values) and **Detailed**. The choice applies immediately across Home, Device, Energy, History and Settings.

| Choice | Experience |
| --- | --- |
| Simple | Shorter optional copy; informational sections start collapsed and remain available to open manually. |
| Detailed | More explanatory and technical copy; informational sections start expanded. |

Changing the choice resets disclosure expansion to the selected mode's defaults. Explicit state-dependent expansion, such as history access needing attention, still applies. Task/action disclosures, including Account actions, remain collapsed by default in both modes and can be opened manually.

Both modes preserve headings, controls, readings, measurement units, freshness, coverage, warnings, failure feedback, recovery actions and confirmations. Detailed explains measurement meaning and technical context; Simple keeps that optional context reachable. Temperature drafts, explicit Apply, supported-capability and current-state command gates, and uncertain command outcomes retain their existing behavior. Switching modes sends no AC command and adds no telemetry fetch or live device read.

Android saves this nonsecret, installation-wide choice in private SharedPreferences, independently of owner history/cache and the protected ThinQ PAT. MainActivity supplies the persistent adapter. Callers without a platform adapter receive an in-memory fallback; that fallback does not promise persistence across app instances.

The selector uses native choice chips with selected semantics, wrapping and a minimum 48 dp height. Disclosure controls expose expanded/collapsed state. Existing theme roles, scalable type and Shared Instrument tokens continue to apply.

## Evidence and disposition

The [verification record](../.impeccable/review/interface-detail/verification.md) and [independent finish review](../.impeccable/review/interface-detail/finish-review.md) support **ship within the reviewed phone Settings theme/text scope**, with no material findings or additional fix batch required. Cross-screen behavior is supported by source inspection and native fixture checks.

- [Build log](../.impeccable/review/interface-detail-build.log): 314 shared tests, debug and instrumentation APK builds, and debug lint passed.
- [Native log](../.impeccable/review/interface-detail-native.log): six checks passed, including cross-screen selection, activity recreation and fresh preference/Activity-instance persistence.
- [Large-text log](../.impeccable/review/interface-detail-large-text.log): one capture check passed at 1.3 text scale.
- [Local preview gallery](http://127.0.0.1:3317/) / [saved gallery](../.impeccable/review/interface-detail/index.html): eight validated native Settings PNGs covering Simple/Detailed, light/dark and text scales 1.0/1.3. Representative [Simple](../.impeccable/review/interface-detail/interface-simple-light-1.0.png) and [Detailed](../.impeccable/review/interface-detail/interface-detailed-light-1.0.png) captures show actual Compose UI over a synthetic debug fixture with isolated preferences; screenshots and fixture data do not ship as UI.

An initial SystemUI ANR dialog blocked instrumentation after emulator cold boot. Dismissing the system dialog was followed by a clean full rerun without an app fix.

Acceptance does not cover physical hardware, real AC commands, production sign-in, iOS, tablets, landscape, maximum font scale or forced-process-kill persistence. The older Home pixel gate remains unchanged; these checks do not certify every production screen or state visually.

## Source entry points

- [Shared preference and selector](../shared/src/commonMain/kotlin/cloud/airchive/shared/InterfaceDetail.kt), [disclosure behavior](../shared/src/commonMain/kotlin/cloud/airchive/shared/InstrumentLayout.kt) and [production scope/Settings](../shared/src/commonMain/kotlin/cloud/airchive/shared/ProductionAirchiveApp.kt).
- [Home](../shared/src/commonMain/kotlin/cloud/airchive/shared/HomeInstrument.kt), [Energy](../shared/src/commonMain/kotlin/cloud/airchive/shared/EnergyRoute.kt) and [History](../shared/src/commonMain/kotlin/cloud/airchive/shared/HistoryHealthRoute.kt) mode-specific context.
- [Android persistence](../androidApp/src/main/kotlin/cloud/airchive/personal/data/preferences/AndroidInterfaceDetailPreferences.kt) and [MainActivity wiring](../androidApp/src/main/kotlin/cloud/airchive/personal/MainActivity.kt).
