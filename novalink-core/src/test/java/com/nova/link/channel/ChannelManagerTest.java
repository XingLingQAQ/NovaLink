package com.nova.link.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ChannelManager.
 * 
 * Requirements: 20.1, 20.5
 */
@DisplayName("ChannelManager Unit Tests")
class ChannelManagerTest {

    private ChannelManager channelManager;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
    }

    // ==================== createChannel tests ====================

    @Test
    @DisplayName("createChannel - creates global channel successfully")
    void createChannel_globalChannel_success() {
        ChannelConfig config = ChannelConfig.builder()
                .id("global-chat")
                .displayName("Global Chat")
                .scope(ChannelScope.GLOBAL)
                .build();

        Channel channel = channelManager.createChannel(config);

        assertThat(channel).isNotNull();
        assertThat(channel.getId()).isEqualTo("global-chat");
        assertThat(channel.getDisplayName()).isEqualTo("Global Chat");
        assertThat(channel.getScope()).isEqualTo(ChannelScope.GLOBAL);
        assertThat(channelManager.channelExists("global-chat")).isTrue();
    }

    @Test
    @DisplayName("createChannel - creates server channel successfully")
    void createChannel_serverChannel_success() {
        ChannelConfig config = ChannelConfig.builder()
                .id("server-local")
                .displayName("Local Chat")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build();

        Channel channel = channelManager.createChannel(config);

        assertThat(channel).isNotNull();
        assertThat(channel.getId()).isEqualTo("server-local");
        assertThat(channel.getScope()).isEqualTo(ChannelScope.SERVER);
        assertThat(channel.getClientId()).isEqualTo("client-1");
    }

    @Test
    @DisplayName("createChannel - creates private channel with auto-generated ID")
    void createChannel_privateChannel_autoGeneratesId() {
        ChannelConfig config = ChannelConfig.builder()
                .displayName("Private Room")
                .scope(ChannelScope.PRIVATE)
                .clientId("client-1")
                .build();

        Channel channel = channelManager.createChannel(config);

        assertThat(channel).isNotNull();
        assertThat(channel.getId()).startsWith("NC-");
        assertThat(channel.getId()).hasSize(7); // NC- + 4 chars
        assertThat(channel.getScope()).isEqualTo(ChannelScope.PRIVATE);
    }

    @Test
    @DisplayName("createChannel - throws exception for duplicate ID")
    void createChannel_duplicateId_throwsException() {
        ChannelConfig config1 = ChannelConfig.builder()
                .id("test-channel")
                .scope(ChannelScope.GLOBAL)
                .build();
        channelManager.createChannel(config1);

        ChannelConfig config2 = ChannelConfig.builder()
                .id("test-channel")
                .scope(ChannelScope.GLOBAL)
                .build();

        assertThatThrownBy(() -> channelManager.createChannel(config2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createChannel - throws exception for null config")
    void createChannel_nullConfig_throwsException() {
        assertThatThrownBy(() -> channelManager.createChannel(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== deleteChannel tests ====================

    @Test
    @DisplayName("deleteChannel - deletes existing channel")
    void deleteChannel_existingChannel_returnsTrue() {
        ChannelConfig config = ChannelConfig.builder()
                .id("to-delete")
                .scope(ChannelScope.GLOBAL)
                .build();
        channelManager.createChannel(config);

        boolean result = channelManager.deleteChannel("to-delete");

        assertThat(result).isTrue();
        assertThat(channelManager.channelExists("to-delete")).isFalse();
    }

    @Test
    @DisplayName("deleteChannel - returns false for non-existent channel")
    void deleteChannel_nonExistentChannel_returnsFalse() {
        boolean result = channelManager.deleteChannel("non-existent");

        assertThat(result).isFalse();
    }

    // ==================== getChannel tests ====================

    @Test
    @DisplayName("getChannel - returns channel for existing ID")
    void getChannel_existingId_returnsChannel() {
        ChannelConfig config = ChannelConfig.builder()
                .id("test-get")
                .displayName("Test Channel")
                .scope(ChannelScope.GLOBAL)
                .build();
        channelManager.createChannel(config);

        Channel channel = channelManager.getChannel("test-get");

        assertThat(channel).isNotNull();
        assertThat(channel.getId()).isEqualTo("test-get");
    }

    @Test
    @DisplayName("getChannel - returns null for non-existent ID")
    void getChannel_nonExistentId_returnsNull() {
        Channel channel = channelManager.getChannel("non-existent");

        assertThat(channel).isNull();
    }

    // ==================== getChannelsByClient tests ====================

    @Test
    @DisplayName("getChannelsByClient - returns channels for client")
    void getChannelsByClient_existingClient_returnsChannels() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("client1-ch1")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("client1-ch2")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("client2-ch1")
                .scope(ChannelScope.SERVER)
                .clientId("client-2")
                .build());

        List<Channel> channels = channelManager.getChannelsByClient("client-1");

        assertThat(channels).hasSize(2);
        assertThat(channels).extracting(Channel::getId)
                .containsExactlyInAnyOrder("client1-ch1", "client1-ch2");
    }

    @Test
    @DisplayName("getChannelsByClient - returns empty list for unknown client")
    void getChannelsByClient_unknownClient_returnsEmptyList() {
        List<Channel> channels = channelManager.getChannelsByClient("unknown-client");

        assertThat(channels).isEmpty();
    }

    // ==================== getGlobalChannels tests ====================

    @Test
    @DisplayName("getGlobalChannels - returns only global channels")
    void getGlobalChannels_mixedChannels_returnsOnlyGlobal() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("global-1")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("global-2")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("server-1")
                .scope(ChannelScope.SERVER)
                .clientId("client-1")
                .build());

        List<Channel> globalChannels = channelManager.getGlobalChannels();

        assertThat(globalChannels).hasSize(2);
        assertThat(globalChannels).extracting(Channel::getId)
                .containsExactlyInAnyOrder("global-1", "global-2");
    }

    // ==================== getAllChannels tests ====================

    @Test
    @DisplayName("getAllChannels - returns all channels")
    void getAllChannels_multipleChannels_returnsAll() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("ch1").scope(ChannelScope.GLOBAL).build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("ch2").scope(ChannelScope.SERVER).clientId("c1").build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("ch3").scope(ChannelScope.PRIVATE).clientId("c1").build());

        Collection<Channel> allChannels = channelManager.getAllChannels();

        assertThat(allChannels).hasSize(3);
    }

    // ==================== getChannelCount tests ====================

    @Test
    @DisplayName("getChannelCount - returns correct count")
    void getChannelCount_multipleChannels_returnsCorrectCount() {
        assertThat(channelManager.getChannelCount()).isEqualTo(0);

        channelManager.createChannel(ChannelConfig.builder()
                .id("ch1").scope(ChannelScope.GLOBAL).build());
        assertThat(channelManager.getChannelCount()).isEqualTo(1);

        channelManager.createChannel(ChannelConfig.builder()
                .id("ch2").scope(ChannelScope.GLOBAL).build());
        assertThat(channelManager.getChannelCount()).isEqualTo(2);
    }

    // ==================== addMember/removeMember tests ====================

    @Test
    @DisplayName("addMember - adds member to channel")
    void addMember_validChannel_addsMember() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("test-ch").scope(ChannelScope.GLOBAL).build());
        UUID playerId = UUID.randomUUID();

        boolean result = channelManager.addMember("test-ch", playerId);

        assertThat(result).isTrue();
        assertThat(channelManager.getChannelMembers("test-ch")).contains(playerId);
    }

    @Test
    @DisplayName("addMember - returns false for non-existent channel")
    void addMember_nonExistentChannel_returnsFalse() {
        UUID playerId = UUID.randomUUID();

        boolean result = channelManager.addMember("non-existent", playerId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("removeMember - removes member from channel")
    void removeMember_existingMember_removesMember() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("test-ch").scope(ChannelScope.GLOBAL).build());
        UUID playerId = UUID.randomUUID();
        channelManager.addMember("test-ch", playerId);

        boolean result = channelManager.removeMember("test-ch", playerId);

        assertThat(result).isTrue();
        assertThat(channelManager.getChannelMembers("test-ch")).doesNotContain(playerId);
    }

    @Test
    @DisplayName("removeMember - returns false for non-existent channel")
    void removeMember_nonExistentChannel_returnsFalse() {
        UUID playerId = UUID.randomUUID();

        boolean result = channelManager.removeMember("non-existent", playerId);

        assertThat(result).isFalse();
    }

    // ==================== getChannelMembers tests ====================

    @Test
    @DisplayName("getChannelMembers - returns empty set for non-existent channel")
    void getChannelMembers_nonExistentChannel_returnsEmptySet() {
        Set<UUID> members = channelManager.getChannelMembers("non-existent");

        assertThat(members).isEmpty();
    }

    // ==================== generatePassword tests ====================

    @Test
    @DisplayName("generatePassword - generates 6-character password")
    void generatePassword_generatesValidPassword() {
        String password = channelManager.generatePassword();

        assertThat(password).hasSize(6);
        assertThat(password).matches("[A-Za-z0-9]+");
    }

    // ==================== clear tests ====================

    @Test
    @DisplayName("clear - removes all channels")
    void clear_withChannels_removesAll() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("ch1").scope(ChannelScope.GLOBAL).build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("ch2").scope(ChannelScope.GLOBAL).build());

        channelManager.clear();

        assertThat(channelManager.getChannelCount()).isEqualTo(0);
        assertThat(channelManager.getAllChannels()).isEmpty();
    }
}
