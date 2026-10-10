//! Head-unit session state machine for the phone-side source.
//!
//! This is a *boundary* model: it tracks the states the adapter must expose, not
//! the exact message sequence of ICCOA Carlink. The real sequence has to come
//! from the specification or a capture, and only then can the transitions below
//! be refined.

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IccoaState {
    Idle,
    /// Advertising so a phone can find this head unit.
    Advertising,
    /// A phone showed up and the transport is being selected.
    PeerFound,
    /// Pairing, authentication and key agreement.
    Securing,
    /// Capability negotiation and channel setup.
    Negotiating,
    /// Media and input channels are live.
    Streaming,
    Fault,
}

impl IccoaState {
    pub fn is_streaming(self) -> bool {
        matches!(self, Self::Streaming)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IccoaEvent {
    AdvertisementStarted,
    PeerFound { address: String },
    PeerLost(String),
    SecureAccepted,
    ChannelsNegotiated,
    FirstMediaUnit,
    SessionEnded,
    Fault(String),
    Reset,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IccoaStateTransition {
    pub from: IccoaState,
    pub to: IccoaState,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct IccoaSessionStats {
    pub advertisement_attempts: u64,
    pub peers_found: u64,
    pub peers_lost: u64,
    pub handshakes: u64,
    pub streaming_sessions: u64,
    pub media_units: u64,
    pub faults: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IccoaSessionError {
    state: IccoaState,
    event: String,
    message: String,
}

impl IccoaSessionError {
    fn new(state: IccoaState, event: &IccoaEvent, message: impl Into<String>) -> Self {
        Self {
            state,
            event: format!("{event:?}"),
            message: message.into(),
        }
    }

    pub fn state(&self) -> IccoaState {
        self.state
    }

    pub fn event(&self) -> &str {
        &self.event
    }
}

impl fmt::Display for IccoaSessionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "invalid ICCOA event {} in state {:?}: {}",
            self.event, self.state, self.message
        )
    }
}

impl std::error::Error for IccoaSessionError {}

#[derive(Debug, Clone)]
pub struct IccoaSession {
    state: IccoaState,
    stats: IccoaSessionStats,
    last_error: Option<String>,
}

impl Default for IccoaSession {
    fn default() -> Self {
        Self {
            state: IccoaState::Idle,
            stats: IccoaSessionStats::default(),
            last_error: None,
        }
    }
}

impl IccoaSession {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn state(&self) -> IccoaState {
        self.state
    }

    pub fn stats(&self) -> &IccoaSessionStats {
        &self.stats
    }

    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    pub fn record_media_unit(&mut self) {
        self.stats.media_units += 1;
    }

    pub fn apply(&mut self, event: IccoaEvent) -> Result<IccoaStateTransition, IccoaSessionError> {
        let from = self.state;
        let to = match (from, &event) {
            (_, IccoaEvent::Fault(reason)) => {
                self.stats.faults += 1;
                self.last_error = Some(reason.clone());
                IccoaState::Fault
            }
            (_, IccoaEvent::Reset) => {
                self.last_error = None;
                IccoaState::Idle
            }
            (IccoaState::Idle, IccoaEvent::AdvertisementStarted) => {
                self.stats.advertisement_attempts += 1;
                IccoaState::Advertising
            }
            (IccoaState::Advertising, IccoaEvent::PeerFound { .. }) => {
                self.stats.peers_found += 1;
                IccoaState::PeerFound
            }
            (IccoaState::PeerFound, IccoaEvent::SecureAccepted) => IccoaState::Securing,
            (IccoaState::Securing, IccoaEvent::ChannelsNegotiated) => {
                self.stats.handshakes += 1;
                IccoaState::Negotiating
            }
            (IccoaState::Negotiating, IccoaEvent::FirstMediaUnit) => {
                self.stats.streaming_sessions += 1;
                IccoaState::Streaming
            }
            (IccoaState::Streaming, IccoaEvent::FirstMediaUnit) => IccoaState::Streaming,
            (
                IccoaState::Advertising
                | IccoaState::PeerFound
                | IccoaState::Securing
                | IccoaState::Negotiating
                | IccoaState::Streaming,
                IccoaEvent::PeerLost(_) | IccoaEvent::SessionEnded,
            ) => {
                if let IccoaEvent::PeerLost(reason) = &event {
                    self.stats.peers_lost += 1;
                    self.last_error = Some(reason.clone());
                }
                IccoaState::Advertising
            }
            (IccoaState::Fault, IccoaEvent::AdvertisementStarted) => {
                return Err(IccoaSessionError::new(
                    from,
                    &event,
                    "reset the fault before advertising again",
                ));
            }
            _ => {
                return Err(IccoaSessionError::new(
                    from,
                    &event,
                    "event is not valid for the current state",
                ));
            }
        };

        self.state = to;
        Ok(IccoaStateTransition { from, to })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn advance_to_streaming(session: &mut IccoaSession) {
        for event in [
            IccoaEvent::AdvertisementStarted,
            IccoaEvent::PeerFound {
                address: "192.0.2.10".into(),
            },
            IccoaEvent::SecureAccepted,
            IccoaEvent::ChannelsNegotiated,
            IccoaEvent::FirstMediaUnit,
        ] {
            session.apply(event).expect("happy path should be valid");
        }
    }

    #[test]
    fn happy_path_reaches_streaming() {
        let mut session = IccoaSession::new();
        advance_to_streaming(&mut session);
        assert_eq!(session.state(), IccoaState::Streaming);
        assert_eq!(session.stats().handshakes, 1);
        assert_eq!(session.stats().streaming_sessions, 1);
    }

    #[test]
    fn peer_loss_returns_to_advertising_and_clears_after_reset() {
        let mut session = IccoaSession::new();
        advance_to_streaming(&mut session);
        session
            .apply(IccoaEvent::PeerLost("radio dropped".into()))
            .unwrap();
        assert_eq!(session.state(), IccoaState::Advertising);
        assert_eq!(session.stats().peers_lost, 1);
        session.apply(IccoaEvent::Reset).unwrap();
        assert_eq!(session.state(), IccoaState::Idle);
        assert_eq!(session.last_error(), None);
    }

    #[test]
    fn media_before_handshake_is_rejected() {
        let mut session = IccoaSession::new();
        session.apply(IccoaEvent::AdvertisementStarted).unwrap();
        let error = session
            .apply(IccoaEvent::FirstMediaUnit)
            .expect_err("media cannot arrive before negotiation");
        assert_eq!(error.state(), IccoaState::Advertising);
        assert_eq!(session.state(), IccoaState::Advertising);
    }

    #[test]
    fn reconnect_cycle_is_repeatable() {
        let mut session = IccoaSession::new();
        for _ in 0..100 {
            advance_to_streaming(&mut session);
            session.apply(IccoaEvent::SessionEnded).unwrap();
            session.apply(IccoaEvent::Reset).unwrap();
        }
        assert_eq!(session.stats().peers_found, 100);
        assert_eq!(session.stats().streaming_sessions, 100);
        assert_eq!(session.state(), IccoaState::Idle);
    }

    #[test]
    fn faults_require_reset() {
        let mut session = IccoaSession::new();
        session
            .apply(IccoaEvent::Fault("negotiation timeout".into()))
            .unwrap();
        assert_eq!(session.state(), IccoaState::Fault);
        assert!(session.apply(IccoaEvent::AdvertisementStarted).is_err());
        session.apply(IccoaEvent::Reset).unwrap();
        assert_eq!(session.state(), IccoaState::Idle);
        assert_eq!(session.stats().faults, 1);
    }
}
