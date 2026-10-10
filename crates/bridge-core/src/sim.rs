use std::fmt;
use std::time::{Duration, Instant};

use crate::media::{AudioBridge, InputRouter, MediaBridge};
use crate::model::{
    AudioKind, AudioPacket, BridgeProfile, InputAction, InputEvent, InputSource, MediaFrame,
    PhoneProfile, Resolution, TouchPhase, VehicleCapabilities, VehicleProfile, VideoCodec,
};
use crate::session::{BridgeSession, BridgeState, SessionEvent};
use crate::wire::{self, Channel};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimulationConfig {
    pub profile: BridgeProfile,
    pub fps: u32,
    pub duration_secs: u64,
    pub media_payload_bytes: usize,
    pub input_interval_us: u64,
}

impl SimulationConfig {
    pub fn lab_default() -> Self {
        Self {
            profile: lab_profile(),
            fps: 60,
            duration_secs: 1_800,
            media_payload_bytes: 4096,
            input_interval_us: 10_000,
        }
    }

    pub fn validate(&self) -> Result<(), SimulationError> {
        self.profile
            .validate()
            .map_err(|error| SimulationError::new(error.to_string()))?;
        if self.fps == 0 || self.fps > self.profile.vehicle.max_fps {
            return Err(SimulationError::new(format!(
                "fps {} exceeds vehicle maximum {}",
                self.fps, self.profile.vehicle.max_fps
            )));
        }
        if self.duration_secs == 0 {
            return Err(SimulationError::new(
                "duration_secs must be greater than zero",
            ));
        }
        if self.media_payload_bytes < 5 {
            return Err(SimulationError::new(
                "media_payload_bytes must hold a start code and NAL header",
            ));
        }
        if self.input_interval_us == 0 {
            return Err(SimulationError::new(
                "input_interval_us must be greater than zero",
            ));
        }
        Ok(())
    }
}

