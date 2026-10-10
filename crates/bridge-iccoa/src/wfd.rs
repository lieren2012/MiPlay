//! Wi-Fi Display session layer for the ICCOA Carlink source side.
//!
//! ICCOA Carlink is a Wi-Fi Display (Miracast) session with vendor extensions;
//! see `docs/iccoa-protocol-notes.md` for the evidence and
//! [`crate::spec`] for the parameter names. This module implements the parts
//! that are fully determined offline:
//!
//! - RTSP request/response framing (CRLF, `CSeq`, `Content-Type`,
//!   `Content-Length`),
//! - the `text/parameters` body format the M3/M4 exchanges use,
//! - the RTP transport description used by `Transport:` and
//!   `wfd_client_rtp_ports`,
//! - the sink capability advertisement, including which of the reports in
//!   [`crate::spec::SINK_CAPABILITY_REPORTS`] are missing.
//!
//! What is *not* here: the Wi-Fi Direct group formation, the WFD information
//! element and the media/RTP data path. Those need either more of the public
//! specification than can be encoded responsibly from memory, or a real peer to
//! verify against.

use crate::spec::SINK_CAPABILITY_REPORTS;
use crate::IccoaError;

/// RTSP methods used by a Wi-Fi Display session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RtspMethod {
    Options,
    GetParameter,
    SetParameter,
    Setup,
    Play,
    Pause,
    Teardown,
}

impl RtspMethod {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Options => "OPTIONS",
            Self::GetParameter => "GET_PARAMETER",
            Self::SetParameter => "SET_PARAMETER",
            Self::Setup => "SETUP",
            Self::Play => "PLAY",
            Self::Pause => "PAUSE",
            Self::Teardown => "TEARDOWN",
        }
    }

    pub fn parse(token: &str) -> Option<Self> {
        Some(match token.to_ascii_uppercase().as_str() {
            "OPTIONS" => Self::Options,
            "GET_PARAMETER" => Self::GetParameter,
            "SET_PARAMETER" => Self::SetParameter,
            "SETUP" => Self::Setup,
            "PLAY" => Self::Play,
            "PAUSE" => Self::Pause,
            "TEARDOWN" => Self::Teardown,
            _ => return None,
        })
    }
}

/// An RTSP request, as exchanged during capability negotiation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RtspRequest {
    pub method: RtspMethod,
    pub uri: String,
    pub cseq: u32,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl RtspRequest {
    pub fn new(method: RtspMethod, uri: impl Into<String>, cseq: u32) -> Self {
        Self {
            method,
            uri: uri.into(),
            cseq,
            headers: Vec::new(),
            body: String::new(),
        }
    }

    /// A `SET_PARAMETER`/`GET_PARAMETER` request carrying a parameter block.
    pub fn with_parameters(
        method: RtspMethod,
        uri: impl Into<String>,
        cseq: u32,
        parameters: &ParameterList,
    ) -> Self {
        let mut request = Self::new(method, uri, cseq);
        request
            .headers
            .push(("Content-Type".to_string(), "text/parameters".to_string()));
        request.body = parameters.serialize();
        request
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    pub fn parameters(&self) -> Result<ParameterList, IccoaError> {
        ParameterList::parse(&self.body)
    }

    pub fn serialize(&self) -> String {
        let mut out = format!("{} {} RTSP/1.0\r\n", self.method.as_str(), self.uri);
        out.push_str(&format!("CSeq: {}\r\n", self.cseq));
        for (name, value) in &self.headers {
            out.push_str(&format!("{name}: {value}\r\n"));
        }
        if !self.body.is_empty() {
            out.push_str(&format!("Content-Length: {}\r\n", self.body.len()));
        }
        out.push_str("\r\n");
        out.push_str(&self.body);
        out
    }

    pub fn parse(text: &str) -> Result<Self, IccoaError> {
        let (head, body) = split_message(text);
        let mut lines = head.lines();
        let request_line = lines
            .next()
            .ok_or_else(|| IccoaError::new("rtsp", "empty request"))?;
        let mut parts = request_line.split_whitespace();
        let method_token = parts
            .next()
            .ok_or_else(|| IccoaError::new("rtsp", "missing method"))?;
        let method = RtspMethod::parse(method_token)
            .ok_or_else(|| IccoaError::new("rtsp", format!("unknown method {method_token}")))?;
        let uri = parts
            .next()
            .ok_or_else(|| IccoaError::new("rtsp", "missing request URI"))?
            .to_string();
        let version = parts.next().unwrap_or("RTSP/1.0");
        if !version.starts_with("RTSP/") {
            return Err(IccoaError::new(
                "rtsp",
                format!("unsupported version {version}"),
            ));
        }

        let mut headers = Vec::new();
        let mut cseq = None;
        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let (name, value) = line
                .split_once(':')
                .ok_or_else(|| IccoaError::new("rtsp", format!("malformed header {line:?}")))?;
            let name = name.trim().to_string();
            let value = value.trim().to_string();
            if name.eq_ignore_ascii_case("CSeq") {
                cseq = Some(value.parse::<u32>().map_err(|_| {
                    IccoaError::new("rtsp", format!("CSeq is not a number: {value:?}"))
                })?);
            }
            headers.push((name, value));
        }
        let cseq = cseq.ok_or_else(|| IccoaError::new("rtsp", "CSeq header is missing"))?;

        Ok(Self {
            method,
            uri,
            cseq,
            headers,
            body: body.to_string(),
        })
    }
}

