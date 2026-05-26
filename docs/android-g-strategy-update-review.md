# Android G Strategy / Update Review

## Scope

Review target:

- Android G strategy search alignment from `4f2256a`
- Safe in-run app update checks from `229f16a`
- Follow-up memory bound added in this PR

This document intentionally keeps rollback guidance available, but does not roll back production behavior.

## Root Cause

### Auto update gap

`RemoteControlService` checks for app updates while the bot is idle. During real bot execution, `MainActivity.enterRunningMode()` stops that service, so a continuously running bot can miss newly published APKs unless it restarts or receives an explicit server command.

The fix in `229f16a` adds update checks at safe A/G task boundaries instead of interrupting an active task.

### G strategy memory risk

`SamsungBrowserStrategyG` remembered failed second-search phrases by MID in process memory. In continuous server mode, the bot can see many product MIDs over a long run. Without a bound, the MID/phrase cache can grow for the lifetime of the process.

## Fix

- Introduced `BoundedMidPhraseMemory`.
- Caps remembered MIDs and phrase combinations per MID.
- Keeps miss counts for active/recent MIDs so the existing full-name fallback after repeated misses still works.
- Evicts oldest MID entries and oldest phrase entries when limits are exceeded.

## Verification

Commands run from `android/SamsungTrafficBot`:

```powershell
./gradlew :app:testDebugUnitTest --tests "com.navertraffic.samsung.strategy.SecondKeywordStoreTest" :app:testDebugUnitTest --tests "com.navertraffic.samsung.data.AndroidServerApiClientJsonTest" :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Result:

- Unit tests passed.
- Debug APK build passed.
- Release APK build passed.
- `lintVitalRelease` ran as part of release assembly and passed.

## Rollback Candidates

Rollback only if field evidence shows ranking regression, missed update loops, or unexpected search behavior.

1. Revert G behavior alignment:
   - Commit: `4f2256a`
   - Effect: returns G to the previous simpler first/second URL-search behavior.

2. Revert in-run update checks:
   - Commit: `229f16a`
   - Effect: updates return to startup/idle-service/manual-command paths only.

3. Revert this memory-bound follow-up:
   - Remove `BoundedMidPhraseMemory`
   - Restore direct `failedSecondPhrasesByMid` / `midMissCountByMid` maps in `SamsungBrowserStrategyG`

Preferred rollback order:

1. Roll back only this memory-bound follow-up if phrase fallback behavior appears wrong.
2. Roll back `4f2256a` if G ranking behavior regresses.
3. Roll back `229f16a` only if APK update checks disrupt task boundaries.

