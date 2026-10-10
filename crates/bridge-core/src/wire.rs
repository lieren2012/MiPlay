use std::fmt;

pub const HEADER_LEN: usize = 20;
pub const MAX_PAYLOAD_BYTES: usize = 16 * 1024 * 1024;
const MAGIC: [u8; 4] = *b"ZB01";
const VERSION: u8 = 1;
const CHECKSUM_OFFSET: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Channel {
    Control = 1,
    Media = 2,
    Audio = 3,
    Input = 4,
}

impl TryFrom<u8> for Channel {
    type Error = WireError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Control),
            2 => Ok(Self::Media),
            3 => Ok(Self::Audio),
            4 => Ok(Self::Input),
            other => Err(WireError::UnknownChannel(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedFrame<'a> {
    pub channel: Channel,
    pub flags: u16,
    pub sequence: u32,
    pub payload: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WireError {
    Truncated { actual: usize, minimum: usize },
    BadMagic([u8; 4]),
    UnsupportedVersion(u8),
    UnknownChannel(u8),
    PayloadTooLarge { actual: usize, maximum: usize },
    ChecksumMismatch { expected: u32, actual: u32 },
}

impl fmt::Display for WireError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Truncated { actual, minimum } => {
                write!(
                    f,
                    "frame is truncated: {actual} bytes, need at least {minimum}"
                )
            }
            Self::BadMagic(magic) => write!(f, "unexpected magic bytes: {magic:02x?}"),
            Self::UnsupportedVersion(version) => {
                write!(f, "unsupported simulator wire version {version}")
            }
            Self::UnknownChannel(channel) => write!(f, "unknown channel {channel}"),
            Self::PayloadTooLarge { actual, maximum } => {
                write!(f, "payload has {actual} bytes; maximum is {maximum}")
            }
            Self::ChecksumMismatch { expected, actual } => {
                write!(
                    f,
                    "checksum mismatch: expected {expected:08x}, actual {actual:08x}"
                )
            }
        }
    }
}

impl std::error::Error for WireError {}

pub fn encode(
    channel: Channel,
    sequence: u32,
    flags: u16,
    payload: &[u8],
) -> Result<Vec<u8>, WireError> {
    if payload.len() > MAX_PAYLOAD_BYTES {
        return Err(WireError::PayloadTooLarge {
            actual: payload.len(),
            maximum: MAX_PAYLOAD_BYTES,
        });
    }

    let mut frame = Vec::with_capacity(HEADER_LEN + payload.len());
    frame.extend_from_slice(&MAGIC);
    frame.push(VERSION);
    frame.push(channel as u8);
    frame.extend_from_slice(&flags.to_be_bytes());
    frame.extend_from_slice(&sequence.to_be_bytes());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());

    let checksum = checksum(&frame[4..CHECKSUM_OFFSET], payload);
    frame.extend_from_slice(&checksum.to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

pub fn decode(frame: &[u8]) -> Result<DecodedFrame<'_>, WireError> {
    if frame.len() < HEADER_LEN {
        return Err(WireError::Truncated {
            actual: frame.len(),
            minimum: HEADER_LEN,
        });
    }

    let magic = [frame[0], frame[1], frame[2], frame[3]];
    if magic != MAGIC {
        return Err(WireError::BadMagic(magic));
    }
    if frame[4] != VERSION {
        return Err(WireError::UnsupportedVersion(frame[4]));
    }
    let channel = Channel::try_from(frame[5])?;
    let flags = u16::from_be_bytes([frame[6], frame[7]]);
    let sequence = u32::from_be_bytes([frame[8], frame[9], frame[10], frame[11]]);
    let payload_len = u32::from_be_bytes([frame[12], frame[13], frame[14], frame[15]]) as usize;
    if payload_len > MAX_PAYLOAD_BYTES {
        return Err(WireError::PayloadTooLarge {
            actual: payload_len,
            maximum: MAX_PAYLOAD_BYTES,
        });
    }

    let total = HEADER_LEN
        .checked_add(payload_len)
        .ok_or(WireError::PayloadTooLarge {
            actual: payload_len,
            maximum: MAX_PAYLOAD_BYTES,
        })?;
    if frame.len() < total {
        return Err(WireError::Truncated {
            actual: frame.len(),
            minimum: total,
        });
    }

    let expected = u32::from_be_bytes([frame[16], frame[17], frame[18], frame[19]]);
    let payload = &frame[HEADER_LEN..total];
    let actual = checksum(&frame[4..CHECKSUM_OFFSET], payload);
    if expected != actual {
        return Err(WireError::ChecksumMismatch { expected, actual });
    }

    Ok(DecodedFrame {
        channel,
        flags,
        sequence,
        payload,
    })
}

fn checksum(header: &[u8], payload: &[u8]) -> u32 {
    let mut hash = 0x811c_9dc5_u32;
    for byte in header.iter().chain(payload) {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_preserves_fields() {
        let payload = b"test payload";
        let encoded = encode(Channel::Media, 42, 7, payload).unwrap();
        let decoded = decode(&encoded).unwrap();
        assert_eq!(decoded.channel, Channel::Media);
        assert_eq!(decoded.flags, 7);
        assert_eq!(decoded.sequence, 42);
        assert_eq!(decoded.payload, payload);
    }

    #[test]
    fn bit_flip_is_detected() {
        let mut encoded = encode(Channel::Audio, 1, 0, b"audio").unwrap();
        encoded[HEADER_LEN] ^= 0x80;
        assert!(matches!(
            decode(&encoded),
            Err(WireError::ChecksumMismatch { .. })
        ));
    }

    #[test]
    fn truncated_frame_is_rejected() {
        let encoded = encode(Channel::Control, 1, 0, b"abc").unwrap();
        assert!(matches!(
            decode(&encoded[..HEADER_LEN - 1]),
            Err(WireError::Truncated { .. })
        ));
    }

    #[test]
    fn bad_magic_is_rejected() {
        let mut encoded = encode(Channel::Input, 1, 0, b"x").unwrap();
        encoded[0] = 0;
        assert!(matches!(decode(&encoded), Err(WireError::BadMagic(_))));
    }
}