/// An RTSP response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RtspResponse {
    pub status: u16,
    pub reason: String,
    pub cseq: u32,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl RtspResponse {
    pub fn new(status: u16, reason: impl Into<String>, cseq: u32) -> Self {
        Self {
            status,
            reason: reason.into(),
            cseq,
            headers: Vec::new(),
            body: String::new(),
        }
    }

    /// The standard "OK, here is my capability block" answer.
    pub fn ok_with_parameters(cseq: u32, parameters: &ParameterList) -> Self {
        let mut response = Self::new(200, "OK", cseq);
        response
            .headers
            .push(("Content-Type".to_string(), "text/parameters".to_string()));
        response.body = parameters.serialize();
        response
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    pub fn parameters(&self) -> Result<ParameterList, IccoaError> {
        ParameterList::parse(&self.body)
    }

    pub fn serialize(&self) -> String {
        let mut out = format!("RTSP/1.0 {} {}\r\n", self.status, self.reason);
        out.push_str(&format!("CSeq: {}\r\n", self.cseq));
        for (name, value) in &self.headers {
            out.push_str(&format!("{name}: {value}\r\n"));
        }
        if !self.body.is_empty() {
            out.push_str(&format!("Content-Length: {}\r\n", self.body.len()));
        }
        out.push_str("\r\n");
        out.push_str(&self.body);
        out
    }

    pub fn parse(text: &str) -> Result<Self, IccoaError> {
        let (head, body) = split_message(text);
        let mut lines = head.lines();
        let status_line = lines
            .next()
            .ok_or_else(|| IccoaError::new("rtsp", "empty response"))?;
        let mut parts = status_line.splitn(3, ' ');
        let version = parts.next().unwrap_or("");
        if !version.starts_with("RTSP/") {
            return Err(IccoaError::new(
                "rtsp",
                format!("unsupported version {version:?}"),
            ));
        }
        let status = parts
            .next()
            .ok_or_else(|| IccoaError::new("rtsp", "missing status code"))?
            .parse::<u16>()
            .map_err(|_| IccoaError::new("rtsp", "status code is not a number"))?;
        let reason = parts.next().unwrap_or("").trim().to_string();

        let mut headers = Vec::new();
        let mut cseq = None;
        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let (name, value) = line
                .split_once(':')
                .ok_or_else(|| IccoaError::new("rtsp", format!("malformed header {line:?}")))?;
            let name = name.trim().to_string();
            let value = value.trim().to_string();
            if name.eq_ignore_ascii_case("CSeq") {
                cseq = Some(value.parse::<u32>().map_err(|_| {
                    IccoaError::new("rtsp", format!("CSeq is not a number: {value:?}"))
                })?);
            }
            headers.push((name, value));
        }

        Ok(Self {
            status,
            reason,
            cseq: cseq.unwrap_or(0),
            headers,
            body: body.to_string(),
        })
    }
}

