$ErrorActionPreference = "Stop"

$env:ADB_SERIAL = "ce12160c9966340705"
$env:BROWSER_PACKAGE = "com.android.chrome"
$env:DEVTOOLS_SOCKET = "chrome_devtools_remote"
$env:LOOP_COUNT = "200"
$env:START_INDEX = "1"
$env:FIRST_KEYWORD = "양갈비"
$env:SECOND_KEYWORD = "최상급 어린양으로 만든 양꼬치 양갈비 양고기"
$env:MID = "82095489871"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "test/0505/yanggalbi-s7-001-$stamp.log"
$err = "test/0505/yanggalbi-s7-001-$stamp.err.log"

node tools/adb-mid-exposure-check.mjs > $out 2> $err
