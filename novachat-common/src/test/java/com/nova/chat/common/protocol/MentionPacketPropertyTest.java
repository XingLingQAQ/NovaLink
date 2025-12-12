package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.MentionPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jqwik.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for MentionPacket serialization.
 * 
 * **Feature: novachat-platform-extensions, Property 14: Mention Packet Serialization Round-Trip**
 * **Validates: Requirements 20.1-20.2**
 * 
 * This test verifies that for any valid MentionPacket, serializing and deserializing
 * produces an equivalent packet with all fields preserved.
 */
class MentionPacketPropertyTest {

    private final PacketRegistry registry = NovaProtocol.createRegistry();

    // ==================== Round-Trip Property ====================

    /**
     * Property 14: Mention Packet Serialization Round-Trip
     * 
     * For any valid MentionPacket, serializing and deserializing should produce
     * an equivalent packet with all fields preserved.
     * 
     * **Feature: novachat-platform-extensions, Property 14: Mention Packet Serialization Round-Trip**
     * **Validates: Requirements 20.1-20.2**
     */
    @Property(tries = 100)
    void mentionPacketRoundTrip(@ForAll("validMentionPacket") MentionPacket original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Serialize
            registry.encode(original, buf);
            
            // Deserialize
            Packet decoded = registry.decode(buf);
            
            // Verify type
            assertThat(decoded).isInstanceOf(MentionPacket.class);
            MentionPacket result = (MentionPacket) decoded;
            
            // Verify all fields match
            assertThat(result.getMentionerId()).isEqualTo(original.getMentionerId());
            assertThat(result.getMentionerName()).isEqualTo(original.getMentionerName());
            assertThat(result.getMentionedId()).isEqualTo(original.getMentionedId());
            assertThat(result.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(result.getMessagePreview()).isEqualTo(original.getMessagePreview());
            assertThat(result.getTimestamp()).isEqualTo(original.getTimestamp());
            
            // Verify buffer is fully consumed
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    /**
     * Property 14: Mention Packet Serialization Round-Trip - Equality
     * 
     * For any valid MentionPacket, the round-tripped packet should be
     * equal to the original using equals().
     * 
     * **Feature: novachat-platform-extensions, Property 14: Mention Packet Serialization Round-Trip**
     * **Validates: Requirements 20.1-20.2**
     */
    @Property(tries = 100)
    void mentionPacketRoundTripEquality(@ForAll("validMentionPacket") MentionPacket original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Serialize
            registry.encode(original, buf);
            
            // Deserialize
            MentionPacket result = (MentionPacket) registry.decode(buf);
            
            // Verify equality (note: requestId is regenerated, so we compare content fields)
            assertThat(result.getMentionerId()).isEqualTo(original.getMentionerId());
            assertThat(result.getMentionerName()).isEqualTo(original.getMentionerName());
            assertThat(result.getMentionedId()).isEqualTo(original.getMentionedId());
            assertThat(result.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(result.getMessagePreview()).isEqualTo(original.getMessagePreview());
            assertThat(result.getTimestamp()).isEqualTo(original.getTimestamp());
        } finally {
            buf.release();
        }
    }

    /**
     * Property 14: Mention Packet Serialization Round-Trip - Multiple Iterations
     * 
     * For any valid MentionPacket, multiple round-trips should produce
     * identical results (idempotence).
     * 
     * **Feature: novachat-platform-extensions, Property 14: Mention Packet Serialization Round-Trip**
     * **Validates: Requirements 20.1-20.2**
     */
    @Property(tries = 100)
    void mentionPacketMultipleRoundTrips(@ForAll("validMentionPacket") MentionPacket original) {
        ByteBuf buf1 = Unpooled.buffer();
        ByteBuf buf2 = Unpooled.buffer();
        try {
            // First round-trip
            registry.encode(original, buf1);
            MentionPacket parsed1 = (MentionPacket) registry.decode(buf1);
            
            // Second round-trip
            registry.encode(parsed1, buf2);
            MentionPacket parsed2 = (MentionPacket) registry.decode(buf2);
            
            // Content should be identical across round-trips
            assertThat(parsed2.getMentionerId()).isEqualTo(original.getMentionerId());
            assertThat(parsed2.getMentionerName()).isEqualTo(original.getMentionerName());
            assertThat(parsed2.getMentionedId()).isEqualTo(original.getMentionedId());
            assertThat(parsed2.getChannelId()).isEqualTo(original.getChannelId());
            assertThat(parsed2.getMessagePreview()).isEqualTo(original.getMessagePreview());
            assertThat(parsed2.getTimestamp()).isEqualTo(original.getTimestamp());
        } finally {
            buf1.release();
            buf2.release();
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<MentionPacket> validMentionPacket() {
        Arbitrary<UUID> mentionerIds = validUUIDs();
        Arbitrary<String> mentionerNames = validPlayerNames();
        Arbitrary<UUID> mentionedIds = validUUIDs();
        Arbitrary<String> channelIds = validChannelIds();
        Arbitrary<String> messagePreviews = validMessagePreviews();
        Arbitrary<Long> timestamps = validTimestamps();
        
        return Combinators.combine(mentionerIds, mentionerNames, mentionedIds, 
                                   channelIds, messagePreviews, timestamps)
            .as(MentionPacket::new);
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
     * Generates valid message previews (can contain various characters).
     */
    private Arbitrary<String> validMessagePreviews() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '@', '!', '?', '.', ',')
                .ofMinLength(1)
                .ofMaxLength(256)
        );
    }

    /**
     * Generates valid timestamps (positive long values representing Unix milliseconds).
     */
    private Arbitrary<Long> validTimestamps() {
        return Arbitraries.longs().between(0L, System.currentTimeMillis() + 86400000L);
    }
}
