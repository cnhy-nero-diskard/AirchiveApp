# Home Instrument refinement

Status: the owner approved Home Instrument and Shared Instrument on 2026-10-24. Native implementation and verification now exist. The original exploration below is retained as design history; DESIGN.md describes final implemented tokens.

## Direction retained

Porcelain surfaces, slate typography, restrained cobalt actions, tabular readouts and fine functional indexing. Comfort control and recorded energy deserve equal optical weight. Native controls stay recognizable; technical setup and collector detail stay secondary.

## Composition comparison

| Option | What changes | Tradeoff |
| --- | --- | --- |
| Original registers | Original identity reference; two generous stacked registers | Large readings crowd context; original chart is not a numerically consistent fixture |
| Balanced registers | Smaller readouts, separate Mode and Fan speed rows, Apply temperature beside its task | Familiar structure; control section still attracts more attention |
| Shared instrument | Equal readouts in a common first row, one continuous panel | Best balance at a glance; must stack at enlarged text or narrow widths |

Owner decision: Shared Instrument approved on 2026-10-24 with instruction to continue with that layout.

## Proposed role tokens

Map these to semantic Material roles in implementation; do not hard-code component colors. Values are refinement proposals rather than sampled pixel measurements from the generated images.

| Role | Light | Dark proposal |
| --- | --- | --- |
| Background | #F1F4F5 | #111A21 |
| Surface | #FFFFFF | #1A252D |
| On surface | #202D34 | #E6EDF2 |
| Secondary text | #53666F | #B4C3CD |
| Primary | #315EAA | #ABC7FF |
| On primary | #FFFFFF | #092A60 |
| Warning ink/container | #8C4B00 / #FFF4E4 | To be rendered and checked |

Computed foreground/background contrast: light body 14.13:1, light secondary 6.00:1, white on cobalt 6.34:1, warning ink/container 6.19:1. Dark proposed body 13.19:1, secondary 8.64:1, primary on surface 9.17:1, on-primary 8.17:1. These are mathematical token checks; generated images and actual native component states have not been contrast-tested. Dark mode is proposed, not visually approved.

Typography: one Roboto-compatible native sans through Material roles; tabular numerals for readings; equal numeral height and weight for temperature and sampled energy. The implemented reading role is 76 sp with 82 sp line height, semibold weight, tabular numerals and -1 sp tracking; stack the readings when they do not fit. Body 16 sp, secondary labels 14 sp; system scaling remains enabled. Use 4/8 dp spacing, 20 dp section grouping, 12 dp control shape where Material supports it, and at least 48 dp touch targets. No decorative tick marks, typography-as-art, fake dial, glass, glow or automatic animation.

## Interaction decisions for implementation

- Label the adjustable value as Set temperature. Minus/plus edit a draft; explicit Apply temperature sends one command. While editing, show the last confirmed value separately. Applying a draft does not change Mode or Fan speed.
- Mode and Fan speed are separate profile-supported selection controls. Surface only real writable choices. Power sends its own explicit command; pending UI must not claim the requested value was confirmed.
- Sending disables conflicting controls. Distinguish request accepted, observed confirmed, failed, and unconfirmed. Keep the last confirmed state on ambiguity and offer a fresh state read; never automatically resend.
- A stale or unavailable live read keeps historical context visible and disables controls with a nearby explanation. Offline energy history does not imply live device access.
- A partial energy record keeps its measured subtotal with the Incomplete coverage label. Missing intervals stay gaps; they are not zeros. Normal context stays quiet and readable; errors explain a recovery action.
- Device-read age belongs to device state, sample age to energy, and history-sync age to the footer/history detail. Do not collapse them into one live or updated badge.

## Energy demonstration

Refined concepts use eight 3-hour preview groups: 120, 180, 240, missing, 220, 280, 200, missing Wh. Valid values sum to 1240 Wh = 1.24 kWh. Labels identify recorded energy, incomplete coverage and the latest sample. Yesterday's LG finalized 3.18 kWh remains separate. These are synthetic concept data. Actual chart aggregation must preserve every gap and coverage distinction from the five-minute source; partial groups cannot be labeled complete merely because they have a subtotal.

## Reach across the app

| Destination | Identity expression |
| --- | --- |
| Home | Paired comfort and energy summary, freshness where relevant |
| Device | Full supported controls and explicit command feedback |
| Energy | Day/week/month records, readable scales, source and coverage |
| History | Observation and collector detail with plain-language status |
| Settings | Native forms for account, protected credential and local history |

Keep the existing five destinations. Home quick controls need deliberate wiring to the live-state and command controllers, since the current Home only links to Device. Any implementation must retain independent freshness gates and bounded refresh behavior; cached telemetry cannot substitute for a live read.

## Review and handoff

The original reference plus two refinements were rendered and visually reviewed. One targeted typography correction was made to Balanced registers; the two separated readings remain less balanced than Shared instrument. The refined chart labels and values were checked against the synthetic total. Prompt sidecars and embedded provenance accompany all concept images under .impeccable/mocks/home-instrument/.

Resolved: the owner approved Shared Instrument and requested continuation. The implementation and verification handoff below supersede the original image-only refinement status. The approved comp and direction contract are persisted; native captures and control checks now exist. Physical-device and broader-device-class acceptance remain separate.

## Implementation handoff, 2026-10-24

Shared Instrument is implemented in HomeInstrument.kt, using AirchiveTheme.kt across the production app. The paired readouts, explicit draft/apply action, supported selectors, three-hour recorded-energy groups, gaps/partial coverage, separate LG finalized total and usable-sample freshness are native Compose UI. Independent review cleared the six listed application fixes. Debug build, lint, 313 shared tests, native control tests and light/dark/large-text captures passed. DESIGN.md records implemented tokens. See .impeccable/review/final-verification.md for capture paths and limits, including the outstanding exact-pixel gate. Physical-phone signed-in acceptance was not rerun. No real AC command was sent.
