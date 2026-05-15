# AI Reward Runtime Snapshot Plan

This document defines what to collect before comparing AI Reward Browser with the Android/Samsung traffic line at the UA, header, and browser-context level.

The goal is not to prove that Samsung Internet has the same fingerprint as AI Reward Browser. Device and browser fingerprints are expected to differ. The goal is to capture enough runtime evidence to decide which values can be compared, which values can be partially aligned in tests, and which values must remain out of scope.

## Scope

Collect snapshots from both sides:

- AI Reward runtime: `com.goodreward.cashmoa` opening `com.aibrowser.app`.
- Samsung runtime: this repository opening `com.sec.android.app.sbrowser` or `com.android.chrome`.

Keep AI Reward capture artifacts under:

```text
D:\Project\Ai reward\analysis\runtime-captures
```

Keep Samsung comparison artifacts and runner changes in this repository.

## 1. Launch Ownership

Capture which app owns the browser launch.

Required fields:

```text
foreground package/activity before click
foreground package/activity after click
reward app package
offerwall activity
browser package
browser activity
intent action
intent data URL
referrer if visible
dumpsys activity activities
```

Expected AI Reward shape:

```text
com.goodreward.cashmoa
-> OfferWallActivity
-> com.rewardrop.offerwall.RdWebActivity
-> com.aibrowser.app
-> org.chromium.chrome.browser.ChromeTabbedActivity
```

Samsung comparison shape:

```text
com.navertraffic.samsung
-> SamsungBrowserLaunchActivity
-> com.sec.android.app.sbrowser
```

## 2. Browser Identity

Capture the browser identity separately from the shopping flow.

Required fields:

```text
package name
versionName
versionCode
main activity
active activity
Chromium version if observable
profile path if observable
DevTools socket if available
```

Known AI Reward baseline:

```text
package=com.aibrowser.app
activity=org.chromium.chrome.browser.ChromeTabbedActivity
profile=/data/data/com.aibrowser.app/app_chrome/Default
observed Chromium=139.0.7258.66
```

## 3. JavaScript Navigator Snapshot

Collect this from a normal web page inside the active browser.

Required fields:

```text
navigator.userAgent
navigator.userAgentData
navigator.platform
navigator.language
navigator.languages
navigator.hardwareConcurrency
navigator.deviceMemory
navigator.cookieEnabled
navigator.webdriver
```

If `navigator.userAgentData.getHighEntropyValues` is available, collect:

```text
architecture
bitness
model
platformVersion
uaFullVersion
fullVersionList
wow64
```

## 4. Request Header Snapshot

Capture actual request headers because JavaScript navigator values and network headers can differ.

Required headers:

```text
User-Agent
Accept-Language
Sec-CH-UA
Sec-CH-UA-Mobile
Sec-CH-UA-Platform
Sec-CH-UA-Full-Version
Sec-CH-UA-Full-Version-List
Sec-CH-UA-Model
Sec-CH-UA-Platform-Version
Referer
Origin
```

Sensitive tokens and tracking parameters must be redacted before saving.

## 5. Viewport And Device Surface

Capture values that commonly explain differences between devices.

Required JavaScript fields:

```text
screen.width
screen.height
screen.availWidth
screen.availHeight
innerWidth
innerHeight
outerWidth
outerHeight
devicePixelRatio
visualViewport.width
visualViewport.height
visualViewport.scale
```

## 6. Locale And Timezone

Required fields:

```text
Intl.DateTimeFormat().resolvedOptions().timeZone
Intl.DateTimeFormat().resolvedOptions().locale
new Date().getTimezoneOffset()
navigator.language
navigator.languages
```

## 7. WebGL And GPU Surface

Capture this only for comparison. Do not make exact WebGL or GPU equality a pass condition.

Required fields:

```text
WebGL vendor
WebGL renderer
WEBGL_debug_renderer_info unmasked vendor
WEBGL_debug_renderer_info unmasked renderer
```

## 8. Storage And Profile State

Capture profile state without storing sensitive cookie values.

Required fields:

```text
profile directory
Cookies database exists
History database exists
Local Storage path exists
Naver cookie domain/name list
Naver login/session continuity observed
```

Do not save raw cookie values. Save only domain/name metadata unless an explicit secure evidence workflow exists.

## 9. Naver Shopping Flow

Use the same endpoint criteria as `test/0506`.

Required flow endpoints:

```text
m.search.naver.com/search.naver
m.search.naver.com/p/crd/rd
cr2.shopping.naver.com/adcr
cr3.shopping.naver.com/v2/bridge/searchGate
m.smartstore.naver.com/.../products/...
```

Required action evidence:

```text
first keyword
second keyword
target MID
matched anchor href
tap coordinate
final product URL
ordered observed flow
```

## 10. Output Artifacts

Use explicit names so later comparisons are mechanical.

AI Reward side:

```text
ai-reward-s10-activity.txt
ai-reward-s10-browser-identity.json
ai-reward-s10-navigator.json
ai-reward-s10-headers.json
ai-reward-s10-viewport.json
ai-reward-s10-webgl.json
ai-reward-s10-profile-state.txt
ai-reward-s10-flow.json
```

Samsung side:

```text
samsung-s10-activity.txt
samsung-s10-browser-identity.json
samsung-s10-navigator.json
samsung-s10-headers.json
samsung-s10-viewport.json
samsung-s10-webgl.json
samsung-s10-profile-state.txt
samsung-s10-flow.json
```

## Alignment Matrix

| Area | Can be aligned in tests | Notes |
| --- | --- | --- |
| User-Agent | Partial | Possible in controlled WebView or some CDP sessions. Not enough to equalize fingerprint. |
| Accept-Language | Yes | Low-risk comparison field. |
| Sec-CH-UA family | Partial | Browser support and override behavior vary. |
| Viewport | Partial | Can adjust test conditions, but device screen and DPR still differ. |
| Package name | No | Samsung Internet is not `com.aibrowser.app`. |
| Launch ownership | No for Samsung line | Requires the actual Cashmoa/offerwall path on the AI Reward side. |
| Chromium profile path | No | Browser-specific storage path. |
| WebGL/GPU | No | Device and driver dependent. |
| History DB order | No | Browser persistence order and CDP network event order are different evidence surfaces. |
| Device fingerprint | No | Expected to differ by model, OS, GPU, browser, and profile. |

## Decision Rule

For Samsung parity, keep the pass condition focused on shopping path evidence:

```text
hasSearch=true
hasClickBeacon=true
hasCr2=true
hasCr3SearchGate=true
hasSmartStore=true
targetMidMatched=true
finalProductMatched=true
```

UA, Client Hints, and fingerprint snapshots are comparison evidence only. They should not be used as strict pass conditions for `test/0506` unless the goal explicitly changes from path parity to browser identity research.
