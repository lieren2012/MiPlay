//! The wire-codec seam.
//!
//! ICCOA Carlink framing, handshake and channelisation are not public outside
//! the alliance specification. This crate therefore ships **no conforming
//! codec**: [`LabCodec`] is a synthetic, deliberately non-conforming framing
//! whose only purpose is to let the adapter, the channel mapping and the
//! simulator be exercised end to end on a laptop.
//!
//! A real implementation must implement [`IccoaCodec`] from the specification,
//! or from a clean-room capture as described in `docs/iccoa-adapter.md`, and
//! plug it in without touching the rest of the crate.

use bridge_core::model::Resolution;

use crate::channel::{DownstreamUnit, UpstreamCommand};
use crate::{IccoaError, IccoaVideoCodec};

/// Decodes phone-to-head-unit units and encodes head-unit-to-phone commands.
pub trait IccoaCodec {
    fn decode(&mut self, chunk: &[u8]) -> Result<Vec<DownstreamUnit>, IccoaError>;

    fn encode(&mut self, command: &UpstreamCommand) -> Result<Vec<u8>, IccoaError>;
}

/// Lab-only framing. **Not ICCOA Carlink.**
///
/// Layout: `tag:u8 | sequence:u32be | timestamp_us:u64be | payload_len:u16be | payload`
/// with tags `1 = video`, `2 = audio`, `3 = microphone`.
pub struct LabCodec {
    resolution: Resolution,
    rate_hz: u32,
}

impl LabCodec {
    const TAG_VIDEO: u8 = 1;
    const TAG_AUDIO: u8 = 2;
    const TAG_MICROPHONE: u8 = 3;
    const HEADER_LEN: usize = 1 + 4 + 8 + 2;

    pub fn new(resolution: Resolution, rate_hz: u32) -> Self {
        Self {
            resolution,
            rate_hz,
        }
    }

    /// Builds a synthetic video unit for the simulator.
    pub fn synthetic_video(
        &self,
        sequence: u64,
        timestamp_us: u64,
        payload_bytes: usize,
    ) -> Vec<u8> {
        let mut payload = vec![0_u8; payload_bytes.max(5)];
        payload[3] = 1;
        payload[4] = if sequence == 1 { 0x67 } else { 0x41 };
        self.frame(Self::TAG_VIDEO, sequence, timestamp_us, &payload)
    }

    /// Builds a synthetic PCM unit for the simulator.
    pub fn synthetic_audio(
        &self,
        tag: u8,
        sequence: u64,
        timestamp_us: u64,
        channels: u16,
        payload_bytes: usize,
    ) -> Vec<u8> {
        let aligned = payload_bytes - payload_bytes % (usize::from(channels) * 2);
        self.frame(tag, sequence, timestamp_us, &vec![0_u8; aligned.max(2)])
    }

    fn frame(&self, tag: u8, sequence: u64, timestamp_us: u64, payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(Self::HEADER_LEN + payload.len());
        out.push(tag);
        out.extend_from_slice(&(sequence as u32).to_be_bytes());
        out.extend_from_slice(&timestamp_us.to_be_bytes());
        out.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        out.extend_from_slice(payload);
        out
    }

    pub const fn audio_tag() -> u8 {
        Self::TAG_AUDIO
    }

    pub const fn microphone_tag() -> u8 {
        Self::TAG_MICROPHONE
    }
}

