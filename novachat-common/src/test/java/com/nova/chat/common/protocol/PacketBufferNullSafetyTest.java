package com.nova.chat.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Null-safety and bounds tests for {@link PacketBuffer}.
 * Guards against NPEs on the wire encode path when optional fields are unset.
 */
@DisplayName("PacketBuffer null-safety and bounds")
class PacketBufferNullSafetyTest {

    private ByteBuf buf;

    @BeforeEach
    void setUp() {
        buf = Unpooled.buffer();
    }

    @AfterEach
    void tearDown() {
        if (buf != null) {
            buf.release();
        }
    }

    @Nested
    @DisplayName("writeString(null)")
    class NullString {

        @Test
        @DisplayName("null string encodes as empty and round-trips")
        void nullBecomesEmpty() {
            PacketBuffer.writeString(buf, null);
            assertThat(PacketBuffer.readString(buf)).isEmpty();
            assertThat(buf.readableBytes()).isZero();
        }
    }

    @Nested
    @DisplayName("writeUUID(null)")
    class NullUuid {

        @Test
        @DisplayName("null UUID encodes as nil UUID")
        void nullBecomesNil() {
            PacketBuffer.writeUUID(buf, null);
            UUID result = PacketBuffer.readUUID(buf);
            assertThat(result.getMostSignificantBits()).isZero();
            assertThat(result.getLeastSignificantBits()).isZero();
        }

        @Test
        @DisplayName("real UUID round-trips")
        void realUuidRoundTrip() {
            UUID original = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            PacketBuffer.writeUUID(buf, original);
            assertThat(PacketBuffer.readUUID(buf)).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("writeByteArray(null)")
    class NullByteArray {

        @Test
        @DisplayName("null byte array encodes as zero-length")
        void nullBecomesEmpty() {
            PacketBuffer.writeByteArray(buf, null);
            assertThat(PacketBuffer.readByteArray(buf)).isEmpty();
        }
    }

    @Nested
    @DisplayName("read bounds")
    class Bounds {

        @Test
        @DisplayName("readString rejects length greater than remaining bytes")
        void stringLengthExceedsReadable() {
            // VarInt length = 10, but no payload bytes follow
            VarInt.writeVarInt(buf, 10);
            assertThatThrownBy(() -> PacketBuffer.readString(buf))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds remaining");
        }

        @Test
        @DisplayName("readString(maxLength) rejects oversized declared length")
        void stringMaxLengthEnforced() {
            PacketBuffer.writeString(buf, "hello-world");
            ByteBuf copy = buf.copy();
            try {
                assertThatThrownBy(() -> PacketBuffer.readString(copy, 5))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum");
            } finally {
                copy.release();
            }
        }

        @Test
        @DisplayName("readByteArray rejects length greater than remaining bytes")
        void byteArrayLengthExceedsReadable() {
            VarInt.writeVarInt(buf, 8);
            assertThatThrownBy(() -> PacketBuffer.readByteArray(buf))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds remaining");
        }

        @Test
        @DisplayName("readByteArray(maxLength) rejects oversized declared length")
        void byteArrayMaxLengthEnforced() {
            PacketBuffer.writeByteArray(buf, new byte[]{1, 2, 3, 4, 5});
            ByteBuf copy = buf.copy();
            try {
                assertThatThrownBy(() -> PacketBuffer.readByteArray(copy, 3))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum");
            } finally {
                copy.release();
            }
        }
    }

    @Nested
    @DisplayName("Unicode stability")
    class Unicode {

        @Test
        @DisplayName("mixed CJK + emoji + ASCII round-trips")
        void mixedUnicode() {
            String original = "NovaChat 跨服聊天 🎮 — café";
            PacketBuffer.writeString(buf, original);
            assertThat(PacketBuffer.readString(buf)).isEqualTo(original);
        }
    }
}
