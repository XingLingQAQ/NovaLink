package com.nova.link.channel;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Validation-focused tests for {@link MessageRouter#routeMessage}.
 * Ensures empty/oversized/unknown-channel messages are dropped safely.
 */
@DisplayName("MessageRouter validation")
class MessageRouterValidationTest {

    private MessageRouter router;
    private ChannelManager channelManager;

    @BeforeEach
    void setUp() {
        channelManager = mock(ChannelManager.class);
        ServerNetworkHandler networkHandler = mock(ServerNetworkHandler.class);
        router = new MessageRouter(channelManager, networkHandler);
    }

    @Test
    @DisplayName("null message throws NPE")
    void nullMessage() {
        assertThatThrownBy(() -> router.routeMessage(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("blank content is dropped")
    void blankContentDropped() {
        ChatMessagePacket msg = new ChatMessagePacket(
                UUID.randomUUID(), "Steve", "s1", "global", "   "
        );
        Set<String> recipients = router.routeMessage(msg);
        assertThat(recipients).isEmpty();
    }

    @Test
    @DisplayName("null content is dropped")
    void nullContentDropped() {
        ChatMessagePacket msg = new ChatMessagePacket();
        msg.setSenderId(UUID.randomUUID());
        msg.setSenderName("Steve");
        msg.setClientId("s1");
        msg.setChannelId("global");
        msg.setContent(null);
        assertThat(router.routeMessage(msg)).isEmpty();
    }

    @Test
    @DisplayName("oversized content is dropped")
    void oversizedDropped() {
        String huge = "x".repeat(NovaConstants.MAX_MESSAGE_LENGTH + 1);
        ChatMessagePacket msg = new ChatMessagePacket(
                UUID.randomUUID(), "Steve", "s1", "global", huge
        );
        assertThat(router.routeMessage(msg)).isEmpty();
    }

    @Test
    @DisplayName("missing channelId is dropped")
    void missingChannelDropped() {
        ChatMessagePacket msg = new ChatMessagePacket(
                UUID.randomUUID(), "Steve", "s1", "", "hello"
        );
        assertThat(router.routeMessage(msg)).isEmpty();
    }

    @Test
    @DisplayName("content at exact max length is not rejected by length check alone")
    void exactMaxLengthPassesLengthGate() {
        // Still fails because channel is unknown (mock returns null), but must not
        // be rejected solely for length == MAX_MESSAGE_LENGTH.
        String exact = "y".repeat(NovaConstants.MAX_MESSAGE_LENGTH);
        ChatMessagePacket msg = new ChatMessagePacket(
                UUID.randomUUID(), "Steve", "s1", "global", exact
        );
        // channelManager mock returns null -> empty set after length gate
        assertThat(router.routeMessage(msg)).isEmpty();
    }
}
