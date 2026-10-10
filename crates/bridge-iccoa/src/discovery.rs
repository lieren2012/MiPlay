//! Discovery identity and capability surface for the head-unit side.
//!
//! Every field that can only be established from the alliance specification or
//! from a captured session is left as [`CAPTURE_REQUIRED`]. [`IccoaAdvertisement::validate`]
//! refuses to run against a phone until those fields are real, which keeps an
//! unverified guess from silently becoming a "supported" configuration.

//! # Discovery mechanism (corrected 2026-10-10)
//!
//! Static analysis of a real phone's projection stack
//! (`docs/iccoa-protocol-notes.md`) shows this family discovers through
//! **Wi-Fi Direct plus the Wi-Fi Display Information Element**, not through
//! mDNS: the phone's projection core *is* a Wi-Fi Display (Miracast) source.
//!
//! The mDNS-shaped fields below are kept because a vendor may publish a Bonjour
//! record as well, but treat them as follows:
//!
//! - [`IccoaTransport::WifiDirect`] is the transport to implement first;
//! - `service_type` / `instance_name` / `host_name` stay `capture-required`
//!   until a capture or a second vendor confirms they are used at all;
//! - once a session is up, what has to be answered is the capability list in
//!   [`crate::spec::SINK_CAPABILITY_REPORTS`].

use bridge_core::Resolution;

use crate::IccoaError;

/// Marker for a value that has not been established from a capture or spec yet.
pub const CAPTURE_REQUIRED: &str = "capture-required";

/// How the phone-side Carlink sender reaches this adapter.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IccoaTransport {
    /// The phone joins an access point this adapter provides.
    WifiAccessPoint,
    /// Wi-Fi Direct / P2P group with no access point.
    WifiDirect,
    /// Bluetooth advertisement bootstrap, then Wi-Fi for the media channels.
    BluetoothBootstrap,
    /// Wired USB session.
    Usb,
}

impl IccoaTransport {
    pub fn label(self) -> &'static str {
        match self {
            Self::WifiAccessPoint => "wifi-access-point",
            Self::WifiDirect => "wifi-direct",
            Self::BluetoothBootstrap => "bluetooth-bootstrap",
            Self::Usb => "usb",
        }
    }
}

/// Video formats the head unit is willing to receive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IccoaVideoCodec {
    H264,
    Hevc,
}

impl IccoaVideoCodec {
    pub fn label(self) -> &'static str {
        match self {
            Self::H264 => "h264",
            Self::Hevc => "hevc",
        }
    }
}

/// Identity this adapter advertises so a phone can find and evaluate it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaAdvertisement {
    /// mDNS service type to publish, for example the Carlink control service.
    pub service_type: String,
    /// Instance name published inside that service type.
    pub instance_name: String,
    /// Host name published for the A/AAAA records.
    pub host_name: String,
    /// Control-plane port offered to the phone.
    pub port: u16,
    pub transport: IccoaTransport,
    /// Name the phone shows to the user.
    pub friendly_name: String,
}

impl IccoaAdvertisement {
    /// A skeleton whose capture-dependent fields are still placeholders.
    pub fn unverified() -> Self {
        Self {
            service_type: CAPTURE_REQUIRED.into(),
            instance_name: CAPTURE_REQUIRED.into(),
            host_name: CAPTURE_REQUIRED.into(),
            port: 0,
            transport: IccoaTransport::WifiAccessPoint,
            friendly_name: "carplaytocarlife bridge".into(),
        }
    }

    /// Fields that still have to come from a capture or the specification.
    pub fn verification_gaps(&self) -> Vec<&'static str> {
        let mut gaps = Vec::new();
        for (field, value) in [
            ("service_type", self.service_type.as_str()),
            ("instance_name", self.instance_name.as_str()),
            ("host_name", self.host_name.as_str()),
        ] {
            if is_unverified(value) {
                gaps.push(field);
            }
        }
        if self.port == 0 {
            gaps.push("port");
        }
        gaps
    }

    pub fn validate(&self) -> Result<(), IccoaError> {
        let gaps = self.verification_gaps();
        if !gaps.is_empty() {
            return Err(IccoaError::new(
                "advertisement",
                format!(
                    "unverified fields remain: {}; fill them from a capture before \
                     advertising to a phone",
                    gaps.join(", ")
                ),
            ));
        }
        Ok(())
    }
}

