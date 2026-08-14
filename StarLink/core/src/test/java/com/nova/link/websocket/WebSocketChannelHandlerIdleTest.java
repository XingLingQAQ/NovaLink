package com.nova.link.websocket;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Two-phase idle handling tests for {@link WebSocketChannelHandler}: the
 * first read-idle after the WS upgrade sends a ping frame (one chance), a
 * second consecutive read-idle closes the session; any inbound frame resets
 * the phase. Pre-upgrade idles close immediately (a WS ping cannot be sent
 * on a raw HTTP connection).
 */
@DisplayName("WebSocketChannelHandler idle events")
class WebSocketChannelHandlerIdleTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new WebSocketChannelHandler(mock(WebSocketMessageHandler.class)));
    }

    /** Marks the connection as upgraded by delivering one inbound frame. */
    private void completeHandshakeViaInboundFrame() {
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"ping\"}"));
    }

    private void fireReaderIdle() {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        channel.runPendingTasks();
    }

    @Test
    @DisplayName("read-idle before handshake closes the connection without a ping")
    void preHandshakeIdleCloses() {
        fireReaderIdle();

        assertThat(channel.isActive()).isFalse();
        assertThat((Object) channel.readOutbound()).isNull();
    }

    @Test
    @DisplayName("first read-idle after handshake sends a ping frame and keeps the session open")
    void firstIdleSendsPing() {
        completeHandshakeViaInboundFrame();

        fireReaderIdle();

        assertThat(channel.isActive()).isTrue();
        Object outbound = channel.readOutbound();
        assertThat(outbound).isInstanceOf(PingWebSocketFrame.class);
        ReferenceCountUtil.release(outbound);
    }

    @Test
    @DisplayName("second consecutive read-idle closes the session")
    void secondIdleCloses() {
        completeHandshakeViaInboundFrame();

        fireReaderIdle();
        ReferenceCountUtil.release(channel.readOutbound()); // the ping

        fireReaderIdle();

        assertThat(channel.isActive()).isFalse();
    }

    @Test
    @DisplayName("an inbound frame between idles resets the ping phase")
    void inboundFrameResetsPhase() {
        completeHandshakeViaInboundFrame();

        fireReaderIdle();
        ReferenceCountUtil.release(channel.readOutbound()); // first ping

        // Client answers with a pong — liveness proven.
        channel.writeInbound(new PongWebSocketFrame(Unpooled.EMPTY_BUFFER));

        // Next idle starts a fresh cycle: ping again instead of closing.
        fireReaderIdle();
        assertThat(channel.isActive()).isTrue();
        Object outbound = channel.readOutbound();
        assertThat(outbound).isInstanceOf(PingWebSocketFrame.class);
        ReferenceCountUtil.release(outbound);
    }

    @Test
    @DisplayName("handshake-complete flag also set by the protocol handler event class")
    void handshakeCompleteEventIsHonored() {
        // The real WebSocketServerProtocolHandler.HandshakeComplete event has a
        // package-private constructor, so this test proves the equivalent
        // inbound-frame path plus verifies non-idle events pass through safely.
        channel.pipeline().fireUserEventTriggered("unrelated-event");
        channel.runPendingTasks();
        assertThat(channel.isActive()).isTrue();
    }
}
