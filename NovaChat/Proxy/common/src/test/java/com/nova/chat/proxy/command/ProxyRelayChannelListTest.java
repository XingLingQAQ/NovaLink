package com.nova.chat.proxy.command;

import com.nova.chat.client.channel.ConfigSyncHandlerRegistrar;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.proxy.relay.BackendRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyRelayChannelListTest {
    private static final String PAYLOAD = """
            {"global_channels":{"global":{},"local":{}},"clients":[
              {"username":"primary","channels":{"primary-owned":{"scope":"SERVER"}}},
              {"username":"secondary","channels":{"joint-switch-owned":{"scope":"SERVER"}}}
            ]}
            """;

    @Test
    void twoConfigSyncHandlersReproduceUnscopedLeakButPlayerListsFollowTheirLink() {
        var fixture = new Fixture();
        var primary = link(fixture.registry, "primary");
        var secondary = link(fixture.registry, "secondary");
        primary.sync(PAYLOAD);
        secondary.sync(PAYLOAD);

        assertThat(fixture.registry.getAll()).contains("joint-switch-owned").doesNotContain("primary-owned");
        assertThat(ListCommandService.formatChannelList(fixture.registry, Set.of()))
                .contains("&7○&r &fjoint-switch-owned");
        assertThat(fixture.list("first")).containsExactly(
                "○ global", "○ local", "○ primary-owned");
        assertThat(fixture.list("second")).containsExactly(
                "○ global", "○ joint-switch-owned", "○ local");

        primary.sync(PAYLOAD);
        assertThat(fixture.list("second")).containsExactly(
                "○ global", "○ joint-switch-owned", "○ local");
        assertThat(fixture.commands.suggest(fixture.sender, new String[]{"join", ""}))
                .containsExactly("global", "joint-switch-owned", "local");
        when(fixture.player.getCurrentServer()).thenReturn("first");
        assertThat(fixture.commands.suggest(fixture.sender, new String[]{"join", ""}))
                .containsExactly("global", "local", "primary-owned");
    }

    @Test
    void globalsRefreshTogetherWhileOwnedRemovalAndJoinedVisibilityStayScoped() {
        var fixture = new Fixture();
        var primary = link(fixture.registry, "primary");
        var secondary = link(fixture.registry, "secondary");
        primary.sync(PAYLOAD);
        secondary.sync(PAYLOAD);
        primary.sync("""
                {"global_channels":{"global":{},"news":{}},"clients":[
                  {"username":"primary","channels":{"replacement":{"scope":"SERVER"}}}
                ]}
                """);

        assertThat(fixture.list("first")).containsExactly("○ global", "○ news", "○ replacement");
        assertThat(fixture.list("second")).containsExactly("○ global", "○ joint-switch-owned", "○ news");
        assertThat(fixture.registry.getAll("unmapped")).containsExactlyInAnyOrder("global", "news");
        assertThat(fixture.registry.getAll((String) null)).containsExactlyInAnyOrder("global", "news");

        var joined = Set.of("global", "joint-switch-owned", "authorized-private");
        assertThat(ListCommandService.formatChannelList(fixture.registry, joined, "primary"))
                .containsExactly("&a✓&r &fglobal", "&7○&r &fnews", "&7○&r &freplacement",
                        "&a✓&r &fauthorized-private");
        assertThat(joined).containsExactlyInAnyOrder("global", "joint-switch-owned", "authorized-private");
        assertThat(fixture.list("first")).noneMatch(line -> line.contains("authorized-private"));
    }

    @Test
    void singleConnectionAndLiteKeepLegacyDiscoveryAndJoinedUnknownChannels() {
        var registry = new KnownChannelRegistry();
        var client = link(registry, "primary");
        client.sync(PAYLOAD);
        var joined = Set.of("global", "new-private");
        assertThat(ListCommandService.formatChannelList(registry, joined, "primary"))
                .isEqualTo(ListCommandService.formatChannelList(registry, joined));
        client.sync(" ");
        assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "local", "primary-owned");
        registry.replaceAll(Set.of("global", "local"));
        assertThat(ListCommandService.formatChannelList(registry, joined, "lite-server"))
                .isEqualTo(ListCommandService.formatChannelList(registry, joined));
        assertThat(registry.getAll("secondary")).containsExactlyInAnyOrder("global", "local");
    }

    private static RecordingClient link(KnownChannelRegistry registry, String identity) {
        var client = new RecordingClient();
        ConfigSyncHandlerRegistrar.register(client, registry, identity);
        return client;
    }

    private static final class RecordingClient extends AbstractPlatformNetworkClient {
        private Consumer<ConfigSyncPacket> handler;

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Packet> void registerHandler(Class<T> type, Consumer<T> consumer) {
            assertThat(type).isEqualTo(ConfigSyncPacket.class);
            handler = (Consumer<ConfigSyncPacket>) consumer;
        }

        void sync(String payload) {
            handler.accept(new ConfigSyncPacket(payload, 1L));
        }
    }

    private static final class Fixture {
        private final KnownChannelRegistry registry = new KnownChannelRegistry();
        private final ProxyPlatform platform = mock(ProxyPlatform.class);
        private final ProxyPlayer player = mock(ProxyPlayer.class);
        private final ProxyCommandSender sender = mock(ProxyCommandSender.class);
        private final ProxyCommandCore commands;

        Fixture() {
            var id = UUID.randomUUID();
            var config = mock(ProxyConfigView.class);
            var router = mock(BackendRouter.class);
            when(config.getPrefix()).thenReturn("");
            when(platform.getPlayer(id)).thenReturn(player);
            when(player.getUniqueId()).thenReturn(id);
            when(sender.getPlayerId()).thenReturn(id);
            when(router.identityFor("first")).thenReturn("primary");
            when(router.identityFor("second")).thenReturn("secondary");
            var ignores = new IgnoreListService();
            var privateMessages = new PrivateMessageService();
            var core = new ProxyChatCore(platform, config, ignores, privateMessages, registry, () -> null);
            core.setBackendRouter(router);
            commands = new ProxyCommandCore(platform, core, new ChannelCommandService(packet -> true),
                    ignores, privateMessages, registry, () -> config);
        }

        List<String> list(String server) {
            when(player.getCurrentServer()).thenReturn(server);
            clearInvocations(platform);
            commands.handleList(sender);
            var lines = ArgumentCaptor.forClass(String.class);
            int count = registry.getAll(server.equals("first") ? "primary" : "secondary").size();
            verify(platform, times(count)).sendMessage(eq(player), lines.capture());
            return lines.getAllValues().stream()
                    .map(line -> line.replaceAll("(?i)[&§][0-9a-fk-orx]", ""))
                    .toList();
        }
    }
}
