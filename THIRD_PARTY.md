# Third-party and clean-room notes

The supplied `base.apk(2).1` is a behavioral reference only. It is not checked
into this repository, and its decompiled output is not used as source code.

`113324561/iccoa-carplay-sink` is the public MIT-licensed protocol and
architecture reference for the CarPlay/iAP2/AirPlay side. MiPlay uses its
published principles and protocol boundaries; it is not a repackaged copy and
the project is not the source of the supplied CarWith/CarLife APK.

Research references reviewed during phase 0:

- `catplay-labs/catplay`: no repository license was declared at review time. No source code is copied, translated, or incorporated.
- `f-io/LIVI`: research signal for CarPlay accessory/head-unit architecture. No code is incorporated.
- `shilapi/xcertplay`: research signal for MFi hardware requirements. No code is incorporated.
- `stonexu1986/carlife-protocol-analysis-tool`: Apache-2.0 protocol-analysis reference. No code is incorporated yet.
- `113324561/iccoa-carplay-sink`: MIT-licensed CarPlay/iAP2/AirPlay protocol and architecture reference. Used for principles and boundary design; no source or binary is copied.
- OpenAI Codex runtime and Rust toolchain are development tools only and are not distributed with this project.

Any future dependency or protocol reference must be added here with its version, license, source URL, and modification status before it enters a build.
