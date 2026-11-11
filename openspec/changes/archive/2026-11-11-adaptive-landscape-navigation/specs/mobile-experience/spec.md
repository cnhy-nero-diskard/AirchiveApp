## MODIFIED Requirements

### Requirement: The shell keeps native navigation and window-safe behavior
In landscape windows at least 600 dp wide the app SHALL use a compact side rail for the five destinations; narrower or portrait windows SHALL use bottom navigation. Navigation SHALL sit outside independently scrolling content and retain visible or scroll-reachable labels. The app SHALL respect system insets, keep on-screen actions reachable while the keyboard is shown, and let Back dismiss the keyboard before leaving the current route when the IME is open. Phone landscape and the maximum supported text scale SHALL be supported, testable states of every route. Where available width and text scale permit, Home SHALL place controls and the hourly chart in separate columns while preserving reading order and accessible controls.

#### Scenario: Rotate to landscape
- **WHEN** the phone rotates to landscape on any of the five routes
- **THEN** navigation labels, current readings, and actions SHALL remain visible or reachable without overlap and the route SHALL still scroll

#### Scenario: Back with the keyboard open
- **WHEN** the owner presses Back while the keyboard is visible
- **THEN** the keyboard SHALL close first without discarding a local draft or navigating away

#### Scenario: Wide landscape instrument
- **WHEN** the landscape content has enough width for both columns at the current text scale
- **THEN** controls and the energy chart SHALL use side-by-side columns with controls first in semantic order

#### Scenario: Enlarged text or narrow window
- **WHEN** the available content is too narrow or text is enlarged
- **THEN** instrument sections SHALL stack and remain scrollable without clipping
