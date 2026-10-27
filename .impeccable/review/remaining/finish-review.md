# Evidence / validity

Reviewed all 16 native PNGs in this directory: Device, Energy, History and Settings, each light/dark at 1.0/1.3 text scale. The captures visibly label preview data and no device connection. Evidence is the current debug fixture on Medium_Phone_API_35 / Android 15; it does not establish production sign-in, real-AC, hardware, tablet, landscape or maximum-font acceptance. Reviewed the requested seven Kotlin files, PRODUCT.md, DESIGN.md, the extension contract, craft floor and Android guidance.

Existing routes-build.log ends BUILD SUCCESSFUL and includes Android lint and shared host tests; the root records 313 shared tests. routes-instrumentation.log reports four passing native tests and routes-large-text.log reports one passing capture test. These are supplied execution evidence, not tests independently rerun by this reviewer. No web detector applies. The older Home image/pixel gate is outside this extension's gate and remains unchanged.

# Direction adherence

The routes extend Home Instrument coherently: porcelain/slate/cobalt roles, flat grouped panels, quiet rules, native navigation and scalable text. Device reuses the comfort instrument with explicit Apply; Energy leads with period, sampled kWh and readable coverage; History keeps cache/refresh before a virtualized ledger; Settings groups account and protected connection while disclosing diagnostics. At 1.3, text wraps and lower content remains in scrolling layouts.

Source retains cache-first Energy projection and explicit download, history's LazyColumn/detail dialog, account/cache/removal confirmations, current-profile command gating and local temperature drafts. Device details pass readOnOpen=false, avoiding another read merely on disclosure. No invented measurement or decorative polling was found in the inspected changes.

# Material findings

**P2 — Settings disclosure text is clipped by the button shape.** InstrumentLayout.kt:71–79 uses the default rounded TextButton with configurable horizontal inset. ProductionAirchiveApp.kt:720 and :888 pass inset=0.dp inside an already padded panel. The curved clip cuts leading glyphs: “Connection details” and “Protected storage…” visibly lose their left edges in settings-light-1.0.png and settings-dark-1.0.png; settings-light-1.3.png and settings-dark-1.3.png additionally cut the “Account actions” start and the wrapped “out” beginning. These are within-control glyph cuts, not ordinary content passing the scroll viewport boundary. The requested scalable-text/readable-copy floor is unmet.

No other material defect was observed within these first-viewport captures and inspected source. Expanded-state visual acceptance is limited to source and the supplied interaction logs.

# Bounded fix instructions

Give inset-zero disclosure buttons RectangleShape so their existing zero-padding text cannot intersect a curved clip. Preserve their left alignment, native ripple/expanded semantics, wrapping, and minimum target height. Avoid adding another 20dp inset inside the already padded Settings blocks. Leave controller behavior and measurements untouched.

Rebuild the affected debug fixture and recapture Settings in the same four theme/text-scale combinations. Confirm complete leading glyphs in both collapsed disclosures and their wrapped summaries, and that expansion and destructive-dialog cancellation still work. A targeted shared-primitive regression check is sufficient; no new visual direction or broad polish batch is warranted.

# Disposition

fix
