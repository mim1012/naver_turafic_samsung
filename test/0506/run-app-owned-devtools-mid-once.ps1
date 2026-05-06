param(
    [string] $Serial = "172.30.1.78:35013",
    [string] $Apk = "android/SamsungTrafficBot/app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"

$jbr = "D:\Android\jbr"
if (Test-Path $jbr) {
    $env:JAVA_HOME = $jbr
}

Push-Location android/SamsungTrafficBot
try {
    ./gradlew.bat :app:assembleDebug
} finally {
    Pop-Location
}

adb.exe -s $Serial install -r $Apk | Out-Host

$env:ADB_SERIAL = $Serial
$env:BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
$env:DEVTOOLS_SOCKET = "Terrace_devtools_remote"
$env:LOOP_COUNT = "1"
$env:START_INDEX = "1"
$env:FIRST_KEYWORD = "양갈비"
$env:SECOND_KEYWORD = "최상급 어린양으로 만든 양꼬치 양갈비 양고기"
$env:MID = "82095489871"
$env:TRACE_REDIRECTS = "1"
$env:REDIRECT_TRACE_MS = "8000"
$env:REDIRECT_TRACE_INTERVAL_MS = "200"
$env:CLOSE_BROWSER_EACH_LOOP = "0"
$env:APP_OWNED_LAUNCH = "1"
$env:LAUNCHER_ACTIVITY = "com.navertraffic.samsung/.strategy.SamsungBrowserLaunchActivity"
$env:LAUNCHER_URL_EXTRA = "com.navertraffic.samsung.extra.URL"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "test/0506/app-owned-devtools-mid-once-$stamp.log"
$err = "test/0506/app-owned-devtools-mid-once-$stamp.err.log"
$activity = "test/0506/app-owned-devtools-mid-once-$stamp.activity.txt"
$flow = "test/0506/app-owned-devtools-mid-once-$stamp.flow.txt"
$flowJson = "test/0506/app-owned-devtools-mid-once-$stamp.flow.json"

$env:FLOW_OUTPUT = $flow
$env:FLOW_JSON_OUTPUT = $flowJson
$env:REQUIRE_TRACKED_FLOW = "1"

node tools/adb-mid-exposure-check.mjs > $out 2> $err
adb.exe -s $Serial shell dumpsys activity activities > $activity

Write-Host "log:      $out"
Write-Host "errors:   $err"
Write-Host "activity: $activity"
Write-Host "flow:     $flow"
Write-Host "flowJson: $flowJson"
