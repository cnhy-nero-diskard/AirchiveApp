# Report patterns and quality rubrics for under-the-hood deliverables

Provider-neutral reference for organizing and grading the explanation before it ships.

## 1. Common section skeleton

purpose -> mental model -> concrete flow -> evidence -> design rationale -> caveats/open questions.

Every report states up front: analyzed ref/SHA, scope, depth, audience, and whether the dirty working tree was included.

## 2. Depth templates

### simple (5–10 minute read)

1. What this code does, in plain language (1 paragraph).
2. The single mental model (diagram optional).
3. Primary modules (short table: module -> role).
4. One example flow, end to end.
5. Most important caveats.
6. Three takeaways.

### standard

1. Functional architecture and component map (diagram when supported).
2. 1–3 traced flows, each with entry point, call chain, data transformations, side effects.
3. Core data structures and state lifecycle.
4. Noteworthy algorithms (state complexity/assumptions when relevant).
5. Tests: what is covered, what is not, and what that means for confidence.
6. Short glossary of project jargon discovered in code.
7. Design choices explained practically (what problem each solves).

### deep

Adds to standard:

- Dependency boundaries and layering rules (what may import what, and where the rule is enforced or violated).
- Function-level traces, schemas/API contracts, concurrency model, failure paths.
- Security and operational implications where relevant.
- Build/runtime/deployment notes.
- Tradeoffs and plausible alternatives (why the chosen design, what it costs).
- Interview Q&A block when the audience is interview-oriented.

## 3. Audience adjustments

- **beginner**: vocabulary first; every acronym expanded at first use; fewer, shorter code excerpts; one flow only.
- **developer**: default from §2.
- **interview**: "explain it in 30 seconds" and "explain it in 2 minutes" summaries; 8–12 plausible technical questions with source-grounded answers; honest ownership language.
- **maintainer**: change points, extension seams, regression risks, debugging breadcrumbs (log lines, tests, CLI commands that localize faults).

## 4. Evidence and uncertainty formatting

- Cite as `path:Lstart-Lend` with the resolved revision stated once in the header; use exact lines only when verified against the analyzed revision, else cite symbol/path only.
- Label confidence explicitly: **confirmed by code / inferred / unknown** — especially in tables and claims that look official.
- Keep an "Open questions / not verified" section; a report with none is suspicious, because real codebases are bigger than any pass.
- Never state line numbers from a different revision than the one cited in the header.

## 5. Diagrams (Mermaid only if the output channel renders it)

- One flow diagram per traced flow, not per component. Nodes = real module/function names as they appear in code.
- Use sequence diagrams for request/response; flowcharts for pipelines; state diagrams for stateful lifecycles.
- If diagrams are unsupported, fall back to an indented `entry -> step -> step` text trace and say so.

## 6. Comparison reports

Structure: verdict table first, then per-area narrative.

| Kind of change | Count | Highlight |
|---|---|---|
| Added | n | list the load-bearing additions only |
| Moved | n | note relocations that change ownership/boundaries |
| Behaviorally changed | n | before -> after, with evidence on the relevant revision |
| Unchanged | - | state explicitly what did not move |

## 7. Final quality rubric

Grade 1–5 (or flag FAIL) before presenting:

- **Accuracy**: every architectural claim traces to source; no invented APIs/line numbers/tests/metrics.
- **Coverage**: representative workflows traced for repo scope; end to end for feature scope; both sides inspected for comparisons — and labeled as such.
- **Faithfulness of depth**: the requested depth was honored rather than silently replaced with a smaller/embellished summary.
- **Honesty**: limits, unverified areas, dirty-tree differences, and merge-base drift disclosed.
- **Usability**: right audience, right format, jargon defined, no unsolicited audit lecture on problems beyond scope.