/// Everything the head unit offers during capability negotiation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaCapabilities {
    pub video_codecs: Vec<IccoaVideoCodec>,
    /// Display geometry the phone should render for.
    pub display: Resolution,
    pub max_fps: u32,
    pub audio_sample_rates_hz: Vec<u32>,
    pub audio_channels: u16,
    pub microphone: bool,
    pub touch: bool,
    pub keys: bool,
    /// Separate control channel for metadata, focus and lifecycle.
    pub control_channel: bool,
}

impl IccoaCapabilities {
    /// Conservative set used by the laptop simulator.
    pub fn lab_default() -> Self {
        Self {
            video_codecs: vec![IccoaVideoCodec::H264],
            display: Resolution::new(1920, 720),
            max_fps: 60,
            audio_sample_rates_hz: vec![48_000, 44_100, 16_000],
            audio_channels: 2,
            microphone: true,
            touch: true,
            keys: true,
            control_channel: true,
        }
    }

    pub fn validate(&self) -> Result<(), IccoaError> {
        if self.video_codecs.is_empty() {
            return Err(IccoaError::new(
                "capabilities",
                "at least one video codec must be offered",
            ));
        }
        if self.display.width == 0 || self.display.height == 0 {
            return Err(IccoaError::new(
                "capabilities",
                "display geometry must be non-zero",
            ));
        }
        if self.max_fps == 0 || self.max_fps > 60 {
            return Err(IccoaError::new(
                "capabilities",
                "max_fps must be between 1 and 60",
            ));
        }
        if self.audio_sample_rates_hz.is_empty() {
            return Err(IccoaError::new(
                "capabilities",
                "at least one audio sample rate must be offered",
            ));
        }
        for rate in &self.audio_sample_rates_hz {
            if !(crate::SAMPLE_RATE_MIN_HZ..=crate::SAMPLE_RATE_MAX_HZ).contains(rate) {
                return Err(IccoaError::new(
                    "capabilities",
                    format!("sample rate {rate} Hz is outside the supported range"),
                ));
            }
        }
        if self.audio_channels == 0 || self.audio_channels > 2 {
            return Err(IccoaError::new(
                "capabilities",
                "audio_channels must be 1 or 2",
            ));
        }
        Ok(())
    }

    /// Formats accepted downstream, in prompt order.
    pub fn accepted_video(&self) -> String {
        self.video_codecs
            .iter()
            .map(|codec| codec.label())
            .collect::<Vec<_>>()
            .join("/")
    }
}

fn is_unverified(value: &str) -> bool {
    let trimmed = value.trim();
    trimmed.is_empty()
        || trimmed.eq_ignore_ascii_case(CAPTURE_REQUIRED)
        || trimmed.eq_ignore_ascii_case("unknown")
        || trimmed.eq_ignore_ascii_case("phase0-required")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unverified_advertisement_reports_every_gap() {
        let advertisement = IccoaAdvertisement::unverified();
        assert_eq!(
            advertisement.verification_gaps(),
            vec!["service_type", "instance_name", "host_name", "port"]
        );
        let error = advertisement
            .validate()
            .expect_err("placeholders must not be advertised");
        assert!(error.message().contains("unverified fields remain"));
    }

    #[test]
    fn filled_advertisement_is_accepted() {
        let advertisement = IccoaAdvertisement {
            service_type: "_example._tcp.local.".into(),
            instance_name: "Bridge".into(),
            host_name: "bridge.local.".into(),
            port: 8_000,
            transport: IccoaTransport::WifiDirect,
            friendly_name: "Bridge".into(),
        };
        advertisement.validate().unwrap();
    }

    #[test]
    fn empty_video_codec_list_is_rejected() {
        let mut capabilities = IccoaCapabilities::lab_default();
        capabilities.video_codecs.clear();
        assert!(capabilities.validate().is_err());
    }

    #[test]
    fn out_of_range_sample_rate_is_rejected() {
        let mut capabilities = IccoaCapabilities::lab_default();
        capabilities.audio_sample_rates_hz = vec![4_000];
        assert!(capabilities.validate().is_err());
    }

    #[test]
    fn lab_capabilities_are_valid_and_describe_h264() {
        let capabilities = IccoaCapabilities::lab_default();
        capabilities.validate().unwrap();
        assert_eq!(capabilities.accepted_video(), "h264");
    }
}