fn split_message(text: &str) -> (String, &str) {
    let normalised = text.replace("\r\n", "\n");
    match normalised.split_once("\n\n") {
        Some((head, tail)) => {
            // The body keeps its original bytes apart from the blank separator,
            // so find where it starts in the original text.
            let offset = text.len() - tail.len();
            (head.to_string(), &text[offset..])
        }
        None => (normalised, ""),
    }
}

/// A `name: value` parameter block, which is what a `text/parameters` body is.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ParameterList {
    entries: Vec<(String, String)>,
}

impl ParameterList {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set(&mut self, name: impl Into<String>, value: impl Into<String>) {
        let name = name.into();
        let value = value.into();
        match self.entries.iter_mut().find(|(key, _)| *key == name) {
            Some(slot) => slot.1 = value,
            None => self.entries.push((name, value)),
        }
    }

    pub fn get(&self, name: &str) -> Option<&str> {
        self.entries
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    pub fn contains(&self, name: &str) -> bool {
        self.get(name).is_some()
    }

    pub fn remove(&mut self, name: &str) {
        self.entries.retain(|(key, _)| key != name);
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &str)> {
        self.entries.iter().map(|(k, v)| (k.as_str(), v.as_str()))
    }

    pub fn parse(text: &str) -> Result<Self, IccoaError> {
        let mut list = Self::new();
        for (index, raw) in text.replace("\r\n", "\n").lines().enumerate() {
            let line = raw.trim_end();
            if line.trim().is_empty() {
                continue;
            }
            let (name, value) = line.split_once(':').ok_or_else(|| {
                IccoaError::new(
                    "parameters",
                    format!("line {} has no ':' separator: {line:?}", index + 1),
                )
            })?;
            list.set(name.trim(), value.trim());
        }
        Ok(list)
    }

    pub fn serialize(&self) -> String {
        let mut out = String::new();
        for (name, value) in &self.entries {
            out.push_str(name);
            out.push_str(": ");
            out.push_str(value);
            out.push('\n');
        }
        out
    }
}

/// The RTP transport description, e.g. `RTP/AVP/UDP;unicast 1028 0 mode=play`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RtpPorts {
    pub transport: RtpTransport,
    pub port0: u16,
    /// Second port, absent for interleaved TCP transport.
    pub port1: Option<u16>,
    pub mode: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RtpTransport {
    Udp,
    Tcp,
}

impl RtpTransport {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Udp => "RTP/AVP/UDP;unicast",
            Self::Tcp => "RTP/AVP/TCP;unicast",
        }
    }
}

impl RtpPorts {
    pub fn parse(text: &str) -> Result<Self, IccoaError> {
        let tokens: Vec<&str> = text.split_whitespace().collect();
        let first = tokens
            .first()
            .ok_or_else(|| IccoaError::new("transport", "empty transport description"))?;
        let transport = if first.to_ascii_uppercase().starts_with("RTP/AVP/TCP") {
            RtpTransport::Tcp
        } else if first.to_ascii_uppercase().starts_with("RTP/AVP/UDP")
            || first.to_ascii_uppercase().starts_with("RTP/AVP;")
        {
            RtpTransport::Udp
        } else {
            return Err(IccoaError::new(
                "transport",
                format!("unsupported transport {first:?}"),
            ));
        };
        // Form used in the SETUP `Transport:` header:
        //   RTP/AVP/UDP;unicast;client_port=1028-1029;server_port=19000-19001
        if let Some(index) = first.to_ascii_lowercase().find("client_port=") {
            let rest = &first[index + "client_port=".len()..];
            let range = rest.split([';', ' ']).next().unwrap_or("");
            let (lower, upper) = match range.split_once('-') {
                Some((lower, upper)) => (lower, Some(upper)),
                None => (range, None),
            };
            let port0 = lower
                .parse::<u16>()
                .map_err(|_| IccoaError::new("transport", "client_port is not a number"))?;
            let port1 = upper.and_then(|value| value.parse::<u16>().ok());
            return Ok(Self {
                transport,
                port0,
                port1,
                mode: "play".to_string(),
            });
        }

        // Form used by `wfd_client_rtp_ports` / the parameter body:
        //   RTP/AVP/UDP;unicast 1028 0 mode=play
        let port0 = tokens
            .get(1)
            .ok_or_else(|| IccoaError::new("transport", "missing port0"))?
            .parse::<u16>()
            .map_err(|_| IccoaError::new("transport", "port0 is not a number"))?;
        let second = tokens
            .get(2)
            .ok_or_else(|| IccoaError::new("transport", "missing port1 slot"))?;
        let port1 = match second.parse::<u16>() {
            Ok(0) | Err(_) => None,
            Ok(port) => Some(port),
        };
        let mode = tokens
            .iter()
            .find_map(|token| token.strip_prefix("mode="))
            .unwrap_or("play")
            .to_string();

        Ok(Self {
            transport,
            port0,
            port1,
            mode,
        })
    }

