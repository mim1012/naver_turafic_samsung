param(
    [string] $Serial = "ce12160c9966340705",
    [int] $Runs = 50
)

$ErrorActionPreference = "Stop"

$batchStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summary = "test/0506/s7-chrome-mid-50-$batchStamp.summary.csv"
$summaryText = "test/0506/s7-chrome-mid-50-$batchStamp.summary.txt"

"run,status,success,hasSearch,hasClickBeacon,hasCr2,hasCr3SearchGate,hasSmartStore,log,err,flow,flowJson" | Set-Content -Path $summary -Encoding UTF8

$env:ADB_SERIAL = $Serial
$env:BROWSER_PACKAGE = "com.android.chrome"
$env:DEVTOOLS_SOCKET = "chrome_devtools_remote"
$env:LOOP_COUNT = "1"
$env:START_INDEX = "1"
$env:FIRST_KEYWORD = "양갈비"
$env:SECOND_KEYWORD = "최상급 어린양으로 만든 양꼬치 양갈비 양고기"
$env:MID = "82095489871"
$env:TRACE_REDIRECTS = "1"
$env:TRACE_ALL_NETWORK = "0"
$env:REDIRECT_TRACE_MS = "8000"
$env:REDIRECT_TRACE_INTERVAL_MS = "200"
$env:CLOSE_BROWSER_EACH_LOOP = "0"
$env:APP_OWNED_LAUNCH = "0"
$env:REQUIRE_TRACKED_FLOW = "1"

$pass = 0
$fail = 0

for ($i = 1; $i -le $Runs; $i += 1) {
    $runStamp = "{0}-{1:D2}" -f $batchStamp, $i
    $out = "test/0506/s7-chrome-mid-50-$runStamp.log"
    $err = "test/0506/s7-chrome-mid-50-$runStamp.err.log"
    $flow = "test/0506/s7-chrome-mid-50-$runStamp.flow.txt"
    $flowJson = "test/0506/s7-chrome-mid-50-$runStamp.flow.json"

    $env:FLOW_OUTPUT = $flow
    $env:FLOW_JSON_OUTPUT = $flowJson

    Write-Host "[$i/$Runs] start"
    node tools/adb-mid-exposure-check.mjs > $out 2> $err
    $exitCode = $LASTEXITCODE

    $status = "fail"
    $success = "false"
    $hasSearch = "false"
    $hasClickBeacon = "false"
    $hasCr2 = "false"
    $hasCr3SearchGate = "false"
    $hasSmartStore = "false"

    if (Test-Path $flowJson) {
        $json = Get-Content -Path $flowJson -Raw | ConvertFrom-Json
        $hasSearch = [string] $json.summary.hasSearch
        $hasClickBeacon = [string] $json.summary.hasClickBeacon
        $hasCr2 = [string] $json.summary.hasCr2
        $hasCr3SearchGate = [string] $json.summary.hasCr3SearchGate
        $hasSmartStore = [string] $json.summary.hasSmartStore
    }

    if ($exitCode -eq 0 -and
        $hasSearch -eq "True" -and
        $hasClickBeacon -eq "True" -and
        $hasCr2 -eq "True" -and
        $hasCr3SearchGate -eq "True" -and
        $hasSmartStore -eq "True") {
        $status = "pass"
        $success = "true"
        $pass += 1
    } else {
        $fail += 1
    }

    "$i,$status,$success,$hasSearch,$hasClickBeacon,$hasCr2,$hasCr3SearchGate,$hasSmartStore,$out,$err,$flow,$flowJson" |
        Add-Content -Path $summary -Encoding UTF8
    Write-Host "[$i/$Runs] $status"
}

$lines = @(
    "batch=$batchStamp",
    "serial=$Serial",
    "browserPackage=com.android.chrome",
    "devtoolsSocket=chrome_devtools_remote",
    "runs=$Runs",
    "pass=$pass",
    "fail=$fail",
    "summaryCsv=$summary"
)
$lines | Set-Content -Path $summaryText -Encoding UTF8

Write-Host "summary: $summary"
Write-Host "summaryText: $summaryText"
