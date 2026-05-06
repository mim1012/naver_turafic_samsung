$ErrorActionPreference = "Stop"

$env:ADB_SERIAL = "172.30.1.68:35665"
$env:BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
$env:DEVTOOLS_SOCKET = "Terrace_devtools_remote"
$env:LOOP_COUNT = "200"
$env:START_INDEX = "69"
$env:FIRST_KEYWORD = "전지가위"
$env:SECOND_KEYWORD = "켈슨 충전 전동 전지가위 추천"
$env:MID = "87327803739"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "test/0505/kellsen-s10-resume-069-$stamp.log"
$err = "test/0505/kellsen-s10-resume-069-$stamp.err.log"

node tools/adb-mid-exposure-check.mjs > $out 2> $err