    pub fn serialize(&self) -> String {
        let second = self.port1.unwrap_or(0);
        format!(
            "{} {} {} mode={}",
            self.transport.as_str(),
            self.port0,
            second,
            self.mode
        )
    }
}

/// What this head unit advertises to the phone in the M3 `SET_PARAMETER`.
///
/// Every field is optional on purpose: leaving one out is exactly how a feature
/// gets disabled on the phone side, which [`Self::missing_reports`] reports.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SinkCapabilities {
    pub video_formats: String,
    pub audio_codecs: String,
    pub client_rtp_ports: RtpPorts,
    pub uibc: bool,
    pub content_protection: bool,
    pub video_latency_ms: Option<u32>,
    pub audio_latency_ms: Option<u32>,
    pub fusion: bool,
    pub hevc: bool,
    pub display_mode: Option<String>,
    pub rotates_with_content: bool,
    pub backstage_projection_full: bool,
    pub audio_encrypt: bool,
    pub relay: bool,
    pub car_display_mode: Option<String>,
    pub car_vars: Option<String>,
    pub pc_display_mode: Option<String>,
    pub av_raw_packet: bool,
}

impl SinkCapabilities {
    /// A conservative block: video and audio, nothing vendor specific.
    pub fn minimal(client_rtp_ports: RtpPorts) -> Self {
        Self {
            video_formats: "00 00 02 04 00000010 00000000 00000000 00 0000 0000 00 none none"
                .to_string(),
            audio_codecs: "LPCM 00000002 00".to_string(),
            client_rtp_ports,
            uibc: true,
            content_protection: false,
            video_latency_ms: Some(50),
            audio_latency_ms: Some(50),
            fusion: false,
            hevc: false,
            display_mode: None,
            rotates_with_content: false,
            backstage_projection_full: false,
            audio_encrypt: false,
            relay: false,
            car_display_mode: None,
            car_vars: None,
            pc_display_mode: None,
            av_raw_packet: false,
        }
    }

    pub fn to_parameters(&self) -> ParameterList {
        let mut list = ParameterList::new();
        list.set("wfd_video_formats", self.video_formats.clone());
        list.set("wfd_audio_codecs", self.audio_codecs.clone());
        list.set("wfd_client_rtp_ports", self.client_rtp_ports.serialize());
        if self.uibc {
            list.set("wfd_uibc_capability", "input_category_list=HIDC;generic_cap_list=Mouse,SingleTouch;hidc_cap_list=Mouse,None");
        }
        if self.content_protection {
            list.set("wfd_content_protection", "HDCP2.2");
        }
        if let Some(latency) = self.video_latency_ms {
            list.set("wfd_video_latency", latency.to_string());
        }
        if let Some(latency) = self.audio_latency_ms {
            list.set("wfd_audio_latency", latency.to_string());
        }
        if self.fusion {
            list.set("iccoa_support_fusion", "1");
        }
        if self.hevc {
            list.set("iccoa_support_hevc", "1");
        }
        if let Some(mode) = &self.display_mode {
            list.set("osc_display_mode", mode.clone());
        }
        if self.rotates_with_content {
            list.set("osc_rotates_with_content", "1");
        }
        if self.backstage_projection_full {
            list.set("osc_backstage_projection_full", "1");
        }
        if self.audio_encrypt {
            list.set("osc_audio_encrypt", "1");
        }
        if self.relay {
            list.set("osc_is_relay", "1");
        }
        if let Some(mode) = &self.car_display_mode {
            list.set("wfd_car_display_mode", mode.clone());
        }
        if let Some(vars) = &self.car_vars {
            list.set("wfd_car_vars", vars.clone());
        }
        if let Some(mode) = &self.pc_display_mode {
            list.set("wfd_pc_display_mode", mode.clone());
        }
        if self.av_raw_packet {
            list.set("wfd_av_raw_packet", "1");
        }
        list
    }

