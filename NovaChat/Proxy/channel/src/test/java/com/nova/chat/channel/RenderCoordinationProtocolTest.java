package com.nova.chat.channel;

import com.nova.chat.channel.RenderCoordinationProtocol.Frame;
import com.nova.chat.channel.RenderCoordinationProtocol.Kind;
import com.nova.chat.channel.RenderCoordinationProtocol.Offer;
import com.nova.chat.channel.RenderCoordinationProtocol.OfferPart;
import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderCoordinationProtocolTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final UUID ZERO = new UUID(0, 0);
    private static final UUID PLAYER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final UUID PROXY = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BUKKIT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REQUEST = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final int BODY_BYTES = 73;
    private static final int PLAYER_OFFSET = 4;
    private static final int PROXY_OFFSET = 20;
    private static final int BUKKIT_OFFSET = 36;
    private static final int REQUEST_OFFSET = 52;
    private static final int PACKET_OFFSET = 68;
    private static final int OWNER_OFFSET = 72;
    private static final int OFFER_OVERHEAD_BYTES = 109;
    private static final int PART_OVERHEAD_BYTES = 117;

    @Test
    void roundTripsEachKindAndSupportedPacket() throws Exception {
        Frame[] frames = {
                frame(Kind.PROBE, ZERO, ZERO, 0, false),
                frame(Kind.HELLO, BUKKIT, ZERO, 0, false),
                frame(Kind.CONFIRM, BUKKIT, ZERO, 0, false),
                frame(Kind.CONFIRMED, BUKKIT, ZERO, 0, false),
                frame(Kind.CLAIM, BUKKIT, REQUEST, 3, false),
                frame(Kind.SKIP, BUKKIT, REQUEST, 3, false),
                frame(Kind.DECISION, BUKKIT, REQUEST, 9, false),
                frame(Kind.DECISION, BUKKIT, REQUEST, 9, true)
        };
        for (Frame frame : frames) {
            assertThat(RenderCoordinationProtocol.decode(RenderCoordinationProtocol.encode(frame, SECRET), SECRET))
                    .isEqualTo(frame);
        }
        for (Kind kind : new Kind[]{Kind.CLAIM, Kind.SKIP}) {
            for (int id : new int[]{3, 9, 16, 18, 20}) {
                Frame frame = frame(kind, BUKKIT, REQUEST, id, false);
                assertThat(RenderCoordinationProtocol.decode(RenderCoordinationProtocol.encode(frame, SECRET), SECRET))
                        .isEqualTo(frame);
            }
        }
    }

    @Test
    void wireLayoutAndMacMatchIndependentEncoding() throws Exception {
        Frame frame = frame(Kind.DECISION, BUKKIT, REQUEST, 9, true);
        byte[] payload = RenderCoordinationProtocol.encode(frame, SECRET);
        ByteBuffer expected = ByteBuffer.allocate(BODY_BYTES);
        expected.put((byte) 'N').put((byte) 'R').put((byte) 1).put((byte) 3);
        for (UUID id : new UUID[]{PLAYER, PROXY, BUKKIT, REQUEST}) {
            expected.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        }
        expected.putInt(9).put((byte) 1);
        assertThat(payload).hasSize(105);
        assertThat(Arrays.copyOf(payload, BODY_BYTES)).isEqualTo(expected.array());
        assertThat(Arrays.copyOfRange(payload, BODY_BYTES, payload.length)).isEqualTo(sign(expected.array(), SECRET));
        assertThat(RenderCoordinationProtocol.CHANNEL_ID).isEqualTo("novachat:render");
        assertThat(RenderCoordinationProtocol.VERSION).isEqualTo(1);
        assertThat(Kind.values()).containsExactly(Kind.PROBE, Kind.HELLO, Kind.CLAIM, Kind.DECISION,
                Kind.CONFIRM, Kind.CONFIRMED, Kind.OFFER, Kind.SKIP, Kind.OFFER_PART);
        assertThat(RenderCoordinationProtocol.MAX_PART_DATA_BYTES).isEqualTo(32767 - 117);
    }

    @Test
    void offerPartRoundTripsBoundaryChunksWithFullMacAndDefensiveCopies() throws Exception {
        Frame frame = frame(Kind.OFFER_PART, BUKKIT, REQUEST, 3, false);
        int maximum = ProtocolLimits.MAX_FRAME_LENGTH;
        int chunkMaximum = RenderCoordinationProtocol.MAX_PART_DATA_BYTES;
        int[][] boundaries = {{1, 0, 1}, {maximum, 0, chunkMaximum},
                {maximum, maximum - chunkMaximum, chunkMaximum}, {maximum, maximum - 1, 1}};
        for (int[] bounds : boundaries) {
            byte[] data = new byte[bounds[2]];
            Arrays.fill(data, (byte) 0x5a);
            byte[] expected = data.clone();
            OfferPart part = new OfferPart(frame, bounds[0], bounds[1], data);
            byte[] payload = RenderCoordinationProtocol.encodeOfferPart(frame, bounds[0], bounds[1], data, SECRET);
            assertThat(payload).hasSize(PART_OVERHEAD_BYTES + data.length);
            assertThat(payload[3]).isEqualTo((byte) 8);
            ByteBuffer fields = ByteBuffer.wrap(payload).position(BODY_BYTES);
            assertThat(fields.getInt()).isEqualTo(bounds[0]);
            assertThat(fields.getInt()).isEqualTo(bounds[1]);
            assertThat(fields.getInt()).isEqualTo(data.length);
            assertThat(Arrays.copyOfRange(payload, BODY_BYTES + 3 * Integer.BYTES, payload.length - 32))
                    .isEqualTo(expected);
            assertThat(Arrays.copyOfRange(payload, payload.length - 32, payload.length))
                    .isEqualTo(sign(Arrays.copyOf(payload, payload.length - 32), SECRET));
            OfferPart decoded = RenderCoordinationProtocol.decodeOfferPart(payload, SECRET);
            assertThat(decoded.frame()).isEqualTo(frame);
            assertThat(decoded.totalLength()).isEqualTo(bounds[0]);
            assertThat(decoded.offset()).isEqualTo(bounds[1]);
            data[0] ^= 1;
            part.data()[0] ^= 1;
            decoded.data()[0] ^= 1;
            Arrays.fill(payload, (byte) 0);
            assertThat(part.data()).isEqualTo(expected);
            assertThat(decoded.data()).isEqualTo(expected);
        }
    }

    @Test
    void offerPartRejectsInvalidBoundsBeforeDataAllocation() throws Exception {
        Frame frame = frame(Kind.OFFER_PART, BUKKIT, REQUEST, 3, false);
        int maximum = ProtocolLimits.MAX_FRAME_LENGTH;
        int[][] invalid = {{-1, 0, 3}, {0, 0, 3}, {maximum + 1, 0, 3}, {Integer.MAX_VALUE, 0, 3},
                {3, -1, 3}, {3, 1, 3}, {maximum, Integer.MAX_VALUE, 3},
                {3, 0, 0}, {3, 0, -1}, {3, 0, Integer.MAX_VALUE},
                {maximum, 0, RenderCoordinationProtocol.MAX_PART_DATA_BYTES + 1}};
        for (int[] bounds : invalid) {
            if (bounds[2] >= 0 && bounds[2] <= RenderCoordinationProtocol.MAX_PART_DATA_BYTES + 1) {
                assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOfferPart(
                        frame, bounds[0], bounds[1], new byte[bounds[2]], SECRET))
                        .isInstanceOf(ProxyChannelException.class);
            }
            assertInvalidSigned(Kind.OFFER_PART, b -> ByteBuffer.wrap(b).position(BODY_BYTES)
                    .putInt(bounds[0]).putInt(bounds[1]).putInt(bounds[2]));
        }
        assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOfferPart(frame, 1, 0, null, SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOfferPart(new byte[32768], null))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("part length");
    }

    @Test
    void offerPartAuthenticatesBeforeParsingAndRejectsTruncationAndTrailingBytes() throws Exception {
        byte[] payload = encoded(Kind.OFFER_PART);
        assertOfferPartRejected(null, SECRET);
        for (int length : new int[]{0, 1, 73, 105, 117, payload.length - 1}) {
            assertOfferPartRejected(Arrays.copyOf(payload, length), SECRET);
        }
        assertOfferPartRejected(payload, "x".repeat(32));
        assertOfferPartRejected(payload, "short");
        for (int i = 0; i < payload.length; i++) {
            byte[] tampered = payload.clone();
            tampered[i] ^= 1;
            assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOfferPart(tampered, SECRET))
                    .isInstanceOf(ProxyChannelException.class).hasMessageContaining("authentication");
        }
        assertInvalidSigned(Kind.OFFER_PART, b -> b[2] = 2);
        assertInvalidSigned(Kind.OFFER_PART, b -> b[3] = (byte) Kind.values().length);
        assertInvalidSigned(Kind.OFFER_PART, b -> b[OWNER_OFFSET] = 2);
        byte[] trailing = Arrays.copyOf(payload, payload.length + 1);
        assertOfferPartRejected(trailing, SECRET);
        resign(trailing);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOfferPart(trailing, SECRET))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("data length");
    }

    @Test
    void offerPartRequiresDedicatedCodecAndKind() throws Exception {
        Frame part = frame(Kind.OFFER_PART, BUKKIT, REQUEST, 3, false);
        assertThatThrownBy(() -> RenderCoordinationProtocol.encode(part, SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertRejected(encoded(Kind.OFFER_PART), SECRET);
        byte[] fixedPart = encoded(Kind.CLAIM);
        fixedPart[3] = (byte) Kind.OFFER_PART.ordinal();
        resign(fixedPart);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decode(fixedPart, SECRET))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("decodeOfferPart");
        assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOfferPart(
                frame(Kind.OFFER, BUKKIT, REQUEST, 3, false), 1, 0, new byte[1], SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertInvalidSigned(Kind.OFFER_PART, b -> b[3] = (byte) Kind.OFFER.ordinal());
    }

    @Test
    void offerWrapsUnchangedV1EnvelopeWithFullOuterMac() throws Exception {
        byte[] envelope = legacyEnvelope();
        for (int id : new int[]{3, 9, 16, 18, 20}) {
            Frame frame = frame(Kind.OFFER, BUKKIT, REQUEST, id, false);
            byte[] payload = RenderCoordinationProtocol.encodeOffer(frame, envelope, SECRET);
            Offer decoded = RenderCoordinationProtocol.decodeOffer(payload, SECRET);
            assertThat(decoded.frame()).isEqualTo(frame);
            assertThat(decoded.envelope()).isEqualTo(envelope);
            assertThat(payload).hasSize(OFFER_OVERHEAD_BYTES + envelope.length);
            assertThat(ByteBuffer.wrap(payload).getInt(BODY_BYTES)).isEqualTo(envelope.length);
            assertThat(Arrays.copyOfRange(payload, BODY_BYTES + Integer.BYTES, payload.length - 32))
                    .isEqualTo(envelope);
            assertThat(Arrays.copyOfRange(payload, payload.length - 32, payload.length))
                    .isEqualTo(sign(Arrays.copyOf(payload, payload.length - 32), SECRET));
            assertThat(((ChatMessagePacket) ProxyChannelProtocol.decode(decoded.envelope()).getPacket()).getContent())
                    .isEqualTo("hello");
            assertRejected(payload, SECRET);
        }
    }

    @Test
    void offerCopiesEnvelopeOnConstructionAndAccess() throws Exception {
        Frame frame = frame(Kind.OFFER, BUKKIT, REQUEST, 3, false);
        byte[] envelope = legacyEnvelope();
        byte[] expected = envelope.clone();
        Offer offer = new Offer(frame, envelope);
        byte[] payload = RenderCoordinationProtocol.encodeOffer(frame, envelope, SECRET);
        envelope[0] ^= 1;
        assertThat(offer.envelope()).isEqualTo(expected);
        offer.envelope()[0] ^= 1;
        assertThat(offer.envelope()).isEqualTo(expected);
        Offer decoded = RenderCoordinationProtocol.decodeOffer(payload, SECRET);
        decoded.envelope()[0] ^= 1;
        Arrays.fill(payload, (byte) 0);
        assertThat(decoded.envelope()).isEqualTo(expected);
    }

    @Test
    void offerChecksPayloadCapBeforeAuthenticationAndLengthFieldAfterAuthentication() throws Exception {
        Frame frame = frame(Kind.OFFER, BUKKIT, REQUEST, 3, false);
        byte[] maximum = new byte[32767 - OFFER_OVERHEAD_BYTES];
        byte[] payload = RenderCoordinationProtocol.encodeOffer(frame, maximum, SECRET);
        assertThat(payload).hasSize(32767);
        assertThat(RenderCoordinationProtocol.decodeOffer(payload, SECRET).envelope()).isEqualTo(maximum);
        assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOffer(frame, new byte[maximum.length + 1], SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOffer(frame, new byte[0], SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOffer(frame, null, SECRET))
                .isInstanceOf(ProxyChannelException.class);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOffer(new byte[32768], null))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("offer length");
        for (int length : new int[]{-1, 0, 1, Integer.MAX_VALUE}) {
            assertInvalidSigned(Kind.OFFER, b -> ByteBuffer.wrap(b).putInt(BODY_BYTES, length));
        }
    }

    @Test
    void offerRejectsTamperingWrongKeysTruncationAndTrailingBytes() throws Exception {
        byte[] payload = encoded(Kind.OFFER);
        assertOfferRejected(null, SECRET);
        for (int length = 0; length < payload.length; length++) {
            assertOfferRejected(Arrays.copyOf(payload, length), SECRET);
        }
        assertOfferRejected(Arrays.copyOf(payload, payload.length + 1), SECRET);
        assertOfferRejected(payload, "x".repeat(32));
        for (int i = 0; i < payload.length; i++) {
            byte[] tampered = payload.clone();
            tampered[i] ^= 1;
            assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOffer(tampered, SECRET))
                    .isInstanceOf(ProxyChannelException.class).hasMessageContaining("authentication");
        }
        byte[] trailing = Arrays.copyOf(payload, payload.length + 1);
        resign(trailing);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOffer(trailing, SECRET))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("envelope length");
    }

    @Test
    void fixedAndOfferCodecsRejectEachOthersFrameShapes() throws Exception {
        Frame offer = frame(Kind.OFFER, BUKKIT, REQUEST, 3, false);
        assertThatThrownBy(() -> RenderCoordinationProtocol.encode(offer, SECRET))
                .isInstanceOf(ProxyChannelException.class);
        for (Kind kind : Kind.values()) {
            if (kind != Kind.OFFER) {
                byte[] payload = encoded(kind);
                Frame other = kind == Kind.OFFER_PART
                        ? RenderCoordinationProtocol.decodeOfferPart(payload, SECRET).frame()
                        : RenderCoordinationProtocol.decode(payload, SECRET);
                assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOffer(
                        other, legacyEnvelope(), SECRET))
                        .isInstanceOf(ProxyChannelException.class);
                assertInvalidSigned(Kind.OFFER, b -> System.arraycopy(payload, 0, b, 0, BODY_BYTES));
            }
        }
        byte[] fixedOffer = encoded(Kind.CLAIM);
        fixedOffer[3] = (byte) Kind.OFFER.ordinal();
        resign(fixedOffer);
        assertThatThrownBy(() -> RenderCoordinationProtocol.decode(fixedOffer, SECRET))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("decodeOffer");
        assertOfferRejected(fixedOffer, SECRET);
    }

    @Test
    void existingV1CodecStillRoundTrips() throws Exception {
        ChatMessagePacket packet = new ChatMessagePacket(PLAYER, "Alice", "server", "global", "hello");
        byte[] encoded = ProxyChannelProtocol.encode("server", packet);
        ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(encoded);
        assertThat(decoded.getSenderId()).isEqualTo("server");
        assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);
        assertThat(((ChatMessagePacket) decoded.getPacket()).getContent()).isEqualTo("hello");
    }

    @Test
    void rejectsTamperingWrongKeyAndWrongLengthBeforeParsing() throws Exception {
        byte[] valid = encoded(Kind.CLAIM);
        assertRejected(null, SECRET);
        for (int length = 0; length < valid.length; length++) {
            assertRejected(Arrays.copyOf(valid, length), SECRET);
        }
        assertRejected(Arrays.copyOf(valid, valid.length + 1), SECRET);
        assertRejected(valid, "x".repeat(32));
        for (int i = 0; i < valid.length; i++) {
            byte[] tampered = valid.clone();
            tampered[i] ^= 1;
            assertRejected(tampered, SECRET);
        }
        byte[] badVersionWithBadMac = valid.clone();
        badVersionWithBadMac[2] = 2;
        assertThatThrownBy(() -> RenderCoordinationProtocol.decode(badVersionWithBadMac, SECRET))
                .isInstanceOf(ProxyChannelException.class).hasMessageContaining("authentication");
    }

    @Test
    void rejectsAuthenticatedInvalidHeadersAndBoolean() throws Exception {
        assertInvalidSigned(Kind.CLAIM, b -> b[0] = 'X');
        assertInvalidSigned(Kind.CLAIM, b -> b[1] = 'X');
        assertInvalidSigned(Kind.CLAIM, b -> b[2] = 2);
        assertInvalidSigned(Kind.CLAIM, b -> b[3] = (byte) Kind.values().length);
        assertInvalidSigned(Kind.CLAIM, b -> b[3] = (byte) 255);
        assertInvalidSigned(Kind.DECISION, b -> b[OWNER_OFFSET] = 2);
        assertInvalidSigned(Kind.DECISION, b -> b[OWNER_OFFSET] = (byte) 255);
        for (Kind kind : new Kind[]{Kind.PROBE, Kind.HELLO, Kind.CLAIM, Kind.CONFIRM, Kind.CONFIRMED,
                Kind.OFFER, Kind.SKIP, Kind.OFFER_PART}) {
            assertInvalidSigned(kind, b -> b[OWNER_OFFSET] = 1);
        }
        assertInvalidSigned(Kind.OFFER, b -> b[0] = 'X');
        assertInvalidSigned(Kind.OFFER, b -> b[1] = 'X');
        assertInvalidSigned(Kind.OFFER, b -> b[2] = 2);
        assertInvalidSigned(Kind.OFFER, b -> b[3] = (byte) Kind.values().length);
        assertInvalidSigned(Kind.OFFER, b -> b[OWNER_OFFSET] = 2);
    }

    @Test
    void rejectsAuthenticatedInvalidIdentifiersPlaceholdersAndPacketIds() throws Exception {
        for (Kind kind : Kind.values()) {
            assertInvalidSigned(kind, b -> zeroUuid(b, PLAYER_OFFSET));
            assertInvalidSigned(kind, b -> zeroUuid(b, PROXY_OFFSET));
        }
        assertInvalidSigned(Kind.PROBE, b -> putUuid(b, BUKKIT_OFFSET, BUKKIT));
        for (Kind kind : new Kind[]{Kind.PROBE, Kind.HELLO, Kind.CONFIRM, Kind.CONFIRMED}) {
            assertInvalidSigned(kind, b -> putUuid(b, REQUEST_OFFSET, REQUEST));
            assertInvalidSigned(kind, b -> ByteBuffer.wrap(b).putInt(PACKET_OFFSET, 3));
        }
        for (Kind kind : new Kind[]{Kind.HELLO, Kind.CLAIM, Kind.DECISION, Kind.CONFIRM, Kind.CONFIRMED,
                Kind.OFFER, Kind.SKIP, Kind.OFFER_PART}) {
            assertInvalidSigned(kind, b -> zeroUuid(b, BUKKIT_OFFSET));
        }
        for (Kind kind : new Kind[]{Kind.CLAIM, Kind.DECISION, Kind.OFFER, Kind.SKIP, Kind.OFFER_PART}) {
            assertInvalidSigned(kind, b -> zeroUuid(b, REQUEST_OFFSET));
            for (int id : new int[]{-1, 0, 4, 8, 10, 17, 19, 21, Integer.MAX_VALUE}) {
                assertInvalidSigned(kind, b -> ByteBuffer.wrap(b).putInt(PACKET_OFFSET, id));
            }
        }
    }

    @Test
    void encodingRejectsInvalidFrames() throws Exception {
        Frame[] invalid = {
                null, new Frame(null, PLAYER, PROXY, BUKKIT, REQUEST, 3, false),
                new Frame(Kind.CLAIM, null, PROXY, BUKKIT, REQUEST, 3, false),
                new Frame(Kind.CLAIM, ZERO, PROXY, BUKKIT, REQUEST, 3, false),
                new Frame(Kind.CLAIM, PLAYER, null, BUKKIT, REQUEST, 3, false),
                new Frame(Kind.CLAIM, PLAYER, PROXY, null, REQUEST, 3, false),
                new Frame(Kind.CLAIM, PLAYER, PROXY, BUKKIT, null, 3, false),
                frame(Kind.PROBE, BUKKIT, ZERO, 0, false),
                frame(Kind.PROBE, ZERO, REQUEST, 0, false),
                frame(Kind.PROBE, ZERO, ZERO, 3, false),
                frame(Kind.HELLO, ZERO, ZERO, 0, false),
                frame(Kind.HELLO, BUKKIT, REQUEST, 0, false),
                frame(Kind.HELLO, BUKKIT, ZERO, 9, false),
                frame(Kind.CLAIM, ZERO, REQUEST, 3, false),
                frame(Kind.CLAIM, BUKKIT, ZERO, 3, false),
                frame(Kind.CLAIM, BUKKIT, REQUEST, 4, false),
                frame(Kind.DECISION, BUKKIT, REQUEST, 0, false),
                frame(Kind.PROBE, ZERO, ZERO, 0, true),
                frame(Kind.HELLO, BUKKIT, ZERO, 0, true),
                frame(Kind.CLAIM, BUKKIT, REQUEST, 3, true)
        };
        for (Frame frame : invalid) {
            assertThatThrownBy(() -> RenderCoordinationProtocol.encode(frame, SECRET))
                    .isInstanceOf(ProxyChannelException.class);
        }
        byte[] envelope = legacyEnvelope();
        for (Kind kind : new Kind[]{Kind.OFFER, Kind.SKIP, Kind.OFFER_PART}) {
            Frame[] invalidRequests = {
                    null, new Frame(null, PLAYER, PROXY, BUKKIT, REQUEST, 3, false),
                    new Frame(kind, null, PROXY, BUKKIT, REQUEST, 3, false),
                    new Frame(kind, ZERO, PROXY, BUKKIT, REQUEST, 3, false),
                    new Frame(kind, PLAYER, null, BUKKIT, REQUEST, 3, false),
                    new Frame(kind, PLAYER, ZERO, BUKKIT, REQUEST, 3, false),
                    frame(kind, null, REQUEST, 3, false), frame(kind, ZERO, REQUEST, 3, false),
                    frame(kind, BUKKIT, null, 3, false), frame(kind, BUKKIT, ZERO, 3, false),
                    frame(kind, BUKKIT, REQUEST, 4, false), frame(kind, BUKKIT, REQUEST, 3, true)
            };
            for (Frame frame : invalidRequests) {
                assertThatThrownBy(() -> {
                    if (kind == Kind.OFFER) {
                        RenderCoordinationProtocol.encodeOffer(frame, envelope, SECRET);
                    } else if (kind == Kind.OFFER_PART) {
                        RenderCoordinationProtocol.encodeOfferPart(frame, 1, 0, new byte[1], SECRET);
                    } else {
                        RenderCoordinationProtocol.encode(frame, SECRET);
                    }
                }).isInstanceOf(ProxyChannelException.class);
            }
        }
    }

    @Test
    void secretLengthIsMeasuredInUtf8Bytes() throws Exception {
        Frame frame = frame(Kind.PROBE, ZERO, ZERO, 0, false);
        for (String secret : new String[]{null, "", "a".repeat(31), "界".repeat(10), "a".repeat(4097)}) {
            assertThatThrownBy(() -> RenderCoordinationProtocol.encode(frame, secret))
                    .isInstanceOf(ProxyChannelException.class);
            assertRejected(encoded(Kind.PROBE), secret);
            assertOfferRejected(encoded(Kind.OFFER), secret);
            assertThatThrownBy(() -> RenderCoordinationProtocol.encodeOffer(
                    frame(Kind.OFFER, BUKKIT, REQUEST, 3, false), legacyEnvelope(), secret))
                    .isInstanceOf(ProxyChannelException.class);
        }
        for (String secret : new String[]{"a".repeat(32), "界".repeat(11), "a".repeat(4096)}) {
            assertThat(RenderCoordinationProtocol.decode(RenderCoordinationProtocol.encode(frame, secret), secret))
                    .isEqualTo(frame);
            Frame offer = frame(Kind.OFFER, BUKKIT, REQUEST, 3, false);
            assertThat(RenderCoordinationProtocol.decodeOffer(
                    RenderCoordinationProtocol.encodeOffer(offer, legacyEnvelope(), secret), secret).frame())
                    .isEqualTo(offer);
        }
    }

    private static Frame frame(Kind kind, UUID bukkit, UUID request, int packet, boolean owner) {
        return new Frame(kind, PLAYER, PROXY, bukkit, request, packet, owner);
    }

    private static byte[] encoded(Kind kind) throws Exception {
        Frame frame = switch (kind) {
            case PROBE -> frame(kind, ZERO, ZERO, 0, false);
            case HELLO, CONFIRM, CONFIRMED -> frame(kind, BUKKIT, ZERO, 0, false);
            case CLAIM, DECISION, OFFER, SKIP, OFFER_PART -> frame(kind, BUKKIT, REQUEST, 3, false);
        };
        if (kind == Kind.OFFER) {
            return RenderCoordinationProtocol.encodeOffer(frame, legacyEnvelope(), SECRET);
        }
        if (kind == Kind.OFFER_PART) {
            return RenderCoordinationProtocol.encodeOfferPart(frame, 3, 0, new byte[]{1, 2, 3}, SECRET);
        }
        return RenderCoordinationProtocol.encode(frame, SECRET);
    }

    private static byte[] legacyEnvelope() throws Exception {
        return ProxyChannelProtocol.encode("server",
                new ChatMessagePacket(PLAYER, "Alice", "server", "global", "hello"));
    }

    private static void assertInvalidSigned(Kind kind, Consumer<byte[]> mutate) throws Exception {
        byte[] payload = encoded(kind);
        mutate.accept(payload);
        resign(payload);
        assertThatThrownBy(() -> {
            if (kind == Kind.OFFER) {
                RenderCoordinationProtocol.decodeOffer(payload, SECRET);
            } else if (kind == Kind.OFFER_PART) {
                RenderCoordinationProtocol.decodeOfferPart(payload, SECRET);
            } else {
                RenderCoordinationProtocol.decode(payload, SECRET);
            }
        })
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageNotContaining("authentication");
    }

    private static void resign(byte[] payload) throws Exception {
        int bodyBytes = payload.length - 32;
        byte[] mac = sign(Arrays.copyOf(payload, bodyBytes), SECRET);
        System.arraycopy(mac, 0, payload, bodyBytes, mac.length);
    }

    private static byte[] sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    private static void zeroUuid(byte[] bytes, int offset) {
        Arrays.fill(bytes, offset, offset + 16, (byte) 0);
    }

    private static void putUuid(byte[] bytes, int offset, UUID id) {
        ByteBuffer.wrap(bytes).position(offset).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static void assertRejected(byte[] payload, String secret) {
        assertThatThrownBy(() -> RenderCoordinationProtocol.decode(payload, secret))
                .isInstanceOf(ProxyChannelException.class);
    }

    private static void assertOfferRejected(byte[] payload, String secret) {
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOffer(payload, secret))
                .isInstanceOf(ProxyChannelException.class);
    }

    private static void assertOfferPartRejected(byte[] payload, String secret) {
        assertThatThrownBy(() -> RenderCoordinationProtocol.decodeOfferPart(payload, secret))
                .isInstanceOf(ProxyChannelException.class);
    }
}
