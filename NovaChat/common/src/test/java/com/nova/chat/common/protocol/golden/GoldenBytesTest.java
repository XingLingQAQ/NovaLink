package com.nova.chat.common.protocol.golden;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.VarInt;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language golden byte verification (Java side).
 *
 * <p>For every sample under {@code <repo>/test/protocol-golden}:
 * <ol>
 *   <li>decode the {@code .bin} frame with the production
 *       {@code NovaProtocol.createRegistry()} codecs,</li>
 *   <li>assert every decoded field equals the {@code .json} descriptor,</li>
 *   <li>re-encode and assert byte-for-byte equality with the {@code .bin}
 *       (skipped for {@code reencodeExact=false} legacy wire forms).</li>
 * </ol>
 *
 * <p>Additionally verifies the on-disk files are exactly what the in-source
 * {@link GoldenSampleSet} produces, so any Java protocol change that shifts
 * bytes fails here first. Regenerate via {@link GoldenFileGenerator}.
 */
@DisplayName("Golden bytes: cross-language protocol freeze (Java authority)")
class GoldenBytesTest {

    private static final Gson GSON = new Gson();

    @TestFactory
    @DisplayName("decode -> assert fields -> re-encode round-trip per sample")
    List<DynamicTest> goldenSamples() throws IOException {
        Path dir = GoldenPaths.goldenDir();
        assertThat(dir).as("golden dir exists (run GoldenFileGenerator first)").isDirectory();

        JsonObject manifest = readJson(dir.resolve("manifest.json"));
        List<DynamicTest> tests = new ArrayList<>();
        for (var element : manifest.getAsJsonArray("samples")) {
            JsonObject entry = element.getAsJsonObject();
            String name = entry.get("name").getAsString();
            tests.add(DynamicTest.dynamicTest(name, () -> verifySample(dir, name)));
        }
        assertThat(tests).as("manifest sample count").isNotEmpty();
        return tests;
    }

    private void verifySample(Path dir, String name) throws IOException {
        byte[] frame = Files.readAllBytes(dir.resolve(name + ".bin"));
        JsonObject json = readJson(dir.resolve(name + ".json"));
        JsonObject fields = json.getAsJsonObject("fields");

        // Descriptor self-consistency: frameHex documents the .bin content.
        assertThat(GoldenSampleSet.hex(frame)).as("%s: frameHex matches .bin", name)
                .isEqualTo(json.get("frameHex").getAsString());

        // ---- Parse frame: Length(VarInt) | PacketID | RequestID | Payload ----
        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        try {
            int length = VarInt.readVarInt(buf);
            assertThat(length).as("%s: VarInt length prefix == body size", name)
                    .isEqualTo(buf.readableBytes());

            int packetId = buf.readByte() & 0xFF;
            assertThat(packetId).as("%s: packet id", name)
                    .isEqualTo(json.get("packetId").getAsInt());

            PacketRegistry registry = NovaProtocol.createRegistry();
            Packet packet = registry.createPacket(packetId);
            assertThat(packet).as("%s: packet id registered", name).isNotNull();

            packet.decode(buf);
            assertThat(buf.readableBytes()).as("%s: payload fully consumed", name).isZero();

            assertThat(packet.getRequestId())
                    .as("%s: request id", name)
                    .isEqualTo(UUID.fromString(json.get("requestId").getAsString()));

            assertFields(name, packet, fields);

            // ---- Re-encode ----
            if (json.get("reencodeExact").getAsBoolean()) {
                byte[] reencoded = encodeFrame(packet);
                assertThat(reencoded)
                        .as("%s: re-encoded frame is byte-identical", name)
                        .isEqualTo(frame);
            }
        } finally {
            buf.release();
        }
    }

