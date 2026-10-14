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
$output = "$root\android\MiPlay-0.4.2.apk"
. "$PSScriptRoot\apk-zip.ps1"

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
$manifest = $manifest -replace 'android:versionCode="10007"', 'android:versionCode="10042"'
$manifest = $manifest -replace 'android:versionName="7-330802c"', 'android:versionName="MiPlay-0.4.2"'
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding utf8
$apktoolYmlPath = "$decoded\apktool.yml"
$apktoolYml = Get-Content -LiteralPath $apktoolYmlPath -Raw
$apktoolYml = $apktoolYml -replace 'versionCode: 10007', 'versionCode: 10042'
$apktoolYml = $apktoolYml -replace 'versionName: 7-330802c', 'versionName: MiPlay-0.4.2'
Set-Content -LiteralPath $apktoolYmlPath -Value $apktoolYml -Encoding utf8

# Bind the reference's existing integrity checks to the certificate that signs MiPlay.
$keystore = "$root\android\reference-debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $keystore -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=MiPlay Reference Debug,O=MiPlay,C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool key generation failed: $LASTEXITCODE" }
}
$certificatePath = "$work\signing-certificate.der"
& "$jdk\bin\keytool.exe" -exportcert -keystore $keystore -alias androiddebugkey `
    -storepass android -file $certificatePath
if ($LASTEXITCODE -ne 0) { throw "keytool certificate export failed: $LASTEXITCODE" }
$certificateHash = (Get-FileHash -Algorithm SHA256 $certificatePath).Hash.ToLowerInvariant()
$encodedPin = -join ($certificateHash.ToCharArray() | ForEach-Object { '{0:x2}' -f ([int]$_ -bxor 0x5a) })
$appPath = "$decoded\smali\dev\carwith\bridge\BridgeApp.smali"
$app = Get-Content -LiteralPath $appPath -Raw
$originalPin = '6263696e3e3c69386e6a683e6c6f6a3f6a3f396d386f3e6b3f693b62393c39396b6b626b386e696d623f6b38623e3c686f6d3f3b6b686c3e6e6d6e396c626f3b'
if ([regex]::Matches($app, [regex]::Escape($originalPin)).Count -ne 1) {
    throw 'Expected exactly one reference signing pin in BridgeApp'
}
$app = $app.Replace($originalPin, $encodedPin)
[IO.File]::WriteAllText($appPath, $app, [Text.UTF8Encoding]::new($false))

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

# Normalize timestamps without recompressing resources or mmap-loaded libraries.
Set-ApkZipTimestamps "$work\rebuilt.apk"
& "$buildTools\zipalign.exe" -f -P 16 4 "$work\rebuilt.apk" "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $output "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed: $LASTEXITCODE" }
& "$buildTools\apksigner.bat" verify --verbose $output
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed: $LASTEXITCODE" }
Test-ApkInstallLayout $output
& "$buildTools\zipalign.exe" -c -P 16 4 $output
if ($LASTEXITCODE -ne 0) { throw "Signed APK alignment verification failed: $LASTEXITCODE" }
$verificationClasspath = "$apktool;$buildTools\lib\apksigner.jar"
& "$jdk\bin\javac.exe" -cp $verificationClasspath -d $work "$PSScriptRoot\VerifyRuntimeSigningPin.java"
if ($LASTEXITCODE -ne 0) { throw "Runtime pin verifier compilation failed: $LASTEXITCODE" }
& "$jdk\bin\java.exe" -cp "$work;$verificationClasspath" VerifyRuntimeSigningPin $output
if ($LASTEXITCODE -ne 0) { throw "Runtime signing pin verification failed: $LASTEXITCODE" }
Write-Host "Reference SHA-256: $referenceHash"
Write-Host "APK: $output"
Write-Host "APK SHA-256: $((Get-FileHash -Algorithm SHA256 $output).Hash)"
