use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Resolution {
    pub width: u32,
    pub height: u32,
}

impl Resolution {
    pub const fn new(width: u32, height: u32) -> Self {
        Self { width, height }
    }

    pub fn pixels(self) -> u64 {
        u64::from(self.width) * u64::from(self.height)
    }
}

impl fmt::Display for Resolution {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}x{}", self.width, self.height)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoCodec {
    H264,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioCodec {
    PcmS16Le,
    AacLc,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AudioKind {
    Media,
    Navigation,
    Call,
    TextToSpeech,
    Microphone,
    System,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputSource {
    Touch,
    Knob,
    Back,
    Media,
    SteeringWheel,
    Voice,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TouchPhase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputAction {
    Touch { x: u32, y: u32, phase: TouchPhase },
    Key { code: u16, pressed: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputEvent {
    pub timestamp_us: u64,
    pub source: InputSource,
    pub action: InputAction,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioPacket {
    pub timestamp_us: u64,
    pub kind: AudioKind,
    pub codec: AudioCodec,
    pub sample_rate_hz: u32,
    pub channels: u16,
    pub sequence: u64,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaFrame {
    pub timestamp_us: u64,
    pub sequence: u64,
    pub resolution: Resolution,
    pub codec: VideoCodec,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VehicleCapabilities {
    pub touch: bool,
    pub knob: bool,
    pub steering_wheel: bool,
    pub microphone: bool,
    pub call_audio: bool,
    pub navigation_audio: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VehicleProfile {
    pub name: String,
    pub carplay_version: Option<String>,
    pub display: Resolution,
    pub max_fps: u32,
    pub audio_channels: u16,
    pub capabilities: VehicleCapabilities,
}

impl VehicleProfile {
    pub fn validate(&self) -> Result<(), ProfileError> {
        if self.name.trim().is_empty() {
            return Err(ProfileError::new("vehicle.name must not be empty"));
        }
        if self.display.width == 0 || self.display.height == 0 {
            return Err(ProfileError::new("vehicle display must be non-zero"));
        }
        if self.display.pixels() > Resolution::new(1920, 720).pixels() {
            return Err(ProfileError::new(
                "vehicle display exceeds the 1920x720 v1 profile",
            ));
        }
        if self.max_fps == 0 || self.max_fps > 60 {
            return Err(ProfileError::new(
                "vehicle.max_fps must be between 1 and 60",
            ));
        }
        if self.audio_channels == 0 {
            return Err(ProfileError::new("vehicle.audio_channels must be non-zero"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhoneProfile {
    pub manufacturer: String,
    pub model: String,
    pub android_version: String,
    pub carlife_version: String,
    pub wireless_carlife: bool,
}

impl PhoneProfile {
    pub fn validate(&self) -> Result<(), ProfileError> {
        for (name, value) in [
            ("phone.manufacturer", self.manufacturer.as_str()),
            ("phone.model", self.model.as_str()),
            ("phone.android_version", self.android_version.as_str()),
            ("phone.carlife_version", self.carlife_version.as_str()),
        ] {
            if value.trim().is_empty() || value.eq_ignore_ascii_case("unknown") {
                return Err(ProfileError::new(format!("{name} must be filled in")));
            }
        }
        if !self.wireless_carlife {
            return Err(ProfileError::new(
                "phone profile requires verified wireless CarLife support",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BridgeProfile {
    pub vehicle: VehicleProfile,
    pub phone: PhoneProfile,
}

impl BridgeProfile {
    pub fn validate(&self) -> Result<(), ProfileError> {
        self.vehicle.validate()?;
        self.phone.validate()
    }
}

impl Default for BridgeProfile {
    fn default() -> Self {
        Self {
            vehicle: VehicleProfile {
                name: "25 Buick LaCrosse".into(),
                carplay_version: Some("unknown".into()),
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
                model: "unknown".into(),
                android_version: "unknown".into(),
                carlife_version: "unknown".into(),
                wireless_carlife: true,
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileError {
    message: String,
}

impl ProfileError {
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for ProfileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ProfileError {}
