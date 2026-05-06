param(
    [string] $Serial = "172.30.1.78:35013",
    [string] $DeviceName = "z1",
    [string] $Apk = "android/SamsungTrafficBot/app/build/outputs/apk/debug/app-debug.apk"
)

$ErrorActionPreference = "Stop"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$prefix = "test/0506/device-runner-sbrowser-once-$stamp"
$logcat = "$prefix.logcat.txt"
$activity = "$prefix.activity.txt"
$installLog = "$prefix.install.txt"
$err = "$prefix.err.log"

try {
    if (-not (Test-Path $Apk)) {
        Push-Location android/SamsungTrafficBot
        try {
            ./gradlew.bat :app:assembleDebug
        } finally {
            Pop-Location
        }
    }

    adb.exe -s $Serial install -r $Apk > $installLog 2> $err
    adb.exe -s $Serial logcat -c

    adb.exe -s $Serial shell am start `
        -n com.navertraffic.samsung/.ui.MainActivity `
        --es deviceName $DeviceName `
        --ez autoRun true `
        --ez externalBrowser true `
        --ei loopCount 1 `
        --ez dryRun false

    Start-Sleep -Seconds 35

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
