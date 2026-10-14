# MiPlay 0.4.1

Fixes the 0.4.0 APK packaging bug responsible for Android installation error
`INSTALL_FAILED_INVALID_APK` (-2).

- Preserves uncompressed `resources.arsc` and native libraries rather than
  compressing every ZIP entry during timestamp normalization.
- Aligns native libraries to 16 KB and resources to four bytes before signing.
- Checks compression, offsets, dex count, zipalign and APK v3 signing before
  accepting a build. The old 0.4.0 fails the new layout check.
- Retains the latest reference's original MainActivity, settings, diagnostics,
  frame statistics and connection services. No historical UI overlay is added.

APK: `MiPlay-0.4.1.apk` (3,967,189 bytes).
SHA-256: `F4E9EB7FF772DFEBCAA932EC5E22365DEBA590D6E5FCB321B300A30FD437E110`.
Signing certificate SHA-256:
`e31e27e2a934e8c89396af8b6cf7a7719005892f2b783fabc530eba042f55597`.

Two builds produced identical SHA-256 hashes. All four native libraries match
the supplied reference. No real phone installation or car connection was tested.

MiPlay 0.3.0/0.3.1 can be updated with the same signing key. An installed original
`CarWith & Carplay Bridge` uses a different key and must be uninstalled first;
uninstalling clears its settings. This signature difference is separate from
the packaging bug and cannot be fixed without the original private signing key.

`113324561/iccoa-carplay-sink` remains a protocol principle reference. The latest
user-supplied APK remains the runtime base.
