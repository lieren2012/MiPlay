function Get-ApkZipEntries {
    param([byte[]]$Bytes)

    # APKs are ordinary ZIP files with a signing block before the central directory.
    $eocd = -1
    for ($i = $Bytes.Length - 22; $i -ge [Math]::Max(0, $Bytes.Length - 65557); $i--) {
        if ([BitConverter]::ToUInt32($Bytes, $i) -eq 0x06054b50 -and
            $i + 22 + [BitConverter]::ToUInt16($Bytes, $i + 20) -eq $Bytes.Length) {
            $eocd = $i
            break
        }
    }
    if ($eocd -lt 0) { throw 'ZIP end record not found' }
    $count = [BitConverter]::ToUInt16($Bytes, $eocd + 10)
    $position = [int][BitConverter]::ToUInt32($Bytes, $eocd + 16)
    for ($i = 0; $i -lt $count; $i++) {
        if ([BitConverter]::ToUInt32($Bytes, $position) -ne 0x02014b50) {
            throw 'Invalid ZIP central directory'
        }
        $nameLength = [BitConverter]::ToUInt16($Bytes, $position + 28)
        $extraLength = [BitConverter]::ToUInt16($Bytes, $position + 30)
        $commentLength = [BitConverter]::ToUInt16($Bytes, $position + 32)
        $local = [int][BitConverter]::ToUInt32($Bytes, $position + 42)
        if ([BitConverter]::ToUInt32($Bytes, $local) -ne 0x04034b50) {
            throw 'Invalid ZIP local header'
        }
        $method = [BitConverter]::ToUInt16($Bytes, $position + 10)
        if ([BitConverter]::ToUInt16($Bytes, $local + 8) -ne $method) {
            throw 'ZIP compression methods disagree'
        }
        [pscustomobject]@{
            Name = [Text.Encoding]::UTF8.GetString($Bytes, $position + 46, $nameLength)
            Method = $method
            CentralOffset = $position
            LocalOffset = $local
            DataOffset = $local + 30 + [BitConverter]::ToUInt16($Bytes, $local + 26) +
                [BitConverter]::ToUInt16($Bytes, $local + 28)
        }
        $position += 46 + $nameLength + $extraLength + $commentLength
    }
}

function Set-ApkZipTimestamps {
    param([string]$Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    foreach ($entry in (Get-ApkZipEntries $bytes)) {
        # Change header timestamps only; preserve compression and payload bytes.
        foreach ($offset in @(($entry.LocalOffset + 10), ($entry.CentralOffset + 12))) {
            $bytes[$offset] = 0
            $bytes[$offset + 1] = 0
            $bytes[$offset + 2] = 0x21
            $bytes[$offset + 3] = 0
        }
    }
    [IO.File]::WriteAllBytes($Path, $bytes)
}

function Test-ApkInstallLayout {
    param([string]$Path)

    $entries = @(Get-ApkZipEntries ([IO.File]::ReadAllBytes($Path)))
    $resources = @($entries | Where-Object Name -eq 'resources.arsc')
    if ($resources.Count -ne 1 -or $resources[0].Method -ne 0 -or
        $resources[0].DataOffset % 4 -ne 0) {
        throw 'resources.arsc must be stored without compression and aligned to 4 bytes (Android 11+)'
    }
    $libraries = @($entries | Where-Object Name -match '^lib/[^/]+/[^/]+\.so$')
    if ($libraries.Count -eq 0) { throw 'Reference native libraries missing' }
    foreach ($entry in $libraries) {
        if ($entry.Method -ne 0 -or $entry.DataOffset % 16384 -ne 0) {
            throw "Native library must be stored and aligned to 16 KB: $($entry.Name)"
        }
    }
    if (@($entries | Where-Object Name -match '^classes\d*\.dex$').Count -ne 1) {
        throw 'Expected only the latest reference classes.dex, without the historical overlay'
    }
    Write-Host "Install layout OK: stored resources.arsc, $($libraries.Count) stored native libraries, 16 KB alignment"
}
