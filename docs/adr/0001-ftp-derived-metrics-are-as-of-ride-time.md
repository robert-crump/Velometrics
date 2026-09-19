# 0001. FTP-derived metrics are as of ride time

Status: Accepted (2026-09-19) — issue #200; FTP history implemented in #218

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

## Implementation (#218)

FTP history is a Room table `ftp_history(effectiveEpochDay, ftp)` (DB v20), edited in Settings as a
list of dated entries. Dates are local dates in the device zone; future dates are blocked; saving on
an existing date overwrites it. The pure `FtpHistory.ftpOn(date)` resolves the FTP in force on a ride
date (latest entry not after it, else the seed).

- Import (`FitImportService`) computes the stored metrics and the ride tag against the ride-date FTP.
- `TrainingLoadAggregator` / `TrainingLoadCache` score each ride against its ride-date FTP and stay
  live: adding or editing an entry reshapes Training Load from its effective date onward.
- The debug "Apply re-tag" and tag dump resolve FTP per ride (the CSV has a per-row `ftp` column).
- The "Change FTP?" dialog and "Recalculate session stats" are gone.

**Backfill.** Migration 19→20 creates the table empty (SQL can't read DataStore); on first use
`FtpHistoryRepository` seeds one "Before first test" row from the legacy DataStore FTP setting (or the
default) and removes that key. Every existing ride therefore resolves to the FTP the user had
configured until they add dated entries.

**Known inconsistency.** Only Training Load is live. Import-time metrics (power zones, fat efficiency,
time below 60% FTP, sprints, intervals, cardiac drift) and the stored ride tag are frozen at import and
never recompute, because the raw samples aren't stored. So adding or editing an *earlier* FTP entry
reshapes Training Load but not those stored metrics or tags, and existing rides keep the FTP that was
current at their import time for those metrics. Settings says so next to the history list.

## Consequences

- One coherent story for users once FTP history lands; no misleading recompute affordance now.
- Existing rides carry the FTP that was current at import, which may differ from their ride-time
  FTP; see the backfill and known inconsistency above.
- Metric-definition changes still require re-importing FIT files.
