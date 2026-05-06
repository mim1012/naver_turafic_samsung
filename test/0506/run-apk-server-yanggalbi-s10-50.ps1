param(
    [string] $Serial = "172.30.1.78:35013",
    [string] $DeviceName = "z1",
    [string] $ServerHost = "172.30.1.55",
    [int] $ServerPort = 18080,
    [string] $ProductsFile = "test/0506/products-server-yanggalbi.json",
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
$prefix = "test/0506/apk-server-yanggalbi-s10-50-$stamp"
$serverLog = "$prefix.server.log"
$installLog = "$prefix.install.txt"
$logcat = "$prefix.logcat.txt"
$activity = "$prefix.activity.txt"
$err = "$prefix.err.log"
$serverUrl = "http://${ServerHost}:${ServerPort}"

try {
    $healthOk = $false
    try {
        $health = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/health" -TimeoutSec 2
        $healthOk = $health.StatusCode -eq 200
    } catch {
        $healthOk = $false
    }

    if (-not $healthOk) {
        $productsPath = (Resolve-Path $ProductsFile).Path
        $repo = (Get-Location).Path
        $serverCommand = "`$env:PRODUCTS_FILE='$productsPath'; `$env:PORT='$ServerPort'; Set-Location '$repo'; node server/dev-server.js *> '$serverLog'"
        Start-Process -FilePath "powershell.exe" -WindowStyle Hidden -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $serverCommand
        Start-Sleep -Seconds 2
    }

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
        --es serverUrl $serverUrl `
        --ez autoRun true `
        --ez externalBrowser true `
        --ei loopCount $LoopCount `
        --ez dryRun false

    Start-Sleep -Seconds $WaitSeconds

    adb.exe -s $Serial shell dumpsys activity activities > $activity
    adb.exe -s $Serial logcat -d -v time -s SamsungTrafficBot > $logcat

    Write-Host "serverUrl: $serverUrl"
    Write-Host "serverLog: $serverLog"
    Write-Host "install:   $installLog"
    Write-Host "activity:  $activity"
    Write-Host "logcat:    $logcat"
    Write-Host "errors:    $err"
} catch {
    $_ | Out-File -FilePath $err -Append
    throw
}
