param(
    [string]$Device,
    [string]$BrowserPackage,
    [switch]$ToggleData,
    [switch]$ToggleWifi,
    [int]$WaitSeconds = 8
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param(
        [string[]]$AdbArgs,
        [switch]$AllowFail
    )

    $output = & adb @AdbArgs 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFail) {
        throw "adb $($AdbArgs -join ' ') failed ($code): $output"
    }
    return ($output -join "`n").Trim()
}

function Invoke-AdbShell {
    param(
        [string]$Serial,
        [string]$Command,
        [switch]$AllowFail
    )

    return Invoke-Adb -AdbArgs @("-s", $Serial, "shell", $Command) -AllowFail:$AllowFail
}

function Get-AttachedDevices {
    $raw = Invoke-Adb -AdbArgs @("devices", "-l")
    $devices = @()
    foreach ($line in ($raw -split "`n")) {
        if ($line -match "^\s*(\S+)\s+device\s+(.*)$") {
            $devices += [pscustomobject]@{
                Serial = $matches[1]
                Detail = $matches[2].Trim()
                IsTcp = $matches[1] -match "^\d+\.\d+\.\d+\.\d+:"
            }
        }
    }
    return $devices
}

function Get-DefaultBrowserPackage {
    param([string]$Serial)

    $packages = Invoke-AdbShell $Serial "pm list packages" -AllowFail
    foreach ($candidate in @("com.android.chrome", "com.sec.android.app.sbrowser", "com.aibrowser.app")) {
        if ($packages -match [regex]::Escape("package:$candidate")) {
            return $candidate
        }
    }
    return $null
}

function Wake-Device {
    param([string]$Serial)

    Invoke-AdbShell $Serial "input keyevent KEYCODE_WAKEUP" -AllowFail | Out-Null
    Invoke-AdbShell $Serial "wm dismiss-keyguard" -AllowFail | Out-Null
    Invoke-AdbShell $Serial "input keyevent 82" -AllowFail | Out-Null
    Start-Sleep -Seconds 1
}

function Get-DeviceSnapshot {
    param([string]$Serial)

    $route = Invoke-AdbShell $Serial "ip route" -AllowFail
    $addr = Invoke-AdbShell $Serial "ip addr show" -AllowFail
    $sim = Invoke-AdbShell $Serial "getprop gsm.sim.state" -AllowFail
    $operator = Invoke-AdbShell $Serial "getprop gsm.operator.alpha" -AllowFail
    $networkType = Invoke-AdbShell $Serial "getprop gsm.network.type" -AllowFail
    $mobileData = Invoke-AdbShell $Serial "settings get global mobile_data" -AllowFail
    $model = Invoke-AdbShell $Serial "getprop ro.product.model" -AllowFail
    $android = Invoke-AdbShell $Serial "getprop ro.build.version.release" -AllowFail

    $defaultIface = $null
    if ($route -match "(?m)^default\s+via\s+\S+\s+dev\s+(\S+)") {
        $defaultIface = $matches[1]
    } elseif ($route -match "(?m)^\S+/\d+\s+dev\s+(\S+).*scope link") {
        $defaultIface = $matches[1]
    }

    $ipv4 = @()
    foreach ($line in ($addr -split "`n")) {
        if ($line -match "inet\s+(\d+\.\d+\.\d+\.\d+/\d+)") {
            $ipv4 += $matches[1]
        }
    }

    return [pscustomobject]@{
        Serial = $Serial
        Model = $model
        Android = $android
        SimState = $sim
        Operator = $operator
        NetworkType = $networkType
        MobileData = $mobileData
        DefaultIface = $defaultIface
        Ipv4 = ($ipv4 -join ", ")
        Route = $route
    }
}

function Get-PublicIpFromBrowser {
    param(
        [string]$Serial,
        [string]$Package,
        [int]$WaitSeconds
    )

    if (-not $Package) {
        return [pscustomobject]@{
            Ip = $null
            Url = $null
            Package = $null
            RawText = "No supported browser package found"
        }
    }

    $url = "https://api.ipify.org"
    Wake-Device $Serial
    Invoke-AdbShell $Serial "am force-stop $Package" -AllowFail | Out-Null
    Invoke-AdbShell $Serial "am start -a android.intent.action.VIEW -d $url -p $Package" -AllowFail | Out-Null
    Start-Sleep -Seconds $WaitSeconds

    Invoke-AdbShell $Serial "uiautomator dump /sdcard/window-ip.xml" -AllowFail | Out-Null
    $xml = Invoke-AdbShell $Serial "cat /sdcard/window-ip.xml" -AllowFail
    $decoded = [System.Net.WebUtility]::HtmlDecode($xml)

    $ip = $null
    if ($decoded -match "\b(?:\d{1,3}\.){3}\d{1,3}\b") {
        $ip = $matches[0]
    }

    return [pscustomobject]@{
        Ip = $ip
        Url = $url
        Package = $Package
        RawText = (($decoded -replace "\s+", " ").Trim())
    }
}

