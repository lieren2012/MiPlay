//! Protocol facts for the ICCOA Carlink source side.
//!
//! # Where these facts come from
//!
//! Read-only static analysis of the owner's OPPO PLP110 phone: the projection
//! core is `com.oplus.ocar`'s `libheycastcore.so`, the higher level flow is
//! `com.heytap.opluscarlink` (`CarLink.apk`). Raw strings, the package paths and
//! the extraction method are recorded in `docs/iccoa-protocol-notes.md`.
//!
//! The base protocol is **Wi-Fi Display (Miracast)**: that library contains
//! `android::WifiDisplaySource`, the RTSP `Transport: RTP/AVP/...` templates and
//! the standard `wfd_*` parameter names. ICCOA/OPPO add parameters of their own
//! (`iccoa_*`, `osc_*`). Only *names* are recorded here - no vendor code is
//! copied, and the extensions are vendor-specific rather than documented.
//!
//! # Why this matters for this crate
//!
//! This crate has to provide the head-unit side. [`SINK_CAPABILITY_REPORTS`] is
//! the list of capability reports the phone looks for on that side; each one it
//! does not find turns a feature off (the phone logs `Sink doesn't report ...`).
//! [`FEATURE_GATES`] pairs each report with the feature it enables.

/// Parameters defined by the public Wi-Fi Display specification.
pub const WFD_PARAMETERS: &[&str] = &[
    "wfd_video_formats",
    "wfd_audio_codecs",
    "wfd_client_rtp_ports",
    "wfd_content_protection",
    "wfd_uibc_capability",
    "wfd_video_latency",
    "wfd_audio_latency",
    "wfd_idr_request",
];

/// ICCOA / OPPO extensions carried in the same RTSP exchange.
///
/// Names come from `libheycastcore.so`; their value encodings are not verified
/// yet, so treat every one of them as "known name, unknown semantics" until a
/// real session says otherwise.
pub const ICCOA_EXTENSION_PARAMETERS: &[&str] = &[
    // ICCOA specific
    "iccoa_support_fusion",
    "iccoa_support_hevc",
    "iccoaFusionResolutions",
    // OPPO "OSC" (screen cast) extensions
    "osc_display_mode",
    "osc_rotates_with_content",
    "osc_backstage_projection_full",
    "osc_is_relay",
    "osc_audio_encrypt",
    "osc_audio_encrypt_aes_key",
    "osc_audio_encrypt_rsa_key",
    // display-mode / codec extensions
    "wfd_car_display_mode",
    "wfd_car_vars",
    "wfd_pc_display_mode",
    "wfd_codec_type",
    "wfd_videosize_capability",
    "wfd_av_raw_packet",
];

/// Capability reports the phone expects from the sink (head unit).
///
/// Derived from the phone's own diagnostics: for each entry there is a
/// matching `Sink doesn't report ...` message whose other branch is
/// `Sink report ...`. Missing report means the feature stays off.
pub const SINK_CAPABILITY_REPORTS: &[&str] = &[
    "wfd_uibc_capability",
    "wfd_content_protection",
    "wfd_video_formats",
    "wfd_audio_codecs",
    "wfd_client_rtp_ports",
    "wfd_video_latency",
    "wfd_audio_latency",
    "iccoa_support_fusion",
    "iccoa_support_hevc",
    "osc_display_mode",
    "osc_rotates_with_content",
    "osc_backstage_projection_full",
    "osc_audio_encrypt",
    "osc_is_relay",
    "wfd_car_display_mode",
    "wfd_car_vars",
    "wfd_pc_display_mode",
    "wfd_av_raw_packet",
];