    private static byte[] encodeFrame(Packet packet) {
        ByteBuf body = Unpooled.buffer();
        ByteBuf out = Unpooled.buffer();
        try {
            packet.encode(body);
            byte[] bodyBytes = new byte[body.readableBytes()];
            body.getBytes(body.readerIndex(), bodyBytes);
            VarInt.writeVarInt(out, bodyBytes.length);
            out.writeBytes(bodyBytes);
            byte[] frame = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), frame);
            return frame;
        } finally {
            body.release();
            out.release();
        }
    }

    private void assertFields(String name, Packet packet, JsonObject f) {
        if (packet instanceof HandshakePacket p) {
            assertThat(p.getProtocolVersion()).as("%s: protocolVersion", name)
                    .isEqualTo(f.get("protocolVersion").getAsInt());
            assertThat(p.getClientId()).as("%s: clientId", name)
                    .isEqualTo(f.get("clientId").getAsString());
            assertThat(p.getPasswordHash()).as("%s: passwordHash", name)
                    .isEqualTo(f.get("passwordHash").getAsString());
            assertThat(p.getPlatform().getId()).as("%s: platform", name)
                    .isEqualTo(f.get("platform").getAsInt());
            assertThat(p.getServerVersion()).as("%s: serverVersion", name)
                    .isEqualTo(f.get("serverVersion").getAsString());
        } else if (packet instanceof HandshakeResponsePacket p) {
            assertThat(p.isSuccess()).as("%s: success", name)
                    .isEqualTo(f.get("success").getAsBoolean());
            assertThat(p.getErrorCode()).as("%s: errorCode", name)
                    .isEqualTo(f.get("errorCode").getAsString());
            assertThat(p.getMessage()).as("%s: message", name)
                    .isEqualTo(f.get("message").getAsString());
        } else if (packet instanceof ChatMessagePacket p) {
            assertThat(p.getSenderId()).as("%s: senderId", name)
                    .isEqualTo(UUID.fromString(f.get("senderId").getAsString()));
            assertThat(p.getSenderName()).as("%s: senderName", name)
                    .isEqualTo(f.get("senderName").getAsString());
            assertThat(p.getClientId()).as("%s: clientId", name)
                    .isEqualTo(f.get("clientId").getAsString());
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getContent()).as("%s: content", name)
                    .isEqualTo(f.get("content").getAsString());
            assertStringMap(name, "placeholders", p.getPlaceholders(),
                    f.getAsJsonObject("placeholders"));
        } else if (packet instanceof ChannelActionPacket p) {
            assertThat(p.getAction().getId()).as("%s: action", name)
                    .isEqualTo(f.get("action").getAsInt());
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getPassword()).as("%s: password", name)
                    .isEqualTo(f.get("password").getAsString());
            assertStringMap(name, "extra", p.getExtra(), f.getAsJsonObject("extra"));
        } else if (packet instanceof ChannelActionResponsePacket p) {
            assertThat(p.isSuccess()).as("%s: success", name)
                    .isEqualTo(f.get("success").getAsBoolean());
            assertThat(p.getAction().getId()).as("%s: action", name)
                    .isEqualTo(f.get("action").getAsInt());
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getErrorCode()).as("%s: errorCode", name)
                    .isEqualTo(f.get("errorCode").getAsString());
            assertThat(p.getMessage()).as("%s: message", name)
                    .isEqualTo(f.get("message").getAsString());
            assertStringMap(name, "extra", p.getExtra(), f.getAsJsonObject("extra"));
        } else if (packet instanceof ConfigSyncPacket p) {
            assertThat(p.getConfigJson()).as("%s: configJson", name)
                    .isEqualTo(f.get("configJson").getAsString());
            assertThat(p.getTimestamp()).as("%s: timestamp", name)
                    .isEqualTo(f.get("timestamp").getAsLong());
        } else if (packet instanceof KeepAlivePacket p) {
            assertThat(p.getTimestamp()).as("%s: timestamp", name)
                    .isEqualTo(f.get("timestamp").getAsLong());
        } else if (packet instanceof TitlePacket p) {
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getTitle()).as("%s: title", name)
                    .isEqualTo(f.get("title").getAsString());
            assertThat(p.getSubtitle()).as("%s: subtitle", name)
                    .isEqualTo(f.get("subtitle").getAsString());
            assertThat(p.getFadeIn()).as("%s: fadeIn", name)
                    .isEqualTo(f.get("fadeIn").getAsInt());
            assertThat(p.getStay()).as("%s: stay", name)
                    .isEqualTo(f.get("stay").getAsInt());
            assertThat(p.getFadeOut()).as("%s: fadeOut", name)
                    .isEqualTo(f.get("fadeOut").getAsInt());
            assertThat(p.getSenderId()).as("%s: senderId", name)
                    .isEqualTo(UUID.fromString(f.get("senderId").getAsString()));
        } else if (packet instanceof AdminActionPacket p) {
            assertThat(p.getAction().getId()).as("%s: action", name)
                    .isEqualTo(f.get("action").getAsInt());
            assertThat(p.getPlayerId()).as("%s: playerId", name)
                    .isEqualTo(UUID.fromString(f.get("playerId").getAsString()));
            assertThat(p.getPasswordHash()).as("%s: passwordHash", name)
                    .isEqualTo(f.get("passwordHash").getAsString());
            assertThat(p.getTarget()).as("%s: target", name)
                    .isEqualTo(f.get("target").getAsString());
            assertStringMap(name, "extra", p.getExtra(), f.getAsJsonObject("extra"));
        } else if (packet instanceof AdminActionResponsePacket p) {
            assertThat(p.getAction().getId()).as("%s: action", name)
                    .isEqualTo(f.get("action").getAsInt());
            assertThat(p.isSuccess()).as("%s: success", name)
                    .isEqualTo(f.get("success").getAsBoolean());
            assertThat(p.getErrorCode()).as("%s: errorCode", name)
                    .isEqualTo(f.get("errorCode").getAsString());
            assertThat(p.getMessage()).as("%s: message", name)
                    .isEqualTo(f.get("message").getAsString());
        } else if (packet instanceof ItemDisplayPacket p) {
            assertThat(p.getSenderId()).as("%s: senderId", name)
                    .isEqualTo(UUID.fromString(f.get("senderId").getAsString()));
            assertThat(p.getSenderName()).as("%s: senderName", name)
                    .isEqualTo(f.get("senderName").getAsString());
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getItemJson()).as("%s: itemJson", name)
                    .isEqualTo(f.get("itemJson").getAsString());
            assertThat(p.getTimestamp()).as("%s: timestamp", name)
                    .isEqualTo(f.get("timestamp").getAsLong());
        } else if (packet instanceof MentionPacket p) {
            assertThat(p.getMentionerId()).as("%s: mentionerId", name)
                    .isEqualTo(UUID.fromString(f.get("mentionerId").getAsString()));
            assertThat(p.getMentionerName()).as("%s: mentionerName", name)
                    .isEqualTo(f.get("mentionerName").getAsString());
            assertThat(p.getMentionedId()).as("%s: mentionedId", name)
                    .isEqualTo(UUID.fromString(f.get("mentionedId").getAsString()));
            assertThat(p.getChannelId()).as("%s: channelId", name)
                    .isEqualTo(f.get("channelId").getAsString());
            assertThat(p.getMessagePreview()).as("%s: messagePreview", name)
                    .isEqualTo(f.get("messagePreview").getAsString());
            assertThat(p.getTimestamp()).as("%s: timestamp", name)
                    .isEqualTo(f.get("timestamp").getAsLong());
        } else if (packet instanceof PrivateMessagePacket p) {
            assertThat(p.getSenderId()).as("%s: senderId", name)
                    .isEqualTo(UUID.fromString(f.get("senderId").getAsString()));
            assertThat(p.getSenderName()).as("%s: senderName", name)
                    .isEqualTo(f.get("senderName").getAsString());
            assertThat(p.getSenderClientId()).as("%s: senderClientId", name)
                    .isEqualTo(f.get("senderClientId").getAsString());
            assertThat(p.getTargetName()).as("%s: targetName", name)
                    .isEqualTo(f.get("targetName").getAsString());
            assertThat(p.getTargetId()).as("%s: targetId", name)
                    .isEqualTo(UUID.fromString(f.get("targetId").getAsString()));
            assertThat(p.getContent()).as("%s: content", name)
                    .isEqualTo(f.get("content").getAsString());
            assertThat(p.getTimestamp()).as("%s: timestamp", name)
                    .isEqualTo(f.get("timestamp").getAsLong());
        } else {
            throw new AssertionError(name + ": unhandled packet type " + packet.getClass());
        }
    }

    private void assertStringMap(String name, String field, Map<String, String> actual,
                                 JsonObject expected) {
        assertThat(actual).as("%s: %s size", name, field).hasSize(expected.size());
        for (String key : expected.keySet()) {
            assertThat(actual).as("%s: %s[%s]", name, field, key)
                    .containsEntry(key, expected.get(key).getAsString());
        }
    }

    /**
     * The committed golden files must be exactly what the current Java
     * implementation generates. If the Java protocol code (or the sample
     * definitions) change, this fails until {@link GoldenFileGenerator} is
     * rerun deliberately - freezing the wire format status quo.
     */
    @Test
    @DisplayName("on-disk golden files match the in-source sample set (no silent drift)")
    void diskMatchesGeneratedSet() throws IOException {
        Path dir = GoldenPaths.goldenDir();
        List<GoldenSampleSet.Sample> samples = GoldenSampleSet.samples();

        TreeSet<String> expected = new TreeSet<>();
        for (GoldenSampleSet.Sample sample : samples) {
            expected.add(sample.name + ".bin");
            expected.add(sample.name + ".json");

            byte[] diskBin = Files.readAllBytes(dir.resolve(sample.name + ".bin"));
            assertThat(diskBin)
                    .as("%s.bin matches current Java encoder output", sample.name)
                    .isEqualTo(sample.frame);

            JsonObject diskJson = readJson(dir.resolve(sample.name + ".json"));
            assertThat(diskJson)
                    .as("%s.json matches current sample definition", sample.name)
                    .isEqualTo(sample.json);
        }

        // Directory contains exactly the expected sample files (plus manifest).
        TreeSet<String> onDisk = new TreeSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{bin,json}")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                if (!fileName.equals("manifest.json")) {
                    onDisk.add(fileName);
                }
            }
        }
        assertThat(onDisk).as("golden dir contains exactly the generated samples")
                .isEqualTo(expected);

        // All 13 registered packet ids are covered by at least one sample.
        TreeSet<Integer> coveredIds = new TreeSet<>();
        for (GoldenSampleSet.Sample sample : samples) {
            coveredIds.add(sample.json.get("packetId").getAsInt());
        }
        assertThat(coveredIds).as("all NovaProtocol.createRegistry() packet ids covered")
                .containsExactly(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09, 0x0B, 0x0C,
                        0x10, 0x12, 0x14);
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
