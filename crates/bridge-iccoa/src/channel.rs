//! Channel mapping between the phone-side source and the CarPlay sender model.
//!
//! The bounds here are not invented: they are the constraints the reference
//! CarWith -> CarPlay provider enforced on the same signals (see
//! `docs/apk-analysis-carwith-carplay.md`, sections 4.1 and 6). Keeping them in
//! one place means the ICCOA path cannot feed the CarPlay side anything the
//! CarLife path would have rejected.

use bridge_core::model::{
    AudioCodec, AudioKind, AudioPacket, InputAction, InputEvent, MediaFrame, Resolution,
    TouchPhase, VideoCodec,
};

use crate::discovery::IccoaVideoCodec;
use crate::IccoaError;

/// Sample rates the source is allowed to send, inclusive.
pub const SAMPLE_RATE_MIN_HZ: u32 = 8_000;
pub const SAMPLE_RATE_MAX_HZ: u32 = 48_000;
/// Largest PCM payload accepted for a single unit.
pub const PCM_BYTES_MAX: usize = 16_384;
/// PCM is signed 16-bit little endian.
pub const PCM_BYTES_PER_SAMPLE: usize = 2;

/// Media metadata published by the phone.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PhoneMetadata {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
}

/// A unit decoded from the phone-side stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DownstreamUnit {
    Video {
        timestamp_us: u64,
        sequence: u64,
        resolution: Resolution,
        codec: IccoaVideoCodec,
        keyframe: bool,
        payload: Vec<u8>,
    },
    Audio {
        timestamp_us: u64,
        sequence: u64,
        kind: AudioKind,
        sample_rate_hz: u32,
        channels: u16,
        payload: Vec<u8>,
    },
    Microphone {
        timestamp_us: u64,
        sequence: u64,
        sample_rate_hz: u32,
        channels: u16,
        payload: Vec<u8>,
    },
    Metadata(PhoneMetadata),
}

impl DownstreamUnit {
    pub fn timestamp_us(&self) -> Option<u64> {
        match self {
            Self::Video { timestamp_us, .. }
            | Self::Audio { timestamp_us, .. }
            | Self::Microphone { timestamp_us, .. } => Some(*timestamp_us),
            Self::Metadata(_) => None,
        }
    }
}

/// A command sent back towards the phone.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpstreamCommand {
    Touch {
        x: u32,
        y: u32,
        phase: TouchPhase,
    },
    /// `code` is the logical bridge key code; translating it to a Carlink wire
    /// code is the codec's job and needs a capture-derived table.
    Key {
        code: u16,
        pressed: bool,
    },
}

/// Rejects PCM that the downstream encoders cannot consume.
pub fn validate_pcm(
    payload_len: usize,
    sample_rate_hz: u32,
    channels: u16,
) -> Result<(), IccoaError> {
    if !(1..=2).contains(&channels) {
        return Err(IccoaError::new(
            "pcm",
            format!("channel count {channels} must be 1 or 2"),
        ));
    }
    if !(SAMPLE_RATE_MIN_HZ..=SAMPLE_RATE_MAX_HZ).contains(&sample_rate_hz) {
        return Err(IccoaError::new(
            "pcm",
            format!("sample rate {sample_rate_hz} Hz is outside {SAMPLE_RATE_MIN_HZ}..={SAMPLE_RATE_MAX_HZ}"),
        ));
    }
    if payload_len == 0 || payload_len > PCM_BYTES_MAX {
        return Err(IccoaError::new(
            "pcm",
            format!("payload of {payload_len} bytes must be 1..={PCM_BYTES_MAX}"),
        ));
    }
    let frame_bytes = usize::from(channels) * PCM_BYTES_PER_SAMPLE;
    if payload_len % frame_bytes != 0 {
        return Err(IccoaError::new(
            "pcm",
            format!("payload of {payload_len} bytes is not aligned to {frame_bytes}-byte frames"),
        ));
    }
    Ok(())
}

