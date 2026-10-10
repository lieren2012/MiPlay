//! ICCOA Carlink head-unit adapter boundary.
//!
//! Direction: a phone that projects through ICCOA Carlink is the media source;
//! this crate is the **head-unit side** of that protocol and the source of the
//! CarPlay sender. It replaces the `carlife-hu-adapter` slot named in
//! `docs/architecture.md`.
//!
//! Nothing in this crate claims to be a conforming Carlink implementation.
//! [`codec`] is the seam that a specification-derived or clean-room-captured
//! implementation has to fill; everything around it (discovery identity,
//! capability negotiation surface, channel mapping, media validation and the
//! session state machine) is transport independent and testable today.
//!
//! The media constraints in [`channel`] are ported from the reference
//! CarWith -> CarPlay provider that was analysed in
//! `docs/apk-analysis-carwith-carplay.md`, so the same bounds are enforced no
//! matter which source protocol feeds them.
//!
//! Protocol facts for the source side live in [`spec`]: the family uses Wi-Fi
//! Display as its base, so the standard `wfd_*` parameters come from a public
//! specification while the `iccoa_*` / `osc_*` extensions are vendor specific
//! names recovered by static analysis. See `docs/iccoa-protocol-notes.md`.

pub mod channel;
pub mod codec;
pub mod discovery;
pub mod session;
pub mod sim;
pub mod spec;
pub mod wfd;

use std::fmt;

pub use channel::{
    scale_touch, to_audio_packet, to_input_command, to_media_frame, validate_pcm, DownstreamUnit,
    PhoneMetadata, UpstreamCommand, PCM_BYTES_MAX, SAMPLE_RATE_MAX_HZ, SAMPLE_RATE_MIN_HZ,
};
pub use codec::{IccoaCodec, LabCodec};
pub use discovery::{
    IccoaAdvertisement, IccoaCapabilities, IccoaTransport, IccoaVideoCodec, CAPTURE_REQUIRED,
};
pub use session::{
    IccoaEvent, IccoaSession, IccoaSessionError, IccoaSessionStats, IccoaState,
    IccoaStateTransition,
};
pub use sim::{
    run_iccoa_reconnect_cycles, run_iccoa_soak, IccoaReconnectReport, IccoaSimConfig,
    IccoaSoakReport,
};
pub use spec::{
    feature_for, is_known_parameter, ICCOA_EXTENSION_PARAMETERS, SINK_CAPABILITY_REPORTS,
    WFD_PARAMETERS,
};
pub use wfd::{
    ParameterList, RtpPorts, RtpTransport, RtspMethod, RtspRequest, RtspResponse, SinkCapabilities,
    WFD_FLOW,
};

/// Configuration, channel and codec failures raised by this crate.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaError {
    context: &'static str,
    message: String,
}

impl IccoaError {
    pub fn new(context: &'static str, message: impl Into<String>) -> Self {
        Self {
            context,
            message: message.into(),
        }
    }

    pub fn context(&self) -> &'static str {
        self.context
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for IccoaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.context, self.message)
    }
}

impl std::error::Error for IccoaError {}
