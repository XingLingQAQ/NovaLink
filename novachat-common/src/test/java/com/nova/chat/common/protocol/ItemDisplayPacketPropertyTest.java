package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jqwik.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ItemDisplayPacket serialization.
 * 
 * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
 * **Validates: Requirements 19.1-19.4**
 * 
 * This test verifies that for any valid ItemDisplayPacket, serializing and deserializing
 * produces an equivalent packet with all fields preserved.
 */
class ItemDisplayPacketPropertyTest {

    private final PacketRegistry registry = NovaProtocol.createRegistry();

    // ==================== Round-Trip Property ====================

    /**
     * Property 13: Display Packet Serialization Round-Trip
     * 
     * For any valid ItemDisplayPacket, serializing and deserializing should produce
     * an equivalent packet with all fields preserved.
     * 
     * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
     * **Validates: Requirements 19.1**
     */
    @Property(tries = 100)
    void itemDisplayPacketRoundTrip(@ForAll("validItemDisplayPacket") ItemDisplayPacket original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Serialize
            registry.encode(original, buf);
            
            // Deserialize
            Packet decoded = registry.decode(buf);
            
            // Verify type
            assertThat(decoded).isInstanceOf(ItemDisplayPacket.class);
            ItemDisplayPacket result = (ItemDisplayPacket) decoded;
            
            // Verify all fields match
            assertThat(result.getSenderId()).isEqualTo(original.getSenderId());
            assertThat(result.getSenderName()).isEqualTo(original.getSenderName());
            assertThat(result.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(result.getItemJson()).isEqualTo(original.getItemJson());
            assertThat(result.getTimestamp()).isEqualTo(original.getTimestamp());

            
            // Verify buffer is fully consumed
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    /**
     * Property 13: Display Packet Serialization Round-Trip - Equality
     * 
     * For any valid ItemDisplayPacket, the round-tripped packet should have
     * equal content fields to the original.
     * 
     * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
     * **Validates: Requirements 19.1**
     */
    @Property(tries = 100)
    void itemDisplayPacketRoundTripEquality(@ForAll("validItemDisplayPacket") ItemDisplayPacket original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Serialize
            registry.encode(original, buf);
            
            // Deserialize
            ItemDisplayPacket result = (ItemDisplayPacket) registry.decode(buf);
            
            // Verify equality of content fields
            assertThat(result.getSenderId()).isEqualTo(original.getSenderId());
            assertThat(result.getSenderName()).isEqualTo(original.getSenderName());
            assertThat(result.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(result.getItemJson()).isEqualTo(original.getItemJson());
            assertThat(result.getTimestamp()).isEqualTo(original.getTimestamp());
        } finally {
            buf.release();
        }
    }

    /**
     * Property 13: Display Packet Serialization Round-Trip - Multiple Iterations
     * 
     * For any valid ItemDisplayPacket, multiple round-trips should produce
     * identical results (idempotence).
     * 
     * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
     * **Validates: Requirements 19.1**
     */
    @Property(tries = 100)
    void itemDisplayPacketMultipleRoundTrips(@ForAll("validItemDisplayPacket") ItemDisplayPacket original) {
        ByteBuf buf1 = Unpooled.buffer();
        ByteBuf buf2 = Unpooled.buffer();
        try {
            // First round-trip
            registry.encode(original, buf1);
            ItemDisplayPacket parsed1 = (ItemDisplayPacket) registry.decode(buf1);
            
            // Second round-trip
            registry.encode(parsed1, buf2);
            ItemDisplayPacket parsed2 = (ItemDisplayPacket) registry.decode(buf2);
            
            // Content should be identical across round-trips
            assertThat(parsed2.getSenderId()).isEqualTo(original.getSenderId());
            assertThat(parsed2.getSenderName()).isEqualTo(original.getSenderName());
            assertThat(parsed2.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(parsed2.getItemJson()).isEqualTo(original.getItemJson());
            assertThat(parsed2.getTimestamp()).isEqualTo(original.getTimestamp());
        } finally {
            buf1.release();
            buf2.release();
        }
    }

    /**
     * Property 13: Display Packet Serialization Round-Trip - With Complex JSON
     * 
     * For any valid ItemDisplayPacket with complex JSON item data,
     * serializing and deserializing should preserve the JSON exactly.
     * 
     * **Feature: novachat-platform-extensions, Property 13: Display Packet Serialization Round-Trip**
     * **Validates: Requirements 19.1, 19.4**
     */
    @Property(tries = 100)
    void itemDisplayPacketWithComplexJson(@ForAll("validItemDisplayPacketWithJson") ItemDisplayPacket original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Serialize
            registry.encode(original, buf);
            
            // Deserialize
            ItemDisplayPacket result = (ItemDisplayPacket) registry.decode(buf);
            
            // Verify JSON is preserved exactly
            assertThat(result.getItemJson()).isEqualTo(original.getItemJson());
        } finally {
            buf.release();
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<ItemDisplayPacket> validItemDisplayPacket() {
        Arbitrary<UUID> senderIds = validUUIDs();
        Arbitrary<String> senderNames = validPlayerNames();
        Arbitrary<String> channelIds = validChannelIds();
        Arbitrary<String> itemJsons = validItemJsons();
        Arbitrary<Long> timestamps = validTimestamps();
        
        return Combinators.combine(senderIds, senderNames, channelIds, itemJsons, timestamps)
            .as(ItemDisplayPacket::new);
    }

    @Provide
    Arbitrary<ItemDisplayPacket> validItemDisplayPacketWithJson() {
        Arbitrary<UUID> senderIds = validUUIDs();
        Arbitrary<String> senderNames = validPlayerNames();
        Arbitrary<String> channelIds = validChannelIds();
        Arbitrary<String> itemJsons = complexItemJsons();
        Arbitrary<Long> timestamps = validTimestamps();
        
        return Combinators.combine(senderIds, senderNames, channelIds, itemJsons, timestamps)
            .as(ItemDisplayPacket::new);
    }

    /**
     * Generates valid UUIDs.
     */
    private Arbitrary<UUID> validUUIDs() {
        return Arbitraries.longs().tuple2()
            .map(tuple -> new UUID(tuple.get1(), tuple.get2()));
    }

    /**
     * Generates valid Minecraft player names (alphanumeric and underscores, 3-16 chars).
     */
    private Arbitrary<String> validPlayerNames() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars('_')
            .ofMinLength(3)
            .ofMaxLength(16)
            .filter(s -> !s.isEmpty() && Character.isLetter(s.charAt(0)));
    }

    /**
     * Generates valid channel IDs (lowercase alphanumeric with hyphens).
     */
    private Arbitrary<String> validChannelIds() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('0', '9')
            .withChars('-', '_')
            .ofMinLength(1)
            .ofMaxLength(32)
            .filter(s -> !s.isEmpty() && Character.isLetterOrDigit(s.charAt(0)));
    }

    /**
     * Generates valid item JSON strings (simple cases).
     */
    private Arbitrary<String> validItemJsons() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.just("{}"),
            Arbitraries.just("{\"id\":\"minecraft:diamond_sword\"}"),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', ':', '"', '{', '}', '[', ']', ',', '_', '-')
                .ofMinLength(1)
                .ofMaxLength(1024)
        );
    }

