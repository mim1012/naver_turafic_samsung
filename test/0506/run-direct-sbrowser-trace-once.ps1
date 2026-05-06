param(
    [string] $Serial = "172.30.1.78:35013"
)

$ErrorActionPreference = "Stop"

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
$env:REDIRECT_TRACE_INTERVAL_MS = "300"
$env:CLOSE_BROWSER_EACH_LOOP = "0"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "test/0506/direct-sbrowser-trace-once-$stamp.log"
$err = "test/0506/direct-sbrowser-trace-once-$stamp.err.log"

node tools/adb-mid-exposure-check.mjs > $out 2> $err

Write-Host "log:    $out"
Write-Host "errors: $err"
