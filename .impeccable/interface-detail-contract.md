# Interface detail preference

User request: a toggle or selector for informative/verbose UI versus a simple interface for casual users.

Add Settings > Interface detail with Simple and Detailed native choice chips, Simple initially. Save a nonsecret installation-wide Android preference across activity recreation/reopening, independent of account history and protected ThinQ credentials. Apply immediately across all production destinations.

Simple keeps headings, controls, recorded readings, units, freshness, coverage, failure feedback, confirmations and recovery actions. It shortens optional copy and leaves optional technical sections collapsed but accessible. Detailed adds explanatory copy and initially opens informational disclosures. Task/action disclosures (e.g. Account actions) are not opened automatically.

Changing the choice must never change data, command gates, temperature draft or access; it must not send an AC command, trigger a new telemetry fetch or cause an extra live device read. Existing native control roles and Shared Instrument tokens remain fixed. Use the existing visual world; no replacement comp is required for this local extension.

Verification: host tests and APK/lint checks; native selection, navigation and recreation persistence test over a separate debug-only preference file; native Settings captures Simple/Detailed in light/dark at text scales1.0 and1.3. No real AC or account graph is constructed for these checks.
