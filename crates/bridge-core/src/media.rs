use std::fmt;

use crate::model::{
    AudioKind, AudioPacket, InputAction, InputEvent, MediaFrame, Resolution, VehicleProfile,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transformation {
    ZeroCopyRemux,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForwardedFrame {
    pub sequence: u64,
    pub transformation: Transformation,
    pub payload_bytes: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaError {
    message: String,
}

impl MediaError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for MediaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for MediaError {}

pub struct MediaBridge {
    expected_resolution: Resolution,
    last_pts_us: Option<u64>,
    last_sequence: Option<u64>,
    frames: u64,
    bytes: u64,
}

impl MediaBridge {
    pub fn new(profile: &VehicleProfile) -> Self {
        Self {
            expected_resolution: profile.display,
            last_pts_us: None,
            last_sequence: None,
            frames: 0,
            bytes: 0,
        }
    }

    pub fn forward(&mut self, frame: &MediaFrame) -> Result<ForwardedFrame, MediaError> {
        if frame.data.is_empty() {
            return Err(MediaError::new("media frame payload must not be empty"));
        }
        if frame.resolution != self.expected_resolution {
            return Err(MediaError::new(format!(
                "unexpected resolution {}; expected {}",
                frame.resolution, self.expected_resolution
            )));
        }
        if let Some(previous) = self.last_pts_us {
            if frame.timestamp_us <= previous {
                return Err(MediaError::new(format!(
                    "timestamp {} is not greater than previous timestamp {previous}",
                    frame.timestamp_us
                )));
            }
        }
        if let Some(previous) = self.last_sequence {
            if frame.sequence != previous + 1 {
                return Err(MediaError::new(format!(
                    "sequence {} does not follow {previous}",
                    frame.sequence
                )));
            }
        }

        self.last_pts_us = Some(frame.timestamp_us);
        self.last_sequence = Some(frame.sequence);
        self.frames += 1;
        self.bytes = self.bytes.saturating_add(frame.data.len() as u64);

        Ok(ForwardedFrame {
            sequence: frame.sequence,
            transformation: Transformation::ZeroCopyRemux,
            payload_bytes: frame.data.len(),
        })
    }

    pub fn frames(&self) -> u64 {
        self.frames
    }

    pub fn bytes(&self) -> u64 {
        self.bytes
    }
}

pub struct InputRouter {
    display: Resolution,
    last_timestamp_us: Option<u64>,
}

impl InputRouter {
    pub fn new(profile: &VehicleProfile) -> Self {
        Self {
            display: profile.display,
            last_timestamp_us: None,
        }
    }

    pub fn route(&mut self, event: &InputEvent) -> Result<InputEvent, MediaError> {
        if let Some(previous) = self.last_timestamp_us {
            if event.timestamp_us < previous {
                return Err(MediaError::new("input timestamps must be monotonic"));
            }
        }

        if let InputAction::Touch { x, y, .. } = event.action {
            if x >= self.display.width || y >= self.display.height {
                return Err(MediaError::new(format!(
                    "touch coordinate ({x}, {y}) is outside {}",
                    self.display
                )));
            }
        }

        self.last_timestamp_us = Some(event.timestamp_us);
        Ok(event.clone())
    }
}
pub struct AudioBridge {
    expected_channels: u16,
    last_sequence: u64,
    packets: u64,
    bytes: u64,
}

impl AudioBridge {
    pub fn new(profile: &VehicleProfile) -> Self {
        Self {
            expected_channels: profile.audio_channels,
            last_sequence: 0,
            packets: 0,
            bytes: 0,
        }
    }

    pub fn forward(&mut self, packet: &AudioPacket) -> Result<AudioKind, MediaError> {
        if packet.payload.is_empty() {
            return Err(MediaError::new("audio packet payload must not be empty"));
        }
        if packet.sample_rate_hz < 8_000 || packet.sample_rate_hz > 192_000 {
            return Err(MediaError::new("unsupported audio sample rate"));
        }
        if packet.channels != self.expected_channels {
            return Err(MediaError::new(format!(
                "unexpected audio channel count {}; expected {}",
                packet.channels, self.expected_channels
            )));
        }
        if packet.sequence == 0 || packet.sequence > self.last_sequence.saturating_add(1) {
            return Err(MediaError::new(format!(
                "audio sequence {} is invalid",
                packet.sequence
            )));
        }

        self.last_sequence = packet.sequence;
        self.packets += 1;
        self.bytes = self.bytes.saturating_add(packet.payload.len() as u64);
        Ok(packet.kind)
    }

    pub fn packets(&self) -> u64 {
        self.packets
    }

    pub fn bytes(&self) -> u64 {
        self.bytes
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{InputSource, TouchPhase, VideoCodec};

    fn profile() -> VehicleProfile {
        VehicleProfile {
            name: "test".into(),
            carplay_version: Some("test".into()),
            display: Resolution::new(1920, 720),
            max_fps: 60,
            audio_channels: 2,
            capabilities: crate::model::VehicleCapabilities {
                touch: true,
                knob: true,
                steering_wheel: true,
                microphone: true,
                call_audio: true,
                navigation_audio: true,
            },
        }
    }

    #[test]
    fn media_is_remuxed_without_payload_changes() {
        let mut bridge = MediaBridge::new(&profile());
        let frame = MediaFrame {
            timestamp_us: 16_666,
            sequence: 1,
            resolution: Resolution::new(1920, 720),
            codec: VideoCodec::H264,
            data: vec![0, 0, 0, 1, 0x65, 1, 2, 3],
        };
        let before = frame.data.clone();
        let forwarded = bridge.forward(&frame).unwrap();
        assert_eq!(forwarded.transformation, Transformation::ZeroCopyRemux);
        assert_eq!(before, frame.data);
        assert_eq!(bridge.frames(), 1);
    }

    #[test]
    fn stale_timestamps_are_rejected() {
        let mut bridge = MediaBridge::new(&profile());
        let mut frame = MediaFrame {
            timestamp_us: 100,
            sequence: 1,
            resolution: Resolution::new(1920, 720),
            codec: VideoCodec::H264,
            data: vec![1],
        };
        bridge.forward(&frame).unwrap();
        frame.timestamp_us = 100;
        frame.sequence = 2;
        assert!(bridge.forward(&frame).is_err());
    }

    #[test]
    fn input_outside_display_is_rejected() {
        let mut router = InputRouter::new(&profile());
        let event = InputEvent {
            timestamp_us: 1,
            source: InputSource::Touch,
            action: InputAction::Touch {
                x: 1920,
                y: 10,
                phase: TouchPhase::Down,
            },
        };
        assert!(router.route(&event).is_err());
    }

    #[test]
    fn audio_sequence_can_duplicate_but_not_jump_forward() {
        let mut bridge = AudioBridge::new(&profile());
        let make = |sequence| AudioPacket {
            timestamp_us: sequence * 10_000,
            kind: AudioKind::Media,
            codec: crate::model::AudioCodec::PcmS16Le,
            sample_rate_hz: 48_000,
            channels: 2,
            sequence,
            payload: vec![0; 4],
        };
        bridge.forward(&make(1)).unwrap();
        bridge.forward(&make(1)).unwrap();
        assert!(bridge.forward(&make(3)).is_err());
    }
}