    /**
     * Generates complex item JSON strings with NBT-like structures.
     */
    private Arbitrary<String> complexItemJsons() {
        return Arbitraries.oneOf(
            // Simple item
            Arbitraries.just("{\"id\":\"minecraft:diamond_sword\",\"Count\":1}"),
            // Item with enchantments
            Arbitraries.just("{\"id\":\"minecraft:diamond_sword\",\"Count\":1,\"tag\":{\"Enchantments\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}}"),
            // Item with display name and lore
            Arbitraries.just("{\"id\":\"minecraft:diamond_sword\",\"Count\":1,\"tag\":{\"display\":{\"Name\":\"\\\"Excalibur\\\"\",\"Lore\":[\"\\\"A legendary sword\\\"\"]}}}"),
            // Item with custom model data
            Arbitraries.just("{\"id\":\"minecraft:stick\",\"Count\":1,\"tag\":{\"CustomModelData\":12345}}"),
            // Empty item
            Arbitraries.just("{}"),
            // Air item
            Arbitraries.just("{\"id\":\"minecraft:air\",\"Count\":0}")
        );
    }

    /**
     * Generates valid timestamps (positive long values representing Unix milliseconds).
     */
    private Arbitrary<Long> validTimestamps() {
        return Arbitraries.longs().between(0L, System.currentTimeMillis() + 86400000L);
    }
}
