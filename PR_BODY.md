# Android G Strategy Memory Bound and Release Verification

## Background

Recent releases aligned Android G with the Electron GUI G flow and added safe in-run app update checks. During codebase review, one remaining long-run risk was identified: G strategy kept failed second-search phrase combinations in unbounded in-memory maps keyed by MID.

## Changes

- Added `BoundedMidPhraseMemory` for MID/phrase miss tracking.
- Capped remembered MIDs and failed phrases per MID.
- Updated `SamsungBrowserStrategyG` to use the bounded memory helper.
- Added unit tests for:
  - G five-word query shape.
  - MID/phrase memory eviction behavior.
- Added review/rollback documentation at `docs/android-g-strategy-update-review.md`.

## Root Cause

Continuous server mode can run across many product MIDs in a single process lifetime. The previous `failedSecondPhrasesByMid` and `midMissCountByMid` maps had no eviction policy, so they could grow indefinitely during long fleet operation.

## Test Plan

```powershell
cd android/SamsungTrafficBot
./gradlew :app:testDebugUnitTest --tests "com.navertraffic.samsung.strategy.SecondKeywordStoreTest" :app:testDebugUnitTest --tests "com.navertraffic.samsung.data.AndroidServerApiClientJsonTest" :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Both commands passed.

## Rollback Plan

This PR is small and isolated. To roll it back:

- Remove `BoundedMidPhraseMemory.kt`.
- Restore the two direct maps in `SamsungBrowserStrategyG`.
- Remove the new memory eviction unit test.

For broader production rollback candidates, see `docs/android-g-strategy-update-review.md`.

