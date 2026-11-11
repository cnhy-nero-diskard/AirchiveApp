## Context

The existing coverage index and range loader provide cache-first access and missing-range queries. Five-minute normalized observations are the source for presentation.

## Goals / Non-Goals

**Goals:** Hourly charts and ledger, bounded cache-first range loading, and honest quality indicators.

**Non-Goals:** Changing storage cadence, background polling, live ThinQ controls, or inferring instantaneous power.

## Decisions

Aggregate using local day and slot positions already normalized and deduplicated by the energy projection. Keep quality and missing evidence separate from sums. Use the existing coverage-aware loader for the selected period only; cancel on context/selection change and serialize requests. Show cached values during failures and provide retry. Keep individual observations available on expansion.

## Risks / Trade-offs

- Large selected months can require multiple bounded pages - fetch only the selected range and reuse coverage; do not prefetch.
- Partial measurements can be mistaken for complete totals - label partial and retain detailed quality evidence.
