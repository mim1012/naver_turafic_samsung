param(
    [string] $Serial = "ce12160c9966340705",
    [string] $DeviceName = "z2",
    [string] $Apk = "android/SamsungTrafficBot/app/build/outputs/apk/debug/app-debug.apk",
    [int] $LoopCount = 50,
    [int] $WaitSeconds = 1800
)

$ErrorActionPreference = "Stop"

$jbr = "D:\Android\jbr"
if (Test-Path $jbr) {
    $env:JAVA_HOME = $jbr
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$prefix = "test/0506/apk-yanggalbi-s7-samsung-50-$stamp"
$installLog = "$prefix.install.txt"
$logcat = "$prefix.logcat.txt"
$activity = "$prefix.activity.txt"
$err = "$prefix.err.log"

try {
    Push-Location android/SamsungTrafficBot
    try {
        ./gradlew.bat :app:assembleDebug
    } finally {
        Pop-Location
    }

    adb.exe -s $Serial install -r $Apk > $installLog 2> $err
    adb.exe -s $Serial logcat -c

    adb.exe -s $Serial shell am start `
        -n com.navertraffic.samsung/.ui.MainActivity `
        --es deviceName $DeviceName `
        --ez autoRun true `
        --ez externalBrowser true `
        --ei loopCount $LoopCount `
        --ez dryRun false

    Start-Sleep -Seconds $WaitSeconds

    adb.exe -s $Serial shell dumpsys activity activities > $activity
    adb.exe -s $Serial logcat -d -v time -s SamsungTrafficBot > $logcat

    Write-Host "install:  $installLog"
    Write-Host "activity: $activity"
    Write-Host "logcat:   $logcat"
    Write-Host "errors:   $err"
} catch {
    $_ | Out-File -FilePath $err -Append
    throw
}
