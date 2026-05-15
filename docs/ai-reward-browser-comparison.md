# AI Reward Browser Comparison

This note compares the observed AI Reward Browser runtime with this Android/Samsung traffic line. The comparison target is this repository, not the PC runner.

## Observed AI Reward Baseline

- Reward app: `com.goodreward.cashmoa`
- Browser app: `com.aibrowser.app`
- Browser activity: `org.chromium.chrome.browser.ChromeTabbedActivity`
- Chromium profile: `/data/data/com.aibrowser.app/app_chrome/Default`
- Observed Chromium version in profile: `139.0.7258.66`
- Observed flow:
  1. Mobile Naver first search.
  2. Mobile Naver second search.
  3. Shopping click through Naver tracking endpoints.
  4. Mobile SmartStore product detail.
- Captured redirect shape:
  - `m.search.naver.com/search.naver`
  - `cr2.shopping.naver.com/adcr`
  - `cr3.shopping.naver.com/v2/bridge/searchGate`
  - `m.smartstore.naver.com/.../products/...`

## Current Android/Samsung Baseline

- Primary external browser target: Samsung Internet, `com.sec.android.app.sbrowser`
- S7 fallback seen in logs: Chrome, `com.android.chrome`
- Search runner: `tools/adb-mid-exposure-check.mjs`
- Android app strategy: `android/SamsungTrafficBot/.../SamsungBrowserStrategyA.kt`
- Launch style:
  - Android `ACTION_VIEW`
  - Samsung Internet `SBrowserMainActivity`
  - `Browser.EXTRA_CREATE_NEW_TAB=false`
  - `NEW_TASK`, `CLEAR_TOP`, `SINGLE_TOP`
- Current flow:
  1. Open mobile Naver first search URL.
  2. Open mobile Naver second search URL.
  3. Detect target MID in the real rendered page.
  4. Tap the real screen coordinate.
  5. Swipe on the detail page.

## Same Parts

- Both are real Android mobile-browser flows, not desktop browser automation.
- Both use `m.search.naver.com/search.naver`.
- Both perform first keyword search, second keyword search, and product-detail entry.
- Both can enter through a tracked Naver shopping bridge when the target MID is exposed.
- Both rely on the browser's persisted cookies/storage instead of passing raw login credentials through the runner.

## Different Parts

| Area | AI Reward Browser | Android/Samsung Traffic |
| --- | --- | --- |
| Browser package | `com.aibrowser.app` | `com.sec.android.app.sbrowser` or `com.android.chrome` |
| Browser engine identity | Chromium app profile, observed 139 | Samsung Internet / Chrome device browser |
| UA family | Chromium-like | Samsung Internet or Chrome UA |
| Launch owner | Reward app opens separate AI browser | Runner opens Samsung Internet directly |
| Search URL details | UI-generated params such as `sm`, `ssc`, `oquery`, `ackey`, `tqi` can appear | Direct URL currently uses `where=m&query=...` |
| Redirect evidence | Browser history captured `cr2` and `cr3` entries | Existing logs showed final SmartStore URL and `tracked-search-gate`, not full chain |
| Inspection method | Rooted profile/history capture and runtime observation | DevTools page inspection plus physical tap |

## Meaning

The Android/Samsung folder is the right comparison line because it already runs the same mobile Naver behavior class: real Android device, mobile search pages, MID-based click, and persisted browser state.

It is not identical to AI Reward Browser at the browser-identity layer. AI Reward is a separate Chromium shell. This repository is the Samsung Internet / Android browser line. Treat them as two Android variants:

- `ai-reward-browser`: observed reference behavior from the reward app.
- `android-samsung`: controlled Samsung Internet/Chrome execution.

Do not overwrite Samsung behavior with the AI Reward browser identity unless the goal explicitly changes to cloning AI Reward Browser. For Samsung traffic, the key is to verify that the search and click path is equivalent enough: search page, tracked bridge, final product, dwell/swipe, and account/session continuity.

## Required Evidence For Real Comparison

Existing Samsung logs prove:

- first and second mobile search happened,
- target MID was found as `tracked-search-gate`,
- a physical tap was issued,
- the final page became the expected SmartStore URL in successful runs.

Missing evidence before this update:

- whether the visible redirect chain contains `cr2.shopping.naver.com/adcr`,
- whether it contains `cr3.shopping.naver.com/v2/bridge/searchGate`,
- whether anomalous final URLs came from wrong tap, stale page, external redirect, or unrelated browser state.

`tools/adb-mid-exposure-check.mjs` now supports optional redirect URL polling:

```powershell
$env:TRACE_REDIRECTS="1"
$env:REDIRECT_TRACE_MS="8000"
$env:REDIRECT_TRACE_INTERVAL_MS="300"
node tools\adb-mid-exposure-check.mjs
```

The trace is diagnostic only. It does not change tapping, search, cookies, or browser lifecycle. Sensitive tracking parameters are redacted in the new trace/final URL logs.

## Runtime Snapshot Evidence

UA, Client Hints, viewport, WebGL/GPU, profile state, and request headers are not fully captured by the `test/0506` flow artifacts. If the comparison needs these browser-context values, collect them with the checklist in `docs/ai-reward-runtime-snapshot-plan.md`.

Those values are comparison evidence, not strict pass conditions for the Samsung path-parity test. The Samsung line should continue to treat browser package, launch owner, Chromium profile path, WebGL/GPU, and device fingerprint as expected differences unless the goal explicitly changes to AI Reward browser identity research.

## Priority Fixes

1. Run one short Samsung trace with `TRACE_REDIRECTS=1` and compare the chain against the AI Reward capture.
2. If `cr2/cr3` appears, keep the Samsung line as-is and use redirect evidence as the equivalence proof.
3. If `cr2/cr3` does not appear, inspect the anchor `href` before tapping and compare it with the AI Reward history entry.
4. Keep AI Reward capture artifacts under `D:\Project\Ai reward\analysis\runtime-captures`; keep Samsung comparison and runner changes in this repository.
5. Keep PC runner changes separate. The PC runner is not the baseline for this comparison.
