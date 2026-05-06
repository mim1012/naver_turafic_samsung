# 2026-05-06 AI Reward parity checks

Goal: test only the parts that can be made similar without spoofing browser identity.

## What is intentionally matched

- Android device browser flow, not desktop automation.
- App-owned launch path for the primary one-shot test:
  `PC -> com.navertraffic.samsung -> SamsungBrowserLaunchActivity -> Samsung Internet`.
- Mobile Naver first search, second search, visible product tap, detail swipe.
- Persisted browser cookies/profile. The one-shot app test does not clear browser data.
- Evidence capture for activity stack and app logs.

## What is not matched

- Browser package is not `com.aibrowser.app`.
- UA/client-hints/browser fingerprint are not spoofed.
- Cashmoa offerwall ownership is not reproduced.
- The app-owned runner currently opens Samsung Internet, not Chrome.

## Scripts

### App-owned one-shot launch

```powershell
.\test\0506\run-device-runner-sbrowser-once.ps1
```

This installs the debug APK if needed, starts `com.navertraffic.samsung/.ui.MainActivity`
with `autoRun=true`, and lets the Android app open Samsung Internet. It captures:

- `device-runner-sbrowser-once-*.install.txt`
- `device-runner-sbrowser-once-*.activity.txt`
- `device-runner-sbrowser-once-*.logcat.txt`
- `device-runner-sbrowser-once-*.err.log`

### Direct trace control

```powershell
.\test\0506\run-direct-sbrowser-trace-once.ps1
```

This is not AI Reward-like ownership, but it enables redirect polling to verify whether
the visible click path includes:

```text
m.search.naver.com/search.naver
cr2.shopping.naver.com/adcr
cr3.shopping.naver.com/v2/bridge/searchGate
m.smartstore.naver.com
```

Use this as a diagnostic fallback when the app-owned path cannot inspect page URLs.

### App-owned launch with DevTools MID verification

```powershell
.\test\0506\run-app-owned-devtools-mid-once.ps1
```

This uses the debug APK's exported `SamsungBrowserLaunchActivity` only as a URL launcher.
The browser URL opens are app-owned, while MID verification and physical tap still use
the DevTools/ADB runner because Samsung Internet web content is not exposed enough through
the accessibility tree.

The script rebuilds and reinstalls the debug APK before running so the debug-only exported
launcher manifest overlay is always present.

It also writes reduced flow artifacts next to the main log:

- `app-owned-devtools-mid-once-*.flow.txt`
- `app-owned-devtools-mid-once-*.flow.json`

The one-shot script fails the run when any required tracked-flow endpoint is missing:

```text
m.search.naver.com/search.naver
m.search.naver.com/p/crd/rd
cr2.shopping.naver.com/adcr
cr3.shopping.naver.com/v2/bridge/searchGate
m.smartstore.naver.com
```

By default the runner logs only these flow-relevant network URLs. Set
`TRACE_ALL_NETWORK=1` when a full noisy network dump is needed.

Expected shape:

```text
PC -> com.navertraffic.samsung/.strategy.SamsungBrowserLaunchActivity
-> com.sec.android.app.sbrowser/.SBrowserMainActivity
-> DevTools verifies MID
-> adb input tap exact matched coordinate
```

## AI Reward reference

AI Reward observed path:

```text
com.goodreward.cashmoa
-> OfferWallActivity
-> com.rewardrop.offerwall.RdWebActivity
-> com.aibrowser.app/.main
-> org.chromium.chrome.browser.ChromeTabbedActivity
```

Comparable app-owned test path:

```text
com.navertraffic.samsung
-> SamsungBrowserLaunchActivity
-> com.sec.android.app.sbrowser/.SBrowserMainActivity
```

## 50-run results

### S10 Samsung Internet

```text
summary: test/0506/app-owned-devtools-mid-50-20260506-154908.summary.txt
runs=50
pass=39
fail=11
```

The first 11 runs failed before a full tracked flow was captured. Runs 12 through 50 passed.

### S7 Chrome

```text
summary: test/0506/s7-chrome-mid-50-20260506-161515.summary.txt
runs=50
pass=45
fail=5
```

Failed S7 runs: `10`, `11`, `17`, `28`, `29`.

Those failed runs still reached SmartStore, but strict parity failed because
`m.search.naver.com/p/crd/rd` and `cr2.shopping.naver.com/adcr` were not captured.

## APK-only script runners

These scripts install the APK and start `MainActivity` with `autoRun=true`.
They do not use the Node/DevTools runner.

```powershell
.\test\0506\run-apk-yanggalbi-samsung-50.ps1
.\test\0506\run-apk-yanggalbi-s7-samsung-50.ps1
```

Current script-only limitations:

- The APK fallback task is hardcoded to the 0506 yanggalbi task:
  - first keyword: `양갈비`
  - second keyword: `최상급 어린양으로 만든 양꼬치 양갈비 양고기`
  - MID: `82095489871`
- With `externalBrowser=true`, the APK opens Samsung Internet through
  `SamsungBrowserLaunchActivity`.
- The current APK does not expose script extras for arbitrary 0505/0506 keyword/MID/product
  values. Use the server task lease path for non-fallback products, or change APK code.
- The APK-only scripts can collect `logcat` and `dumpsys activity`, but they cannot produce
  the `.flow.json` strict endpoint evidence from the DevTools runner.

## Server-task APK runners

These are closer to the AI Reward shape: the APK receives `serverUrl`, leases a task from
`/android/tasks/lease`, runs it, and reports to `/android/tasks/report`.

```powershell
.\test\0506\run-apk-server-yanggalbi-s10-50.ps1
.\test\0506\run-apk-server-yanggalbi-s7-50.ps1
.\test\0506\run-apk-server-kellsen-s10-50.ps1
```

Local product files:

- `test/0506/products-server-yanggalbi.json`
- `test/0506/products-server-kellsen.json`
- `test/0506/products-server-0505-0506.json`

The scripts start `server/dev-server.js` with `PRODUCTS_FILE=<product json>` and pass
`serverUrl=http://172.30.1.55:<port>` into the APK.

Current APK behavior leases one task at app start and repeats that same task for
`loopCount`. Leasing a fresh server task per loop requires APK code changes.
