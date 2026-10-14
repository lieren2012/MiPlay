# MiPlay 0.4.2

Fixes a confirmed certificate-pin mismatch in MiPlay 0.4.1 that rejects the
home screen's CONNECT request before media startup.

The supplied runtime pins its original author's signing certificate. MiPlay
is signed with its own key; previous builds updated the APK signature but did
not update the runtime trust pin. The service therefore emitted
`Unsupported or invalid service entry` for a valid internal connection request.

- Binds the runtime certificate pin to the actual MiPlay keystore certificate.
- Retains the existing integrity checks, entry token, permissions, original
  home/settings/log views, frame statistics and connection pipeline.
- Verifies the final APK's signature and its actual DEX-embedded pin agree.
  The original reference passes, 0.4.1 fails and 0.4.2 passes this regression check.
- Retains the 0.4.1 uncompressed resources/native libraries and alignment fix.

APK: `MiPlay-0.4.2.apk` (3,967,189 bytes).
SHA-256: `0BFD7B8046C6717B0A580823CF233C20435D0FB8122C1763D79E58AC0E1A1311`.
Signing certificate SHA-256:
`e31e27e2a934e8c89396af8b6cf7a7719005892f2b783fabc530eba042f55597`.

Install over MiPlay 0.4.1 to preserve settings. No uninstall is needed.
No phone-side crash stack or real-device retest was available during this fix;
the service-entry mismatch is verified, but resolution of the reported exit
and car video/audio still require the phone and car test.

The latest user-supplied APK remains the runtime base. The GitHub project
`113324561/iccoa-carplay-sink` is only a protocol principle reference.