    pub fn from_parameters(list: &ParameterList) -> Result<Self, IccoaError> {
        let client_rtp_ports = list
            .get("wfd_client_rtp_ports")
            .ok_or_else(|| IccoaError::new("capabilities", "wfd_client_rtp_ports is required"))
            .and_then(RtpPorts::parse)?;
        let flag = |name: &str| list.get(name).is_some();
        Ok(Self {
            video_formats: list.get("wfd_video_formats").unwrap_or("").to_string(),
            audio_codecs: list.get("wfd_audio_codecs").unwrap_or("").to_string(),
            client_rtp_ports,
            uibc: flag("wfd_uibc_capability"),
            content_protection: flag("wfd_content_protection"),
            video_latency_ms: list.get("wfd_video_latency").and_then(|v| v.parse().ok()),
            audio_latency_ms: list.get("wfd_audio_latency").and_then(|v| v.parse().ok()),
            fusion: flag("iccoa_support_fusion"),
            hevc: flag("iccoa_support_hevc"),
            display_mode: list.get("osc_display_mode").map(str::to_string),
            rotates_with_content: flag("osc_rotates_with_content"),
            backstage_projection_full: flag("osc_backstage_projection_full"),
            audio_encrypt: flag("osc_audio_encrypt"),
            relay: flag("osc_is_relay"),
            car_display_mode: list.get("wfd_car_display_mode").map(str::to_string),
            car_vars: list.get("wfd_car_vars").map(str::to_string),
            pc_display_mode: list.get("wfd_pc_display_mode").map(str::to_string),
            av_raw_packet: flag("wfd_av_raw_packet"),
        })
    }

    /// Which of the capability reports the phone looks for are *not* advertised.
    ///
    /// Each name returned here corresponds to a feature the phone will keep
    /// switched off; [`spec::feature_for`] gives the feature's meaning.
    pub fn missing_reports(&self) -> Vec<&'static str> {
        let parameters = self.to_parameters();
        SINK_CAPABILITY_REPORTS
            .iter()
            .filter(|report| {
                !parameters.contains(report)
                    || parameters.get(report).is_some_and(|value| value.is_empty())
            })
            .copied()
            .collect()
    }
}

/// One step of the Wi-Fi Display session establishment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WfdStep {
    /// `M1` .. `M7`, matching [`crate::spec::WFD_SESSION_STEPS`].
    pub name: &'static str,
    pub method: RtspMethod,
    /// `true` when the source (the phone) sends this step.
    pub from_source: bool,
    pub purpose: &'static str,
}

