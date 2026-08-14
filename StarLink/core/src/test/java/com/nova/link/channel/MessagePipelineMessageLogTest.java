package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.log.MessageLogService;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 8 (async persistence) behavior of {@link MessagePipeline}: messages
 * are handed to {@link MessageLogService} only when the
 * {@code message-log-enabled} feature switch is on AND fan-out succeeded.
 */
@DisplayName("MessagePipeline message-log persistence hook")
class MessagePipelineMessageLogTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MessagePipeline pipeline;
    private MessageLogService messageLogService;

    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);

        // One live connection so GLOBAL fan-out succeeds.
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.getClientId()).thenReturn("Survival");
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.isActive()).thenReturn(true);
        when(connection.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.getConnections()).thenReturn(Set.of(connection));

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());

        messageLogService = mock(MessageLogService.class);
        pipeline = new MessagePipeline(channelManager, networkHandler);
        pipeline.setMessageLogService(messageLogService);
    }

    private ChatMessagePacket msg(String content) {
        return new ChatMessagePacket(senderId, "Steve", "Survival", "global", content);
    }

    @Test
    @DisplayName("enabled: delivered message is queued with all fields mapped")
    void enabledDeliveredMessageIsLogged() {
        pipeline.setMessageLogEnabled(true);

        MessagePipelineResult result = pipeline.process(msg("hello there"));
        assertThat(result.isDelivered()).isTrue();

        ArgumentCaptor<ChatMessageRecord> captor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(messageLogService).logAsync(captor.capture());
        ChatMessageRecord record = captor.getValue();
        assertThat(record.getChannelId()).isEqualTo("global");
        assertThat(record.getSenderId()).isEqualTo(senderId.toString());
        assertThat(record.getSenderName()).isEqualTo("Steve");
        assertThat(record.getClientId()).isEqualTo("Survival");
        assertThat(record.getContent()).isEqualTo("hello there");
        assertThat(record.getTimestamp()).isPositive();
    }

    @Test
    @DisplayName("disabled (default): nothing is queued even on successful delivery")
    void disabledNothingIsLogged() {
        // messageLogEnabled defaults to false.
        MessagePipelineResult result = pipeline.process(msg("hello there"));
        assertThat(result.isDelivered()).isTrue();

        verify(messageLogService, never()).logAsync(any());
    }

    @Test
    @DisplayName("enabled but dropped (no recipients): nothing is queued")
    void enabledButDroppedIsNotLogged() {
        pipeline.setMessageLogEnabled(true);
        // No connections → GLOBAL fan-out yields NO_RECIPIENTS.
        when(networkHandler.getConnections()).thenReturn(Set.of());

        MessagePipelineResult result = pipeline.process(msg("hello there"));
        assertThat(result.isDelivered()).isFalse();

        verify(messageLogService, never()).logAsync(any());
    }
}
