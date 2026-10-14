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

The output is `android/MiPlay-0.4.1.apk`. The build preserves the original
package and launcher, changes only the release version and the documented
session-log reset patch, then signs the result with the repository debug key.

The original supplied APK is signed by a different certificate. Android cannot
update that installed package with MiPlay because the original private signing
key is not available. Uninstall the original `CarWith & Carplay Bridge` /
`com.baidu.carlife.xiaomi` package first, then install MiPlay. Later MiPlay
builds use the same repository key and can update one another. Uninstalling
removes the app's settings. MiPlay 0.3.0 and 0.3.1 share the current signing key.

The verified build artifact is 3,967,189 bytes with SHA-256
`F4E9EB7FF772DFEBCAA932EC5E22365DEBA590D6E5FCB321B300A30FD437E110`.
Repeated builds from the same reference hash produced the same artifact hash.

The MiPlay signing certificate SHA-256 is
`e31e27e2a934e8c89396af8b6cf7a7719005892f2b783fabc530eba042f55597`.
The release download is
`https://github.com/lieren2012/MiPlay/releases/download/v0.4.1/MiPlay-0.4.1.apk`.

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
