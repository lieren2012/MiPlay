use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BridgeState {
    Idle,
    CarplayEnumerating,
    CarplayConnected,
    CarlifeConnecting,
    CarlifeConnected,
    Active,
    Reconnecting,
    Fault,
}

impl BridgeState {
    pub fn is_connected(self) -> bool {
        matches!(self, Self::Active | Self::CarlifeConnected)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEvent {
    CarplayEnumerationStarted,
    CarplayEnumerated,
    CarplayDisconnected(String),
    CarlifeConnectRequested,
    CarlifeHandshakeCompleted,
    FirstFrameForwarded,
    LinkLost(String),
    RecoveryCompleted,
    Fault(String),
    Reset,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StateTransition {
    pub from: BridgeState,
    pub to: BridgeState,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionError {
    state: BridgeState,
    event: String,
    message: String,
}

impl SessionError {
    fn new(state: BridgeState, event: &SessionEvent, message: impl Into<String>) -> Self {
        Self {
            state,
            event: format!("{event:?}"),
            message: message.into(),
        }
    }

    pub fn state(&self) -> BridgeState {
        self.state
    }

    pub fn event(&self) -> &str {
        &self.event
    }
}

impl fmt::Display for SessionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "invalid event {} in state {:?}: {}",
            self.event, self.state, self.message
        )
    }
}

impl std::error::Error for SessionError {}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SessionStats {
    pub enumeration_attempts: u64,
    pub carplay_connections: u64,
    pub carlife_connections: u64,
    pub frames_forwarded: u64,
    pub input_events_forwarded: u64,
    pub audio_packets_forwarded: u64,
    pub disconnects: u64,
    pub recovery_attempts: u64,
    pub faults: u64,
}

#[derive(Debug, Clone)]
pub struct BridgeSession {
    state: BridgeState,
    stats: SessionStats,
    last_error: Option<String>,
}

impl Default for BridgeSession {
    fn default() -> Self {
        Self {
            state: BridgeState::Idle,
            stats: SessionStats::default(),
            last_error: None,
        }
    }
}

impl BridgeSession {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn state(&self) -> BridgeState {
        self.state
    }

    pub fn stats(&self) -> &SessionStats {
        &self.stats
    }

    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    pub fn record_media_frame(&mut self) {
        self.stats.frames_forwarded += 1;
    }

    pub fn record_input_event(&mut self) {
        self.stats.input_events_forwarded += 1;
    }

    pub fn record_audio_packet(&mut self) {
        self.stats.audio_packets_forwarded += 1;
    }

    pub fn apply(&mut self, event: SessionEvent) -> Result<StateTransition, SessionError> {
        let from = self.state;
        let to = match (from, &event) {
            (_, SessionEvent::Fault(reason)) => {
                self.stats.faults += 1;
                self.last_error = Some(reason.clone());
                BridgeState::Fault
            }
            (_, SessionEvent::Reset) => {
                self.last_error = None;
                BridgeState::Idle
            }
            (BridgeState::Idle, SessionEvent::CarplayEnumerationStarted) => {
                self.stats.enumeration_attempts += 1;
                BridgeState::CarplayEnumerating
            }
            (BridgeState::CarplayEnumerating, SessionEvent::CarplayEnumerated) => {
                self.stats.carplay_connections += 1;
                BridgeState::CarplayConnected
            }
            (BridgeState::CarplayConnected, SessionEvent::CarlifeConnectRequested)
            | (BridgeState::Reconnecting, SessionEvent::CarlifeConnectRequested) => {
                BridgeState::CarlifeConnecting
            }
            (BridgeState::CarlifeConnecting, SessionEvent::CarlifeHandshakeCompleted) => {
                self.stats.carlife_connections += 1;
                BridgeState::CarlifeConnected
            }
            (BridgeState::CarlifeConnected, SessionEvent::FirstFrameForwarded) => {
                BridgeState::Active
            }
            (BridgeState::Active, SessionEvent::FirstFrameForwarded) => BridgeState::Active,
            (
                BridgeState::CarplayEnumerating
                | BridgeState::CarplayConnected
                | BridgeState::CarlifeConnecting
                | BridgeState::CarlifeConnected
                | BridgeState::Active,
                SessionEvent::CarplayDisconnected(reason) | SessionEvent::LinkLost(reason),
            ) => {
                self.stats.disconnects += 1;
                self.stats.recovery_attempts += 1;
                self.last_error = Some(reason.clone());
                BridgeState::Reconnecting
            }
            (BridgeState::Reconnecting, SessionEvent::CarplayEnumerated) => {
                self.stats.carplay_connections += 1;
                BridgeState::CarplayConnected
            }
            (BridgeState::Reconnecting, SessionEvent::RecoveryCompleted) => BridgeState::Active,
            (BridgeState::Fault, SessionEvent::CarplayEnumerationStarted) => {
                return Err(SessionError::new(
                    from,
                    &event,
                    "reset the fault before starting a new session",
                ));
            }
            _ => {
                return Err(SessionError::new(
                    from,
                    &event,
                    "event is not valid for the current state",
                ));
            }
        };

        self.state = to;
        Ok(StateTransition { from, to })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn advance_to_active(session: &mut BridgeSession) {
        for event in [
            SessionEvent::CarplayEnumerationStarted,
            SessionEvent::CarplayEnumerated,
            SessionEvent::CarlifeConnectRequested,
            SessionEvent::CarlifeHandshakeCompleted,
            SessionEvent::FirstFrameForwarded,
        ] {
            session.apply(event).expect("happy path should be valid");
        }
        session.record_media_frame();
    }

    #[test]
    fn happy_path_reaches_active() {
        let mut session = BridgeSession::new();
        advance_to_active(&mut session);
        assert_eq!(session.state(), BridgeState::Active);
        assert_eq!(session.stats().frames_forwarded, 1);
        assert_eq!(session.stats().disconnects, 0);
    }

    #[test]
    fn active_session_recovers() {
        let mut session = BridgeSession::new();
        advance_to_active(&mut session);
        session
            .apply(SessionEvent::LinkLost("cable jiggle".into()))
            .unwrap();
        assert_eq!(session.state(), BridgeState::Reconnecting);
        session.apply(SessionEvent::RecoveryCompleted).unwrap();
        assert_eq!(session.state(), BridgeState::Active);
        assert_eq!(session.stats().recovery_attempts, 1);
    }

    #[test]
    fn invalid_sequence_is_rejected() {
        let mut session = BridgeSession::new();
        let error = session
            .apply(SessionEvent::FirstFrameForwarded)
            .expect_err("frame cannot arrive before links exist");
        assert_eq!(error.state(), BridgeState::Idle);
        assert_eq!(session.state(), BridgeState::Idle);
    }

    #[test]
    fn faults_require_reset() {
        let mut session = BridgeSession::new();
        session
            .apply(SessionEvent::Fault("ncm timeout".into()))
            .unwrap();
        assert_eq!(session.state(), BridgeState::Fault);
        assert!(session
            .apply(SessionEvent::CarplayEnumerationStarted)
            .is_err());
        session.apply(SessionEvent::Reset).unwrap();
        assert_eq!(session.state(), BridgeState::Idle);
    }
}
