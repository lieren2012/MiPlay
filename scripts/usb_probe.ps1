#Requires -Version 5.1
<#
.SYNOPSIS
  USB identity probe for the ICCOA -> CarPlay box (车连易 CPC200-U2H IE).

.DESCRIPTION
  Snapshots the USB devices Windows can see so that plugging the box in can be
  diffed instead of guessed. Read-only: it does not install drivers, does not
  send custom USB requests and does not modify anything.

  Usage:
    # 1. before plugging the box in
    powershell -ExecutionPolicy Bypass -File scripts\usb_probe.ps1 -Mode baseline

    # 2. plug the box into the laptop, then
    powershell -ExecutionPolicy Bypass -File scripts\usb_probe.ps1 -Mode diff
#>
param(
    [ValidateSet('baseline', 'diff', 'list')]
    [string]$Mode = 'baseline',
    [string]$Baseline = 'reports\usb-baseline.json',
    [string]$Report = 'reports\usb-probe.md'
)

$ErrorActionPreference = 'Stop'
$Keys = @(
    'DEVPKEY_Device_Service',
    'DEVPKEY_Device_Manufacturer',
    'DEVPKEY_Device_DriverDesc',
    'DEVPKEY_Device_HardwareIds'
)

function Get-UsbSnapshot {
    # one server-side filter, then one bulk property read: this stays fast.
    $devices = @(Get-PnpDevice -PresentOnly -InstanceId 'USB\*' -ErrorAction SilentlyContinue)
    if ($devices.Count -eq 0) { return @() }
    $ids = @($devices | ForEach-Object { $_.InstanceId })
    $properties = @(Get-PnpDeviceProperty -InstanceId $ids -KeyName $Keys -ErrorAction SilentlyContinue)
    $grouped = @{}
    foreach ($property in $properties) {
        if (-not $grouped.ContainsKey($property.InstanceId)) {
            $grouped[$property.InstanceId] = @{}
        }
        $grouped[$property.InstanceId][$property.KeyName] = @($property.Data)
    }
    $snapshot = foreach ($device in $devices) {
        $bag = if ($grouped.ContainsKey($device.InstanceId)) { $grouped[$device.InstanceId] } else { @{} }
        [pscustomobject]@{
            instance_id   = $device.InstanceId
            class         = [string]$device.Class
            friendly_name = [string]$device.FriendlyName
            status        = [string]$device.Status
            service       = (@($bag['DEVPKEY_Device_Service']) -join ',')
            manufacturer  = (@($bag['DEVPKEY_Device_Manufacturer']) -join ',')
            description   = (@($bag['DEVPKEY_Device_DriverDesc']) -join ',')
            hardware_ids  = (@($bag['DEVPKEY_Device_HardwareIds']) -join ';')
        }
    }
    return @($snapshot | Sort-Object instance_id)
}

function Get-Verdict {
    param($Device)
    $text = "$($Device.friendly_name) $($Device.description) $($Device.manufacturer) $($Device.hardware_ids) $($Device.service)"
    $vid = if ($text -match 'VID_([0-9A-Fa-f]{4})') { $Matches[1].ToUpper() } else { '????' }
    $productId = if ($text -match 'PID_([0-9A-Fa-f]{4})') { $Matches[1].ToUpper() } else { '????' }
    $verdicts = @()
    if ($vid -eq '18D1') { $verdicts += 'google/android VID: try adb devices (best case)' }
    if ($text -match 'ADB|Android|WinUsb|libusb') { $verdicts += 'looks like an Android/ADB interface: try adb devices' }
    if ($vid -eq '05AC') { $verdicts += 'Apple VID: this is the identity it presents to the car, no shell expected' }
    if ($Device.class -eq 'Ports' -or $text -match 'CDC|Serial|UsbSerialPort') { $verdicts += 'serial port: read the banner at 115200 baud (read-only)' }
    if ($text -match 'RNDIS|NCM|ECM|Remote NDIS|Ethernet') { $verdicts += 'USB network interface: run ipconfig and probe that gateway' }
    if ($text -match 'MTP|PTP|WPD|Portable') { $verdicts += 'MTP/PTP profile: Android-style device' }
    if ($text -match 'Allwinner|Rockchip|Ingenic|Amlogic|MediaTek|MTK|Unisoc|Spreadtrum') { $verdicts += 'SoC vendor string present' }
    if ($verdicts.Count -eq 0) { $verdicts += 'no obvious entry point from USB alone' }
    return [pscustomobject]@{ vid = $vid; pid = $productId; verdicts = $verdicts }
}

