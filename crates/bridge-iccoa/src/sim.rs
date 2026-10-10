//! Laptop-only soak and reconnect harness for the ICCOA head-unit adapter.
//!
//! Everything here runs against [`LabCodec`], so a green run proves the adapter
//! wiring, the channel mapping and the downstream media validation are
//! consistent. It says nothing about whether a real phone would connect.

use std::fmt;
use std::time::{Duration, Instant};

use bridge_core::media::{AudioBridge, InputRouter, MediaBridge};
use bridge_core::model::{
    InputAction, InputEvent, InputSource, Resolution, TouchPhase, VehicleCapabilities,
    VehicleProfile,
};

use crate::channel;
use crate::codec::{IccoaCodec, LabCodec};
use crate::session::{IccoaEvent, IccoaSession, IccoaState};
use crate::IccoaError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaSimConfig {
    pub display: Resolution,
    pub phone_display: Resolution,
    pub fps: u32,
    pub duration_secs: u64,
    pub video_payload_bytes: usize,
    pub audio_packets_per_second: u32,
    pub microphone_packets_per_second: u32,
    pub input_events_per_second: u32,
}

impl IccoaSimConfig {
    pub fn lab_default() -> Self {
        Self {
            display: Resolution::new(1920, 720),
            phone_display: Resolution::new(2400, 1080),
            fps: 60,
            duration_secs: 1_800,
            video_payload_bytes: 4_096,
            audio_packets_per_second: 100,
            microphone_packets_per_second: 50,
            input_events_per_second: 100,
        }
    }

