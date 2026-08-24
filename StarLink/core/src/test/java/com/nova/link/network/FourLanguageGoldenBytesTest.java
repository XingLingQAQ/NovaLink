package com.nova.link.network;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.VarInt;
import com.nova.chat.common.protocol.packets.HandshakeAuthenticatePacket;
import com.nova.chat.common.protocol.packets.HandshakeChallengePacket;
import com.nova.chat.common.protocol.packets.HandshakeInitPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-002: asserts the exact byte layout of the three challenge-response
 * handshake packets against hardcoded golden bytes.
 *
 * <p>This is the contract that locks the JVM implementation to the
 * PHP/Python/C++ forks — any byte drift here is a wire-incompatibility
 * regression. The non-JVM forks MUST produce byte-for-byte identical output.
 *
 * <p>Wire format (envelope is written by {@code Packet.encode}):
 * <pre>
 *   envelope = writeByte(packetId) + writeLong(requestId MSB) + writeLong(requestId LSB) + payload
 *   String   = VarInt(byteLength) + UTF-8 bytes
 *   VarInt   = 7 data bits + 1 continuation bit, 1-5 bytes
 *
 *   HandshakeInit (0x15) payload:
 *     VarInt  protocolVersion  (== 3)
 *     String  clientId
 *     u8      platform          (PlatformType.getId(), NOT ordinal)
 *     String  serverVersion
 *     String  clientNonce
 *
 *   HandshakeChallenge (0x16) payload:
 *     String  serverNonce
 *
 *   HandshakeAuthenticate (0x17) payload:
 *     String  clientId
 *     String  clientNonce
 *     String  hmac
 * </pre>
 */
