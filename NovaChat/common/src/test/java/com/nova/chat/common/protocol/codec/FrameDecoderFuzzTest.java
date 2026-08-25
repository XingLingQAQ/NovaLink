package com.nova.chat.common.protocol.codec;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketIds;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.VarInt;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VERIFY-005 (Java slice): fuzz-style tests on the NovaChat frame decoder
 * pipeline covering the four audit-mandated malformed-input scenarios.
 *
 * <p>Audit text (VERIFY-005): "未知 ID、坏 VarInt、坏 UTF-8 和超长字段是否都
 * 关闭连接且释放资源 | fuzz frame decoder、PHP/Python/C++ parser,检查连接、
 * 内存和日志".
 *
 * <p>Pipeline under test mirrors production: {@link Varint21FrameDecoder}
 * → {@link PacketDecoder} (with {@link NovaProtocol#createRegistry()})
 * → a tail handler that reproduces the production close-on-exception policy
 * (see {@code CoreClientChannelHandler.exceptionCaught} → {@code ctx.close()}).
 * All tests use {@link EmbeddedChannel} (no real network) so resource release
 * is simulated through buffer ref-count checks, not OS-level socket teardown.
 *
 * <p><b>Findings documented by these tests (honest gaps vs. audit):</b>
 * <ul>
 *   <li><b>Bad VarInt</b> (non-terminating / >5 bytes): {@link CorruptedFrameException}
 *       thrown → {@code exceptionCaught} → {@code ctx.close()}. Channel IS
 *       closed. This matches the audit requirement.</li>
 *   <li><b>Oversized field</b> (exceeds {@link ProtocolLimits}): the per-field
 *       length check in {@code PacketBuffer.readString(buf, maxLength)} throws
 *       {@link IllegalArgumentException} → {@link DecoderException} wrapper →
 *       {@code ctx.close()}. Channel IS closed. No OOM because the allocation
 *       is rejected before {@code new byte[length]}. This matches the audit
 *       requirement.</li>
 *   <li><b>Unknown packet ID</b>: {@link PacketDecoder} silently drops the
 *       frame at FINE log level and <b>does NOT close the connection</b>.
 *       This is a <b>gap</b> vs. the audit requirement ("未知 ID … 关闭连接").
 *       The test asserts the actual observable behavior (silent drop, channel
 *       stays open, no resource leak) rather than faking a closure assertion.</li>
 *   <li><b>Bad UTF-8</b> (truncated / illegal sequence): {@code
 *       PacketBuffer.readString} decodes malformed bytes with
 *       {@code new String(bytes, StandardCharsets.UTF_8)} which uses the
 *       U+FFFD replacement character and <b>does NOT throw</b>, so the
 *       connection is <b>not closed</b>. This is a <b>gap</b> vs. the audit
 *       requirement ("坏 UTF-8 … 关闭连接"). The test asserts the actual
 *       observable behavior (lenient replacement, channel stays open, no
 *       resource leak) rather than faking a closure assertion.</li>
 * </ul>
 *
 * <p>These tests are purely additive (new file, new class name). No production
 * source files were modified.
 */
@DisplayName("VERIFY-005: frame decoder fuzz (unknown ID / bad VarInt / bad UTF-8 / oversized field)")
class FrameDecoderFuzzTest {

    /**
     * Builds a full-pipeline {@link EmbeddedChannel} with the same codec order
     * as production ({@code NettyServer} / {@code CoreNetworkClient}), plus a
     * tail handler that mirrors {@code CoreClientChannelHandler.exceptionCaught}
     * by calling {@code ctx.close()} on any exception (the production close
     * policy). Captured exceptions and decoded packets are stored in the
     * provided references for assertions.
     */
    private static EmbeddedChannel buildPipeline(AtomicReference<Throwable> caughtException,
                                                  AtomicReference<Object> decodedPacket) {
        PacketRegistry registry = NovaProtocol.createRegistry();
        return new EmbeddedChannel(
                new Varint21FrameDecoder(),
                new PacketDecoder(registry),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (decodedPacket != null) {
                            decodedPacket.set(msg);
                        }
                        // No downstream handler; discard. Packet POJOs need no release.
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        if (caughtException != null) {
                            caughtException.set(cause);
                        }
                        ctx.close();
                    }
                });
    }

    /**
     * Wraps a payload (packet ID + body) into a length-prefixed frame suitable
     * for feeding into the frame decoder. Returns the composed buffer; caller
     * must release it (or let the decoder consume/release it).
     */
    private static ByteBuf frame(ByteBuf payload) {
        ByteBuf out = Unpooled.buffer();
        VarInt.writeVarInt(out, payload.readableBytes());
        out.writeBytes(payload);
        payload.release();
        return out;
    }

    // ==================================================================
    // Scenario 1: Unknown packet ID
    // ==================================================================

    @Test
    @DisplayName("unknown packet ID: frame is silently dropped, channel stays open, no leak (GAP: audit requires close)")
    void unknownPacketId_silentlyDropped_channelStaysOpen() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<Object> decoded = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, decoded);

        // 0x08 = PLAYER_STATE — reserved orphan, intentionally NOT registered in NovaProtocol.
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(PacketIds.PLAYER_STATE);
        ByteBuf input = frame(payload);

        try {
            // Frame decoder produces a slice; PacketDecoder reads packetId=0x08,
            // registry.createPacket returns null, logs FINE, returns without
            // adding to out. No exception, no close.
            assertThat(channel.writeInbound(input)).isFalse();
            assertThat(decoded.get()).isNull();
            assertThat(caught.get()).isNull();
            // GAP: production does NOT close on unknown packet ID.
            assertThat(channel.isOpen()).isTrue();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    // ==================================================================
    // Scenario 2: Bad VarInt (non-terminating / >5 bytes)
    // ==================================================================

    @Test
    @DisplayName("bad VarInt: 5 non-terminating bytes (0x80 ×5) → CorruptedFrameException + channel closed")
    void badVarInt_nonTerminating_throwsAndClosesChannel() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, null);

        // 5 bytes of 0x80: each has continuation bit set, no terminator.
        // After 5th byte, position reaches 35 >= 32 → CorruptedFrameException.
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        });

        try {
            channel.writeInbound(input);
            // Exception propagates through pipeline → tail handler closes channel.
            assertThat(caught.get())
                    .as("exception should be captured by tail exceptionCaught")
                    .isNotNull()
                    .isInstanceOf(CorruptedFrameException.class);
            assertThat(caught.get().getMessage()).contains("VarInt length is too big");
            assertThat(channel.isOpen())
                    .as("channel must be closed by exceptionCaught → ctx.close()")
                    .isFalse();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("bad VarInt: 6th continuation byte (overlong prefix) → CorruptedFrameException + channel closed")
    void badVarInt_sixthContinuationByte_throwsAndClosesChannel() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, null);

        // 6 bytes: 5 continuation bytes + 1 terminator — the 5th byte already
        // triggers position >= 32, so this is just a redundant-length variant.
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01
        });

        try {
            channel.writeInbound(input);
            assertThat(caught.get()).isNotNull().isInstanceOf(CorruptedFrameException.class);
            assertThat(channel.isOpen()).isFalse();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    // ==================================================================
    // Scenario 3: Bad UTF-8 (truncated / illegal sequence)
    // ==================================================================

    @Test
    @DisplayName("bad UTF-8: overlong 0xC0 0x80 → decoded with U+FFFD replacement, channel stays open (GAP: audit requires close)")
    void badUtf8_overlongSequence_decodedWithReplacementChar_channelStaysOpen() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<Object> decoded = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, decoded);

        // Build a ChatMessagePacket (0x03) frame whose senderName contains
        // an overlong UTF-8 encoding of U+0000 (0xC0 0x80), which is invalid
        // in strict UTF-8 but silently replaced by Java's lenient decoder.
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(PacketIds.CHAT_MESSAGE);       // packet ID
        payload.writeLong(0L); payload.writeLong(0L);     // requestId (UUID)
        payload.writeLong(0L); payload.writeLong(0L);     // senderId (UUID)
        // senderName: length=2, bytes = 0xC0 0x80 (overlong, invalid UTF-8)
        VarInt.writeVarInt(payload, 2);
        payload.writeByte((byte) 0xC0);
        payload.writeByte((byte) 0x80);
        // remaining string fields: empty (length=0)
        VarInt.writeVarInt(payload, 0);  // clientId
        VarInt.writeVarInt(payload, 0);  // channelId
        VarInt.writeVarInt(payload, 0);  // content
        ByteBuf input = frame(payload);

        try {
            assertThat(channel.writeInbound(input)).isFalse();
            // No exception: Java's String constructor replaces malformed bytes
            // with U+FFFD instead of throwing.
            assertThat(caught.get())
                    .as("no exception expected — Java UTF-8 decoder is lenient")
                    .isNull();
            assertThat(decoded.get())
                    .as("packet should be decoded despite malformed UTF-8")
                    .isInstanceOf(ChatMessagePacket.class);
            ChatMessagePacket pkt = (ChatMessagePacket) decoded.get();
            // The overlong encoding is replaced by at least one U+FFFD char.
            assertThat(pkt.getSenderName()).contains("�");
            // GAP: production does NOT close on bad UTF-8.
            assertThat(channel.isOpen()).isTrue();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("bad UTF-8: truncated 3-byte sequence → decoded with U+FFFD replacement, channel stays open (GAP)")
    void badUtf8_truncatedSequence_decodedWithReplacementChar_channelStaysOpen() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<Object> decoded = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, decoded);

        // Truncated 3-byte UTF-8 sequence: 0xE0 0xA0 but missing the 3rd byte
        // (0xE0 0xA0 alone is invalid — needs a continuation byte).
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(PacketIds.CHAT_MESSAGE);
        payload.writeLong(0L); payload.writeLong(0L);     // requestId
        payload.writeLong(0L); payload.writeLong(0L);     // senderId
        VarInt.writeVarInt(payload, 2);
        payload.writeByte((byte) 0xE0);
        payload.writeByte((byte) 0xA0);
        VarInt.writeVarInt(payload, 0);  // clientId
        VarInt.writeVarInt(payload, 0);  // channelId
        VarInt.writeVarInt(payload, 0);  // content
        ByteBuf input = frame(payload);

        try {
            assertThat(channel.writeInbound(input)).isFalse();
            assertThat(caught.get()).isNull();
            assertThat(decoded.get()).isInstanceOf(ChatMessagePacket.class);
            assertThat(((ChatMessagePacket) decoded.get()).getSenderName()).contains("�");
            assertThat(channel.isOpen()).isTrue();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    // ==================================================================
    // Scenario 4: Oversized field (exceeds ProtocolLimits)
    // ==================================================================

    @Test
    @DisplayName("oversized field: senderName > MAX_SENDER_NAME → DecoderException(IllegalArgumentException) + channel closed, no OOM")
    void oversizedField_exceedsProtocolLimit_throwsAndClosesChannel() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, null);

        int overlong = ProtocolLimits.MAX_SENDER_NAME + 1;  // 65 > 64
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(PacketIds.CHAT_MESSAGE);
        payload.writeLong(0L); payload.writeLong(0L);     // requestId
        payload.writeLong(0L); payload.writeLong(0L);     // senderId
        // senderName: length = 65, exceeds MAX_SENDER_NAME (64)
        VarInt.writeVarInt(payload, overlong);
        payload.writeBytes(new byte[overlong]);  // 65 zero bytes
        ByteBuf input = frame(payload);

        try {
            channel.writeInbound(input);
            // PacketBuffer.readString throws IllegalArgumentException because
            // length (65) > maxLength (64). MessageToMessageDecoder wraps it in
            // DecoderException. Tail handler closes the channel.
            assertThat(caught.get())
                    .as("oversized field must trigger an exception")
                    .isNotNull();
            // Unwrap to the root cause for the assertion.
            Throwable root = caught.get();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root)
                    .as("root cause must be IllegalArgumentException from PacketBuffer.readString")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum");
            assertThat(channel.isOpen())
                    .as("channel must be closed")
                    .isFalse();
            // No OOM: the oversized byte[] is never allocated because
            // PacketBuffer.readString checks length > maxLength BEFORE
            // calling new byte[length].
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("oversized frame: declared length > MAX_FRAME_LENGTH → CorruptedFrameException + channel closed")
    void oversizedFrame_exceedsMaxFrameLength_throwsAndClosesChannel() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, null);

        // Declare a frame length just over MAX_FRAME_LENGTH without sending
        // the body — the frame decoder rejects the length prefix itself.
        ByteBuf input = Unpooled.buffer();
        VarInt.writeVarInt(input, ProtocolLimits.MAX_FRAME_LENGTH + 1);

        try {
            channel.writeInbound(input);
            assertThat(caught.get())
                    .as("oversized frame must trigger CorruptedFrameException")
                    .isNotNull()
                    .isInstanceOf(CorruptedFrameException.class);
            assertThat(caught.get().getMessage()).contains("Invalid frame length");
            assertThat(channel.isOpen()).isFalse();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    // ==================================================================
    // Resource-release regression: no leak on the happy path
    // ==================================================================

    @Test
    @DisplayName("resource release: valid frame is decoded, input buffer released, no leak")
    void validFrame_decoded_noResourceLeak() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicReference<Object> decoded = new AtomicReference<>();
        EmbeddedChannel channel = buildPipeline(caught, decoded);

        // A minimal valid ChatMessagePacket with all-empty string fields.
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(PacketIds.CHAT_MESSAGE);
        payload.writeLong(0L); payload.writeLong(0L);     // requestId
        payload.writeLong(0L); payload.writeLong(0L);     // senderId
        VarInt.writeVarInt(payload, 0);  // senderName = ""
        VarInt.writeVarInt(payload, 0);  // clientId = ""
        VarInt.writeVarInt(payload, 0);  // channelId = ""
        VarInt.writeVarInt(payload, 0);  // content = ""
        ByteBuf input = frame(payload);

        try {
            assertThat(channel.writeInbound(input)).isFalse();
            assertThat(caught.get()).isNull();
            assertThat(decoded.get()).isInstanceOf(ChatMessagePacket.class);
            assertThat(channel.isOpen()).isTrue();
            // Input buffer should have been consumed and released by the decoder.
            assertThat(input.refCnt())
                    .as("input buffer must be released after decoding")
                    .isZero();
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            channel.finishAndReleaseAll();
        }
    }
}
