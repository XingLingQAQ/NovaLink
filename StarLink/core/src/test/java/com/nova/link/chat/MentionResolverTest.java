package com.nova.link.chat;

import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MentionResolver}: @name resolution, self-mention
 * exclusion, @all expansion, cross-instance skip, and best-effort failure
 * isolation. Verifies the backend emit path for §11.6 Proposal 05.
 */
@DisplayName("MentionResolver backend emit")
class MentionResolverTest {

    private PlayerStateManager playerStateManager;
    private ServerNetworkHandler networkHandler;
    private MentionResolver resolver;
    private MentionNotifier notifier;

    private UUID steveId;
    private UUID alexId;
    private PlayerState steveState;
    private PlayerState alexState;
    private ClientConnection survivalConn;
    private ClientConnection creativeConn;

    @BeforeEach
    void setUp() {
        playerStateManager = mock(PlayerStateManager.class);
        networkHandler = mock(ServerNetworkHandler.class);
        notifier = new MentionNotifier();
        resolver = new MentionResolver(notifier, playerStateManager, networkHandler);

        steveId = UUID.randomUUID();
        alexId = UUID.randomUUID();
        steveState = new PlayerState(steveId, "Steve");
        steveState.setClientId("Survival");
        alexState = new PlayerState(alexId, "Alex");
        alexState.setClientId("Creative");

        survivalConn = mockConn("Survival");
        creativeConn = mockConn("Creative");
        when(networkHandler.findByClientId("Survival")).thenReturn(survivalConn);
        when(networkHandler.findByClientId("Creative")).thenReturn(creativeConn);
        when(playerStateManager.getAllPlayerStates())
                .thenReturn(List.of(steveState, alexState));
        when(playerStateManager.getCachedState(alexId))
                .thenReturn(java.util.Optional.of(alexState));
        when(playerStateManager.getCachedState(steveId))
                .thenReturn(java.util.Optional.of(steveState));
    }