/// Maps a coordinate from the head-unit display into the phone display,
/// clamping to the last addressable pixel.
pub fn scale_touch(
    x: u32,
    y: u32,
    head_unit: Resolution,
    phone: Resolution,
) -> Result<(u32, u32), IccoaError> {
    if head_unit.width == 0 || head_unit.height == 0 {
        return Err(IccoaError::new(
            "touch",
            "head-unit display geometry must be non-zero",
        ));
    }
    if phone.width == 0 || phone.height == 0 {
        return Err(IccoaError::new(
            "touch",
            "phone display geometry must be non-zero",
        ));
    }
    if x >= head_unit.width || y >= head_unit.height {
        return Err(IccoaError::new(
            "touch",
            format!("coordinate ({x}, {y}) is outside the head-unit display {head_unit}"),
        ));
    }

    let scaled_x = u64::from(x) * u64::from(phone.width) / u64::from(head_unit.width);
    let scaled_y = u64::from(y) * u64::from(phone.height) / u64::from(head_unit.height);
    let max_x = u64::from(phone.width - 1);
    let max_y = u64::from(phone.height - 1);

    Ok((scaled_x.min(max_x) as u32, scaled_y.min(max_y) as u32))
}

/// Converts a decoded unit into the media model the CarPlay sender consumes.
///
/// Returns `Ok(None)` for units that carry no video.
pub fn to_media_frame(unit: &DownstreamUnit) -> Result<Option<MediaFrame>, IccoaError> {
    let DownstreamUnit::Video {
        timestamp_us,
        sequence,
        resolution,
        codec,
        payload,
        ..
    } = unit
    else {
        return Ok(None);
    };

    if payload.is_empty() {
        return Err(IccoaError::new("video", "video payload must not be empty"));
    }
    if resolution.width == 0 || resolution.height == 0 {
        return Err(IccoaError::new(
            "video",
            "video resolution must be non-zero",
        ));
    }
    let codec = match codec {
        IccoaVideoCodec::H264 => VideoCodec::H264,
        IccoaVideoCodec::Hevc => {
            return Err(IccoaError::new(
                "video",
                "HEVC needs a transcoding path that bridge-core v1 does not provide",
            ));
        }
    };

    Ok(Some(MediaFrame {
        timestamp_us: *timestamp_us,
        sequence: *sequence,
        resolution: *resolution,
        codec,
        data: payload.clone(),
    }))
}

/// Converts a decoded unit into a PCM packet, validating the PCM bounds.
///
/// Returns `Ok(None)` for units that carry no audio.
pub fn to_audio_packet(unit: &DownstreamUnit) -> Result<Option<AudioPacket>, IccoaError> {
    let (timestamp_us, sequence, kind, sample_rate_hz, channels, payload) = match unit {
        DownstreamUnit::Audio {
            timestamp_us,
            sequence,
            kind,
            sample_rate_hz,
            channels,
            payload,
        } => (
            *timestamp_us,
            *sequence,
            *kind,
            *sample_rate_hz,
            *channels,
            payload,
        ),
        DownstreamUnit::Microphone {
            timestamp_us,
            sequence,
            sample_rate_hz,
            channels,
            payload,
        } => (
            *timestamp_us,
            *sequence,
            AudioKind::Microphone,
            *sample_rate_hz,
            *channels,
            payload,
        ),
        _ => return Ok(None),
    };

    validate_pcm(payload.len(), sample_rate_hz, channels)?;
    if kind == AudioKind::Microphone && channels != 1 {
        return Err(IccoaError::new(
            "pcm",
            "microphone capture must be mono on the source side",
        ));
    }

    Ok(Some(AudioPacket {
        timestamp_us,
        kind,
        codec: AudioCodec::PcmS16Le,
        sample_rate_hz,
        channels,
        sequence,
        payload: payload.clone(),
    }))
}

