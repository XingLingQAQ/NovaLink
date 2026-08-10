package com.nova.chat.mod.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatInterceptor}, covering the mod-common chat routing
 * logic: HYBRID skip, REPLACE-mode forward, {@code sendToChannel} default
 * fallback + world placeholder, mention highlight routing, online/offline
 * guards, and config-driven {@code globalMode} (incl. reload).
 */
@DisplayName("ChatInterceptor")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorTest {

    private static final UUID PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID OTHER = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
    private static final UUID MENTIONER = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private final List<String> sentToPlayer = new ArrayList<>();
    private final List<String> sentToOther = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sentToPlayer.clear();
        sentToOther.clear();
    }

    private void recordMessages() {
        org.mockito.Mockito.doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            String msg = inv.getArgument(1);
            if (id.equals(PLAYER)) sentToPlayer.add(msg);
            else if (id.equals(OTHER)) sentToOther.add(msg);
            return null;
        }).when(platform).sendMessage(any(), anyString());
    }

    private ModConfig config(boolean replaceVanilla, String defaultChannel) {
        ModConfig config = new ModConfig();
        config.getChat().setReplaceVanilla(replaceVanilla);
        config.getChat().setDefaultChannel(defaultChannel);
        config.getBackend().setUsername("mod-server");
        return config;
    }

    private ChatInterceptor interceptor(boolean replaceVanilla) {
        return new ChatInterceptor(platform, networkClient,
                config(replaceVanilla, "global"),
                new MessageFormatter(Map.of("global", "[{channel_name}] {player}: {message}"), "{player}: {message}"));
    }

    // ============================ onPlayerChat (outgoing) ============================

    @Nested
    @DisplayName("onPlayerChat")
    class OnPlayerChat {

        @Test
        @DisplayName("HYBRID mode skips backend forwarding (no packet sent)")
        void hybridSkipsForwarding() {
            recordMessages();
            ChatInterceptor ci = interceptor(false); // HYBRID
            when(networkClient.isAuthenticated()).thenReturn(true);

            ci.onPlayerChat(PLAYER, "Steve", "hello");

            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("REPLACE mode + not authenticated sends a not-connected error to the player")
        void replaceNotAuthenticatedSendsError() {
            recordMessages();
            ChatInterceptor ci = interceptor(true); // REPLACE
            when(networkClient.isAuthenticated()).thenReturn(false);

            ci.onPlayerChat(PLAYER, "Steve", "hello");

            verify(networkClient, never()).sendPacket(any());
            assertThat(sentToPlayer).hasSize(1);
            // Error prefix is applied via MessageFormatter.formatError
        }

        @Test
        @DisplayName("REPLACE mode + authenticated forwards a ChatMessagePacket to the active channel")
        void replaceAuthenticatedForwardsPacket() {
            recordMessages();
            ChatInterceptor ci = interceptor(true); // REPLACE
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn("overworld");

            ci.onPlayerChat(PLAYER, "Steve", "hello");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            ChatMessagePacket sent = captor.getValue();
            assertThat(sent.getChannelId()).isEqualTo("global"); // default active channel
            assertThat(sent.getSenderName()).isEqualTo("Steve");
            assertThat(sent.getPlaceholders()).containsEntry("world", "overworld");
            assertThat(sent.getPlaceholders()).containsEntry("player", "Steve");
        }

        @Test
        @DisplayName("REPLACE mode with blank message skips forwarding")
        void replaceBlankMessageSkips() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);

            ci.onPlayerChat(PLAYER, "Steve", "");

            verify(networkClient, never()).sendPacket(any());
        }
    }

    // ============================ sendToChannel ============================

    @Nested
    @DisplayName("sendToChannel")
    class SendToChannel {

        @Test
        @DisplayName("not authenticated -> error message, no packet")
        void notAuthenticatedSendsError() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(false);

            ci.sendToChannel(PLAYER, "Steve", "trade", "hi");

            verify(networkClient, never()).sendPacket(any());
            assertThat(sentToPlayer).hasSize(1);
        }

        @Test
        @DisplayName("blank channelId falls back to the configured default channel")
        void blankChannelFallsBackToDefault() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn(null);

            ci.sendToChannel(PLAYER, "Steve", "  ", "hi");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("global");
        }

        @Test
        @DisplayName("null channelId falls back to the configured default channel")
        void nullChannelFallsBackToDefault() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn(null);

            ci.sendToChannel(PLAYER, "Steve", null, "hi");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("global");
        }

        @Test
        @DisplayName("world placeholder is omitted when getCurrentWorld returns null")
        void nullWorldOmitsPlaceholder() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn(null);

            ci.sendToChannel(PLAYER, "Steve", "trade", "hi");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getPlaceholders()).doesNotContainKey("world");
        }

        @Test
        @DisplayName("world placeholder is omitted when getCurrentWorld returns empty")
        void emptyWorldOmitsPlaceholder() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn("");

            ci.sendToChannel(PLAYER, "Steve", "trade", "hi");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getPlaceholders()).doesNotContainKey("world");
        }

        @Test
        @DisplayName("null playerName is coerced to empty in the packet")
        void nullPlayerNameCoercedToEmpty() {
            recordMessages();
            ChatInterceptor ci = interceptor(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            when(platform.getCurrentWorld(PLAYER)).thenReturn("world");

            ci.sendToChannel(PLAYER, null, "trade", "hi");

            ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getSenderName()).isEmpty();
            assertThat(captor.getValue().getPlaceholders()).containsEntry("player", "");
        }
    }

    // ============================ mention routing ============================

    @Nested
    @DisplayName("handleMention (via reflection)")
    class Mention {

        private void invokeHandleMention(ChatInterceptor ci, MentionPacket packet) throws Exception {
            java.lang.reflect.Method m = ChatInterceptor.class.getDeclaredMethod("handleMention", MentionPacket.class);
            m.setAccessible(true);
            m.invoke(ci, packet);
        }

        @Test
        @DisplayName("mention to an online player routes a highlighted chat line containing the mentioner name")
        void mentionOnlinePlayerRoutesHighlight() throws Exception {
            recordMessages();
            ChatInterceptor ci = interceptor(false);
            when(platform.isPlayerOnline(PLAYER)).thenReturn(true);

            invokeHandleMention(ci, new MentionPacket(MENTIONER, "Alex", PLAYER, "global", "hi @Steve", System.currentTimeMillis()));

            assertThat(sentToPlayer).hasSize(1);
            // The mentioner name is prefixed with the highlight color.
            assertThat(sentToPlayer.get(0)).contains("Alex");
            assertThat(sentToPlayer.get(0)).contains("§e"); // &e -> §e
        }

        @Test
        @DisplayName("mention to an offline player is skipped (no message sent)")
        void mentionOfflinePlayerSkipped() throws Exception {
            recordMessages();
            ChatInterceptor ci = interceptor(false);
            when(platform.isPlayerOnline(PLAYER)).thenReturn(false);

            invokeHandleMention(ci, new MentionPacket(MENTIONER, "Alex", PLAYER, "global", "hi", 0L));

            verify(platform, never()).sendMessage(eq(PLAYER), anyString());
            assertThat(sentToPlayer).isEmpty();
        }

        @Test
        @DisplayName("mention with null mentionedId is skipped")
        void mentionNullMentionedIdSkipped() throws Exception {
            recordMessages();
            ChatInterceptor ci = interceptor(false);

            MentionPacket packet = new MentionPacket(MENTIONER, "Alex", null, "global", "hi", 0L);
            invokeHandleMention(ci, packet);

            verify(platform, never()).sendMessage(any(), anyString());
        }

        @Test
        @DisplayName("mention with null mentionerId is skipped")
        void mentionNullMentionerIdSkipped() throws Exception {
            recordMessages();
            ChatInterceptor ci = interceptor(false);

            MentionPacket packet = new MentionPacket(null, "Alex", PLAYER, "global", "hi", 0L);
            invokeHandleMention(ci, packet);

            verify(platform, never()).sendMessage(any(), anyString());
        }

        @Test
        @DisplayName("displayMessage colorizes & codes to section signs")
        void displayMessageColorizes() {
            recordMessages();
            ChatInterceptor ci = interceptor(false);
            when(platform.isPlayerOnline(PLAYER)).thenReturn(true);

            ci.displayMessage(PLAYER, "&ehello &rworld");

            assertThat(sentToPlayer).hasSize(1);
            assertThat(sentToPlayer.get(0)).contains("§e").doesNotContain("&e");
        }

        @Test
        @DisplayName("displayMessage skips when the player is offline")
        void displayMessageSkipsOffline() {
            recordMessages();
            ChatInterceptor ci = interceptor(false);
            when(platform.isPlayerOnline(PLAYER)).thenReturn(false);

            ci.displayMessage(PLAYER, "hello");

            verify(platform, never()).sendMessage(eq(PLAYER), anyString());
            assertThat(sentToPlayer).isEmpty();
        }
    }

    // ============================ state + reload ============================

    @Nested
    @DisplayName("state + reload")
    class StateAndReload {

        @Test
        @DisplayName("globalMode is REPLACE when config.replaceVanilla=true")
        void globalModeIsReplaceWhenConfigured() {
            ChatInterceptor ci = interceptor(true);
            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.REPLACE);
        }

        @Test
        @DisplayName("globalMode is HYBRID when config.replaceVanilla=false")
        void globalModeIsHybridWhenConfigured() {
            ChatInterceptor ci = interceptor(false);
            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.HYBRID);
        }

        @Test
        @DisplayName("setGlobalMode updates the global mode")
        void setGlobalModeUpdates() {
            ChatInterceptor ci = interceptor(false);
            ci.setGlobalMode(ChatMode.REPLACE);
            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.REPLACE);
        }

        @Test
        @DisplayName("reload re-reads replaceVanilla from the config")
        void reloadRereadsConfig() {
            ModConfig config = config(true, "global");
            ChatInterceptor ci = new ChatInterceptor(platform, networkClient, config,
                    new MessageFormatter(Map.of(), "{player}: {message}"));
            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.REPLACE);

            // Flip config to HYBRID and reload
            config.getChat().setReplaceVanilla(false);
            ci.reload();

            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.HYBRID);
        }

        @Test
        @DisplayName("getOrCreateState returns a state seeded with the default channel + global mode")
        void getOrCreateStateSeedsDefaults() {
            ChatInterceptor ci = interceptor(false);
            PlayerChannelState state = ci.getOrCreateState(PLAYER, "Steve");

            assertThat(state).isNotNull();
            assertThat(state.getActiveChannel()).isEqualTo("global");
            assertThat(state.getChatMode()).isEqualTo(ChatMode.HYBRID);
        }

        @Test
        @DisplayName("setPlayerChannel / getPlayerChannel round-trip")
        void setAndGetPlayerChannel() {
            ChatInterceptor ci = interceptor(false);
            ci.setPlayerChannel(PLAYER, "trade");
            assertThat(ci.getPlayerChannel(PLAYER)).isEqualTo("trade");
        }

        @Test
        @DisplayName("removePlayerState clears the state")
        void removePlayerStateClears() {
            ChatInterceptor ci = interceptor(false);
            ci.getOrCreateState(PLAYER, "Steve");
            assertThat(ci.getState(PLAYER)).isNotNull();

            ci.removePlayerState(PLAYER);
            assertThat(ci.getState(PLAYER)).isNull();
        }

        @Test
        @DisplayName("togglePlayerMode flips the per-player chat mode")
        void togglePlayerModeFlips() {
            ChatInterceptor ci = interceptor(false); // HYBRID
            ChatMode before = ci.getOrCreateState(PLAYER, "Steve").getChatMode();
            ChatMode after = ci.togglePlayerMode(PLAYER);
            assertThat(after).isNotEqualTo(before);
        }
    }

    // ============================ null network guard ============================

    @Nested
    @DisplayName("null network client")
    class NullNetwork {

        @Test
        @DisplayName("constructs without registering handlers when networkClient is null")
        void constructsSafelyWithNullNetwork() {
            // registerIncomingHandlers() early-returns on null networkClient; no NPE.
            ChatInterceptor ci = new ChatInterceptor(platform, null,
                    config(false, "global"),
                    new MessageFormatter(Map.of(), "{player}: {message}"));
            assertThat(ci.getGlobalMode()).isEqualTo(ChatMode.HYBRID);
        }
    }

    // ============================ highlight color constant ============================

    @Test
    @DisplayName("MENTION_HIGHLIGHT_COLOR matches MentionNotifier.DEFAULT_HIGHLIGHT_COLOR")
    void mentionHighlightColorMatchesNotifierDefault() {
        assertThat(ChatInterceptor.MENTION_HIGHLIGHT_COLOR)
                .isEqualTo(MentionNotifier.DEFAULT_HIGHLIGHT_COLOR);
    }
}
