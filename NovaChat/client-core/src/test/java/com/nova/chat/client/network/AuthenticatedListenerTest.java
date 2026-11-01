package com.nova.chat.client.network;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthenticatedListenerTest {
    @Test
    void runsOncePerAuthenticatedConnectionAndIgnoresStaleResponses() {
        var config = ClientConnectionConfig.builder().host("localhost").port(8888)
                .username("test").password("test").build();
        var client = new CoreNetworkClient(config, PlatformType.BUKKIT,
                mock(SchedulerBridge.class), mock(ClientLogger.class), "config.yml", null);
        var calls = new AtomicInteger();
        client.onAuthenticated(() -> { throw new IllegalStateException("fixture"); });
        client.onAuthenticated(calls::incrementAndGet);
        var first = new EmbeddedChannel();
        var second = new EmbeddedChannel();
        try {
            client.setChannelForTest(first);
            client.handlePacket(first, HandshakeResponsePacket.success("ok"));
            client.handlePacket(first, HandshakeResponsePacket.success("duplicate"));
            assertThat(calls).hasValue(1);
            client.onDisconnect(first);
            client.setChannelForTest(second);
            client.handlePacket(first, HandshakeResponsePacket.success("stale"));
            assertThat(calls).hasValue(1);
            client.handlePacket(second, HandshakeResponsePacket.success("reconnected"));
            assertThat(calls).hasValue(2);
        } finally {
            client.disconnect();
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }
}