impl IccoaCodec for LabCodec {
    fn decode(&mut self, chunk: &[u8]) -> Result<Vec<DownstreamUnit>, IccoaError> {
        if chunk.len() < Self::HEADER_LEN {
            return Err(IccoaError::new(
                "lab-codec",
                format!(
                    "frame of {} bytes is shorter than the {} byte header",
                    chunk.len(),
                    Self::HEADER_LEN
                ),
            ));
        }
        let tag = chunk[0];
        let sequence = u64::from(u32::from_be_bytes([chunk[1], chunk[2], chunk[3], chunk[4]]));
        let timestamp_us = u64::from_be_bytes([
            chunk[5], chunk[6], chunk[7], chunk[8], chunk[9], chunk[10], chunk[11], chunk[12],
        ]);
        let declared = usize::from(u16::from_be_bytes([chunk[13], chunk[14]]));
        let available = chunk.len() - Self::HEADER_LEN;
        if declared != available {
            return Err(IccoaError::new(
                "lab-codec",
                format!("declared payload {declared} does not match {available} available bytes"),
            ));
        }
        let payload = &chunk[Self::HEADER_LEN..];

        let unit = match tag {
            Self::TAG_VIDEO => DownstreamUnit::Video {
                timestamp_us,
                sequence,
                resolution: self.resolution,
                codec: IccoaVideoCodec::H264,
                keyframe: payload.get(4) == Some(&0x67),
                payload: payload.to_vec(),
            },
            Self::TAG_AUDIO => DownstreamUnit::Audio {
                timestamp_us,
                sequence,
                kind: bridge_core::model::AudioKind::Media,
                sample_rate_hz: self.rate_hz,
                channels: 2,
                payload: payload.to_vec(),
            },
            Self::TAG_MICROPHONE => DownstreamUnit::Microphone {
                timestamp_us,
                sequence,
                sample_rate_hz: 16_000,
                channels: 1,
                payload: payload.to_vec(),
            },
            other => {
                return Err(IccoaError::new(
                    "lab-codec",
                    format!("unknown lab tag {other}"),
                ));
            }
        };
        Ok(vec![unit])
    }

    fn encode(&mut self, command: &UpstreamCommand) -> Result<Vec<u8>, IccoaError> {
        match command {
            UpstreamCommand::Touch { x, y, phase } => {
                Ok(format!("touch {x} {y} {phase:?}").into_bytes())
            }
            UpstreamCommand::Key { code, pressed } => {
                Ok(format!("key {code} {pressed}").into_bytes())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bridge_core::model::TouchPhase;

    #[test]
    fn synthetic_video_round_trips_through_the_lab_codec() {
        let mut codec = LabCodec::new(Resolution::new(1920, 720), 48_000);
        let frame = codec.synthetic_video(1, 0, 256);
        let units = codec.decode(&frame).unwrap();
        assert_eq!(units.len(), 1);
        match &units[0] {
            DownstreamUnit::Video {
                sequence,
                keyframe,
                payload,
                ..
            } => {
                assert_eq!(*sequence, 1);
                assert!(*keyframe, "first synthetic frame must look like a keyframe");
                assert_eq!(payload.len(), 256);
            }
            other => panic!("unexpected unit {other:?}"),
        }
    }

    #[test]
    fn truncated_frame_is_rejected() {
        let mut codec = LabCodec::new(Resolution::new(1920, 720), 48_000);
        let frame = codec.synthetic_video(1, 0, 32);
        assert!(codec.decode(&frame[..LabCodec::HEADER_LEN - 1]).is_err());
    }

    #[test]
    fn length_mismatch_is_rejected() {
        let mut codec = LabCodec::new(Resolution::new(1920, 720), 48_000);
        let mut frame = codec.synthetic_video(1, 0, 32);
        frame.push(0);
        assert!(codec.decode(&frame).is_err());
    }

    #[test]
    fn microphone_units_decode_as_mono() {
        let mut codec = LabCodec::new(Resolution::new(1920, 720), 48_000);
        let frame = codec.synthetic_audio(LabCodec::microphone_tag(), 1, 0, 1, 64);
        let units = codec.decode(&frame).unwrap();
        match &units[0] {
            DownstreamUnit::Microphone { channels, .. } => assert_eq!(*channels, 1),
            other => panic!("unexpected unit {other:?}"),
        }
    }

    #[test]
    fn commands_encode_to_bytes() {
        let mut codec = LabCodec::new(Resolution::new(1920, 720), 48_000);
        let touch = UpstreamCommand::Touch {
            x: 10,
            y: 20,
            phase: TouchPhase::Up,
        };
        assert!(!codec.encode(&touch).unwrap().is_empty());
    }
}
