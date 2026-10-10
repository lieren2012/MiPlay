//! Clean-room bridge orchestration and laptop-only simulation primitives.
//!
//! The wire format in [`wire`] is private to this project and to the simulator.
//! It is not a claim about the proprietary CarPlay or CarLife wire protocols.

pub mod media;
pub mod model;
pub mod session;
pub mod sim;
pub mod wire;

pub use media::{AudioBridge, ForwardedFrame, InputRouter, MediaBridge, Transformation};
pub use model::{
    AudioCodec, AudioKind, AudioPacket, BridgeProfile, InputAction, InputEvent, InputSource,
    MediaFrame, PhoneProfile, ProfileError, Resolution, TouchPhase, VehicleCapabilities,
    VehicleProfile, VideoCodec,
};
pub use session::{
    BridgeSession, BridgeState, SessionError, SessionEvent, SessionStats, StateTransition,
};
pub use sim::{
    lab_profile, run_reconnect_cycles, run_simulation, ReconnectReport, SimulationConfig,
    SimulationError, SimulationReport,
};
pub use wire::{Channel, DecodedFrame, WireError};