/// The standard WFD session establishment order.
///
/// The direction and purpose of M1..M4 and M6/M7 follow the published Wi-Fi
/// Display flow; M5 is the sink's `wfd_trigger_method` step, whose exact
/// parameter name was *not* found in the phone's library, so treat it as
/// unverified.
pub const WFD_FLOW: &[WfdStep] = &[
    WfdStep {
        name: "M1",
        method: RtspMethod::Options,
        from_source: true,
        purpose: "discover which RTSP methods the sink supports",
    },
    WfdStep {
        name: "M2",
        method: RtspMethod::GetParameter,
        from_source: true,
        purpose: "ask the sink for its capability parameters",
    },
    WfdStep {
        name: "M3",
        method: RtspMethod::SetParameter,
        from_source: false,
        purpose: "sink advertises its capabilities",
    },
    WfdStep {
        name: "M4",
        method: RtspMethod::SetParameter,
        from_source: true,
        purpose: "source sends the parameters it selected",
    },
    WfdStep {
        name: "M5",
        method: RtspMethod::SetParameter,
        from_source: false,
        purpose: "sink triggers SETUP (parameter name unverified)",
    },
    WfdStep {
        name: "M6",
        method: RtspMethod::Setup,
        from_source: true,
        purpose: "source sets up the RTP transport",
    },
    WfdStep {
        name: "M7",
        method: RtspMethod::Play,
        from_source: true,
        purpose: "session starts streaming",
    },
];

#[cfg(test)]
mod tests {
    use super::*;
    use crate::spec;

    fn sample_ports() -> RtpPorts {
        RtpPorts {
            transport: RtpTransport::Udp,
            port0: 1028,
            port1: Some(1029),
            mode: "play".to_string(),
        }
    }

    #[test]
    fn rtsp_request_round_trips() {
        let mut parameters = ParameterList::new();
        parameters.set(
            "wfd_client_rtp_ports",
            "RTP/AVP/UDP;unicast 1028 0 mode=play",
        );
        let request = RtspRequest::with_parameters(
            RtspMethod::SetParameter,
            "rtsp://localhost/wfd1.0",
            3,
            &parameters,
        );
        let text = request.serialize();
        assert!(text.starts_with("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"));
        assert!(text.contains("CSeq: 3\r\n"));
        assert!(text.contains("Content-Type: text/parameters\r\n"));
        let parsed = RtspRequest::parse(&text).unwrap();
        assert_eq!(parsed.method, RtspMethod::SetParameter);
        assert_eq!(parsed.cseq, 3);
        assert_eq!(parsed.uri, "rtsp://localhost/wfd1.0");
        assert_eq!(
            parsed.parameters().unwrap().get("wfd_client_rtp_ports"),
            Some("RTP/AVP/UDP;unicast 1028 0 mode=play")
        );
    }

    #[test]
    fn rtsp_response_round_trips() {
        let mut parameters = ParameterList::new();
        parameters.set("wfd_video_formats", "00 00 02 04 none none");
        let response = RtspResponse::ok_with_parameters(7, &parameters);
        let parsed = RtspResponse::parse(&response.serialize()).unwrap();
        assert_eq!(parsed.status, 200);
        assert!(parsed.is_success());
        assert_eq!(parsed.cseq, 7);
        assert_eq!(
            parsed.parameters().unwrap().get("wfd_video_formats"),
            Some("00 00 02 04 none none")
        );
    }

    #[test]
    fn rtsp_parsing_is_lenient_about_header_case_and_line_endings() {
        let text = "OPTIONS * RTSP/1.0\ncseq: 1\nRequire: org.wfa.wfd1.0\n\n";
        let parsed = RtspRequest::parse(text).unwrap();
        assert_eq!(parsed.method, RtspMethod::Options);
        assert_eq!(parsed.header("require"), Some("org.wfa.wfd1.0"));
        assert!(parsed.body.is_empty());
    }

    #[test]
    fn missing_cseq_is_rejected() {
        let error = RtspRequest::parse("OPTIONS * RTSP/1.0\r\n\r\n").unwrap_err();
        assert!(error.message().contains("CSeq"));
    }

    #[test]
    fn malformed_parameter_line_is_rejected() {
        let error = ParameterList::parse("wfd_video_formats\n").unwrap_err();
        assert!(error.message().contains("no ':' separator"));
    }

