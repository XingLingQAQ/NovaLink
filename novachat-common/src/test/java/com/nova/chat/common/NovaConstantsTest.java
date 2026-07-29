package com.nova.chat.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NovaConstants.
 */
class NovaConstantsTest {
    
    @Test
    void protocolVersionShouldBePositive() {
        assertThat(NovaConstants.PROTOCOL_VERSION).isPositive();
    }
    
    @Test
    void defaultPortsShouldBeInValidRange() {
        assertThat(NovaConstants.DEFAULT_PORT).isBetween(1024, 65535);
        assertThat(NovaConstants.DEFAULT_WEBSOCKET_PORT).isBetween(1024, 65535);
    }
    
    @Test
    void packetIdsShouldBeUnique() {
        byte[] packetIds = {
            NovaConstants.PACKET_HANDSHAKE,
            NovaConstants.PACKET_HANDSHAKE_RESPONSE,
            NovaConstants.PACKET_CHAT_MESSAGE,
            NovaConstants.PACKET_CHANNEL_ACTION,
            NovaConstants.PACKET_CHANNEL_ACTION_RESPONSE,
            NovaConstants.PACKET_CONFIG_SYNC,
            NovaConstants.PACKET_KEEP_ALIVE,
            NovaConstants.PACKET_PLAYER_STATE,
            NovaConstants.PACKET_TITLE,
            NovaConstants.PACKET_ANNOUNCEMENT,
            NovaConstants.PACKET_ADMIN_ACTION,
            NovaConstants.PACKET_ADMIN_ACTION_RESPONSE,
            NovaConstants.PACKET_CHANNEL_UPDATE,
            NovaConstants.PACKET_ITEM_DISPLAY,
            NovaConstants.PACKET_INVENTORY_SNAPSHOT,
            NovaConstants.PACKET_MENTION,
            NovaConstants.PACKET_IMAGE_DISPLAY
        };

        // Check all packet IDs are unique
        for (int i = 0; i < packetIds.length; i++) {
            for (int j = i + 1; j < packetIds.length; j++) {
                assertThat(packetIds[i])
                    .as("Packet ID at index %d should differ from index %d", i, j)
                    .isNotEqualTo(packetIds[j]);
            }
        }
    }

    @Test
    void packetIdsShouldMatchPacketIdsClass() {
        assertThat(NovaConstants.PACKET_HANDSHAKE).isEqualTo((byte) com.nova.chat.common.protocol.PacketIds.HANDSHAKE);
        assertThat(NovaConstants.PACKET_CHAT_MESSAGE).isEqualTo((byte) com.nova.chat.common.protocol.PacketIds.CHAT_MESSAGE);
        assertThat(NovaConstants.PACKET_KEEP_ALIVE).isEqualTo((byte) com.nova.chat.common.protocol.PacketIds.KEEP_ALIVE);
        assertThat(NovaConstants.PACKET_ITEM_DISPLAY).isEqualTo((byte) com.nova.chat.common.protocol.PacketIds.ITEM_DISPLAY);
        assertThat(NovaConstants.PACKET_MENTION).isEqualTo((byte) com.nova.chat.common.protocol.PacketIds.MENTION);
        assertThat(NovaConstants.PROTOCOL_VERSION).isEqualTo(com.nova.chat.common.protocol.NovaProtocol.PROTOCOL_VERSION);
    }
    
    @Test
    void errorCodesShouldFollowNamingConvention() {
        assertThat(NovaConstants.ERROR_BAD_REQUEST).startsWith("NC-");
        assertThat(NovaConstants.ERROR_UNAUTHORIZED).startsWith("NC-");
        assertThat(NovaConstants.ERROR_FORBIDDEN).startsWith("NC-");
        assertThat(NovaConstants.ERROR_NOT_FOUND).startsWith("NC-");
        assertThat(NovaConstants.ERROR_CONFLICT).startsWith("NC-");
        assertThat(NovaConstants.ERROR_INVITATION_EXPIRED).startsWith("NC-");
        assertThat(NovaConstants.ERROR_INVITATION_USED).startsWith("NC-");
        assertThat(NovaConstants.ERROR_TOO_MANY_REQUESTS).startsWith("NC-");
        assertThat(NovaConstants.ERROR_INTERNAL).startsWith("NC-");
        assertThat(NovaConstants.ERROR_SERVICE_UNAVAILABLE).startsWith("NC-");
    }
    
    @Test
    void timeoutsShouldBePositive() {
        assertThat(NovaConstants.HEARTBEAT_INTERVAL_MS).isPositive();
        assertThat(NovaConstants.CONNECTION_TIMEOUT_MS).isPositive();
        assertThat(NovaConstants.AUTH_TIMEOUT_MS).isPositive();
    }
    
    @Test
    void limitsShouldBePositive() {
        assertThat(NovaConstants.MAX_MESSAGE_LENGTH).isPositive();
        assertThat(NovaConstants.MAX_CHANNEL_NAME_LENGTH).isPositive();
        assertThat(NovaConstants.MAX_PASSWORD_LENGTH).isPositive();
        assertThat(NovaConstants.INVITATION_CODE_LENGTH).isPositive();
        assertThat(NovaConstants.PRIVATE_CHANNEL_ID_LENGTH).isPositive();
    }
}