function Measure-Device {
    param(
        [pscustomobject]$DeviceInfo,
        [string]$BrowserPackage,
        [string]$Label
    )

    $snapshot = Get-DeviceSnapshot $DeviceInfo.Serial
    $browser = if ($BrowserPackage) { $BrowserPackage } else { Get-DefaultBrowserPackage $DeviceInfo.Serial }
    $public = Get-PublicIpFromBrowser -Serial $DeviceInfo.Serial -Package $browser -WaitSeconds $WaitSeconds

    return [pscustomobject]@{
        Label = $Label
        Serial = $DeviceInfo.Serial
        Model = $snapshot.Model
        Android = $snapshot.Android
        SimState = $snapshot.SimState
        Operator = $snapshot.Operator
        NetworkType = $snapshot.NetworkType
        MobileData = $snapshot.MobileData
        DefaultIface = $snapshot.DefaultIface
        Ipv4 = $snapshot.Ipv4
        BrowserPackage = $public.Package
        PublicIp = $public.Ip
        PublicIpUrl = $public.Url
        IsTcpAdb = $DeviceInfo.IsTcp
        Route = $snapshot.Route
    }
}

$devices = Get-AttachedDevices
if ($Device) {
    $devices = @($devices | Where-Object { $_.Serial -eq $Device })
}

if ($devices.Count -eq 0) {
    throw "No attached ADB devices matched."
}

$results = @()
foreach ($d in $devices) {
    Write-Host "== $($d.Serial) baseline =="
    $before = Measure-Device -DeviceInfo $d -BrowserPackage $BrowserPackage -Label "before"
    $results += $before
    $before | Format-List Label,Serial,Model,Android,SimState,Operator,NetworkType,MobileData,DefaultIface,Ipv4,BrowserPackage,PublicIp,IsTcpAdb

    if ($ToggleWifi) {
        if ($d.IsTcp) {
            Write-Warning "Skipping Wi-Fi toggle for $($d.Serial): this is TCP ADB and disabling Wi-Fi would disconnect ADB."
        } else {
            Write-Host "Toggling Wi-Fi off/on on $($d.Serial)"
            Invoke-AdbShell $d.Serial "svc wifi disable" -AllowFail | Out-Null
            Start-Sleep -Seconds 5
            Invoke-AdbShell $d.Serial "svc wifi enable" -AllowFail | Out-Null
            Start-Sleep -Seconds $WaitSeconds
            $afterWifi = Measure-Device -DeviceInfo $d -BrowserPackage $BrowserPackage -Label "after-wifi-toggle"
            $results += $afterWifi
            $afterWifi | Format-List Label,Serial,DefaultIface,Ipv4,PublicIp
        }
    }

    if ($ToggleData) {
        Write-Host "Toggling mobile data off/on on $($d.Serial)"
        Invoke-AdbShell $d.Serial "svc data disable" -AllowFail | Out-Null
        Start-Sleep -Seconds 5
        Invoke-AdbShell $d.Serial "svc data enable" -AllowFail | Out-Null
        Start-Sleep -Seconds $WaitSeconds
        $afterData = Measure-Device -DeviceInfo $d -BrowserPackage $BrowserPackage -Label "after-data-toggle"
        $results += $afterData
        $afterData | Format-List Label,Serial,DefaultIface,Ipv4,PublicIp
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path (Get-Location) "test\ip-check"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$csvPath = Join-Path $outDir "adb-network-ip-check-$timestamp.csv"
$jsonPath = Join-Path $outDir "adb-network-ip-check-$timestamp.json"
$results | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $csvPath
$results | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -Path $jsonPath

Write-Host ""
Write-Host "Saved:"
Write-Host "  $csvPath"
Write-Host "  $jsonPath"

if ($results.Count -gt 1) {
    Write-Host ""
    Write-Host "IP comparison:"
    $results | Group-Object Serial | ForEach-Object {
        $ips = @($_.Group | Select-Object -ExpandProperty PublicIp | Where-Object { $_ }) | Select-Object -Unique
        $status = if ($ips.Count -gt 1) { "CHANGED" } elseif ($ips.Count -eq 1) { "UNCHANGED" } else { "UNKNOWN" }
        Write-Host "  $($_.Name): $status ($($ips -join ' -> '))"
    }
}
