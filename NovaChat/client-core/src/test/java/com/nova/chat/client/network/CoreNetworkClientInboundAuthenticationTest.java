package com.nova.chat.client.network;

import com.nova.chat.common.extension.events.ChatMessageEvent;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.HandshakeChallengePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreNetworkClientInboundAuthenticationTest {
    private static CoreNetworkClient newClient() {
        var config = ClientConnectionConfig.builder().host("localhost").port(8888)
                .username("test").password("test").build();
        return new CoreNetworkClient(config, PlatformType.BUKKIT,
                mock(SchedulerBridge.class), mock(ClientLogger.class));
    }

    private static ChatMessagePacket chat(String content) {
        return new ChatMessagePacket(UUID.randomUUID(), "sender", "client", "global", content);
    }

    @Test
    void networkBusinessPacketsReachNeitherExtensionsNorHandlersUntilAuthenticated() {
        var client = newClient();
        var events = new AtomicInteger();
        List<ChatMessagePacket> received = new ArrayList<>();
        client.getExtensionEventBus().register("test", ChatMessageEvent.class, event -> events.incrementAndGet());
        client.registerHandler(ChatMessagePacket.class, received::add);
        var channel = new EmbeddedChannel(new CoreClientChannelHandler(client));
        client.setChannelForTest(channel);
        try {
            channel.writeInbound(chat("before"));
            assertThat(events).hasValue(0);
            assertThat(received).isEmpty();

            channel.writeInbound(HandshakeResponsePacket.success("ok"));
            assertThat(client.isAuthenticated()).isTrue();
            channel.writeInbound(chat("after"));
            assertThat(events).hasValue(1);
            assertThat(received).extracting(ChatMessagePacket::getContent).containsExactly("after");
        } finally {
            client.disconnect();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void localDispatchStillWorksBeforeAuthentication() {
        var client = newClient();
        var events = new AtomicInteger();
        List<ChatMessagePacket> received = new ArrayList<>();
        client.getExtensionEventBus().register("test", ChatMessageEvent.class, event -> events.incrementAndGet());
        client.registerHandler(ChatMessagePacket.class, received::add);
        var channel = new EmbeddedChannel();
        client.setChannelForTest(channel);
        try {
            client.handlePacket(chat("local"));
            assertThat(client.isAuthenticated()).isFalse();
            assertThat(events).hasValue(1);
            assertThat(received).extracting(ChatMessagePacket::getContent).containsExactly("local");
        } finally {
            client.disconnect();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void preAuthenticationHandshakeAndKeepAliveControlsPass() {
        var client = newClient();
        var challenges = new AtomicInteger();
        client.registerHandler(HandshakeChallengePacket.class, packet -> challenges.incrementAndGet());
        var channel = new EmbeddedChannel(new CoreClientChannelHandler(client));
        client.setChannelForTest(channel);
        try {
            channel.writeInbound(new HandshakeChallengePacket("nonce"));
            var ping = new KeepAlivePacket(123L);
            channel.writeInbound(ping);
            KeepAlivePacket reply = channel.readOutbound();
            assertThat(challenges).hasValue(1);
            assertThat(reply).isNotNull();
            assertThat(reply.getRequestId()).isEqualTo(ping.getRequestId());
            assertThat(reply.getTimestamp()).isEqualTo(ping.getTimestamp());
            assertThat(client.isAuthenticated()).isFalse();

            channel.writeInbound(HandshakeResponsePacket.success("ok"));
            assertThat(client.isAuthenticated()).isTrue();
        } finally {
            client.disconnect();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectedHandshakeStillClosesCurrentChannel() {
        var client = newClient();
        var channel = new EmbeddedChannel(new CoreClientChannelHandler(client));
        client.setChannelForTest(channel);
        try {
            channel.writeInbound(HandshakeResponsePacket.failure("NC-401", "Invalid credentials"));
            assertThat(client.isAuthenticated()).isFalse();
            assertThat(channel.isActive()).isFalse();
        } finally {
            client.disconnect();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void staleGenerationCannotDispatchControlsOrBusinessPackets() {
        var client = newClient();
        var challenges = new AtomicInteger();
        List<ChatMessagePacket> received = new ArrayList<>();
        client.registerHandler(HandshakeChallengePacket.class, packet -> challenges.incrementAndGet());
        client.registerHandler(ChatMessagePacket.class, received::add);
        var oldChannel = new EmbeddedChannel(new CoreClientChannelHandler(client));
        var currentChannel = new EmbeddedChannel(new CoreClientChannelHandler(client));
        try {
            client.setChannelForTest(oldChannel);
            oldChannel.writeInbound(HandshakeResponsePacket.success("first"));
            assertThat(client.isAuthenticated()).isTrue();
            client.onDisconnect(oldChannel);
            client.setChannelForTest(currentChannel);
            assertThat(client.isAuthenticated()).isFalse();

            oldChannel.writeInbound(new HandshakeChallengePacket("stale"));
            oldChannel.writeInbound(new KeepAlivePacket(123L));
            oldChannel.writeInbound(HandshakeResponsePacket.success("stale"));
            oldChannel.writeInbound(chat("stale before"));
            assertThat(challenges).hasValue(0);
            assertThat((Object) currentChannel.readOutbound()).isNull();
            assertThat(client.isAuthenticated()).isFalse();
            assertThat(received).isEmpty();

            currentChannel.writeInbound(HandshakeResponsePacket.success("ok"));
            oldChannel.writeInbound(chat("stale after"));
            currentChannel.writeInbound(chat("current"));
            assertThat(received).extracting(ChatMessagePacket::getContent).containsExactly("current");
        } finally {
            client.disconnect();
            oldChannel.finishAndReleaseAll();
            currentChannel.finishAndReleaseAll();
        }
    }
}