pub fn lab_profile() -> BridgeProfile {
    BridgeProfile {
        vehicle: VehicleProfile {
            name: "25 Buick LaCrosse lab profile".into(),
            carplay_version: Some("phase0-required".into()),
            display: Resolution::new(1920, 720),
            max_fps: 60,
            audio_channels: 2,
            capabilities: VehicleCapabilities {
                touch: true,
                knob: true,
                steering_wheel: true,
                microphone: true,
                call_audio: true,
                navigation_audio: true,
            },
        },
        phone: PhoneProfile {
            manufacturer: "OPPO/vivo/OnePlus".into(),
            model: "phase0-required".into(),
            android_version: "phase0-required".into(),
            carlife_version: "phase0-required".into(),
            wireless_carlife: true,
        },
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimulationReport {
    pub requested_frames: u64,
    pub forwarded_frames: u64,
    pub media_bytes: u64,
    pub audio_packets: u64,
    pub audio_bytes: u64,
    pub input_events: u64,
    pub wire_round_trips: u64,
    pub final_state: BridgeState,
    pub wall_time: Duration,
}

impl SimulationReport {
    pub fn meets_phase1_soak(&self, duration_secs: u64, fps: u32) -> bool {
        let expected = duration_secs.saturating_mul(u64::from(fps));
        self.final_state == BridgeState::Active
            && self.requested_frames == expected
            && self.forwarded_frames == expected
            && self.wire_round_trips == expected
    }

    pub fn frames_per_second(&self) -> f64 {
        let seconds = self.wall_time.as_secs_f64();
        if seconds == 0.0 {
            return 0.0;
        }
        self.forwarded_frames as f64 / seconds
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimulationError {
    message: String,
}

impl SimulationError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for SimulationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for SimulationError {}

pub fn run_simulation(config: &SimulationConfig) -> Result<SimulationReport, SimulationError> {
    config.validate()?;
    let started = Instant::now();
    let profile = &config.profile;
    let duration_us = config.duration_secs.saturating_mul(1_000_000);
    let requested_frames = duration_us.saturating_mul(u64::from(config.fps)) / 1_000_000;

    let mut session = BridgeSession::new();
    for event in [
        SessionEvent::CarplayEnumerationStarted,
        SessionEvent::CarplayEnumerated,
        SessionEvent::CarlifeConnectRequested,
        SessionEvent::CarlifeHandshakeCompleted,
    ] {
        session
            .apply(event)
            .map_err(|error| SimulationError::new(error.to_string()))?;
    }

    let mut media = MediaBridge::new(&profile.vehicle);
    let mut audio = AudioBridge::new(&profile.vehicle);
    let mut input = InputRouter::new(&profile.vehicle);
    let mut wire_round_trips = 0_u64;
    let mut next_audio_us = 0_u64;
    let mut next_input_us = 0_u64;

    for frame_index in 0..requested_frames {
        let timestamp_us = frame_index.saturating_mul(1_000_000) / u64::from(config.fps);

        if frame_index == 0 {
            session
                .apply(SessionEvent::FirstFrameForwarded)
                .map_err(|error| SimulationError::new(error.to_string()))?;
        }

        let mut payload = vec![0_u8; config.media_payload_bytes];
        payload[..5].copy_from_slice(&[0, 0, 0, 1, 0x65]);
        for (offset, byte) in payload[5..].iter_mut().enumerate() {
            *byte = ((frame_index as usize + offset) % 251) as u8;
        }
        let frame = MediaFrame {
            timestamp_us,
            sequence: frame_index + 1,
            resolution: profile.vehicle.display,
            codec: VideoCodec::H264,
            data: payload,
        };

        media
            .forward(&frame)
            .map_err(|error| SimulationError::new(error.to_string()))?;
        session.record_media_frame();
        let encoded = wire::encode(Channel::Media, frame_index as u32, 0, &frame.data)
            .map_err(|error| SimulationError::new(error.to_string()))?;
        let decoded =
            wire::decode(&encoded).map_err(|error| SimulationError::new(error.to_string()))?;
        if decoded.payload != frame.data {
            return Err(SimulationError::new(
                "wire round trip changed media payload",
            ));
        }
        wire_round_trips += 1;

        while next_audio_us <= timestamp_us && next_audio_us < duration_us {
            let sequence = audio.packets() + 1;
            let packet = AudioPacket {
                timestamp_us: next_audio_us,
                kind: if sequence % 120 == 0 {
                    AudioKind::Navigation
                } else {
                    AudioKind::Media
                },
                codec: crate::model::AudioCodec::PcmS16Le,
                sample_rate_hz: 48_000,
                channels: profile.vehicle.audio_channels,
                sequence,
                payload: vec![0_u8; 384],
            };
            audio
                .forward(&packet)
                .map_err(|error| SimulationError::new(error.to_string()))?;
            session.record_audio_packet();
            next_audio_us = next_audio_us.saturating_add(10_000);
        }

        while next_input_us <= timestamp_us && next_input_us < duration_us {
            let event = InputEvent {
                timestamp_us: next_input_us,
                source: InputSource::Touch,
                action: InputAction::Touch {
                    x: ((next_input_us / config.input_interval_us) as u32)
                        % profile.vehicle.display.width,
                    y: (frame_index as u32 / 2) % profile.vehicle.display.height,
                    phase: if next_input_us == 0 {
                        TouchPhase::Down
                    } else {
                        TouchPhase::Move
                    },
                },
            };
            input
                .route(&event)
                .map_err(|error| SimulationError::new(error.to_string()))?;
            session.record_input_event();
            next_input_us = next_input_us.saturating_add(config.input_interval_us);
        }
    }

    while next_audio_us < duration_us {
        let sequence = audio.packets() + 1;
        let packet = AudioPacket {
            timestamp_us: next_audio_us,
            kind: AudioKind::Media,
            codec: crate::model::AudioCodec::PcmS16Le,
            sample_rate_hz: 48_000,
            channels: profile.vehicle.audio_channels,
            sequence,
            payload: vec![0_u8; 384],
        };
        audio
            .forward(&packet)
            .map_err(|error| SimulationError::new(error.to_string()))?;
        session.record_audio_packet();
        next_audio_us = next_audio_us.saturating_add(10_000);
    }

    while next_input_us < duration_us {
        let event = InputEvent {
            timestamp_us: next_input_us,
            source: InputSource::Touch,
            action: InputAction::Touch {
                x: 0,
                y: 0,
                phase: TouchPhase::Up,
            },
        };
        input
            .route(&event)
            .map_err(|error| SimulationError::new(error.to_string()))?;
        session.record_input_event();
        next_input_us = next_input_us.saturating_add(config.input_interval_us);
    }
    let report = SimulationReport {
        requested_frames,
        forwarded_frames: media.frames(),
        media_bytes: media.bytes(),
        audio_packets: audio.packets(),
        audio_bytes: audio.bytes(),
        input_events: session.stats().input_events_forwarded,
        wire_round_trips,
        final_state: session.state(),
        wall_time: started.elapsed(),
    };
    Ok(report)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconnectReport {
    pub requested_cycles: u64,
    pub carplay_connections: u64,
    pub carlife_connections: u64,
    pub disconnects: u64,
    pub final_state: BridgeState,
}

pub fn run_reconnect_cycles(cycles: u64) -> Result<ReconnectReport, SimulationError> {
    if cycles == 0 {
        return Err(SimulationError::new(
            "reconnect cycles must be greater than zero",
        ));
    }

    let mut session = BridgeSession::new();
    for cycle in 0..cycles {
        for event in [
            SessionEvent::CarplayEnumerationStarted,
            SessionEvent::CarplayEnumerated,
            SessionEvent::CarlifeConnectRequested,
            SessionEvent::CarlifeHandshakeCompleted,
            SessionEvent::FirstFrameForwarded,
        ] {
            session
                .apply(event)
                .map_err(|error| SimulationError::new(error.to_string()))?;
        }

        if cycle + 1 < cycles {
            session
                .apply(SessionEvent::LinkLost(format!("soak disconnect {cycle}")))
                .map_err(|error| SimulationError::new(error.to_string()))?;
            session
                .apply(SessionEvent::RecoveryCompleted)
                .map_err(|error| SimulationError::new(error.to_string()))?;
            session
                .apply(SessionEvent::Reset)
                .map_err(|error| SimulationError::new(error.to_string()))?;
        }
    }

    let stats = session.stats();
    Ok(ReconnectReport {
        requested_cycles: cycles,
        carplay_connections: stats.carplay_connections,
        carlife_connections: stats.carlife_connections,
        disconnects: stats.disconnects,
        final_state: session.state(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_simulation_forwards_every_frame() {
        let mut config = SimulationConfig::lab_default();
        config.duration_secs = 1;
        config.media_payload_bytes = 256;
        let report = run_simulation(&config).unwrap();
        assert_eq!(report.requested_frames, 60);
        assert_eq!(report.forwarded_frames, 60);
        assert_eq!(report.final_state, BridgeState::Active);
        assert_eq!(report.audio_packets, 100);
        assert_eq!(report.input_events, 100);
    }

    #[test]
    fn phase1_30_minute_soak_completes_without_dropped_frames() {
        let mut config = SimulationConfig::lab_default();
        config.media_payload_bytes = 1024;
        let report = run_simulation(&config).unwrap();
        assert!(report.meets_phase1_soak(config.duration_secs, config.fps));
        assert_eq!(report.final_state, BridgeState::Active);
    }

    #[test]
    fn reconnect_soak_completes_100_cycles() {
        let report = run_reconnect_cycles(100).unwrap();
        assert_eq!(report.carplay_connections, 100);
        assert_eq!(report.carlife_connections, 100);
        assert_eq!(report.disconnects, 99);
        assert_eq!(report.final_state, BridgeState::Active);
    }

    #[test]
    fn unsupported_frame_rate_is_rejected() {
        let mut config = SimulationConfig::lab_default();
        config.fps = 120;
        assert!(config.validate().is_err());
    }
}