/// A capability report and the feature it gates.
///
/// The second field is the meaning inferred from the phone's diagnostics, not
/// from a specification.
pub const FEATURE_GATES: &[(&str, &str)] = &[
    ("wfd_uibc_capability", "touch and key input over UIBC"),
    (
        "wfd_content_protection",
        "AES content protection for the video stream",
    ),
    (
        "wfd_video_formats",
        "any video at all (codec/resolution negotiation)",
    ),
    ("wfd_audio_codecs", "any audio at all"),
    ("wfd_client_rtp_ports", "RTP transport ports"),
    ("wfd_video_latency", "video latency reporting"),
    ("wfd_audio_latency", "audio latency reporting"),
    ("iccoa_support_fusion", "fused car/phone desktop"),
    ("iccoa_support_hevc", "HEVC instead of H.264"),
    ("osc_display_mode", "display mode selection"),
    ("osc_rotates_with_content", "rotation follows content"),
    (
        "osc_backstage_projection_full",
        "backstage full-screen projection",
    ),
    ("osc_audio_encrypt", "separate audio encryption"),
    ("osc_is_relay", "relay (non-direct) cast path"),
    ("wfd_car_display_mode", "car display mode"),
    ("wfd_car_vars", "car side variables"),
    ("wfd_pc_display_mode", "PC display mode"),
    ("wfd_av_raw_packet", "raw audio/video packet mode"),
];

/// Parameters the phone found in the binary but that have no understood meaning
/// yet. Kept separate so nobody mistakes them for something implemented.
pub const UNVERIFIED_PARAMETERS: &[&str] = &[
    "Sink doesn't report OVM control capabitity",
    "Sink doesn't report VS capabitity",
    "Sink doesn't report pad_device_info",
];

/// RTSP methods the session uses.
pub const RTSP_METHODS: &[&str] = &[
    "OPTIONS",
    "GET_PARAMETER",
    "SET_PARAMETER",
    "PLAY",
    "PAUSE",
    "TEARDOWN",
];

/// WFD session steps, in order. M3/M4 are the capability exchange.
pub const WFD_SESSION_STEPS: &[&str] = &["M1", "M2", "M3", "M4", "M5", "M6", "M7"];

/// `true` when `name` is any parameter this protocol family defines.
pub fn is_known_parameter(name: &str) -> bool {
    WFD_PARAMETERS.contains(&name)
        || ICCOA_EXTENSION_PARAMETERS.contains(&name)
        || SINK_CAPABILITY_REPORTS.contains(&name)
}

/// The feature a capability report gates, if it is one of the known gates.
pub fn feature_for(report: &str) -> Option<&'static str> {
    FEATURE_GATES
        .iter()
        .find(|(name, _)| *name == report)
        .map(|(_, feature)| *feature)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parameter_tables_have_no_duplicates() {
        let mut all: Vec<&str> = WFD_PARAMETERS
            .iter()
            .chain(ICCOA_EXTENSION_PARAMETERS)
            .copied()
            .collect();
        let before = all.len();
        all.sort_unstable();
        all.dedup();
        assert_eq!(before, all.len(), "duplicate parameter name across tables");
    }

    #[test]
    fn every_report_name_is_a_known_parameter() {
        for report in SINK_CAPABILITY_REPORTS {
            assert!(is_known_parameter(report), "{report} is not in any table");
        }
    }

    #[test]
    fn every_feature_gate_names_a_real_report() {
        for (report, feature) in FEATURE_GATES {
            assert!(
                SINK_CAPABILITY_REPORTS.contains(report),
                "{report} is gated but not in SINK_CAPABILITY_REPORTS"
            );
            assert!(!feature.is_empty());
            assert_eq!(feature_for(report), Some(*feature));
        }
    }

    #[test]
    fn the_observed_iccoa_extensions_are_recorded() {
        for name in [
            "iccoa_support_fusion",
            "iccoa_support_hevc",
            "osc_display_mode",
            "osc_audio_encrypt_aes_key",
            "wfd_car_display_mode",
        ] {
            assert!(ICCOA_EXTENSION_PARAMETERS.contains(&name), "{name} missing");
        }
    }

    #[test]
    fn unknown_names_are_rejected() {
        assert!(!is_known_parameter("wfd_made_up"));
        assert_eq!(feature_for("wfd_made_up"), None);
    }
}
