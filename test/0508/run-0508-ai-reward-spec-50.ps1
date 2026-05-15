param(
    [string] $Serial     = "172.30.1.78:35013",
    [string] $DeviceName = "z1-1",
    [string] $Apk        = "android/SamsungTrafficBot/app/build/outputs/apk/debug/app-debug.apk",
    [int]    $LoopCount  = 50,
    [int]    $WaitSeconds = 1800
)

# 0508 — AI Reward 스펙 전체 반영
#
# 변경 사항 (vs 0507):
#   - UA: SamsungBrowser/29.0 Chrome/136 (기존 Chrome/136 기본값 → Samsung Internet 위장)
#   - sec-ch-ua: Samsung Internet 브랜드 포함
#   - 1차 검색: sm=mtp_hty.top 추가
#   - 2차 검색: sm=mtb_hty.top + oquery + ssc=tab.m.all 추가
#   - 초기 랜딩: https://snsz.kr 경유 후 검색 시작

$ErrorActionPreference = "Stop"

$jbr = "D:\Android\jbr"
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }

$stamp  = Get-Date -Format "yyyyMMdd-HHmmss"
$prefix = "test/0508/ai-reward-spec-50-$stamp"
$installLog = "$prefix.install.txt"
$logcat     = "$prefix.logcat.txt"
$activity   = "$prefix.activity.txt"
$err        = "$prefix.err.log"

try {
    Write-Host "=== 빌드 시작 ==="
    Push-Location android/SamsungTrafficBot
    try {
        ./gradlew.bat :app:assembleDebug
    } finally {
        Pop-Location
    }

    Write-Host "=== APK 설치 ==="
    adb.exe -s $Serial install -r $Apk > $installLog 2> $err

    Write-Host "=== 로그캣 초기화 ==="
    adb.exe -s $Serial logcat -c

    Write-Host "=== 앱 실행 (loopCount=$LoopCount) ==="
    adb.exe -s $Serial shell am start `
        -n com.navertraffic.samsung/.ui.MainActivity `
        --es deviceName $DeviceName `
        --ez autoRun true `
        --ez externalBrowser false `
        --ei loopCount $LoopCount `
        --ez dryRun false

    Write-Host "=== 대기 중 ($WaitSeconds 초) ==="
    Start-Sleep -Seconds $WaitSeconds

    Write-Host "=== 로그 수집 ==="
    adb.exe -s $Serial shell dumpsys activity activities > $activity
    adb.exe -s $Serial logcat -d -v time -s SamsungTrafficBot > $logcat

    Write-Host ""
    Write-Host "install:  $installLog"
    Write-Host "activity: $activity"
    Write-Host "logcat:   $logcat"
    Write-Host "errors:   $err"
} catch {
    $_ | Out-File -FilePath $err -Append
    throw
}
