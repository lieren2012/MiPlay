# Latest reference APK

The current MiPlay build starts from the user supplied APK:

- SHA-256: `E152D830B099A3986DE27432781968CDEAD4EA62CCB30FDA520C433DB9D76686`
- package: `com.baidu.carlife.xiaomi`
- original version: `7-330802c` / `10007`
- launcher: `dev.carwith.bridge.MainActivity`
- application: `dev.carwith.bridge.BridgeApp`
- connection service: `com.baidu.carlife.service.CarlifeConnectService`

This APK already contains the real Compose UI, session state, diagnostics log,
iAP2 Bluetooth bootstrap, Wi-Fi handoff, Bonjour/AirPlay pairing, Surface H.264
encoder, input path and audio probe. MiPlay keeps those components as the
runtime base. The old overlay build is not used by the latest build route.

## Verified runtime evidence

The supplied diagnostics reached Bluetooth, iAP2 detection, authentication,
Wi-Fi and Bonjour receiver discovery. Its reported session state includes
encoded frames, sent FPS, video Mbps, dropped frames, queue/write timings,
resolution and audio counters. These are the reference app's own state fields,
not values invented by MiPlay.

## Current patch

When a new session starts, the reference code cleared the current-session
summary and audio map but left the observable recent-log list in place. The
latest build clears that list at the same session boundary, so a new diagnostic
export contains only the new session's startup log and recent events.

The patch is applied to decoded `of.smali` by
`android/build-latest-reference-miplay.ps1`; the supplied APK itself is not
committed to this repository.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File android\build-latest-reference-miplay.ps1 `
  -ReferenceApkPath 'F:\path\to\base.apk(2).1'
```

The output is `android/MiPlay-0.4.2.apk`. The build preserves the original
package and launcher, applies the session-log reset and signing pin patches,
then signs the result with the repository debug key.

The original supplied APK is signed by a different certificate. Android cannot
update that installed package with MiPlay because the original private signing
key is not available. Uninstall the original `CarWith & Carplay Bridge` /
`com.baidu.carlife.xiaomi` package first, then install MiPlay. Later MiPlay
builds use the same repository key and can update one another. Uninstalling
removes the app's settings. MiPlay 0.3.0, 0.3.1 and 0.4.1 share the current signing key.

The verified build artifact is 3,967,189 bytes with SHA-256
`0BFD7B8046C6717B0A580823CF233C20435D0FB8122C1763D79E58AC0E1A1311`.
Repeated builds from the same reference hash produced the same artifact hash.

The MiPlay signing certificate SHA-256 is
`e31e27e2a934e8c89396af8b6cf7a7719005892f2b783fabc530eba042f55597`.
The release download is
`https://github.com/lieren2012/MiPlay/releases/download/v0.4.2/MiPlay-0.4.2.apk`.

## Connection entry signing fix (0.4.2)

The original `BridgeApp.onCreate()` installs one obfuscated SHA-256 certificate
pin: `8934df3b402d650e0ec7b5d1e3a8cfcc1181b4378e1b8df257ea126d474c685a`.
The service checks the installed signer, the on-disk APK signing block and
certificate history against that pin. MiPlay 0.4.1 still pinned the original
certificate while its actual signer was the MiPlay key. Thus the service's
integrity gate rejected a valid UI CONNECT request with the diagnostic
`Unsupported or invalid service entry`, before media startup.

0.4.2 exports the actual signing certificate from the keystore and updates
only the pin constant in BridgeApp. It retains the existing integrity gates,
internal entry token, permissions, transport and UI. This is app signing
configuration, separate from CarPlay authentication and Apple certificates.

`android/VerifyRuntimeSigningPin.java` uses apksig to verify the final APK and
dexlib2 to read BridgeApp's actual DEX instructions. It compares the embedded
pin with every verified signer. The original reference passes, MiPlay 0.4.1
fails with the original-pin/MiPlay-signer mismatch, and MiPlay 0.4.2 passes.
The build aborts on mismatches. No Android crash stack or developer-device
reproduction was available; a phone retest is needed to confirm the reported
exit is resolved and to observe the next connection stage.

## Installation packaging fix (0.4.1)

The 0.4.0 timestamp normalization rebuilt all ZIP entries with compression.
This compressed `resources.arsc`, which Android 11+ rejects for target SDK 30+
with `INSTALL_FAILED_INVALID_APK` (-2). It also compressed native libraries
despite the manifest's `extractNativeLibs="false"`, preventing direct loading.
The downloaded 0.4.0 matched the local file byte for byte; changing download
hosts or uninstalling an existing package does not repair this packaging bug.

0.4.1 changes only ZIP header timestamps, preserving apktool's compression
methods and contents. It then aligns stored native libraries to 16 KB and
resources to four bytes before signing. `android/apk-zip.ps1` checks the actual
ZIP methods and local data offsets after signing; it rejects the old 0.4.0
and accepts 0.4.1. All four native libraries match the reference byte for byte.
The launcher remains `dev.carwith.bridge.MainActivity`, with one `classes.dex`
and no historical UI overlay. No phone installation or car connection has been
verified on development hardware.

The audio diagnostics still truthfully report whether PCM bytes were received
and forwarded. A successful audio negotiation is not treated as proof that the
vehicle produced audible sound; that requires a real phone and vehicle test.
