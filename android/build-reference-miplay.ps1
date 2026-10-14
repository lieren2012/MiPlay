param(
    [Parameter(Mandatory = $true)]
    [string]$ReferenceApkPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:MIPLAY_JDK) { $env:MIPLAY_JDK } else { "$root\tools\android\jdk-17.0.20.1+1" }
$buildTools = if ($env:MIPLAY_BUILD_TOOLS) { $env:MIPLAY_BUILD_TOOLS } else { "$root\tools\android\android-15" }
$platform = if ($env:MIPLAY_ANDROID_JAR) { $env:MIPLAY_ANDROID_JAR } else { "$root\tools\android\android-35\android.jar" }
$apktool = "$root\tools\apktool\apktool-2.10.0.jar"
$work = "$root\android\reference-build"
$decoded = "$work\decoded"
$classes = "$work\classes"
$dex = "$work\dex"
$output = "$root\android\MiPlay-0.3.1.apk"

if (-not (Test-Path -LiteralPath $ReferenceApkPath)) { throw "Reference APK not found: $ReferenceApkPath" }
if (-not (Test-Path -LiteralPath $apktool)) { throw "Missing apktool: $apktool" }
New-Item -ItemType Directory -Force -Path $work,$classes,$dex | Out-Null

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$buildTools;$env:PATH"
& "$jdk\bin\java.exe" -jar $apktool d -f -o $decoded $ReferenceApkPath

$manifestPath = "$decoded\AndroidManifest.xml"
$manifest = Get-Content -LiteralPath $manifestPath -Raw
$manifest = $manifest -replace 'package="com\.baidu\.carlife\.xiaomi"', 'package="com.baidu.carlife.xiaomi" android:versionCode="10031" android:versionName="MiPlay-0.3.1"'
$manifest = $manifest -replace 'android:label="@string/app_name"', 'android:label="MiPlay"'
$activityPattern = '(?s)\s*<activity android:exported="true" android:name="dev\.carwith\.bridge\.MainActivity".*?</activity>'
$activityReplacement = @'
        <activity android:exported="true" android:name="dev.carwith.bridge.MainActivity" android:windowSoftInputMode="adjustResize"/>
        <activity android:exported="true" android:name="miplay.overlay.MiPlayActivity" android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
'@
$manifest = [regex]::Replace($manifest, $activityPattern, $activityReplacement)
Set-Content -LiteralPath $manifestPath -Value $manifest -Encoding utf8

& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -classpath $platform -d $classes `
    (Get-ChildItem -LiteralPath "$root\android\reference-overlay\java" -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\bin\jar.exe" cf "$work\overlay.jar" -C $classes .
& "$buildTools\d8.bat" --lib $platform --min-api 29 --output $dex "$work\overlay.jar"
& "$jdk\bin\java.exe" -jar $apktool b $decoded -o "$work\rebuilt.apk"

Copy-Item -LiteralPath "$work\rebuilt.apk" -Destination "$work\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$work\unsigned.apk", 'Update')
try { [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$dex\classes.dex", 'classes2.dex') } finally { $zip.Dispose() }
& "$buildTools\zipalign.exe" -f 4 "$work\unsigned.apk" "$work\aligned.apk"
$keystore = "$root\android\reference-debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore $keystore -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=MiPlay Reference Debug,O=MiPlay,C=CN' | Out-Null
}
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $output "$work\aligned.apk"
& "$buildTools\apksigner.bat" verify --verbose $output
Write-Host "APK: $output"
Write-Host "SHA-256: $((Get-FileHash -Algorithm SHA256 $output).Hash)"
