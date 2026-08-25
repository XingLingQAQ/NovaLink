package com.nova.chat.common.protocol.packets;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIFY-002 runtime evidence for the {@link ChannelActionPacket} legacy JSON
 * {@code extra} fallback (audit ref: docs/PRODUCTION_READINESS_AND_PRODUCT_PLAN.md §7).
 *
 * <p>{@link ChannelActionPacket#read(io.netty.buffer.ByteBuf)} carries no
 * protocol-version field, so the decoder guesses the wire format per frame: it
 * optimistically reads a VarInt pair count guarded by
 * {@code size < 0 || size > 64}, and on <em>any</em> exception rewinds the
 * reader and retries the whole remaining section as one length-prefixed JSON
 * object string. These fixtures pin the observable behavior of both branches
 * at the boundaries called out by the audit doc.
 *
 * <p><b>Maintainer fixture note — legacy-format deprecation recommendation.</b>
 * Audit §7 asks to 明确协议版本和旧格式淘汰时间 ("state the protocol version and
 * the retirement date of the old format"). Recommendation: because
 * ChannelActionPacket itself has no version field, gate the legacy JSON branch
 * on the handshake-era protocol version ({@code NovaProtocol.PROTOCOL_VERSION},
 * currently v3 — pre-v3 clients are the only plausible legacy-JSON senders):
 * (1) log a WARN with the client id on every fallback hit so deployment
 * telemetry accumulates; (2) announce removal of the fallback at v4 and keep
 * one LTS window (about two quarters) of warn-only observation; (3) delete the
 * fallback and turn the guard into a hard reject of
 * {@code size < 0 || size > 64} in the release after telemetry flatlines at
 * zero. Until then the trade-offs pinned by
 * {@code HeuristicGuardBoundary} (silent extra loss and mid-frame desync for
 * modern frames with 65+ pairs) remain accepted, documented limits.
 *
 * <p><b>Honest boundary (VERIFY-002 scope).</b> Static analysis cannot
 * enumerate deployed legacy clients. These tests pin decoder behavior for both
 * wire formats reproducibly on the JVM; they do not prove whether any
 * legacy-JSON sender still exists in the wild — that requires the telemetry
 * recommended above.
 */
@DisplayName("ChannelActionPacket legacy JSON extra fallback (VERIFY-002)")
class ChannelActionLegacyExtraFallbackTest {

    /**
     * Legacy-format fixture: a JSON object short enough (46 UTF-8 bytes, &le; 64)
     * that the modern pair-parse is attempted FIRST and must fail on truncated
     * framing before the fallback gets its turn.
     */
    private static final String LEGACY_SHORT_JSON =
            "{\"display_name\":\"Old Client\",\"scope\":\"SERVER\"}";

    /**
     * Legacy-format fixture: a JSON object longer than 64 UTF-8 bytes, so the
     * heuristic guard rejects the leading VarInt outright and only the fallback
     * branch can recover the data.
     */
    private static final String LEGACY_LONG_JSON =
            "{\"display_name\":\"Ancient Pre-Varint Client Build 2019\",\"scope\":\"SERVER\",\"motd\":\"legacy hello\"}";

    private PacketRegistry registry;
    private ByteBuf buf;

    @BeforeEach
    void setUp() {
        registry = NovaProtocol.createRegistry();
        buf = Unpooled.buffer();
    }

    @AfterEach
    void tearDown() {
        if (buf != null) {
            buf.release();
        }
    }

    // ==================== Helpers ====================

    /**
     * Writes a full ChannelActionPacket frame header (packet id, request id,
     * action, channel id, password) exactly as {@code Packet.encode} +
     * {@code ChannelActionPacket.write} would, leaving the buffer positioned at
     * the start of the optional {@code extra} section.
     */
    private void writeFrameHeader(ByteBuf out, UUID requestId, ChannelAction action,
                                  String channelId, String password) {
        out.writeByte(PacketIds.CHANNEL_ACTION);
        out.writeLong(requestId.getMostSignificantBits());
        out.writeLong(requestId.getLeastSignificantBits());
        out.writeByte(action.getId());
        PacketBuffer.writeString(out, channelId);
        PacketBuffer.writeString(out, password);
    }

    /**
     * Byte offset of the extra section within a frame with the given header
     * fields (request ids are a fixed 16 bytes, so the offset is deterministic).
     */
    private int extraSectionOffset(ChannelAction action, String channelId, String password) {
        ByteBuf scratch = Unpooled.buffer();
        try {
            writeFrameHeader(scratch, UUID.randomUUID(), action, channelId, password);
            return scratch.readableBytes();
        } finally {
            scratch.release();
        }
    }

    private ChannelActionPacket decodeSingle(ByteBuf source) {
        Packet decoded = registry.decode(source);
        assertThat(decoded).isInstanceOf(ChannelActionPacket.class);
        return (ChannelActionPacket) decoded;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, String> legacyExpectedMap() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("display_name", "Old Client");
        expected.put("scope", "SERVER");
        return expected;
    }

    // ==================== Legacy JSON fallback ====================

    @Nested
    @DisplayName("Legacy JSON extra fallback")
    class LegacyJsonFallbackTests {

        @Test
        @DisplayName("Should decode a short legacy JSON extra object through the fallback")
        void shouldDecodeShortLegacyJsonExtra() {
            // Preconditions documenting why this frame exercises the rewind path:
            // the JSON byte length (46) passes the <= 64 guard as a "pair count",
            // then the first JSON byte '{' (123) is rejected as a key length
            // because only 45 bytes remain -> modern attempt throws -> rewind.
            assertThat(utf8Length(LEGACY_SHORT_JSON)).isLessThanOrEqualTo(64);

            writeFrameHeader(buf, UUID.randomUUID(), ChannelAction.JOIN, "global", "");
            PacketBuffer.writeString(buf, LEGACY_SHORT_JSON);

            ChannelActionPacket result = decodeSingle(buf);

            assertThat(result.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(result.getChannelId()).isEqualTo("global");
            assertThat(result.getExtra()).containsAllEntriesOf(legacyExpectedMap());
            assertThat(result.getExtra()).hasSize(2);
            // The fallback must consume exactly the legacy string: nothing left over.
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should decode a long legacy JSON extra (> 64 bytes) after guard rejection")
        void shouldDecodeLongLegacyJsonExtraAfterGuardRejection() {
            // Length above 64 makes the guard throw before any pair is read;
            // the fallback then reads the whole string and parses it.
            assertThat(utf8Length(LEGACY_LONG_JSON)).isGreaterThan(64);

            writeFrameHeader(buf, UUID.randomUUID(), ChannelAction.CREATE, "legacy-chan", "");
            PacketBuffer.writeString(buf, LEGACY_LONG_JSON);

            ChannelActionPacket result = decodeSingle(buf);

            assertThat(result.getAction()).isEqualTo(ChannelAction.CREATE);
            assertThat(result.getExtra())
                    .containsEntry("display_name", "Ancient Pre-Varint Client Build 2019")
                    .containsEntry("scope", "SERVER")
                    .containsEntry("motd", "legacy hello");
            assertThat(result.getExtra()).hasSize(3);
            assertThat(buf.readableBytes()).isZero();
        }
    }

    // ==================== Malformed extra resilience ====================

    @Nested
    @DisplayName("Malformed extra resilience")
    class MalformedExtraResilience {

        @Test
        @DisplayName("Should degrade malformed extra to an empty map without throwing and keep the stream aligned")
        void shouldDegradeMalformedExtraGracefullyAndKeepStreamAligned() {
            writeFrameHeader(buf, UUID.randomUUID(), ChannelAction.JOIN, "global", "");

            // Neither valid modern pairs nor valid JSON:
            // VarInt(4) + raw bytes 81 02 41 42.
            // Modern attempt: size=4 (ok), then the first key length VarInt is
            // 0x81 0x02 -> 257 > MAX_METADATA_KEY(128) -> throw -> rewind.
            // Fallback: readString(8192) succeeds reading the same 4 raw bytes
            // as a non-JSON string -> parseJsonToMap best-effort -> empty map,
            // having consumed exactly the 5 extra-section bytes.
            PacketBuffer.writeVarInt(buf, 4);
            buf.writeBytes(new byte[] {(byte) 0x81, 0x02, 0x41, 0x42});

            // A well-formed packet MUST survive behind the malformed one: proves
            // the degraded decode neither throws nor desynchronizes the stream.
            long keepAliveTimestamp = 1_724_678_400_123L;
            registry.encode(new KeepAlivePacket(keepAliveTimestamp), buf);

            ChannelActionPacket first = decodeSingle(buf);
            assertThat(first.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(first.getChannelId()).isEqualTo("global");
            assertThat(first.getExtra()).isEmpty();

            KeepAlivePacket second = (KeepAlivePacket) registry.decode(buf);
            assertThat(second.getTimestamp()).isEqualTo(keepAliveTimestamp);
            assertThat(buf.readableBytes()).isZero();
        }
    }

    // ==================== Heuristic guard boundary ====================

    @Nested
    @DisplayName("Heuristic guard boundary (pair count 64 vs 65)")
    class HeuristicGuardBoundary {

        @Test
        @DisplayName("Should decode a modern frame with exactly 64 extra pairs")
        void shouldDecodeModernFrameWithExactlySixtyFourPairs() {
            Map<String, String> sixtyFour = new LinkedHashMap<>();
            for (int i = 0; i < 64; i++) {
                sixtyFour.put(String.format("k%02d", i), "v" + i);
            }

            ChannelActionPacket original = new ChannelActionPacket(ChannelAction.CREATE, "big-channel");
            original.setExtra(sixtyFour);

            registry.encode(original, buf);
            ChannelActionPacket result = decodeSingle(buf);

            // size=64 is inside [0..64]: parsed as modern pairs, no data loss.
            assertThat(result.getExtra()).isEqualTo(sixtyFour);
            assertThat(result.getExtra()).hasSize(64);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("KNOWN LIMIT: a modern frame with 65 pairs silently degrades to empty extra and desyncs the stream")
        void sixtyFivePairsSilentlyDegradeToEmptyExtraAndDesyncStream() {
            Map<String, String> sixtyFive = new LinkedHashMap<>();
            for (int i = 0; i < 65; i++) {
                sixtyFive.put(String.format("k%02d", i), "v" + i);
            }

            ChannelActionPacket original = new ChannelActionPacket(ChannelAction.CREATE, "over-channel");
            original.setExtra(sixtyFive);

            registry.encode(original, buf);

            ChannelActionPacket result = decodeSingle(buf);

            // PINNED CURRENT BEHAVIOR, documented audit trade-off (not desired
            // behavior): size=65 trips the > 64 guard, the rewind retries the
            // section as a JSON string whose length prefix is 0x41 (65), reads
            // 65 bytes of pair data as garbage, and parseJsonToMap best-effort
            // yields an empty map -> all 65 pairs are lost silently.
            assertThat(result.getExtra()).isEmpty();

            // Worse: the fallback stopped mid-payload (consumed 1 + 65 of the
            // 521 extra-section bytes), so the connection stream is left
            // misaligned for whatever follows the frame.
            assertThat(buf.readableBytes()).isPositive();
        }
    }

    // ==================== '{' as first extra key character ====================

    @Nested
    @DisplayName("Modern frame with '{' as the first extra key character")
    class AmbiguousBraceKey {

        @Test
        @DisplayName("Should decode key \"{\" as a modern pair thanks to size-first framing")
        void shouldDecodeBraceKeyAsModernPair() {
            ChannelActionPacket original = new ChannelActionPacket(ChannelAction.INVITE, "chan-x");
            original.setExtra(Map.of("{", "x"));

            registry.encode(original, buf);

            // The doc's specific worry ("把 { 当作 VarInt 长度") cannot happen on
            // a modern frame: the pair-count VarInt (1) is read first, so the
            // '{' byte is only ever seen as a length-prefixed key VALUE byte.
            int offset = extraSectionOffset(ChannelAction.CREATE, "chan-y", "");
            assertThat(buf.getByte(offset))
                    .as("extra section must open with pair-count VarInt 1")
                    .isEqualTo((byte) 1);

            ChannelActionPacket result = decodeSingle(buf);

            assertThat(result.getExtra()).hasSize(1);
            assertThat(result.getExtra()).containsKey("{");
            assertThat(result.getExtra("{")).isEqualTo("x");
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should round-trip a JSON-shaped extra value as an opaque modern pair")
        void shouldRoundTripJsonShapedValueAsOpaquePair() {
            ChannelActionPacket original = new ChannelActionPacket(ChannelAction.CREATE, "chan-y");

            original.setExtra(Map.of("payload_json", "{\"looks\":\"like json\",\"n\":42}"));

            registry.encode(original, buf);
            ChannelActionPacket result = decodeSingle(buf);

            assertThat(result.getExtra("payload_json")).isEqualTo("{\"looks\":\"like json\",\"n\":42}");
            assertThat(buf.readableBytes()).isZero();
        }
    }

    // ==================== Upgrade-on-read stability ====================

    @Nested
    @DisplayName("Round-trip stability (upgrade-on-read)")
    class UpgradeOnRead {

        @Test
        @DisplayName("Should re-encode a legacy-decoded packet in modern pair format")
        void shouldReEncodeLegacyDecodedPacketAsModernPairs() {
            writeFrameHeader(buf, UUID.randomUUID(), ChannelAction.CREATE, "global", "");
            PacketBuffer.writeString(buf, LEGACY_SHORT_JSON);

            ChannelActionPacket decoded = decodeSingle(buf);
            Map<String, String> parsedOnce = new LinkedHashMap<>(decoded.getExtra());

            ByteBuf reencoded = Unpooled.buffer();
            try {
                registry.encode(decoded, reencoded);

                // Byte-level proof of upgrade-on-read: the extra section must
                // now open with the modern pair-count VarInt (2), not the legacy
                // JSON byte length (46 = 0x2E).
                int offset = extraSectionOffset(ChannelAction.CREATE, "global", "");
                assertThat(reencoded.getByte(offset))
                        .as("re-encoded extra section must open with pair-count VarInt 2, "
                                + "not the legacy JSON length %d", utf8Length(LEGACY_SHORT_JSON))
                        .isEqualTo((byte) 2);

                ChannelActionPacket reread = decodeSingle(reencoded);
                assertThat(reread.getRequestId()).isEqualTo(decoded.getRequestId());
                assertThat(reread.getExtra()).isEqualTo(parsedOnce);
                assertThat(reencoded.readableBytes()).isZero();
            } finally {
                reencoded.release();
            }
        }
    }
}
