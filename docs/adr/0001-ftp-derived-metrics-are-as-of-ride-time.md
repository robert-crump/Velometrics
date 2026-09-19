# 0001. FTP-derived metrics are as of ride time

Status: Accepted (2026-09-19) — issue #200

## Context

Two FTP semantics coexist in the app:

- **Frozen at import.** Power-zone distribution, fat-efficiency score, time-below-60%-FTP,
  sprint and interval detection, cardiac drift and the ride tag are computed once in
  `FitImportService` / `SessionMetricsCalculator` from raw datapoints that are never persisted.
- **Live against current FTP.** Training Load (CTL/ATL/TSB, `TrainingLoadCache`) and the
  Recovery rule of `RideClassifier` read the current FTP setting on every recompute.

The Settings action "Recalculate session stats" implied the first group could be recomputed. It
never could: the raw samples aren't stored, and the loop that pretended to (via
`SessionComparator`) discarded its result. What the button actually did was re-run
`RideClassificationService.reclassifyAll` with the current FTP.

## Decision

FTP-derived metrics are **as of ride time**: a ride is judged against the FTP in force when it
was ridden, and retesting must not rewrite history.

The target shape is an **FTP history** (FTP with an effective date). Import, the Recovery rule
and Training Load all read the FTP in force on the ride date. Persisting downsampled
per-record samples so metrics can be recomputed ("as of today") was rejected: heavy storage and
a schema migration for a rare action, with no backfill for existing rides.

Until FTP history exists (follow-up issue):

- The stored metrics stay frozen at import, using the FTP current at import time.
- Training Load and the Recovery rule still follow the *current* FTP for all rides. This is a
  known deviation from the decision, not the intended behavior.
- The "Recalculate session stats" Settings action is removed. Tags are import-time only; a
  debug-only "Apply re-tag" row (next to the tag dump) runs `reclassifyAll` for threshold
  tuning.
- The FTP-change dialog is left as is; FTP history makes it obsolete.

## Consequences

- One coherent story for users once FTP history lands; no misleading recompute affordance now.
- Existing rides carry the FTP that was current at import, which may differ from their ride-time
  FTP. The FTP-history work must decide how to backfill (e.g. "FTP effective from date X").
- Metric-definition changes still require re-importing FIT files.
