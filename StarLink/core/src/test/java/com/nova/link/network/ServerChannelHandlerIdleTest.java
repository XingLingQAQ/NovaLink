package com.nova.link.network;

import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Idle handling tests for {@link ServerChannelHandler} via
 * {@link EmbeddedChannel}-fired {@link IdleStateEvent}s: read-idle closes the
 * connection (triggering the regular disconnect cleanup), write-idle sends a
 * server-initiated KeepAlive ping whose echo is recognized (no re-echo loop).
 */
@DisplayName("ServerChannelHandler idle events")
class ServerChannelHandlerIdleTest {

    private ServerNetworkHandler networkHandler;
    private EmbeddedChannel channel;
    private ClientConnection connection;

    @BeforeEach
    void setUp() {
        networkHandler = mock(ServerNetworkHandler.class);
        channel = new EmbeddedChannel(new ServerChannelHandler(networkHandler));

        ArgumentCaptor<ClientConnection> captor = ArgumentCaptor.forClass(ClientConnection.class);
        verify(networkHandler).onClientConnected(captor.capture());
        connection = captor.getValue();
    }

    @Test
    @DisplayName("read-idle closes the connection and triggers disconnect cleanup")
    void readerIdleClosesConnection() {
        assertThat(channel.isActive()).isTrue();

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isActive()).isFalse();
        verify(networkHandler).onClientDisconnected(connection);
    }

    @Test
    @DisplayName("read-idle on an authenticated connection also closes it")
    void readerIdleClosesAuthenticatedConnection() {
        connection.setAuthenticated(true);
        connection.setClientId("Survival");

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isActive()).isFalse();
        verify(networkHandler).onClientDisconnected(connection);
    }

    @Test
    @DisplayName("write-idle sends a server-initiated KeepAlive ping and records its requestId")
    void writerIdleSendsKeepAlivePing() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);
        channel.runPendingTasks();

        assertThat(channel.isActive()).isTrue();
        Object outbound = channel.readOutbound();
        assertThat(outbound).isInstanceOf(KeepAlivePacket.class);

        KeepAlivePacket ping = (KeepAlivePacket) outbound;
        // The echo of our ping must be recognized exactly once (loop guard).
        assertThat(connection.consumePendingKeepAliveId(ping.getRequestId())).isTrue();
        assertThat(connection.consumePendingKeepAliveId(ping.getRequestId())).isFalse();
    }

    @Test
    @DisplayName("client-initiated KeepAlive requestIds are not treated as ping echoes")
    void unrelatedKeepAliveIsNotAnEcho() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.WRITER_IDLE_STATE_EVENT);
        channel.runPendingTasks();
        assertThat((Object) channel.readOutbound()).isNotNull();

        // A different requestId (client-initiated ping) must not match.
        assertThat(connection.consumePendingKeepAliveId(UUID.randomUUID())).isFalse();
        assertThat(connection.consumePendingKeepAliveId(null)).isFalse();
    }

    @Test
    @DisplayName("other user events pass through without closing the channel")
    void unrelatedEventsPassThrough() {
        channel.pipeline().fireUserEventTriggered("some-other-event");
        channel.runPendingTasks();
        assertThat(channel.isActive()).isTrue();
    }
}
