## ADDED Requirements

### Requirement: Supported mode and fan sliders
The app SHALL present supported mode and fan values as compact discrete sliders when multiple values exist, show one selected value with color and accessible state distinguishing unconfirmed changes, and send a command on release only when the value changed. It SHALL NOT expose unsupported options or send commands while dragging.

#### Scenario: Owner changes a selection
- **WHEN** the owner moves a slider and releases it on a changed value
- **THEN** the app SHALL submit exactly one command for the chosen supported value

#### Scenario: No supported values
- **WHEN** a control has no supported values
- **THEN** the app SHALL show that the control is unavailable

### Requirement: Power status adapts to available space
Home and Device SHALL keep the power icon, textual status, and switch visible and accessible without clipping at narrow widths and enlarged text.

#### Scenario: Narrow or enlarged display
- **WHEN** the header cannot comfortably fit in one row
- **THEN** the identity and power status SHALL reflow onto separate rows
