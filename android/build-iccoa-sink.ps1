# Builds the ICCOA sink APK with aapt2 + javac + d8 + apksigner (no Gradle).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:MIPLAY_JDK) { $env:MIPLAY_JDK } else { "$root\tools\android\jdk-17.0.20.1+1" }
$buildToo = if ($env:MIPLAY_BUILD_TOOLS) { $env:MIPLAY_BUILD_TOOLS } else { "$root\tools\android\android-15" }
$platform = if ($env:MIPLAY_ANDROID_JAR) { $env:MIPLAY_ANDROID_JAR } else { "$root\tools\android\android-35\android.jar" }
$bouncycastle = if ($env:MIPLAY_BOUNCYCASTLE) { $env:MIPLAY_BOUNCYCASTLE } else { "$root\tools\libs\bcprov-jdk18on-1.78.1.jar" }
$src = "$root\android\iccoa-sink"
$out = "$root\android\build"
$apkName = 'MiPlay-0.2.1.apk'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$buildToo;$env:PATH"

foreach ($dir in @("$out\gen", "$out\classes", "$out\dex")) {
    New-Item -ItemType Directory -Force $dir | Out-Null
}

Write-Host '[1/7] aapt2 compile'
& "$buildToo\aapt2.exe" compile --dir "$src\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[2/7] aapt2 link'
& "$buildToo\aapt2.exe" link -o "$out\base.apk" `
    -I $platform `
    --manifest "$src\AndroidManifest.xml" `
    -R "$out\res.zip" --auto-add-overlay `
    --java "$out\gen" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 4 --version-name 0.2.1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[3/7] javac'
$sources = @(Get-ChildItem -Path "$src\java" -Recurse -Filter *.java | Where-Object { $_.Name -notlike 'SelfTest*' } | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Path "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath "$platform;$bouncycastle" -d "$out\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[4/7] d8'
# d8 takes a jar; a list of .class files runs past the Windows command line limit
& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& "$buildToo\d8.bat" --lib $platform --min-api 24 --output "$out\dex" "$out\classes.jar" $bouncycastle
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[5/7] add classes.dex to the apk'
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$out\dex\classes.dex", 'classes.dex')
} finally {
    $zip.Dispose()
}

Write-Host '[6/7] stamp + zipalign'
# Zip entries inherit the build machine's clock, so two builds of the same
# sources produced two different files and a published hash identified nothing.
# Every entry gets the same fixed timestamp (the zip epoch) first, which makes
# the output of this script reproducible: same sources, same bytes, same hash.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$epoch = [DateTime]::SpecifyKind([DateTime]'1980-01-01T00:00:00', 'Local')
$stamp = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
try {
    foreach ($entry in $stamp.Entries) { $entry.LastWriteTime = $epoch }
} finally {
    $stamp.Dispose()
}
& "$buildToo\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[7/7] sign'
$keystore = "$root\android\debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $keystore `
        -alias androiddebugkey -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' | Out-Null
}
$finalApk = "$root\android\$apkName"
& "$buildToo\apksigner.bat" sign --ks $keystore --ks-pass pass:android `
    --key-pass pass:android --out $finalApk "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& "$buildToo\apksigner.bat" verify --print-certs $finalApk | Select-Object -First 4

$apk = Get-Item $finalApk
Write-Host ("APK: {0} ({1:N0} bytes)" -f $apk.FullName, $apk.Length)
$hash = (Get-FileHash -Algorithm SHA256 $finalApk).Hash
Write-Host ("SHA-256: {0}" -f $hash)
Write-Host 'the stamp above makes this reproducible: the same sources give this same hash'
