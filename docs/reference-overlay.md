# Historical reference APK overlay experiment

This document describes the old overlay experiment. It is retained for
history only and is not the current MiPlay product build route. The current
route is documented in [`latest-reference-apk.md`](latest-reference-apk.md)
and preserves the latest supplied APK's original launcher, Compose UI and
transport implementation.

`android/build-reference-miplay.ps1` is the product build for MiPlay. It takes
the user supplied `base.apk(2).1` as an external input and performs these steps:

1. Decode and rebuild the original APK without replacing its `classes.dex`.
2. Keep the original `BridgeApp`, `CarlifeConnectService`, CarLife mixing AIDL,
   native libraries and Shizuku provider.
3. Add `classes2.dex` containing `miplay.overlay.MiPlayActivity`.
4. Make the MiPlay activity the launcher while keeping the original activity
   and service entries available.
5. Sign the result as `MiPlay-0.3.0.apk`.

The new home screen calls the original service using the observed
`dev.carwith.bridge.CONNECT` action and the service's internal token. This is
why the resulting package remains `com.baidu.carlife.xiaomi` and is materially
larger than the earlier independent protocol experiment.

The original APK must be supplied locally. It is not committed to this public
repository. `iccoa-carplay-sink` remains a protocol principle reference only.