    #[test]
    fn parameter_list_keeps_last_value_for_repeats() {
        let list = ParameterList::parse("a: 1\nb: 2\na: 3\n").unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list.get("a"), Some("3"));
        list.get("b").unwrap();
    }

    #[test]
    fn rtp_ports_parse_both_transports() {
        let udp = RtpPorts::parse("RTP/AVP/UDP;unicast 1028 1029 mode=play").unwrap();
        assert_eq!(udp.transport, RtpTransport::Udp);
        assert_eq!(udp.port0, 1028);
        assert_eq!(udp.port1, Some(1029));

        let tcp = RtpPorts::parse("RTP/AVP/TCP;unicast 19000 0 mode=play").unwrap();
        assert_eq!(tcp.transport, RtpTransport::Tcp);
        assert_eq!(tcp.port1, None);

        // The `Transport:` header form, as seen in the phone's library.
        let header =
            RtpPorts::parse("RTP/AVP/UDP;unicast;client_port=1028-1029;server_port=19000-19001")
                .unwrap();
        assert_eq!(header.port0, 1028);
        assert_eq!(header.port1, Some(1029));

        let single = RtpPorts::parse("RTP/AVP/TCP;unicast;client_port=19000").unwrap();
        assert_eq!(single.transport, RtpTransport::Tcp);
        assert_eq!(single.port1, None);

        assert!(
            RtpPorts::parse("RTP/AVP/UDP;unicast;").is_err(),
            "no ports at all"
        );
        assert!(RtpPorts::parse("bogus").is_err());
    }

    #[test]
    fn rtp_ports_round_trip() {
        let ports = sample_ports();
        let text = ports.serialize();
        assert_eq!(text, "RTP/AVP/UDP;unicast 1028 1029 mode=play");
        assert_eq!(RtpPorts::parse(&text).unwrap(), ports);
    }

    #[test]
    fn minimal_sink_reports_only_the_basics() {
        let sink = SinkCapabilities::minimal(sample_ports());
        let parameters = sink.to_parameters();
        assert!(parameters.contains("wfd_video_formats"));
        assert!(parameters.contains("wfd_client_rtp_ports"));
        assert!(!parameters.contains("iccoa_support_fusion"));

        let missing = sink.missing_reports();
        assert!(missing.contains(&"iccoa_support_fusion"));
        assert!(missing.contains(&"osc_audio_encrypt"));
        assert!(!missing.contains(&"wfd_video_formats"));
        for report in &missing {
            assert!(
                spec::feature_for(report).is_some(),
                "{report} has no feature"
            );
        }
    }

    #[test]
    fn full_sink_reports_nothing_missing() {
        let mut sink = SinkCapabilities::minimal(sample_ports());
        sink.content_protection = true;
        sink.fusion = true;
        sink.hevc = true;
        sink.display_mode = Some("car".to_string());
        sink.rotates_with_content = true;
        sink.backstage_projection_full = true;
        sink.audio_encrypt = true;
        sink.relay = true;
        sink.car_display_mode = Some("car".to_string());
        sink.car_vars = Some("0".to_string());
        sink.pc_display_mode = Some("pc".to_string());
        sink.av_raw_packet = true;
        assert!(
            sink.missing_reports().is_empty(),
            "{:?}",
            sink.missing_reports()
        );
    }

    #[test]
    fn sink_capabilities_round_trip() {
        let sink = SinkCapabilities::minimal(sample_ports());
        let parameters = sink.to_parameters();
        let parsed = SinkCapabilities::from_parameters(&parameters).unwrap();
        assert_eq!(parsed, sink);
        assert_eq!(parsed.client_rtp_ports, sample_ports());
    }

    #[test]
    fn capabilities_require_rtp_ports() {
        let mut parameters = ParameterList::new();
        parameters.set("wfd_video_formats", "00 00 02 04");
        let error = SinkCapabilities::from_parameters(&parameters).unwrap_err();
        assert!(error.message().contains("wfd_client_rtp_ports"));
    }

    #[test]
    fn flow_matches_the_spec_step_names() {
        let names: Vec<&str> = WFD_FLOW.iter().map(|step| step.name).collect();
        assert_eq!(names, spec::WFD_SESSION_STEPS);
        assert!(WFD_FLOW[0].from_source);
        assert!(!WFD_FLOW[2].from_source, "M3 comes from the sink");
        assert_eq!(WFD_FLOW[5].method, RtspMethod::Setup, "M6 is SETUP");
        assert_eq!(WFD_FLOW[6].method, RtspMethod::Play, "M7 is PLAY");
    }
}
