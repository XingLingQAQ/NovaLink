package com.nova.link.channel;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Extremely detailed stage-by-stage tests for {@link MessagePipeline}.
 * Every {@link MessagePipelineResult.DropReason} and happy-path branch is covered.
 */
@DisplayName("MessagePipeline detailed")
class MessagePipelineDetailedTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MessagePipeline pipeline;
    private MuteManager muteManager;
    private SensitiveWordFilter filter;
    private PermissionManager permissionManager;

    private UUID superAdminId;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);
        when(networkHandler.getConnections()).thenReturn(Set.of());

        permissionManager = new PermissionManager();
        superAdminId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        muteManager = new MuteManager(null, permissionManager, channelManager);
        filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord("spam");

        pipeline = new MessagePipeline(channelManager, networkHandler);
        pipeline.setMuteManager(muteManager);
        pipeline.setSensitiveWordFilter(filter);
        // process() always enforces boundary; processForChannel defaults to trusted (off).

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .permission("novachat.channel.staff")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("local")
                .displayName("Local")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("party")
                .displayName("Party")
                .scope(ChannelScope.PRIVATE)
                .clientId("Survival")
                .ownerId(senderId)
                .build());
    }

    private ChatMessagePacket msg(String channelId, String content) {
        return new ChatMessagePacket(senderId, "Steve", "Survival", channelId, content);
    }

    private ChatMessagePacket msg(String clientId, String channelId, String content) {
        return new ChatMessagePacket(senderId, "Steve", clientId, channelId, content);
    }

    private ClientConnection mockConn(String clientId, boolean auth, boolean active) {
        ClientConnection c = mock(ClientConnection.class);
        when(c.getClientId()).thenReturn(clientId);
        when(c.isAuthenticated()).thenReturn(auth);
        when(c.isActive()).thenReturn(active);
        when(c.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return c;
    }

    // -------------------------------------------------------------------------
    // Stage 1: Validation
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 1: Validation")
    class Validation {

        @Test
        @DisplayName("null message → NULL_MESSAGE")
        void nullMessage() {
            MessagePipelineResult r = pipeline.process(null);
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NULL_MESSAGE);
            assertThat(r.getMessage()).isNull();
            assertThat(r.getChannel()).isNull();
            assertThat(r.getRecipients()).isEmpty();
            verify(networkHandler, never()).getConnections();
        }

        @Test
        @DisplayName("null content → EMPTY_CONTENT")
        void nullContent() {
            ChatMessagePacket m = msg("global", "x");
            m.setContent(null);
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.EMPTY_CONTENT);
            assertThat(r.getMessage()).isSameAs(m);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t", "\n", " \t\n "})
        @DisplayName("blank/whitespace content → EMPTY_CONTENT")
        void blankContent(String content) {
            assertThat(pipeline.process(msg("global", content)).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.EMPTY_CONTENT);
        }

        @Test
        @DisplayName("content longer than MAX → OVERSIZED_CONTENT")
        void oversized() {
            String huge = "x".repeat(NovaConstants.MAX_MESSAGE_LENGTH + 1);
            MessagePipelineResult r = pipeline.process(msg("global", huge));
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.OVERSIZED_CONTENT);
            assertThat(r.getMessage().getContent()).hasSize(NovaConstants.MAX_MESSAGE_LENGTH + 1);
        }

        @Test
        @DisplayName("content at exact MAX length passes length gate and can deliver")
        void exactMaxLength() {
            ClientConnection c1 = mockConn("c1", true, true);
            ClientConnection c2 = mockConn("c2", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1, c2));

            String exact = "y".repeat(NovaConstants.MAX_MESSAGE_LENGTH);
            MessagePipelineResult r = pipeline.process(msg("global", exact));

            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.OVERSIZED_CONTENT);
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.EMPTY_CONTENT);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getMessage().getContent()).hasSize(NovaConstants.MAX_MESSAGE_LENGTH);
            assertThat(r.getRecipients()).containsExactlyInAnyOrder("c1", "c2");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("missing channelId → MISSING_CHANNEL_ID")
        void missingChannelId(String channelId) {
            ChatMessagePacket m = msg("global", "hello");
            m.setChannelId(channelId);
            assertThat(pipeline.process(m).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.MISSING_CHANNEL_ID);
        }

        @Test
        @DisplayName("validation order: empty content checked before channel id")
        void emptyContentBeforeMissingChannel() {
            ChatMessagePacket packet = new ChatMessagePacket();
            packet.setSenderId(senderId);
            packet.setSenderName("Steve");
            packet.setClientId("Survival");
            packet.setChannelId(null);
            packet.setContent("  ");

            assertThat(pipeline.process(packet).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.EMPTY_CONTENT);
        }

        @Test
        @DisplayName("validation order: oversized checked before channel id")
        void oversizedBeforeMissingChannel() {
            ChatMessagePacket packet = new ChatMessagePacket();
            packet.setSenderId(senderId);
            packet.setSenderName("Steve");
            packet.setClientId("Survival");
            packet.setChannelId("");
            packet.setContent("z".repeat(NovaConstants.MAX_MESSAGE_LENGTH + 1));

            assertThat(pipeline.process(packet).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.OVERSIZED_CONTENT);
        }
    }

    // -------------------------------------------------------------------------
    // Stage 2: Channel resolve
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 2: Channel resolve")
    class ChannelResolve {

        @Test
        @DisplayName("unknown channel → CHANNEL_NOT_FOUND")
        void unknown() {
            MessagePipelineResult r = pipeline.process(msg("nope", "hi"));
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.CHANNEL_NOT_FOUND);
            assertThat(r.getChannel()).isNull();
            assertThat(r.getMessage().getChannelId()).isEqualTo("nope");
        }

        @Test
        @DisplayName("known channel continues past resolve stage")
        void knownChannelProceeds() {
            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            MessagePipelineResult r = pipeline.process(msg("global", "hello"));
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.CHANNEL_NOT_FOUND);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getChannel()).isNotNull();
            assertThat(r.getChannel().getId()).isEqualTo("global");
        }

        @Test
        @DisplayName("processForChannel rejects null channel")
        void processForChannelNullChannel() {
            assertThatThrownBy(() -> pipeline.processForChannel(null, msg("global", "hi")))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("channel");
        }

        @Test
        @DisplayName("processForChannel rejects null message")
        void processForChannelNullMessage() {
            Channel channel = channelManager.getChannel("global");
            assertThatThrownBy(() -> pipeline.processForChannel(channel, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
        }
    }

    // -------------------------------------------------------------------------
    // Stage 3: Cross-client boundary
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 3: Cross-client boundary")
    class CrossClient {

        @Test
        @DisplayName("SERVER with wrong clientId → CROSS_CLIENT_DENIED when enforced")
        void serverDenied() {
            ChatMessagePacket m = msg("local", "hi");
            m.setClientId("OtherServer");
            // process() always enforces boundary
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
            assertThat(r.getChannel()).isNotNull();
            assertThat(r.getChannel().getId()).isEqualTo("local");
            verify(networkHandler, never()).findByClientId(any());
        }

        @Test
        @DisplayName("PRIVATE with wrong clientId → CROSS_CLIENT_DENIED when enforced")
        void privateDenied() {
            ChatMessagePacket m = msg("party", "hi");
            m.setClientId("OtherServer");
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
            assertThat(r.getChannel().getScope()).isEqualTo(ChannelScope.PRIVATE);
        }

        @Test
        @DisplayName("SERVER with null sender client → CROSS_CLIENT_DENIED")
        void serverNullSenderClientDenied() {
            ChatMessagePacket packet = new ChatMessagePacket(senderId, "Steve", null, "local", "hello");
            assertThat(pipeline.process(packet).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
        }

        @Test
        @DisplayName("SERVER owner client is allowed through boundary")
        void serverOwnerAllowed() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            MessagePipelineResult r = pipeline.process(msg("local", "hello"));
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("Survival");
            verify(owner).sendPacket(any());
        }

        @Test
        @DisplayName("PRIVATE owner client is allowed through boundary")
        void privateOwnerAllowed() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            MessagePipelineResult r = pipeline.process(msg("party", "secret"));
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("Survival");
            verify(owner).sendPacket(any());
        }

        @Test
        @DisplayName("SERVER wrong client allowed when boundary enforcement disabled")
        void boundaryDisabled() {
            ChatMessagePacket m = msg("local", "hi");
            m.setClientId("OtherServer");

            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            // Trusted path: processForChannel defaults to boundary OFF
            MessagePipelineResult r = pipeline.processForChannel(channelManager.getChannel("local"), m);
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("Survival");
        }

        @Test
        @DisplayName("PRIVATE wrong client allowed when boundary enforcement disabled")
        void boundaryDisabledPrivate() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            MessagePipelineResult r = pipeline.processForChannel(
                    channelManager.getChannel("party"), msg("Other", "party", "hi"));
            assertThat(r.isDelivered()).isTrue();
        }

        @Test
        @DisplayName("GLOBAL never triggers CROSS_CLIENT_DENIED")
        void globalOk() {
            ClientConnection c1 = mockConn("AnyClient", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            ChatMessagePacket m = msg("global", "hi");
            m.setClientId("AnyClient");
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
            assertThat(r.isDelivered()).isTrue();
        }

        @Test
        @DisplayName("processForChannel with enforce=true still applies boundary")
        void processForChannelEnforcesBoundary() {
            Channel server = channelManager.getChannel("local");
            MessagePipelineResult r = pipeline.processForChannel(server, msg("wrong", "local", "hello"), true);
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
        }

        @Test
        @DisplayName("processForChannel with enforce=false skips boundary")
        void processForChannelSkipsBoundary() {
            Channel server = channelManager.getChannel("local");
            MessagePipelineResult r = pipeline.processForChannel(server, msg("wrong", "local", "hello"), false);
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
        }
    }

    // -------------------------------------------------------------------------
    // Stage 4: Mute
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 4: Mute")
    class Mute {

        @Test
        @DisplayName("channel mute → SENDER_MUTED")
        void channelMute() {
            MuteResult muteResult = muteManager.mutePlayer(
                    superAdminId, senderId, "global", 60_000, "t", "Survival");
            assertThat(muteResult.isSuccess()).isTrue();

            ClientConnection c = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            MessagePipelineResult r = pipeline.process(msg("global", "hello"));
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            assertThat(r.getChannel()).isNotNull();
            assertThat(r.getChannel().getId()).isEqualTo("global");
            verify(c, never()).sendPacket(any());
        }

        @Test
        @DisplayName("global mute (null channel) blocks all channels")
        void globalMute() {
            MuteResult muteResult = muteManager.mutePlayer(
                    superAdminId, senderId, null, 60_000, "t", "Survival");
            assertThat(muteResult.isSuccess()).isTrue();
            assertThat(muteManager.isMuted(senderId, "global")).isTrue();

            assertThat(pipeline.process(msg("global", "hello")).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            assertThat(pipeline.process(msg("local", "hello")).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
        }

        @Test
        @DisplayName("processForChannel also enforces mute")
        void processForChannelMute() {
            muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "t", "Survival");
            Channel ch = channelManager.getChannel("global");
            assertThat(pipeline.processForChannel(ch, msg("global", "x")).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
        }

        @Test
        @DisplayName("null senderId is not treated as muted")
        void nullSenderNotMuted() {
            muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "t", "Survival");
            ChatMessagePacket m = msg("global", "hello");
            m.setSenderId(null);
            assertThat(pipeline.process(m).getDropReason())
                    .isNotEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
        }

        @Test
        @DisplayName("non-muted sender is not dropped for mute")
        void nonMutedPasses() {
            ClientConnection c = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            MessagePipelineResult r = pipeline.process(msg("global", "hello clean"));
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            assertThat(r.isDelivered()).isTrue();
        }

        @Test
        @DisplayName("mute of different player does not affect sender")
        void otherPlayerMuteIrrelevant() {
            UUID other = UUID.randomUUID();
            muteManager.mutePlayer(superAdminId, other, "global", 60_000, "other", "admin");
            ClientConnection c = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            assertThat(pipeline.process(msg("global", "i am free")).isDelivered()).isTrue();
        }

        @Test
        @DisplayName("null muteManager skips mute check")
        void nullMuteManagerSkips() {
            muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "x", "a");
            pipeline.setMuteManager(null);

            ClientConnection c = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            MessagePipelineResult r = pipeline.process(msg("global", "hello"));
            assertThat(r.getDropReason()).isNotEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            assertThat(r.isDelivered()).isTrue();
        }

        @Test
        @DisplayName("SENDER_MUTED also applies on SERVER channel via processForChannel")
        void muteOnServerChannel() {
            muteManager.mutePlayer(superAdminId, senderId, "local", 60_000, "t", "a");
            Channel channel = channelManager.getChannel("local");

            MessagePipelineResult r = pipeline.processForChannel(channel, msg("Survival", "local", "muted"));
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
        }
    }

    // -------------------------------------------------------------------------
    // Stage 5: Sensitive-word filter
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 5: Sensitive-word filter")
    class Filter {

        @Test
        @DisplayName("sensitive word is replaced in packet content")
        void filtersContent() {
            ClientConnection c = mockConn("Survival", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            ChatMessagePacket m = msg("global", "please no spam here");
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.isContentFiltered()).isTrue();
            assertThat(r.getFilterMatchCount()).isGreaterThanOrEqualTo(1);
            assertThat(m.getContent()).isEqualTo("please no *** here");
            assertThat(r.getMessage().getContent()).isEqualTo("please no *** here");
        }

        @Test
        @DisplayName("clean message is not marked filtered")
        void cleanUnchanged() {
            ClientConnection c = mockConn("Survival", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            ChatMessagePacket m = msg("global", "hello world");
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.isContentFiltered()).isFalse();
            assertThat(r.getFilterMatchCount()).isZero();
            assertThat(m.getContent()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("null filter is a no-op")
        void nullFilter() {
            pipeline.setSensitiveWordFilter(null);
            ClientConnection c = mockConn("Survival", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));
            ChatMessagePacket m = msg("global", "spam spam");
            MessagePipelineResult r = pipeline.process(m);
            assertThat(r.isDelivered()).isTrue();
            assertThat(m.getContent()).isEqualTo("spam spam");
            assertThat(r.isContentFiltered()).isFalse();
        }

        @Test
        @DisplayName("filtered content is what gets fan-out to connections")
        void filteredContentIsSent() {
            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            ChatMessagePacket packet = msg("global", "spam alert");
            pipeline.process(packet);

            verify(c1).sendPacket(packet);
            assertThat(packet.getContent()).contains("***");
        }
    }

    // -------------------------------------------------------------------------
    // Stage 6: Fan-out
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 6: Fan-out")
    class FanOut {

        @Test
        @DisplayName("GLOBAL delivers to all authenticated active clients (2 mock connections)")
        void globalTwoClients() {
            ClientConnection a = mockConn("A", true, true);
            ClientConnection b = mockConn("B", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(a, b));

            ChatMessagePacket packet = msg("global", "hi");
            MessagePipelineResult r = pipeline.process(packet);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NONE);
            assertThat(r.getRecipients()).containsExactlyInAnyOrder("A", "B");
            assertThat(r.getChannel().getScope()).isEqualTo(ChannelScope.GLOBAL);
            verify(a).sendPacket(packet);
            verify(b).sendPacket(packet);
        }

        @Test
        @DisplayName("GLOBAL skips unauthenticated and inactive clients")
        void skipsBadConnections() {
            ClientConnection good = mockConn("Good", true, true);
            ClientConnection unauth = mockConn("Unauth", false, true);
            ClientConnection inactive = mockConn("Inactive", true, false);
            when(networkHandler.getConnections()).thenReturn(Set.of(good, unauth, inactive));

            MessagePipelineResult r = pipeline.process(msg("global", "hi"));
            assertThat(r.getRecipients()).containsExactly("Good");
            verify(good).sendPacket(any());
            verify(unauth, never()).sendPacket(any());
            verify(inactive, never()).sendPacket(any());
        }

        @Test
        @DisplayName("GLOBAL permission checker filters one of two clients")
        void permissionFilter() {
            ClientConnection staff = mockConn("StaffNode", true, true);
            ClientConnection survival = mockConn("Survival", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(staff, survival));
            pipeline.setPermissionChecker((clientId, perm) -> "StaffNode".equals(clientId));

            MessagePipelineResult r = pipeline.process(msg("staff", "secret"));
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("StaffNode");
            verify(staff).sendPacket(any());
            verify(survival, never()).sendPacket(any());
        }

        @Test
        @DisplayName("permission filter via ClientPermissionRegistry.asChecker")
        void permissionFilterViaRegistry() {
            ClientConnection staff = mockConn("staff-node", true, true);
            ClientConnection publicNode = mockConn("public-node", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(staff, publicNode));

            ClientPermissionRegistry registry = new ClientPermissionRegistry();
            registry.grant("staff-node", "novachat.channel.staff");
            pipeline.setPermissionChecker(registry.asChecker());

            MessagePipelineResult r = pipeline.process(msg("staff", "mod talk"));
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("staff-node");
            verify(staff).sendPacket(any());
            verify(publicNode, never()).sendPacket(any());
        }

        @Test
        @DisplayName("GLOBAL without permission node delivers to all authenticated active clients")
        void noPermissionNodeAllClients() {
            ClientConnection a = mockConn("a", true, true);
            ClientConnection b = mockConn("b", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(a, b));

            MessagePipelineResult r = pipeline.process(msg("global", "broadcast"));
            assertThat(r.getRecipients()).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("setPermissionChecker(null) restores always-allow checker")
        void nullPermissionCheckerAllowsAll() {
            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            pipeline.setPermissionChecker((id, perm) -> false);
            assertThat(pipeline.process(msg("staff", "x")).isDelivered()).isFalse();

            pipeline.setPermissionChecker(null);
            MessagePipelineResult r = pipeline.process(msg("staff", "x"));
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("c1");
        }

        @Test
        @DisplayName("SERVER routes only to owning client")
        void serverScope() {
            ClientConnection owner = mockConn("Survival", true, true);
            ClientConnection other = mockConn("Creative", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);
            when(networkHandler.getConnections()).thenReturn(Set.of(owner, other));

            ChatMessagePacket packet = msg("local", "hi");
            MessagePipelineResult r = pipeline.process(packet);
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("Survival");
            verify(owner).sendPacket(packet);
            verify(other, never()).sendPacket(any());
            verify(networkHandler).findByClientId("Survival");
        }

        @Test
        @DisplayName("PRIVATE routes only to owning client (same as SERVER)")
        void privateScope() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            MessagePipelineResult r = pipeline.process(msg("party", "hi"));
            assertThat(r.isDelivered()).isTrue();
            assertThat(r.getRecipients()).containsExactly("Survival");
            verify(owner).sendPacket(any());
        }

        @Test
        @DisplayName("no online recipients → NO_RECIPIENTS")
        void noRecipients() {
            when(networkHandler.getConnections()).thenReturn(Set.of());
            MessagePipelineResult r = pipeline.process(msg("global", "hi"));
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
            assertThat(r.getChannel()).isNotNull();
            assertThat(r.getRecipients()).isEmpty();
        }

        @Test
        @DisplayName("only inactive / unauthenticated connections → NO_RECIPIENTS")
        void onlyInactiveOrUnauthenticated() {
            ClientConnection dead = mockConn("dead", true, false);
            ClientConnection unauth = mockConn("unauth", false, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(dead, unauth));

            MessagePipelineResult r = pipeline.process(msg("global", "hello"));
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
        }

        @Test
        @DisplayName("SERVER owner offline → NO_RECIPIENTS")
        void serverOwnerOffline() {
            when(networkHandler.findByClientId("Survival")).thenReturn(null);
            assertThat(pipeline.process(msg("local", "hi")).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
        }

        @Test
        @DisplayName("SERVER owner connection inactive → NO_RECIPIENTS")
        void serverOwnerInactive() {
            ClientConnection sleepy = mockConn("Survival", true, false);
            when(networkHandler.findByClientId("Survival")).thenReturn(sleepy);

            MessagePipelineResult r = pipeline.process(msg("local", "hello"));
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
            verify(sleepy, never()).sendPacket(any());
        }

        @Test
        @DisplayName("GLOBAL connection with null clientId still receives packet but is not listed")
        void nullClientIdStillReceivesButNotListed() {
            ClientConnection noId = mock(ClientConnection.class);
            when(noId.getClientId()).thenReturn(null);
            when(noId.isAuthenticated()).thenReturn(true);
            when(noId.isActive()).thenReturn(true);
            when(noId.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
            when(networkHandler.getConnections()).thenReturn(Set.of(noId));

            MessagePipelineResult r = pipeline.process(msg("global", "hello"));
            verify(noId).sendPacket(any());
            assertThat(r.isDelivered()).isFalse();
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
        }
    }

    // -------------------------------------------------------------------------
    // Stage 7: Side channels
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Stage 7: Side channels (spy / websocket)")
    class SideChannels {

        @Test
        @DisplayName("successful delivery notifies spy and websocket")
        void sideChannelsOnSuccess() {
            ClientConnection c = mockConn("Survival", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c));

            SpyManager spy = mock(SpyManager.class);
            AtomicInteger wsCalls = new AtomicInteger();
            AtomicReference<String> wsContent = new AtomicReference<>();
            pipeline.setSpyManager(spy);
            pipeline.setWebSocketBroadcaster((ch, sid, name, content) -> {
                wsCalls.incrementAndGet();
                wsContent.set(content);
            });

            ChatMessagePacket packet = msg("global", "hi");
            MessagePipelineResult r = pipeline.process(packet);
            assertThat(r.isDelivered()).isTrue();
            verify(spy, times(1)).forwardToSpies(packet);
            assertThat(wsCalls.get()).isEqualTo(1);
            assertThat(wsContent.get()).isEqualTo("hi");
        }

        @Test
        @DisplayName("WebSocketBroadcaster receives correct args on success")
        void webSocketArgs() {
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setWebSocketBroadcaster(ws);

            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            ChatMessagePacket packet = msg("global", "hello panel");
            assertThat(pipeline.process(packet).isDelivered()).isTrue();

            verify(ws).broadcastChatMessage(
                    eq("global"),
                    eq(senderId.toString()),
                    eq("Steve"),
                    eq("hello panel"));
        }

        @Test
        @DisplayName("both spy and websocket called together on success with 2 clients")
        void bothSideChannelsOnSuccess() {
            SpyManager spyManager = mock(SpyManager.class);
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setSpyManager(spyManager);
            pipeline.setWebSocketBroadcaster(ws);

            ClientConnection c1 = mockConn("c1", true, true);
            ClientConnection c2 = mockConn("c2", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1, c2));

            ChatMessagePacket packet = msg("global", "broadcast");
            assertThat(pipeline.process(packet).isDelivered()).isTrue();

            verify(spyManager).forwardToSpies(packet);
            verify(ws).broadcastChatMessage(eq("global"), any(), eq("Steve"), eq("broadcast"));
        }

        @Test
        @DisplayName("spy/ws still invoked for GLOBAL with NO_RECIPIENTS (ops visibility)")
        void globalNoRecipientsStillMirrors() {
            SpyManager spyManager = mock(SpyManager.class);
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setSpyManager(spyManager);
            pipeline.setWebSocketBroadcaster(ws);
            when(networkHandler.getConnections()).thenReturn(Set.of());

            ChatMessagePacket packet = msg("global", "nobody online");
            MessagePipelineResult r = pipeline.process(packet);

            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.NO_RECIPIENTS);
            verify(spyManager).forwardToSpies(packet);
            verify(ws).broadcastChatMessage(eq("global"), any(), eq("Steve"), eq("nobody online"));
        }

        @Test
        @DisplayName("muted message does not notify spy/websocket")
        void mutedNoSideChannels() {
            muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "t", "Survival");
            SpyManager spy = mock(SpyManager.class);
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setSpyManager(spy);
            pipeline.setWebSocketBroadcaster(ws);

            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            MessagePipelineResult r = pipeline.process(msg("global", "hi"));
            assertThat(r.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.SENDER_MUTED);
            verifyNoInteractions(spy);
            verifyNoInteractions(ws);
        }

        @Test
        @DisplayName("null spy/ws does not throw on delivery")
        void nullSideChannelsSafe() {
            pipeline.setSpyManager(null);
            pipeline.setWebSocketBroadcaster(null);
            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            assertThat(pipeline.process(msg("global", "ok")).isDelivered()).isTrue();
        }

        @Test
        @DisplayName("websocket uses null senderId string when senderId is null")
        void webSocketNullSenderId() {
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setWebSocketBroadcaster(ws);
            ClientConnection c1 = mockConn("c1", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(c1));

            ChatMessagePacket packet = new ChatMessagePacket(null, "Anon", "c1", "global", "hi");
            pipeline.process(packet);

            verify(ws).broadcastChatMessage(eq("global"), eq(null), eq("Anon"), eq("hi"));
        }

        @Test
        @DisplayName("SERVER delivery also mirrors to spy/ws")
        void serverDeliveryMirrors() {
            SpyManager spyManager = mock(SpyManager.class);
            MessageRouter.WebSocketBroadcaster ws = mock(MessageRouter.WebSocketBroadcaster.class);
            pipeline.setSpyManager(spyManager);
            pipeline.setWebSocketBroadcaster(ws);

            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            ChatMessagePacket packet = msg("local", "local msg");
            assertThat(pipeline.process(packet).isDelivered()).isTrue();

            verify(spyManager).forwardToSpies(packet);
            verify(ws).broadcastChatMessage(eq("local"), any(), eq("Steve"), eq("local msg"));
        }
    }

    // -------------------------------------------------------------------------
    // calculateRecipients dry-run
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("calculateRecipients dry-run")
    class CalculateRecipients {

        @Test
        @DisplayName("GLOBAL respects permission checker without sending")
        void dryRunPermission() {
            ClientConnection a = mockConn("A", true, true);
            ClientConnection b = mockConn("B", true, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(a, b));
            pipeline.setPermissionChecker((id, p) -> "A".equals(id));

            Set<String> recipients = pipeline.calculateRecipients(channelManager.getChannel("staff"));
            assertThat(recipients).containsExactly("A");
            verify(a, never()).sendPacket(any());
            verify(b, never()).sendPacket(any());
        }

        @Test
        @DisplayName("GLOBAL without permission returns all authenticated clients")
        void globalAllAuthenticated() {
            ClientConnection a = mockConn("a", true, true);
            ClientConnection b = mockConn("b", true, true);
            ClientConnection unauth = mockConn("u", false, true);
            when(networkHandler.getConnections()).thenReturn(Set.of(a, b, unauth));

            Set<String> recipients = pipeline.calculateRecipients(channelManager.getChannel("global"));
            assertThat(recipients).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("SERVER dry-run only includes online owner")
        void dryRunServer() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);
            when(owner.isAuthenticated()).thenReturn(true);

            Set<String> recipients = pipeline.calculateRecipients(channelManager.getChannel("local"));
            assertThat(recipients).containsExactly("Survival");
        }

        @Test
        @DisplayName("SERVER empty when owner missing or unauthenticated")
        void serverOwnerMissingOrUnauth() {
            when(networkHandler.findByClientId("Survival")).thenReturn(null);
            assertThat(pipeline.calculateRecipients(channelManager.getChannel("local"))).isEmpty();

            ClientConnection unauth = mockConn("Survival", false, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(unauth);
            assertThat(pipeline.calculateRecipients(channelManager.getChannel("local"))).isEmpty();
        }

        @Test
        @DisplayName("PRIVATE same as SERVER for calculateRecipients")
        void privateSameAsServer() {
            ClientConnection owner = mockConn("Survival", true, true);
            when(networkHandler.findByClientId("Survival")).thenReturn(owner);

            assertThat(pipeline.calculateRecipients(channelManager.getChannel("party")))
                    .containsExactly("Survival");
        }

        @Test
        @DisplayName("calculateRecipients rejects null channel")
        void nullChannelThrows() {
            assertThatThrownBy(() -> pipeline.calculateRecipients(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("calculateRecipients ignores inactive flag (only auth + clientId)")
        void ignoresActiveFlag() {
            ClientConnection inactiveButAuth = mockConn("x", true, false);
            when(networkHandler.getConnections()).thenReturn(Set.of(inactiveButAuth));

            Set<String> recipients = pipeline.calculateRecipients(channelManager.getChannel("global"));
            assertThat(recipients).containsExactly("x");
        }
    }

    // -------------------------------------------------------------------------
    // Construction / flags
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Construction and flags")
    class Construction {

        @Test
        @DisplayName("constructor rejects null channelManager")
        void nullChannelManager() {
            assertThatThrownBy(() -> new MessagePipeline(null, networkHandler))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("channelManager");
        }

        @Test
        @DisplayName("constructor rejects null networkHandler")
        void nullNetworkHandler() {
            assertThatThrownBy(() -> new MessagePipeline(channelManager, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("networkHandler");
        }

        @Test
        @DisplayName("process() always enforces boundary (no mutable flag)")
        void processAlwaysEnforcesBoundary() {
            MessagePipeline fresh = new MessagePipeline(channelManager, networkHandler);
            ChatMessagePacket m = msg("local", "hi");
            m.setClientId("OtherServer");
            assertThat(fresh.process(m).getDropReason())
                    .isEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
        }

        @Test
        @DisplayName("processForChannel defaults to trusted (boundary off)")
        void processForChannelDefaultsTrusted() {
            MessagePipeline fresh = new MessagePipeline(channelManager, networkHandler);
            Channel ch = channelManager.getChannel("local");
            ChatMessagePacket m = msg("local", "hi");
            m.setClientId("OtherServer");
            // No recipients online → NO_RECIPIENTS rather than CROSS_CLIENT
            assertThat(fresh.processForChannel(ch, m).getDropReason())
                    .isNotEqualTo(MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED);
        }
    }
}