    pub fn validate(&self) -> Result<(), IccoaError> {
        if self.display.width == 0 || self.display.height == 0 {
            return Err(IccoaError::new("sim", "display geometry must be non-zero"));
        }
        if self.phone_display.width == 0 || self.phone_display.height == 0 {
            return Err(IccoaError::new(
                "sim",
                "phone display geometry must be non-zero",
            ));
        }
        if self.fps == 0 || self.fps > 60 {
            return Err(IccoaError::new("sim", "fps must be between 1 and 60"));
        }
        if self.duration_secs == 0 {
            return Err(IccoaError::new("sim", "duration_secs must be non-zero"));
        }
        if self.video_payload_bytes < 5 {
            return Err(IccoaError::new(
                "sim",
                "video_payload_bytes must hold a start code and NAL header",
            ));
        }
        for (name, value) in [
            ("audio_packets_per_second", self.audio_packets_per_second),
            (
                "microphone_packets_per_second",
                self.microphone_packets_per_second,
            ),
            ("input_events_per_second", self.input_events_per_second),
        ] {
            if value == 0 {
                return Err(IccoaError::new("sim", format!("{name} must be non-zero")));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaSoakReport {
    pub requested_frames: u64,
    pub forwarded_frames: u64,
    pub video_bytes: u64,
    pub audio_packets: u64,
    pub audio_bytes: u64,
    pub microphone_packets: u64,
    pub upstream_commands: u64,
    pub final_state: IccoaState,
    pub wall_time: Duration,
}

impl IccoaSoakReport {
    pub fn meets_soak(&self, duration_secs: u64, fps: u32) -> bool {
        let expected = duration_secs.saturating_mul(u64::from(fps));
        self.final_state == IccoaState::Streaming
            && self.requested_frames == expected
            && self.forwarded_frames == expected
            && self.upstream_commands > 0
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
pub struct IccoaReconnectReport {
    pub requested_cycles: u64,
    pub peers_found: u64,
    pub handshakes: u64,
    pub streaming_sessions: u64,
    pub peers_lost: u64,
    pub final_state: IccoaState,
}

impl fmt::Display for IccoaSoakReport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "ICCOA Carlink head-unit soak")?;
        writeln!(f, "  requested frames:    {}", self.requested_frames)?;
        writeln!(f, "  forwarded frames:    {}", self.forwarded_frames)?;
        writeln!(f, "  video bytes:         {}", self.video_bytes)?;
        writeln!(f, "  audio packets:       {}", self.audio_packets)?;
        writeln!(f, "  microphone packets:  {}", self.microphone_packets)?;
        writeln!(f, "  upstream commands:   {}", self.upstream_commands)?;
        writeln!(f, "  final state:         {:?}", self.final_state)?;
        write!(
            f,
            "  wall time:           {} ms",
            self.wall_time.as_millis()
        )
    }
}

/// Runs the ICCOA source path end to end at laptop speed.
pub fn run_iccoa_soak(config: &IccoaSimConfig) -> Result<IccoaSoakReport, IccoaError> {
    config.validate()?;
    let started = Instant::now();

    let mut session = IccoaSession::new();
    for event in [
        IccoaEvent::AdvertisementStarted,
        IccoaEvent::PeerFound {
            address: "192.0.2.10".into(),
        },
        IccoaEvent::SecureAccepted,
        IccoaEvent::ChannelsNegotiated,
    ] {
        session
            .apply(event)
            .map_err(|error| IccoaError::new("sim", error.to_string()))?;
    }

    let sink = lab_sink_profile(config.display);
    let mut media = MediaBridge::new(&sink);
    let mut audio = AudioBridge::new(&sink);
    let mut input = InputRouter::new(&sink);
    let mut codec = LabCodec::new(config.display, 48_000);

    let duration_us = config.duration_secs.saturating_mul(1_000_000);
    let requested_frames = ticks(duration_us, u64::from(config.fps));

    // Each channel is generated by index so the count matches `ticks` exactly;
    // accumulating a truncated interval used to overshoot the last tick.
    for index in 0..requested_frames {
        let timestamp_us = index * 1_000_000 / u64::from(config.fps);
        let wire = codec.synthetic_video(index + 1, timestamp_us, config.video_payload_bytes);
        for unit in codec.decode(&wire)? {
            let frame = channel::to_media_frame(&unit)?
                .ok_or_else(|| IccoaError::new("sim", "expected a video unit"))?;
            media
                .forward(&frame)
                .map_err(|error| IccoaError::new("video", error.to_string()))?;
            session.record_media_unit();
        }
    }

    let audio_packets = ticks(duration_us, u64::from(config.audio_packets_per_second));
    for index in 0..audio_packets {
        let timestamp_us = index * 1_000_000 / u64::from(config.audio_packets_per_second);
        let wire = codec.synthetic_audio(LabCodec::audio_tag(), index + 1, timestamp_us, 2, 384);
        for unit in codec.decode(&wire)? {
            let packet = channel::to_audio_packet(&unit)?
                .ok_or_else(|| IccoaError::new("sim", "expected an audio unit"))?;
            audio
                .forward(&packet)
                .map_err(|error| IccoaError::new("audio", error.to_string()))?;
        }
    }

    let microphone_packets = ticks(duration_us, u64::from(config.microphone_packets_per_second));
    for index in 0..microphone_packets {
        let timestamp_us = index * 1_000_000 / u64::from(config.microphone_packets_per_second);
        let wire =
            codec.synthetic_audio(LabCodec::microphone_tag(), index + 1, timestamp_us, 1, 320);
        for unit in codec.decode(&wire)? {
            let packet = channel::to_audio_packet(&unit)?
                .ok_or_else(|| IccoaError::new("sim", "expected a microphone unit"))?;
            channel::validate_pcm(packet.payload.len(), packet.sample_rate_hz, packet.channels)?;
        }
    }

    let input_events = ticks(duration_us, u64::from(config.input_events_per_second));
    let mut upstream_commands = 0_u64;
    let mut step = 0_u32;
    for index in 0..input_events {
        let event = InputEvent {
            timestamp_us: index * 1_000_000 / u64::from(config.input_events_per_second),
            source: InputSource::Touch,
            action: InputAction::Touch {
                x: step % config.display.width,
                y: step % config.display.height,
                phase: if step % 2 == 0 {
                    TouchPhase::Down
                } else {
                    TouchPhase::Up
                },
            },
        };
        input
            .route(&event)
            .map_err(|error| IccoaError::new("input", error.to_string()))?;
        let command = channel::to_input_command(&event, config.display, config.phone_display)?;
        let encoded = codec.encode(&command)?;
        if encoded.is_empty() {
            return Err(IccoaError::new("input", "codec produced an empty command"));
        }
        upstream_commands += 1;
        step = step.wrapping_add(37);
    }

    session
        .apply(IccoaEvent::FirstMediaUnit)
        .map_err(|error| IccoaError::new("sim", error.to_string()))?;

    Ok(IccoaSoakReport {
        requested_frames,
        forwarded_frames: media.frames(),
        video_bytes: media.bytes(),
        audio_packets: audio.packets(),
        audio_bytes: audio.bytes(),
        microphone_packets,
        upstream_commands,
        final_state: session.state(),
        wall_time: started.elapsed(),
    })
}

/// How many units a channel produces in `duration_us` at `per_second`.
fn ticks(duration_us: u64, per_second: u64) -> u64 {
    duration_us.saturating_mul(per_second) / 1_000_000
}

/// Runs orderly peer-loss cycles without touching any radio.
pub fn run_iccoa_reconnect_cycles(cycles: u64) -> Result<IccoaReconnectReport, IccoaError> {
    if cycles == 0 {
        return Err(IccoaError::new(
            "sim",
            "reconnect cycles must be greater than zero",
        ));
    }

    let mut session = IccoaSession::new();
    for _ in 0..cycles {
        for event in [
            IccoaEvent::AdvertisementStarted,
            IccoaEvent::PeerFound {
                address: "192.0.2.10".into(),
            },
            IccoaEvent::SecureAccepted,
            IccoaEvent::ChannelsNegotiated,
            IccoaEvent::FirstMediaUnit,
        ] {
            session
                .apply(event)
                .map_err(|error| IccoaError::new("sim", error.to_string()))?;
        }
        session
            .apply(IccoaEvent::PeerLost("soak disconnect".into()))
            .map_err(|error| IccoaError::new("sim", error.to_string()))?;
        session
            .apply(IccoaEvent::Reset)
            .map_err(|error| IccoaError::new("sim", error.to_string()))?;
    }

    let stats = session.stats();
    Ok(IccoaReconnectReport {
        requested_cycles: cycles,
        peers_found: stats.peers_found,
        handshakes: stats.handshakes,
        streaming_sessions: stats.streaming_sessions,
        peers_lost: stats.peers_lost,
        final_state: session.state(),
    })
}

fn lab_sink_profile(display: Resolution) -> VehicleProfile {
    VehicleProfile {
        name: "ICCOA head-unit lab sink".into(),
        carplay_version: Some("phase0-required".into()),
        display,
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
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_second_soak_forwards_every_frame() {
        let mut config = IccoaSimConfig::lab_default();
        config.duration_secs = 1;
        let report = run_iccoa_soak(&config).unwrap();
        assert_eq!(report.requested_frames, 60);
        assert_eq!(report.forwarded_frames, 60);
        assert_eq!(report.audio_packets, 100);
        assert_eq!(report.microphone_packets, 50);
        assert_eq!(report.upstream_commands, 100);
        assert_eq!(report.final_state, IccoaState::Streaming);
    }

    #[test]
    fn phase1_sized_soak_completes_without_dropped_frames() {
        let mut config = IccoaSimConfig::lab_default();
        config.video_payload_bytes = 1_024;
        let report = run_iccoa_soak(&config).unwrap();
        assert!(report.meets_soak(config.duration_secs, config.fps));
        assert_eq!(report.final_state, IccoaState::Streaming);
    }

    #[test]
    fn reconnect_soak_completes_100_cycles() {
        let report = run_iccoa_reconnect_cycles(100).unwrap();
        assert_eq!(report.peers_found, 100);
        assert_eq!(report.streaming_sessions, 100);
        assert_eq!(report.peers_lost, 100);
        assert_eq!(report.final_state, IccoaState::Idle);
    }

    #[test]
    fn zero_fps_is_rejected() {
        let mut config = IccoaSimConfig::lab_default();
        config.fps = 0;
        assert!(config.validate().is_err());
        assert!(run_iccoa_soak(&config).is_err());
    }

    #[test]
    fn unsupported_frame_rate_is_rejected() {
        let mut config = IccoaSimConfig::lab_default();
        config.fps = 120;
        assert!(config.validate().is_err());
    }

    #[test]
    fn report_renders_a_human_readable_summary() {
        let mut config = IccoaSimConfig::lab_default();
        config.duration_secs = 1;
        let report = run_iccoa_soak(&config).unwrap();
        let rendered = report.to_string();
        assert!(rendered.contains("ICCOA Carlink head-unit soak"));
        assert!(rendered.contains("forwarded frames:    60"));
    }
}
