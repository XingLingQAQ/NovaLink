package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.golden.GoldenPaths;
import com.nova.chat.common.protocol.packets.SpyMessagePacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpyMessagePacketTest {
    @Test
    void sharedFixturePreservesTargetAndChatPayload() throws Exception {
        byte[] bytes = HexFormat.of().parseHex(Files.readString(GoldenPaths.repoRoot()
                .resolve("NovaChat/Bedrock/test-fixtures/spy-message.hex")).trim());
        var input = Unpooled.wrappedBuffer(bytes);
        var output = Unpooled.buffer();
        try {
            var registry = NovaProtocol.createRegistry();
            var packet = (SpyMessagePacket) registry.decode(input);
            assertThat(input.isReadable()).isFalse();
            assertThat(packet.getTargetId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
            assertThat(packet.getMessage().getSenderName()).isEqualTo("Sender");
            assertThat(packet.getMessage().getClientId()).isEqualTo("source");
            assertThat(packet.getMessage().getChannelId()).isEqualTo("staff");
            assertThat(packet.getMessage().getContent()).isEqualTo("observed");
            assertThat(packet.getMessage().getPlaceholders()).containsEntry("rank", "member");
            registry.encode(packet, output);
            byte[] encoded = new byte[output.readableBytes()];
            output.readBytes(encoded);
            assertThat(encoded).isEqualTo(bytes);
        } finally {
            input.release();
            output.release();
        }
    }
}
