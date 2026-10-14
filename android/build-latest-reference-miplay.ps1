param(
    [Parameter(Mandatory = $true)]
    [string]$ReferenceApkPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:MIPLAY_JDK) { $env:MIPLAY_JDK } else { "$root\tools\android\jdk-17.0.20.1+1" }
$buildTools = if ($env:MIPLAY_BUILD_TOOLS) { $env:MIPLAY_BUILD_TOOLS } else { "$root\tools\android\android-15" }
$apktool = "$root\tools\apktool\apktool-2.10.0.jar"
$work = "$root\android\latest-reference-build"
$decoded = "$work\decoded"
$output = "$root\android\MiPlay-0.4.0.apk"

if (-not (Test-Path -LiteralPath $ReferenceApkPath)) { throw "Reference APK not found: $ReferenceApkPath" }
if (-not (Test-Path -LiteralPath $apktool)) { throw "Missing apktool: $apktool" }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$referenceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ReferenceApkPath).Hash
if ($referenceHash -ne 'E152D830B099A3986DE27432781968CDEAD4EA62CCB30FDA520C433DB9D76686') {
    throw "Unexpected reference APK SHA-256: $referenceHash"
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$buildTools;$env:PATH"
& "$jdk\bin\java.exe" -jar $apktool d -f -o $decoded $ReferenceApkPath
if ($LASTEXITCODE -ne 0) { throw "apktool decode failed: $LASTEXITCODE" }

$manifestPath = "$decoded\AndroidManifest.xml"
$manifest = Get-Content -LiteralPath $manifestPath -Raw
$manifest = $manifest -replace 'android:versionCode="10007"', 'android:versionCode="10040"'
$manifest = $manifest -replace 'android:versionName="7-330802c"', 'android:versionName="MiPlay-0.4.0"'
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding utf8
$apktoolYmlPath = "$decoded\apktool.yml"
$apktoolYml = Get-Content -LiteralPath $apktoolYmlPath -Raw
$apktoolYml = $apktoolYml -replace 'versionCode: 10007', 'versionCode: 10040'
$apktoolYml = $apktoolYml -replace 'versionName: 7-330802c', 'versionName: MiPlay-0.4.0'
Set-Content -LiteralPath $apktoolYmlPath -Value $apktoolYml -Encoding utf8

# The reference app owns the real UI and transport. Reset its recent-log state
# when a new session starts so diagnostics never mix two sessions.
$smaliPath = "$decoded\smali\of.smali"
$smali = Get-Content -LiteralPath $smaliPath -Raw
$needle = '    invoke-virtual {v0}, Ljava/util/LinkedHashMap;->clear()V'
$index = $smali.IndexOf($needle)
if ($index -lt 0) { throw "Could not locate of.a() log reset point" }
$insert = @'

    # MiPlay patch: clear the observable recent-log list for the new session.
    sget-object v0, Lof;->d:Lhi1;
    sget-object v1, Lf00;->e:Lf00;
    const/4 v2, 0x0
    invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    invoke-virtual {v0, v2, v1}, Lhi1;->h(Ljava/lang/Object;Ljava/lang/Object;)Z
'@
$smali = $smali.Insert($index + $needle.Length, $insert)
[IO.File]::WriteAllText((Resolve-Path $smaliPath), $smali, (New-Object System.Text.UTF8Encoding($false)))

& "$jdk\bin\java.exe" -jar $apktool b $decoded -o "$work\rebuilt.apk"
if ($LASTEXITCODE -ne 0) { throw "apktool build failed: $LASTEXITCODE" }

# Apktool writes the current time into zip entries. Normalize it before
# alignment/signing so the same reference input produces the same APK hash.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$normalized = "$work\rebuilt.normalized.apk"
$sourceZip = [IO.Compression.ZipFile]::OpenRead("$work\rebuilt.apk")
$targetZip = [IO.Compression.ZipFile]::Open($normalized, [IO.Compression.ZipArchiveMode]::Create)
$fixedTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
try {
    foreach ($entry in $sourceZip.Entries) {
        $targetEntry = $targetZip.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
        $targetEntry.LastWriteTime = $fixedTime
        if (-not $entry.FullName.EndsWith('/')) {
            $inputStream = $entry.Open()
            $outputStream = $targetEntry.Open()
            try { $inputStream.CopyTo($outputStream) }
            finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }
        }
    }
}
finally {
    $targetZip.Dispose()
    $sourceZip.Dispose()
}
Move-Item -LiteralPath $normalized -Destination "$work\rebuilt.apk" -Force
& "$buildTools\zipalign.exe" -f 4 "$work\rebuilt.apk" "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }
$keystore = "$root\android\reference-debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $keystore -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=MiPlay Reference Debug,O=MiPlay,C=CN' | Out-Null
}
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $output "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed: $LASTEXITCODE" }
& "$buildTools\apksigner.bat" verify --verbose $output
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed: $LASTEXITCODE" }
Write-Host "Reference SHA-256: $referenceHash"
Write-Host "APK: $output"
Write-Host "APK SHA-256: $((Get-FileHash -Algorithm SHA256 $output).Hash)"
