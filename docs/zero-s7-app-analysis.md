# S7 Zero App Analysis

## Scope

ADB target:

```text
serial: ce12160c9966340705
model: SM-G930S
```

Installed Zero-related package on the S7:

```text
package: com.zero.updater.rank
label: 제로순위 Updater
versionCode: 12
versionName: 2.3r
main activity: com.loveplusplus.update.sample.MainActivity
```

Important distinction:

- The S7-installed package is an updater shell, not the full `com.zero.traffic` execution app.
- Its manifest contains update-related components only: `MainActivity`, `BootReceiver`, `DownloadService`, and `UpdateApkFileProvider`.
- It requests `INTERNET`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`, and `KILL_BACKGROUND_PROCESSES`.
- It does not expose WebView, task claim, or UA-handling logic in the manifest-level installed package surface.
- The traffic execution logic is in the Zero Android source trees:
  - `D:\Project\zero\apps\android`
  - `D:\Project\zero-android-optionc`

The rest of this document describes the traffic app implementation found in those source trees.

## Server Base URL

The Android traffic service receives `server_url` from the launch intent or saved preferences.

In the traffic service it builds the API client like this:

```text
serverUrl + "/zero/api/v1"
```

So if the UI or boot receiver starts the service with:

```text
https://example.com
```

the app calls:

```text
https://example.com/zero/api/v1/...
```

## UA Fetch Flow

The newer `zero-android-optionc` implementation fetches UA/header data from the server before WebView initialization.

Flow:

```text
TrafficService.init(serverUrl)
  -> api = ApiClient(serverUrl + "/zero/api/v1")
  -> api.fetchMobileHeaders()
  -> GET /headers/mobile
  -> MobileHeaderConfig(json)
  -> initWebView()
  -> WebSettings.setUserAgentString(mobileHeaderConfig.userAgent)
  -> runner.setMobileHeaders(mobileHeaderConfig)
  -> ActionExecutor.navigate()
  -> webView.loadUrl(url, buildNavHeaders(url))
```

Client model:

```text
user_agent        -> WebView User-Agent
sec_ch_ua         -> navigation header Sec-CH-UA
sec_ch_ua_mobile  -> navigation header Sec-CH-UA-Mobile
sec_ch_ua_platform -> navigation header Sec-CH-UA-Platform
accept_language   -> navigation header Accept-Language
```

Server endpoint:

```text
GET /zero/api/v1/headers/mobile
```

Server behavior:

- Reads active rows from Supabase table `mobile_headers`.
- Picks one row randomly.
- If the table is unavailable or empty, falls back to hardcoded Samsung/Chrome mobile headers in `apps/server/app/api/v1/headers.py`.

Fallback examples include Samsung model UAs such as `SM-S928N`, `SM-G991N`, `SM-A546N`, and `SM-G977N` with Chrome `133`.

## UA Application Details

There are two generations in the source.

`D:\Project\zero\apps\android`:

- Uses the WebView default UA.
- Converts it to a Chrome-like UA by removing `; wv)` and `Version/x.x`.
- Applies it at navigation time with `setUserAgentString`.
- Builds `UserAgentMetadata` from the Chrome version detected in that UA.
- Attempts to remove `X-Requested-With` through AndroidX WebKit and reflection.

`D:\Project\zero-android-optionc`:

- Fetches server-provided mobile headers first.
- Applies `mobileHeaderConfig.userAgent` directly during WebView initialization.
- Passes the same config into `ScenarioRunner` and `ActionExecutor`.
- Adds request headers for navigation through `loadUrl(url, headers)`.
- If server headers are missing, falls back to WebView UA cleanup and a Chrome `131`-style fallback.

## Work Claim Flow

Android task claim:

```text
TrafficService.mainLoop()
  -> taskManager.claimWork()
  -> POST /zero/api/v1/traffic/claim-work
     body: { "device_id": "<ANDROID_ID>" }
  -> TaskInfo(response)
```

Server claim implementation:

```text
traffic-navershopping-app
  -> select oldest row
  -> read id, slot_id, keyword, link_url

slot_naverapp
  -> lookup by slot_id
  -> read mid, product_name

traffic-navershopping-app
  -> delete claimed row

landing_redirects
  -> create/update redirect slug

response
  -> traffic_id
  -> slot_id
  -> product_name
  -> nv_mid
  -> short_keyword
  -> target_url
  -> login_id/login_pw in optionc server flow
```

Android `TaskInfo` maps:

```text
traffic_id    -> task traffic id
slot_id       -> slot id for complete/fail
product_name  -> product name
nv_mid        -> target MID
short_keyword -> search keyword
target_url    -> landing/search URL
login_id      -> Naver login id, optionc only
login_pw      -> Naver login password, optionc only
```

## Execution Flow

Once a task is claimed:

```text
ScenarioManager.selectScenario()
  -> ScenarioRunner.execute(scenario, task)
  -> VariableResolver substitutes:
       {{task.keyword}}
       {{task.nv_mid}}
       {{task.target_url}}
       {{task.login_id}}
       {{task.login_pw}}
  -> ActionExecutor executes steps
  -> complete/fail report
```

Completion:

```text
POST /zero/api/v1/traffic/complete
body: traffic_id, slot_id, device_id
```

Failure:

```text
POST /zero/api/v1/traffic/fail
body: traffic_id, slot_id, device_id, error_message
```

Server updates:

- `slot_naverapp.success_count` or `slot_naverapp.fail_count`
- best-effort insert into `slot_rank_naverapp_history`

## S7-Specific Notes

The S7 device model is `SM-G930S`.

The server config has an old S7-style UA entry in:

```text
D:\Project\zero\apps\server\app\config\user_agents.json
```

```text
SM-G930 -> Mozilla/5.0 (Linux; Android 8.0.0; SM-G930S) ... Chrome/119...
```

But the active `/headers/mobile` endpoint does not necessarily use `user_agents.json`; it primarily uses Supabase `mobile_headers`, then the hardcoded fallback pool in `headers.py`.

Therefore the UA used on the device depends on which Android build is actually installed:

- Installed `com.zero.updater.rank`: updater only, no observed traffic UA flow.
- `com.zero.traffic` from `D:\Project\zero\apps\android`: derives UA from local WebView.
- `com.zero.traffic` from `D:\Project\zero-android-optionc`: fetches UA from `/headers/mobile` and applies it to WebView plus navigation headers.

## Evidence Collected

ADB package evidence:

```text
pm list packages: com.zero.updater.rank
pm path: /data/app/com.zero.updater.rank-p2THmLCqH2BRZBGFs8i2hQ==/base.apk
aapt badging: label=제로순위 Updater, versionName=2.3r
manifest: updater MainActivity, BootReceiver, DownloadService, FileProvider
```

Source evidence:

```text
D:\Project\zero-android-optionc\app\src\main\java\com\zero\traffic\service\TrafficService.java
D:\Project\zero-android-optionc\app\src\main\java\com\zero\traffic\server\ApiClient.java
D:\Project\zero-android-optionc\app\src\main\java\com\zero\traffic\model\MobileHeaderConfig.java
D:\Project\zero-android-optionc\app\src\main\java\com\zero\traffic\engine\ActionExecutor.java
D:\Project\zero\apps\server\app\api\v1\headers.py
D:\Project\zero\apps\server\app\api\v1\traffic.py
```
