# Android Bot Release Notes

This document records the Android bot APK versions that were relevant during the
0.1.23 -> 0.1.32 fleet update.

## Current production release

- Current production endpoint: `https://www.sellermate.ai.kr/android/app-release/latest`
- Current APK: `https://github.com/mim1012/naver_turafic_samsung/releases/download/android-0.1.35/naver-traffic-samsung-0.1.35-v36-release.apk`
- Current package: `com.navertraffic.samsung`
- Current version: `versionName=0.1.35`, `versionCode=36`
- Current APK SHA256: `99e123a88bcd3d072e30dc491f6f1dbcfa446609ad79a46927d881e2803c0940`

## Version history

| Version name | Version code | Status | Notes |
| --- | ---: | --- | --- |
| 0.1.23 | 24 | Previous field version | Legacy bot APK observed from `z1-2-installed.apk`. This is the version operators referred to as the old `23` bot. It predates the current 0.1.24 rollout and should be replaced on active devices. |
| 0.1.24 | 25 | Previous production | Fleet update target. Published through the Sellermate production latest endpoint and manually verified on the connected ADB devices. |
| 0.1.25 | 26 | Previous production | Adds a foreground remote-control service so configured idle devices keep polling admin commands without ADB. |
| 0.1.26 | 27 | Previous production | Persists operator-started auto-run intent so configured bots resume automatically after update/restart. |
| 0.1.27 | 28 | Previous production | Sets G Chrome-mode UA and `sec-ch-ua` metadata back to Chrome 137 while keeping the auto-resume update path. |
| 0.1.28 | 29 | Previous production | Makes the manual start button visible immediately and lets manual starts continue when Chrome/WebView preflight update is unavailable. |
| 0.1.29 | 30 | Previous production | Routes Naver Shopping tasks by product-scoped strategy assignment: unassigned stays on default G, while A/B/C run in the same APK from task lease metadata. |
| 0.1.30 | 31 | Previous production | Keeps boss-device hotspot/tethering enabled around mobile-data IP rotation and adds idle remote-service auto update checks. |
| 0.1.31 | 32 | Previous production | Aligns Android G with the Electron GUI G flow: five-word first search, search-box second search, failed second-phrase memory, and full-name fallback after repeated MID misses. |
| 0.1.32 | 33 | Previous production | Checks for app updates at safe in-run task boundaries so future releases can apply even while the bot loop keeps running. |
| 0.1.33 | 34 | Previous production | Adds S7 Chrome/WebView 138 update support at safe task boundaries, including APKMirror ZIP-with-single-APK extraction before rooted `pm install -r`. |
| 0.1.34 | 35 | Previous production | Aligns Android G URL loading with Electron G and makes update metadata fall back to GitHub Android releases when the DB row is stale. |
| 0.1.35 | 36 | Current production | Reduces Supabase report growth by suppressing duplicate task reports and keeping success reports out of append-only history. |

## 0.1.24 / v25 changes

Operational changes in the current 0.1.24 build and matching server API work:

1. Android app release channel
   - Bumped Android package metadata to `versionCode=25` and `versionName=0.1.24`.
   - Production `/android/app-release/latest` returns the v25 APK URL and SHA256.
   - Devices checking the update endpoint should see v25 as the latest release.

2. Supabase task leasing/reporting
   - Added RPC-first Android task claiming through `claim_android_naver_task`.
   - Added RPC task reporting through `report_android_naver_task`.
   - Kept raw REST table access as a local/development fallback only, guarded by configuration.
   - Added tests and documentation for durable task claims/reports against existing product tables.

3. Browser/WebView compatibility
   - Updated Chrome-mode UA and `sec-ch-ua` metadata from Chrome 137 to Chrome 138.
   - Added Chrome/WebView update checks for Android 8/9 devices that need a newer Chromium provider.
   - Added server/config driven Chrome APK metadata fields: update URL, SHA256, and minimum Chrome major.
   - Added package visibility for Chrome and Android System WebView.

4. Bot runtime stability
   - Added foreground-service data sync permission and battery optimization handling hooks.
   - Improved keep-alive/restart behavior around APK replacement and device maintenance.
   - Added local status screen support so the WebView can show clear blocked/update states instead of silently idling.
   - Tightened Naver login success checks to require an actual reusable NID cookie session.

5. Fleet/server API surface
   - Expanded Vercel/Supabase Android API documentation for account leases, task leases, cookies, app releases, and Chrome releases.
   - Added server-side routes/tests needed by the Android fleet maintenance/update flow.

## Rollout verification on 2026-05-22

Connected ADB devices were checked with:

```bash
adb devices
adb -s <device> shell dumpsys package com.navertraffic.samsung | grep -E 'versionCode=|versionName='
```

Observed devices after update/check:

| Device | Model | Installed version | Result |
| --- | --- | --- | --- |
| `100.77.48.17:5555` | `SM-G930S` | `versionCode=25`, `versionName=0.1.24` | v25 release APK installed successfully. This device is the `z1-2` bot. |
| `100.119.102.67:5555` | `SM-A908N` | `versionCode=25`, `versionName=0.1.24` | Already on v25. Release APK reinstall was blocked by Android signature mismatch, so this device remains on its existing signing track unless uninstalled or updated with a matching debug-signed APK. |

## Rollout caveats

- Release APK and debug APK signatures are not interchangeable. If a device has a
debug-signed `com.navertraffic.samsung`, `adb install -r` with the release APK fails
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` even when the version is already current.
- Do not uninstall a bot just to switch signing tracks unless app data loss is acceptable
or the operator explicitly approves it.
- For future fleet updates, verify all three layers: public APK URL, latest endpoint
metadata, and `dumpsys package` on each connected device.