    private ClientConnection mockConn(String clientId) {
        ClientConnection c = mock(ClientConnection.class);
        when(c.getClientId()).thenReturn(clientId);
        when(c.isActive()).thenReturn(true);
        when(c.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return c;
    }

    private Channel globalChannel() {
        return new Channel("global", "Global", ChannelScope.GLOBAL, null);
    }

    private ChatMessagePacket fromSteve(String content) {
        return new ChatMessagePacket(steveId, "Steve", "Survival", "global", content);
    }

    @Test
    @DisplayName("@name mention delivers MentionPacket to mentioned player's connection")
    void nameMentionDelivered() {
        ChatMessagePacket msg = fromSteve("hey @Alex come here");
        Channel channel = globalChannel();
        Set<String> recipients = Set.of("Survival", "Creative");

        resolver.emitMentions(msg, channel, recipients);

        ArgumentCaptor<MentionPacket> captor =
                ArgumentCaptor.forClass(MentionPacket.class);
        verify(creativeConn).sendPacket(captor.capture());
        MentionPacket sent = captor.getValue();
        assertThat(sent.getMentionerId()).isEqualTo(steveId);
        assertThat(sent.getMentionerName()).isEqualTo("Steve");
        assertThat(sent.getMentionedId()).isEqualTo(alexId);
        assertThat(sent.getChannelId()).isEqualTo("global");
        assertThat(sent.getMessagePreview()).contains("@Alex");
    }

    @Test
    @DisplayName("self-mention is excluded (no packet to sender)")
    void selfMentionExcluded() {
        ChatMessagePacket msg = fromSteve("I am @Steve great");
        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        // MentionNotifier filters self; Steve's connection gets no MentionPacket.
        verify(survivalConn, never()).sendPacket(any(MentionPacket.class));
        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("unknown @name resolves to null and is skipped silently")
    void unknownNameSkipped() {
        ChatMessagePacket msg = fromSteve("hi @NobodyHere");
        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(survivalConn, never()).sendPacket(any(MentionPacket.class));
        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("@all expands to all recipients on this backend instance")
    void allMentionExpands() {
        ChatMessagePacket msg = fromSteve("attention @all");
        Set<String> recipients = Set.of("Survival", "Creative");

        resolver.emitMentions(msg, globalChannel(), recipients);

        // Steve is sender (excluded), Alex is the only other recipient.
        verify(creativeConn, times(1)).sendPacket(any(MentionPacket.class));
        verify(survivalConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("@all with no recipients expands to nothing")
    void allMentionNoRecipients() {
        ChatMessagePacket msg = fromSteve("attention @all");
        resolver.emitMentions(msg, globalChannel(), Set.of());

        verify(survivalConn, never()).sendPacket(any());
        verify(creativeConn, never()).sendPacket(any());
    }

    @Test
    @DisplayName("message with no mentions emits nothing")
    void noMentionsEmitsNothing() {
        ChatMessagePacket msg = fromSteve("just a normal message");
        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(survivalConn, never()).sendPacket(any(MentionPacket.class));
        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("cross-instance: mentioned player not in cache -> skipped")
    void crossInstanceNotCachedSkipped() {
        // Bob is mentioned but his PlayerState is not in this backend's cache.
        UUID bobId = UUID.randomUUID();
        ChatMessagePacket msg = fromSteve("hey @Bob");
        when(playerStateManager.getCachedState(bobId)).thenReturn(java.util.Optional.empty());

        // Bob's name resolves (so createMentionPackets produces a packet),
        // but delivery fails because Bob's PlayerState was evicted before
        // delivery. emitMentions must not throw.
        PlayerState bobState = new PlayerState(bobId, "Bob");
        bobState.setClientId("Skyblock");
        when(playerStateManager.getAllPlayerStates())
                .thenReturn(List.of(steveState, alexState, bobState));

        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        // No connection lookup matched Bob's clientId -> no send.
        verify(survivalConn, never()).sendPacket(any(MentionPacket.class));
        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("mentioned player's connection not active -> skipped")
    void inactiveConnectionSkipped() {
        ChatMessagePacket msg = fromSteve("hey @Alex");
        when(creativeConn.isActive()).thenReturn(false);

        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("null senderId resolves to no mentions")
    void nullSenderIdNoMentions() {
        ChatMessagePacket msg = new ChatMessagePacket(null, "Anon", "Survival", "global", "hi @Alex");
        List<MentionPacket> packets = resolver.resolveMentions(msg, globalChannel(), Set.of("Creative"));
        assertThat(packets).isEmpty();
    }

    @Test
    @DisplayName("resolveMentions returns packets without delivering")
    void resolveOnlyDoesNotDeliver() {
        ChatMessagePacket msg = fromSteve("hey @Alex");
        List<MentionPacket> packets = resolver.resolveMentions(msg, globalChannel(),
                Set.of("Survival", "Creative"));

        assertThat(packets).hasSize(1);
        assertThat(packets.get(0).getMentionedId()).isEqualTo(alexId);
        // resolveMentions must not touch the network handler.
        verify(networkHandler, never()).findByClientId(any(String.class));
    }

    @Test
    @DisplayName("mentioner ignores mentioned -> MentionPacket suppressed (§11.6 提案 08)")
    void mentionerIgnoresMentionedSkipped() {
        // Steve (mentioner) ignores Alex (mentioned) -> Alex gets no ping.
        MentionResolver r = new MentionResolver(notifier, playerStateManager, networkHandler,
                (src, tgt) -> src.equals(steveId) && tgt.equals(alexId),
                id -> true);
        ChatMessagePacket msg = fromSteve("hey @Alex");

        r.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("mentioned ignores mentioner -> MentionPacket suppressed (§11.6 提案 08)")
    void mentionedIgnoresMentionerSkipped() {
        // Alex (mentioned) ignores Steve (mentioner) -> Alex gets no ping.
        MentionResolver r = new MentionResolver(notifier, playerStateManager, networkHandler,
                (src, tgt) -> src.equals(alexId) && tgt.equals(steveId),
                id -> true);
        ChatMessagePacket msg = fromSteve("hey @Alex");

        r.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("mentioned player has mentions disabled -> MentionPacket suppressed")
    void mentionsDisabledSkipped() {
        // Alex has mentions disabled in their NotificationPreference.
        MentionResolver r = new MentionResolver(notifier, playerStateManager, networkHandler,
                (src, tgt) -> false,
                id -> !id.equals(alexId));
        ChatMessagePacket msg = fromSteve("hey @Alex");

        r.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, never()).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("null lookups (legacy 3-arg wiring) deliver mention normally")
    void nullLookupsLegacyDeliver() {
        // 3-arg constructor -> lookups null -> no filtering -> mention delivered.
        ChatMessagePacket msg = fromSteve("hey @Alex");

        resolver.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, times(1)).sendPacket(any(MentionPacket.class));
    }

    @Test
    @DisplayName("lookups throw -> fail-open, mention still delivered")
    void lookupThrowsFailOpen() {
        // A persistence gap (db down) must NOT suppress the mention.
        MentionResolver r = new MentionResolver(notifier, playerStateManager, networkHandler,
                (src, tgt) -> { throw new RuntimeException("db down"); },
                id -> { throw new RuntimeException("db down"); });
        ChatMessagePacket msg = fromSteve("hey @Alex");

        r.emitMentions(msg, globalChannel(), Set.of("Survival", "Creative"));

        verify(creativeConn, times(1)).sendPacket(any(MentionPacket.class));
    }
}
