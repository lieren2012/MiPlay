package org.carplaytocarlife.iccoa;

import java.util.Arrays;
import java.util.Map;

/** Desktop self test for the AirPlay audio packet. */
public final class SelfTestAudio {

    private static int failures;

    public static void main(String[] args) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (0x10 + i);
        }
        byte[] pcm = new byte[384];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 3);
        }

        AudioSender sender = new AudioSender(96);
        byte[] packet = sender.packet(pcm, key, 7, 0x11223344L, 42);

        check("packet has header, sealed payload and nonce trailer",
                packet.length == AudioSender.HEADER_LENGTH + pcm.length
                        + AudioSender.TAG_LENGTH + AudioSender.TRAILER_LENGTH);
        check("byte 0 is 0x80", (packet[0] & 0xFF) == 0x80);
        check("payload type is carried", (packet[1] & 0xFF) == 96);
        check("sequence is big endian", sender.sequenceOf(packet) == 7);
        check("timestamp is big endian", sender.timestampOf(packet) == 0x11223344L);
        check("ssrc is zero", packet[8] == 0 && packet[11] == 0);

        byte[] nonce = AudioSender.nonce(42);
        check("the nonce carries the counter little endian", nonce[4] == 42 && nonce[11] == 0);
        boolean trailerMatches = true;
        for (int i = 0; i < AudioSender.TRAILER_LENGTH; i++) {
            if (packet[packet.length - AudioSender.TRAILER_LENGTH + i] != nonce[4 + i]) {
                trailerMatches = false;
            }
        }
        check("the trailer is the tail of the nonce", trailerMatches);

        byte[] opened = AudioSender.open(packet, key);
        check("the payload decrypts back to the PCM", opened != null
                && Arrays.equals(opened, pcm), opened == null ? "open failed" : "mismatch");

        byte[] wrongKey = key.clone();
        wrongKey[0] ^= 0x01;
        check("a wrong key is rejected", AudioSender.open(packet, wrongKey) == null);

        byte[] tampered = packet.clone();
        tampered[AudioSender.HEADER_LENGTH + 4] ^= 0x20;
        check("a tampered payload is rejected", AudioSender.open(tampered, key) == null);

        byte[] otherHeader = packet.clone();
        otherHeader[5] ^= 0x01;
        check("tampering with the AAD (timestamp) is rejected",
                AudioSender.open(otherHeader, key) == null);

        // the nonce must advance with the counter, not repeat
        byte[] second = sender.packet(pcm, key, 8, 0x11223344L, 43);
        check("a later packet uses a different nonce",
                !Arrays.equals(Arrays.copyOfRange(packet, packet.length - 8, packet.length),
                        Arrays.copyOfRange(second, second.length - 8, second.length)));
        check("a later packet still decrypts", AudioSender.open(second, key) != null);

        // ------------------------------------------------- the nonce and the trailer
        byte[] nonceBytes = AudioSender.nonce(0x0102030405060708L);
        check("the nonce's first four bytes are zero",
                nonceBytes[0] == 0 && nonceBytes[1] == 0 && nonceBytes[2] == 0 && nonceBytes[3] == 0);
        check("the counter is little endian in the nonceBytes",
                nonceBytes[4] == 0x08 && nonceBytes[5] == 0x07 && nonceBytes[6] == 0x06 && nonceBytes[7] == 0x05
                        && nonceBytes[8] == 0x04 && nonceBytes[9] == 0x03 && nonceBytes[10] == 0x02
                        && nonceBytes[11] == 0x01,
                Logs.hex(nonceBytes, nonceBytes.length));
        AudioSender audioSender = new AudioSender(AirplayMessages.AUDIO_STREAM_TYPE);
        byte[] audioKey = new byte[32];
        byte[] audioPacket = audioSender.packet(new byte[8], audioKey, 7, 90_000L, 0x0102030405060708L);
        check("the header starts with 0x80 and the stream type",
                audioPacket[0] == (byte) 0x80 && audioPacket[1] == AirplayMessages.AUDIO_STREAM_TYPE);
        check("the sequence is big endian in the header",
                audioSender.sequenceOf(audioPacket) == 7);
        check("the timestamp is big endian in the header",
                audioSender.timestampOf(audioPacket) == 90_000L);
        check("the trailer is the back half of the nonceBytes",
                audioPacket[audioPacket.length - 8] == 0x08 && audioPacket[audioPacket.length - 1] == 0x01);
        check("the audioPacket carries a 16 byte tag",
                audioPacket.length == AudioSender.HEADER_LENGTH + 8 + AudioSender.TAG_LENGTH
                        + AudioSender.TRAILER_LENGTH);
        check("the audioPacket opens with the same audioKey",
                AudioSender.open(audioPacket, audioKey) != null);

        // ------------------------------------------- the negotiated audio offers
        check("there are four PCM offers", AirplayMessages.PCM_CANDIDATES.length == 4);
        check("the offers are the reference's values",
                AirplayMessages.PCM_CANDIDATES[0].format == 32768
                        && AirplayMessages.PCM_CANDIDATES[0].rate == 48000
                        && AirplayMessages.PCM_CANDIDATES[0].channels == 2
                        && AirplayMessages.PCM_CANDIDATES[1].format == 2048
                        && AirplayMessages.PCM_CANDIDATES[1].rate == 44100
                        && AirplayMessages.PCM_CANDIDATES[2].format == 16384
                        && AirplayMessages.PCM_CANDIDATES[2].channels == 1
                        && AirplayMessages.PCM_CANDIDATES[3].format == 1024
                        && AirplayMessages.PCM_CANDIDATES[3].rate == 44100);
        check("samples per frame is a two hundredth of the rate",
                AirplayMessages.PCM_CANDIDATES[0].samplesPerFrame() == 240
                        && AirplayMessages.PCM_CANDIDATES[1].samplesPerFrame() == 220);
        Map<String, Bplist.Node> stream = AirplayMessages.audioStream(
                AirplayMessages.AUDIO_STREAM_TYPE, AirplayMessages.AUDIO_TYPE_MEDIA,
                AirplayMessages.PCM_CANDIDATES[1], 2, 100);
        check("the audio stream entry has the nine fields", stream.size() == 8,
                stream.keySet().toString());
        check("the audio stream names its type and audio type",
                stream.get("type").asInteger() == 96
                        && "media".equals(stream.get("audioType").asString()));
        check("the audio stream carries the format, rate and frame size",
                stream.get("audioFormat").asInteger() == 2048
                        && stream.get("sr").asInteger() == 44100
                        && stream.get("spf").asInteger() == 220);
        check("the audio stream carries its connection id, latency and compression",
                stream.get("streamConnectionID").asInteger() == 2
                        && stream.get("audioLatencyMs").asInteger() == 100
                        && stream.get("ct").asInteger() == 1);

        // what the car announces decides what is offered: the reference reads a
        // bit mask of PCM formats off the car's audio stream and offers the first
        // candidate that mask allows
        java.util.Map<String, Bplist.Node> entry = new java.util.LinkedHashMap<>();
        entry.put("type", Bplist.Node.integer(AirplayMessages.AUDIO_STREAM_TYPE));
        entry.put("audioType", Bplist.Node.string(AirplayMessages.AUDIO_TYPE_MEDIA));
        entry.put("audioOutputFormats", Bplist.Node.integer(2048));
        long mask = AirplayMessages.audioOutputMask(streamsOf(entry));
        check("the mask is read off the car's audio stream", mask == 2048, String.valueOf(mask));
        check("the first format the mask allows is the one offered",
                AirplayMessages.formatFromMask(mask) == AirplayMessages.PCM_CANDIDATES[1]);
        check("the announced format leads the offer order",
                AirplayMessages.offerOrder(mask).get(0) == AirplayMessages.PCM_CANDIDATES[1]
                        && AirplayMessages.offerOrder(mask).size()
                                == AirplayMessages.PCM_CANDIDATES.length);
        check("with nothing announced the reference order is kept",
                AirplayMessages.offerOrder(0).get(0) == AirplayMessages.PCM_CANDIDATES[0]
                        && AirplayMessages.formatFromMask(0) == null);
        check("a mask that allows nothing this side can send is not guessed at",
                AirplayMessages.formatFromMask(1) == null
                        && AirplayMessages.formatFromMask(0x40000000L) == null);
        java.util.Map<String, Bplist.Node> screenEntry = new java.util.LinkedHashMap<>();
        screenEntry.put("type", Bplist.Node.integer(AirplayMessages.SCREEN_STREAM_TYPE));
        screenEntry.put("audioOutputFormats", Bplist.Node.integer(2048));
        check("a mask on another kind of stream is not read as audio",
                AirplayMessages.audioOutputMask(streamsOf(screenEntry)) == 0);
        java.util.Map<String, Bplist.Node> speechEntry = new java.util.LinkedHashMap<>();
        speechEntry.put("type", Bplist.Node.integer(AirplayMessages.AUDIO_STREAM_TYPE));
        speechEntry.put("audioType", Bplist.Node.string("speechRecognition"));
        speechEntry.put("audioOutputFormats", Bplist.Node.integer(2048));
        check("and neither is the microphone stream's",
                AirplayMessages.audioOutputMask(streamsOf(speechEntry)) == 0);
        check("a reply with no streams names no formats",
                AirplayMessages.audioOutputMask(null) == 0
                        && AirplayMessages.audioOutputMask(
                                new java.util.LinkedHashMap<String, Bplist.Node>()) == 0);

        // the tone source follows the negotiated shape
        PcmToneSource tone = new PcmToneSource(440.0, 48000, 1, 240);
        tone.setSilent(false);
        check("the tone source uses the agreed rate and channels",
                tone.rate() == 48000 && tone.channels() == 1
                        && tone.frameSamples() == 240);
        check("one frame is rate-independent in size",
                tone.nextFrame().length == 240 * 1 * 2);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST AUDIO OK" : ("SELFTEST AUDIO FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** One reply carrying the given stream entry. */
    private static Map<String, Bplist.Node> streamsOf(java.util.Map<String, Bplist.Node> stream) {
        java.util.Map<String, Bplist.Node> reply = new java.util.LinkedHashMap<>();
        reply.put("streams", Bplist.Node.array(
                java.util.Collections.singletonList(Bplist.Node.dict(stream))));
        return reply;
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
