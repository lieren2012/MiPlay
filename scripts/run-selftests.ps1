# Runs every desktop self test and reports anything that did not finish cleanly.
#
# A suite that throws half way through prints no "SELFTEST" line, which the old
# runner did not notice; exit codes and the verdict line are both checked now.
#
# Every suite's full output (including the app log it prints) is written to
# reports\selftest-all.log. The console stays short, but the corpus the log
# coverage audit needs is complete - it used to be thrown away with the passing
# suites' output, which made that audit report everything as untested.
$ErrorActionPreference = 'Stop'
$root    = 'C:\carplaytocarlife'
$jdk     = "$root\tools\android\jdk-17.0.20.1+1"
$bc      = "$root\tools\libs\bcprov-jdk18on-1.78.1.jar"
$platform = "$root\tools\android\android-36\android.jar"
$src     = "$root\android\iccoa-sink\java"
$gen     = "$root\android\build\gen"
$out     = "$root\android\build\selftest"

New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Path $gen -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -classpath "$platform;$bc" -d $out $sources
if ($LASTEXITCODE -ne 0) { throw 'the self tests do not compile' }

$suites = @(
    'SelfTest','SelfTestAirplay','SelfTestAudio','SelfTestAuth','SelfTestBootstrap',
    'SelfTestCarplay','SelfTestControl','SelfTestCrypto','SelfTestHid','SelfTestIap2',
    'SelfTestModes','SelfTestPairing','SelfTestRtsp','SelfTestScreen','SelfTestStream',
    'SelfTestTiming','SelfTestTouch','SelfTestVideo','SelfTestClient'
)
$total = 0
$bad = 0
$broken = @()
$all = @()
foreach ($suite in $suites) {
    $output = & "$jdk\bin\java.exe" -cp "$out;$platform;$bc" "org.carplaytocarlife.iccoa.$suite" 2>&1
    $code = $LASTEXITCODE
    $pass = ($output | Select-String -Pattern '^PASS ').Count
    $fail = ($output | Select-String -Pattern '^FAIL ').Count
    $verdict = ($output | Select-String -Pattern 'SELFTEST').Count -gt 0
    $all += @("---- $suite ----") + @($output)
    $total += $pass
    $bad += $fail
    if ($fail -gt 0 -or $code -ne 0 -or -not $verdict) {
        $broken += $suite
        Write-Host ("--- {0} pass={1} fail={2} exit={3} verdict={4}" -f $suite, $pass, $fail, $code, $verdict)
        $output | Select-String -Pattern '^FAIL|Exception|Error' | Select-Object -First 4 |
            ForEach-Object { Write-Host ('    ' + $_.Line) }
    }
}
Write-Host ("TOTAL pass={0} fail={1} broken suites={2}" -f $total, $bad, ($broken -join ','))

New-Item -ItemType Directory -Force "$root\reports" | Out-Null
$all | Set-Content -Encoding utf8 "$root\reports\selftest-all.log"
Write-Host ("full output: reports\selftest-all.log ({0} lines)" -f $all.Count)
