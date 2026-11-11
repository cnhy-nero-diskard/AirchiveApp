# Discrete Control Presentation Specification

## Purpose

Makes supported AC selections and power controls discoverable, accessible, and usable across phone layouts, with haptics that acknowledge deliberate local interaction.

## Requirements

### Requirement: Supported mode and fan sliders
The app SHALL present supported mode and fan values as compact discrete sliders when multiple values exist.

#### Scenario: Slider interaction preserves named supported values
- **WHEN** the owner taps or drags a mode or fan slider
- **THEN** Each slider SHALL have a clearly visible thumb, a continuous track with tick marks aligned to supported values, and named stop labels where they fit. During interaction it SHALL show the selected value, distinguish it from the current confirmed value, and expose named values and adjustment actions to accessibility services. The slider SHALL support tapping and dragging, retain at least a 48 dp touch target, and send one command on release only when the value changed. It SHALL NOT expose unsupported options or send commands while dragging.

#### Scenario: Owner changes a selection
- **WHEN** the owner moves a slider and releases it on a changed value
- **THEN** the app SHALL submit exactly one command for the chosen supported value

#### Scenario: Owner browses categorical mode values
- **WHEN** the owner views or adjusts the mode slider
- **THEN** the supported mode names SHALL be discoverable at the stops, and the selected value SHALL update during interaction

#### Scenario: No supported values
- **WHEN** a control has no supported values
- **THEN** the app SHALL show that the control is unavailable

### Requirement: Power status adapts to available space
Home and Device SHALL keep the power icon, textual status, and switch visible and accessible without clipping at narrow widths and enlarged text.

#### Scenario: Narrow or enlarged display
- **WHEN** the header cannot comfortably fit in one row
- **THEN** the identity and power status SHALL reflow onto separate rows

### Requirement: Control haptics acknowledge local interaction
Enabled user actions that change a supported control value SHALL use subtle native haptic feedback when the local interaction is accepted.

#### Scenario: Haptics distinguish local interaction from remote outcomes
- **WHEN** a slider selection, temperature step, power action, or command outcome changes
- **THEN** A discrete slider SHALL tick only when the selection crosses into a different supported value. Temperature step buttons SHALL tick once per enabled step, and power actions SHALL use the corresponding on/off feedback. Haptics SHALL respect system preferences and SHALL NOT be triggered by command acceptance, confirmation, failure, recomposition, disabled controls, or repeated observations. Command outcomes SHALL continue to use their separate visual and accessible feedback.

#### Scenario: Slider crosses a discrete setting
- **WHEN** the owner crosses into a different supported slider value
- **THEN** the app SHALL produce one subtle tick for that value transition, without ticking for every pointer update within the same value

#### Scenario: Command outcome changes
- **WHEN** a command is accepted, confirmed, fails, or remains unconfirmed
- **THEN** the outcome SHALL update its visual and accessible status without producing an additional haptic
