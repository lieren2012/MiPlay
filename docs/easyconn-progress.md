# EasyConnection replacement progress

This checkout contains the current ICCOA/EasyConnection route for the Android
sink APK.

## Verified on the OPPO phone

- Device: OPPO PLP110, Android SDK 37 (Android 17), no root or Shizuku.
- The local EasyConnection control handshake succeeds:
  `0x10000 -> 0x10001`, client information `0x10010 -> 0x10011`, and reverse
  handshake `0x20000 -> 0x20001`.
- The media control exchange succeeds with `0x0011` (H.264, 1280x720) and
  `0x0071` (start). The phone produced real Annex-B H.264 frames; the probe
  received 22 frames and about 31 KB.
- The sink startup crash was fixed by using `MulticastSocket` for mDNS and by
  moving the first mDNS send off the main thread.

## Current implementation

`EasyConnSource` now performs the local control/media handshake from the sink,
reads the length-prefixed H.264 stream on port 10920, responds to reverse
heartbeats, and forwards access units into the existing AirPlay video tunnel
once a CarPlay receiver session is ready. A key frame is cached while waiting
for the car so the video configuration can be sent as soon as the tunnel is
available.

The build is intentionally kept as a portable `aapt2` + `javac` + `d8` script;
run `powershell -ExecutionPolicy Bypass -File android/build-iccoa-sink.ps1` to
rebuild `android/iccoa-sink-0.1.0.apk`.

## Remaining field test

The phone must be beside the car for the Bluetooth/iAP2 and AirPlay handshake.
The current work has not yet been validated against a real head unit, so the
APK may still need adjustments to the particular car's pairing, wireless
network handoff, audio, and touch behavior.
