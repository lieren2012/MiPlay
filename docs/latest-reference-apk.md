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

The output is `android/MiPlay-0.4.0.apk`. The build preserves the original
package and launcher, changes only the release version and the documented
session-log reset patch, then signs the result with the repository debug key.

The verified build artifact is 3,967,189 bytes with SHA-256
`600325B885F77CCB12CB37DE492AFF581995FC17DC720A2441E9153EA18E8F3A`.
Repeated builds from the same reference hash produced the same artifact hash.

The audio diagnostics still truthfully report whether PCM bytes were received
and forwarded. A successful audio negotiation is not treated as proof that the
vehicle produced audible sound; that requires a real phone and vehicle test.