function Write-Report {
    param([object[]]$Devices, [string]$Path, [string]$Title)
    $lines = @("# $Title", '')
    foreach ($device in $Devices) {
        $verdict = Get-Verdict $device
        $lines += "## $($device.friendly_name)"
        $lines += ''
        $lines += "- InstanceId: ``$($device.instance_id)``"
        $lines += "- Class: $($device.class) / Status: $($device.status) / Service: $($device.service)"
        $lines += "- VID:PID: $($verdict.vid):$($verdict.pid)"
        $lines += "- Manufacturer: $($device.manufacturer)"
        $lines += "- HardwareIds: ``$($device.hardware_ids)``"
        foreach ($line in $verdict.verdicts) { $lines += "- 判断：$line" }
        $lines += ''
    }
    $lines += '只读采集，未安装驱动、未发送自定义 USB 请求。'
    $lines += ''
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Set-Content -LiteralPath $Path -Value $lines -Encoding UTF8
    return $Path
}

$current = Get-UsbSnapshot

switch ($Mode) {
    'list' {
        $path = Write-Report -Devices $current -Path $Report -Title 'USB 设备清单'
        "devices: $($current.Count)"
        "report: $path"
        foreach ($device in $current) {
            $verdict = Get-Verdict $device
            "{0}  {1}:{2}  {3}" -f $device.class, $verdict.vid, $verdict.pid, $device.friendly_name
        }
    }
    'baseline' {
        $parent = Split-Path -Parent $Baseline
        if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
        Set-Content -LiteralPath $Baseline -Value ($current | ConvertTo-Json -Depth 5) -Encoding UTF8
        "baseline devices: $($current.Count)"
        "baseline: $Baseline"
        'now plug the box into the laptop, then run -Mode diff'
    }
    'diff' {
        if (-not (Test-Path -LiteralPath $Baseline)) { throw "baseline not found: $Baseline (run -Mode baseline first)" }
        $before = @(Get-Content -LiteralPath $Baseline -Raw | ConvertFrom-Json)
        $beforeIds = @($before | ForEach-Object { $_.instance_id })
        $currentIds = @($current | ForEach-Object { $_.instance_id })
        $new = @($current | Where-Object { $beforeIds -notcontains $_.instance_id })
        $gone = @($before | Where-Object { $currentIds -notcontains $_.instance_id })

        "new devices: $($new.Count)"
        foreach ($device in $new) {
            $verdict = Get-Verdict $device
            "{0}  VID:PID {1}:{2}  {3}" -f $device.class, $verdict.vid, $verdict.pid, $device.friendly_name
            foreach ($line in $verdict.verdicts) { "    -> $line" }
        }
        "removed devices: $($gone.Count)"
        $path = Write-Report -Devices $new -Path $Report -Title 'USB 插拔差异分析'
        "report: $path"
        if ($new | Where-Object { "$($_.hardware_ids) $($_.instance_id) $($_.friendly_name)" -match 'ADB|Android|VID_18D1' }) {
            'next: tools\platform-tools\adb.exe devices'
        }
        if ($new | Where-Object { $_.class -eq 'Net' }) {
            'next: ipconfig /all   (find the new adapter and its gateway)'
        }
    }
}