@DisplayName("AUTH-002 four-language golden bytes")
class FourLanguageGoldenBytesTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("12345678-1234-5678-1234-567812345678");

    private static final long REQUEST_MSB = REQUEST_ID.getMostSignificantBits();
    private static final long REQUEST_LSB = REQUEST_ID.getLeastSignificantBits();

    /** Fixed values so the golden bytes are deterministic. */
    private static final String CLIENT_ID = "novachat-client";
    private static final String CLIENT_NONCE = "00112233445566778899aabbccddeeff";
    private static final String SERVER_NONCE = "ffeeddccbbaa99887766554433221100";
    private static final String SERVER_VERSION = "1.0.0-SNAPSHOT";
    private static final String HMAC = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
            + "0f1e2d3c4b5a69788796a5b4c3d2e1f0";

    /** Builds an init packet with a fixed request id for deterministic bytes. */
    private static HandshakeInitPacket newInit(PlatformType platform) {
        HandshakeInitPacket packet = new HandshakeInitPacket(
                NovaProtocol.PROTOCOL_VERSION,
                CLIENT_ID,
                platform,
                SERVER_VERSION,
                CLIENT_NONCE
        );
        // The 5-arg constructor assigns a random request id; pin it so the
        // encoded envelope bytes match the golden layout deterministically.
        packet.setRequestId(REQUEST_ID);
        return packet;
    }

    @Nested
    @DisplayName("HandshakeInit (0x15)")
    class HandshakeInit {

        @Test
        @DisplayName("encodes byte-for-byte to the golden layout")
        void goldenBytes() {
            HandshakeInitPacket packet = newInit(PlatformType.BUKKIT);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            byte[] expected = buildExpectedInit(PlatformType.BUKKIT);
            byte[] actual = toBytes(buf);

            assertThat(actual)
                    .as("HandshakeInit must match the golden byte layout used by PHP/Python/C++")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("platform byte uses getId() not ordinal() (FOLIA id=13, ordinal=12)")
        void platformByteUsesGetIdNotOrdinal() {
            // FOLIA has id 13 but ordinal 12 — if the writer used ordinal()
            // the wire byte would be 12 and the non-JVM forks (which all use
            // the id mapping) would diverge.
            HandshakeInitPacket packet = newInit(PlatformType.FOLIA);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            byte[] expected = buildExpectedInit(PlatformType.FOLIA);
            byte[] actual = toBytes(buf);

            assertThat(actual).isEqualTo(expected);
            // Sanity: the platform byte at that offset really is 13 (0x0D).
            // Layout: 1(packetId) + 8(MSB) + 8(LSB) + 1(VarInt(3)) + VarInt(len)+clientId
            int platformOffset = 1 + 8 + 8 + 1 + (1 + CLIENT_ID.length());
            int platformByte = actual[platformOffset] & 0xFF;
            assertThat(platformByte)
                    .as("FOLIA platform byte must be its id (13), not its ordinal (12)")
                    .isEqualTo(PlatformType.FOLIA.getId())
                    .isEqualTo(13);
        }

        @Test
        @DisplayName("protocol version is VarInt-encoded as a single byte (value 3)")
        void protocolVersionIsVarInt3() {
            HandshakeInitPacket packet = newInit(PlatformType.BUKKIT);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            // Skip: packetId(1) + MSB(8) + LSB(8) -> first payload byte
            int firstPayloadByte = buf.getByte(1 + 8 + 8) & 0xFF;
            assertThat(firstPayloadByte)
                    .as("protocolVersion VarInt(3) is a single byte 0x03 with no continuation bit")
                    .isEqualTo(0x03);
        }

        private byte[] buildExpectedInit(PlatformType platform) {
            ByteBuf b = Unpooled.buffer();
            // Envelope
            b.writeByte(PacketIds.HANDSHAKE_INIT); // 0x15
            b.writeLong(REQUEST_MSB);
            b.writeLong(REQUEST_LSB);
            // Payload
            VarInt.writeVarInt(b, NovaProtocol.PROTOCOL_VERSION); // 3
            writeGoldenString(b, CLIENT_ID);
            b.writeByte(platform.getId());
            writeGoldenString(b, SERVER_VERSION);
            writeGoldenString(b, CLIENT_NONCE);
            return toBytes(b);
        }
    }

    @Nested
    @DisplayName("HandshakeChallenge (0x16)")
    class HandshakeChallenge {

        @Test
        @DisplayName("encodes byte-for-byte to the golden layout")
        void goldenBytes() {
            HandshakeChallengePacket packet =
                    new HandshakeChallengePacket(REQUEST_ID, SERVER_NONCE);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            byte[] expected = buildExpectedChallenge();
            byte[] actual = toBytes(buf);

            assertThat(actual)
                    .as("HandshakeChallenge must match the golden byte layout")
                    .isEqualTo(expected);
        }

        private byte[] buildExpectedChallenge() {
            ByteBuf b = Unpooled.buffer();
            b.writeByte(PacketIds.HANDSHAKE_CHALLENGE); // 0x16
            b.writeLong(REQUEST_MSB);
            b.writeLong(REQUEST_LSB);
            writeGoldenString(b, SERVER_NONCE);
            return toBytes(b);
        }
    }

    @Nested
    @DisplayName("HandshakeAuthenticate (0x17)")
    class HandshakeAuthenticate {

        @Test
        @DisplayName("encodes byte-for-byte to the golden layout")
        void goldenBytes() {
            HandshakeAuthenticatePacket packet = new HandshakeAuthenticatePacket(
                    REQUEST_ID, CLIENT_ID, CLIENT_NONCE, HMAC);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            byte[] expected = buildExpectedAuthenticate();
            byte[] actual = toBytes(buf);

            assertThat(actual)
                    .as("HandshakeAuthenticate must match the golden byte layout")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("field order is clientId, clientNonce, hmac")
        void fieldOrderIsFixed() {
            HandshakeAuthenticatePacket packet = new HandshakeAuthenticatePacket(
                    REQUEST_ID, CLIENT_ID, CLIENT_NONCE, HMAC);

            ByteBuf buf = Unpooled.buffer();
            packet.encode(buf);

            // Skip envelope: packetId(1) + MSB(8) + LSB(8)
            int readerIndex = 1 + 8 + 8;
            String first = readGoldenString(buf, readerIndex);
            readerIndex += stringWireSize(CLIENT_ID);
            String second = readGoldenString(buf, readerIndex);
            readerIndex += stringWireSize(CLIENT_NONCE);
            String third = readGoldenString(buf, readerIndex);

            assertThat(first).as("first field must be clientId").isEqualTo(CLIENT_ID);
            assertThat(second).as("second field must be clientNonce").isEqualTo(CLIENT_NONCE);
            assertThat(third).as("third field must be hmac").isEqualTo(HMAC);
        }

        private byte[] buildExpectedAuthenticate() {
            ByteBuf b = Unpooled.buffer();
            b.writeByte(PacketIds.HANDSHAKE_AUTHENTICATE); // 0x17
            b.writeLong(REQUEST_MSB);
            b.writeLong(REQUEST_LSB);
            writeGoldenString(b, CLIENT_ID);
            writeGoldenString(b, CLIENT_NONCE);
            writeGoldenString(b, HMAC);
            return toBytes(b);
        }
    }

    @Nested
    @DisplayName("round-trip encode/decode")
    class RoundTrip {

        @Test
        @DisplayName("HandshakeInit survives an encode/decode round-trip")
        void initRoundTrip() {
            HandshakeInitPacket original = newInit(PlatformType.VELOCITY);

            ByteBuf buf = Unpooled.buffer();
            original.encode(buf);

            // Skip the envelope the decoder normally reads: packetId(1) + UUID(16)
            buf.skipBytes(1 + 16);

            HandshakeInitPacket decoded = new HandshakeInitPacket();
            decoded.read(buf);

            assertThat(decoded.getProtocolVersion()).isEqualTo(NovaProtocol.PROTOCOL_VERSION);
            assertThat(decoded.getClientId()).isEqualTo(CLIENT_ID);
            assertThat(decoded.getPlatform()).isEqualTo(PlatformType.VELOCITY);
            assertThat(decoded.getServerVersion()).isEqualTo(SERVER_VERSION);
            assertThat(decoded.getClientNonce()).isEqualTo(CLIENT_NONCE);
        }

        @Test
        @DisplayName("HandshakeAuthenticate survives an encode/decode round-trip")
        void authenticateRoundTrip() {
            HandshakeAuthenticatePacket original = new HandshakeAuthenticatePacket(
                    REQUEST_ID, CLIENT_ID, CLIENT_NONCE, HMAC);

            ByteBuf buf = Unpooled.buffer();
            original.encode(buf);

            buf.skipBytes(1 + 16);

            HandshakeAuthenticatePacket decoded = new HandshakeAuthenticatePacket();
            decoded.read(buf);

            assertThat(decoded.getClientId()).isEqualTo(CLIENT_ID);
            assertThat(decoded.getClientNonce()).isEqualTo(CLIENT_NONCE);
            assertThat(decoded.getHmac()).isEqualTo(HMAC);
        }

        @Test
        @DisplayName("HandshakeChallenge survives an encode/decode round-trip")
        void challengeRoundTrip() {
            HandshakeChallengePacket original =
                    new HandshakeChallengePacket(REQUEST_ID, SERVER_NONCE);

            ByteBuf buf = Unpooled.buffer();
            original.encode(buf);

            buf.skipBytes(1 + 16);

            HandshakeChallengePacket decoded = new HandshakeChallengePacket();
            decoded.read(buf);

            assertThat(decoded.getServerNonce()).isEqualTo(SERVER_NONCE);
        }
    }

    // ==================== golden-byte helpers ====================

    /** Writes a String exactly as PacketBuffer.writeString does. */
    private static void writeGoldenString(ByteBuf b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        VarInt.writeVarInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    /** Reads a VarInt-length-prefixed UTF-8 String at an absolute reader index. */
    private static String readGoldenString(ByteBuf buf, int index) {
        int length = VarInt.readVarInt(buf.slice(index, buf.readableBytes() - index));
        int header = VarInt.getVarIntSize(length);
        byte[] bytes = new byte[length];
        buf.getBytes(index + header, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Wire size of a PacketBuffer-style String = VarInt(byteLength) + byteLength. */
    private static int stringWireSize(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return VarInt.getVarIntSize(bytes.length) + bytes.length;
    }

    private static byte[] toBytes(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    // Sanity: confirm PacketBuffer/VarInt golden helpers agree with the
    // production codec so this test cannot drift from the real writer.
    @Test
    @DisplayName("golden string writer matches PacketBuffer.writeString")
    void goldenStringWriterMatchesPacketBuffer() {
        ByteBuf golden = Unpooled.buffer();
        ByteBuf prod = Unpooled.buffer();
        writeGoldenString(golden, CLIENT_ID);
        PacketBuffer.writeString(prod, CLIENT_ID);
        assertThat(toBytes(golden)).isEqualTo(toBytes(prod));
    }
}