/// Converts a head-unit input event into a command for the phone.
pub fn to_input_command(
    event: &InputEvent,
    head_unit: Resolution,
    phone: Resolution,
) -> Result<UpstreamCommand, IccoaError> {
    match event.action {
        InputAction::Touch { x, y, phase } => {
            let (x, y) = scale_touch(x, y, head_unit, phone)?;
            Ok(UpstreamCommand::Touch { x, y, phase })
        }
        InputAction::Key { code, pressed } => Ok(UpstreamCommand::Key { code, pressed }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bridge_core::model::InputSource;

    fn video(sequence: u64, resolution: Resolution, payload: Vec<u8>) -> DownstreamUnit {
        DownstreamUnit::Video {
            timestamp_us: sequence * 16_666,
            sequence,
            resolution,
            codec: IccoaVideoCodec::H264,
            keyframe: sequence == 1,
            payload,
        }
    }

    #[test]
    fn pcm_bounds_match_the_reference_provider() {
        assert!(validate_pcm(384, 48_000, 2).is_ok());
        assert!(validate_pcm(192, 8_000, 1).is_ok());
        assert!(validate_pcm(383, 48_000, 2).is_err(), "frame must align");
        assert!(validate_pcm(384, 7_999, 2).is_err(), "rate below range");
        assert!(validate_pcm(384, 48_001, 2).is_err(), "rate above range");
        assert!(validate_pcm(0, 48_000, 2).is_err(), "empty payload");
        assert!(
            validate_pcm(PCM_BYTES_MAX + 1, 48_000, 2).is_err(),
            "payload above the ceiling"
        );
        assert!(validate_pcm(384, 48_000, 3).is_err(), "channel count");
    }

    #[test]
    fn touch_is_scaled_and_clamped_into_the_phone_display() {
        let head_unit = Resolution::new(1920, 720);
        let phone = Resolution::new(2400, 1080);
        assert_eq!(scale_touch(0, 0, head_unit, phone).unwrap(), (0, 0));
        assert_eq!(
            scale_touch(1919, 719, head_unit, phone).unwrap(),
            (2398, 1078)
        );
        assert!(scale_touch(1920, 0, head_unit, phone).is_err());
    }

    #[test]
    fn touch_never_exceeds_the_last_phone_pixel() {
        let head_unit = Resolution::new(1920, 720);
        let phone = Resolution::new(1920, 720);
        let (x, y) = scale_touch(1919, 719, head_unit, phone).unwrap();
        assert!(x < phone.width && y < phone.height);
    }

    #[test]
    fn video_unit_becomes_an_h264_frame() {
        let unit = video(1, Resolution::new(1920, 720), vec![0, 0, 0, 1, 0x67]);
        let frame = to_media_frame(&unit).unwrap().expect("video unit");
        assert_eq!(frame.codec, VideoCodec::H264);
        assert_eq!(frame.sequence, 1);
    }

    #[test]
    fn hevc_is_rejected_until_a_transcoding_path_exists() {
        let mut unit = video(1, Resolution::new(1920, 720), vec![0, 0, 0, 1, 0x40]);
        if let DownstreamUnit::Video { codec, .. } = &mut unit {
            *codec = IccoaVideoCodec::Hevc;
        }
        let error = to_media_frame(&unit).expect_err("HEVC has no v1 path");
        assert!(error.message().contains("HEVC"));
    }

    #[test]
    fn metadata_and_audio_units_do_not_produce_frames() {
        let unit = DownstreamUnit::Metadata(PhoneMetadata::default());
        assert!(to_media_frame(&unit).unwrap().is_none());
        assert!(to_audio_packet(&unit).unwrap().is_none());
    }

    #[test]
    fn microphone_must_be_mono() {
        let unit = DownstreamUnit::Microphone {
            timestamp_us: 0,
            sequence: 1,
            sample_rate_hz: 16_000,
            channels: 2,
            payload: vec![0; 64],
        };
        assert!(to_audio_packet(&unit).is_err());

        let mono = DownstreamUnit::Microphone {
            timestamp_us: 0,
            sequence: 1,
            sample_rate_hz: 16_000,
            channels: 1,
            payload: vec![0; 32],
        };
        let packet = to_audio_packet(&mono).unwrap().expect("audio unit");
        assert_eq!(packet.kind, AudioKind::Microphone);
    }

    #[test]
    fn input_events_map_to_phone_commands() {
        let head_unit = Resolution::new(1920, 720);
        let phone = Resolution::new(2400, 1080);
        let touch = InputEvent {
            timestamp_us: 10,
            source: InputSource::Touch,
            action: InputAction::Touch {
                x: 960,
                y: 360,
                phase: TouchPhase::Down,
            },
        };
        assert_eq!(
            to_input_command(&touch, head_unit, phone).unwrap(),
            UpstreamCommand::Touch {
                x: 1_200,
                y: 540,
                phase: TouchPhase::Down
            }
        );

        let key = InputEvent {
            timestamp_us: 20,
            source: InputSource::Media,
            action: InputAction::Key {
                code: 8_109,
                pressed: true,
            },
        };
        assert_eq!(
            to_input_command(&key, head_unit, phone).unwrap(),
            UpstreamCommand::Key {
                code: 8_109,
                pressed: true
            }
        );
    }
}
