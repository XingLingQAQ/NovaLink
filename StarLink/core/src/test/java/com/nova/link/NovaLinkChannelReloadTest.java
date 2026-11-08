package com.nova.link;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.channel.GlobalChannelLoader;
import com.nova.link.channel.ServerChannelLoader;
import com.nova.link.channel.TemplateManager;
import com.nova.link.config.NovaLinkConfig;
import com.nova.link.config.ClientConfig;
import com.nova.link.config.GlobalChannelConfig;
import com.nova.link.config.ServerChannelConfig;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NovaLinkChannelReloadTest {

    @Test
    void staleConfigSnapshotCannotDeleteARuntimeReplacement() throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        try {
            var manager = new ChannelManager(provider) {
                private boolean replaceDuringSnapshot = true;

                @Override
                public Collection<Channel> getAllChannels() {
                    var snapshot = new ArrayList<>(super.getAllChannels());
                    if (replaceDuringSnapshot) {
                        replaceDuringSnapshot = false;
                        deleteChannel("reused");
                        createChannel(ChannelConfig.builder().id("reused").scope(ChannelScope.SERVER)
                                .clientId("new-client").displayName("Runtime replacement").build());
                    }
                    return snapshot;
                }
            };
            manager.createChannel(ChannelConfig.builder().id("reused").scope(ChannelScope.GLOBAL).build(),
                    ChannelSource.CONFIG);

            apply(manager, new NovaLinkConfig());

            Channel current = manager.getChannel("reused");
            assertThat(current).isNotNull();
            assertThat(current.getSource()).isEqualTo(ChannelSource.RUNTIME);
            assertThat(manager.getChannelsByClient("new-client")).containsExactly(current);
            assertThat(provider.loadChannel("reused").orElseThrow().getDisplayName())
                    .isEqualTo("Runtime replacement");
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void removedConfigChannelIsStillPruned() throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        try {
            var manager = new ChannelManager(provider);
            manager.createChannel(ChannelConfig.builder().id("removed").scope(ChannelScope.GLOBAL).build(),
                    ChannelSource.CONFIG);

            apply(manager, new NovaLinkConfig());

            assertThat(manager.getChannel("removed")).isNull();
            assertThat(manager.getGlobalChannels()).isEmpty();
            assertThat(provider.loadChannel("removed")).isEmpty();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void globalToServerReloadChangesTheRoutingBoundaryAndDropsOldMembers() throws Exception {
        var manager = new ChannelManager();
        var previous = manager.createChannel(ChannelConfig.builder().id("moved").scope(ChannelScope.GLOBAL)
                .build(), ChannelSource.CONFIG);
        previous.addMember(java.util.UUID.randomUUID());
        var removed = new ArrayList<Channel>();
        manager.setDeletionCleanup(removed::add);

        apply(manager, serverConfig("primary", "moved", "Private to primary"));

        var current = manager.getChannel("moved");
        assertThat(current.getScope()).isEqualTo(ChannelScope.SERVER);
        assertThat(current.getClientId()).isEqualTo("primary");
        assertThat(current).isNotSameAs(previous);
        assertThat(current.getRevision()).isGreaterThan(previous.getRevision());
        assertThat(current.getMembers()).isEmpty();
        assertThat(manager.getGlobalChannels()).isEmpty();
        assertThat(manager.getChannelsByClient("primary")).containsExactly(current);
        assertThat(removed).containsExactly(previous);
    }

    @Test
    void reassigningAConfiguredChannelChangesItsClientIndex() throws Exception {
        var manager = new ChannelManager();
        manager.createChannel(ChannelConfig.builder().id("moved").scope(ChannelScope.SERVER)
                .clientId("old-client").build(), ChannelSource.CONFIG);

        apply(manager, serverConfig("new-client", "moved", "Moved"));

        assertThat(manager.getChannel("moved").getClientId()).isEqualTo("new-client");
        assertThat(manager.getChannelsByClient("old-client")).isEmpty();
        assertThat(manager.getChannelsByClient("new-client")).containsExactly(manager.getChannel("moved"));
    }

    @Test
    void duplicateDeclarationsKeepTheFirstDefinitionLikeStartup() throws Exception {
        var manager = new ChannelManager();
        var config = serverConfig("primary", "shared", "Server definition");
        var global = new GlobalChannelConfig();
        global.setDisplayName("Global definition");
        global.setMaxCapacity(100);
        config.getGlobalChannels().put("shared", global);

        apply(manager, config);

        assertThat(manager.getChannel("shared").getDisplayName()).isEqualTo("Global definition");
        assertThat(manager.getChannel("shared").getScope()).isEqualTo(ChannelScope.GLOBAL);
    }

    @Test
    void ordinaryReloadKeepsMembersAndSkipsDynamicChannels() throws Exception {
        var manager = new ChannelManager();
        var configured = manager.createChannel(ChannelConfig.builder().id("configured").scope(ChannelScope.SERVER)
                .clientId("primary").build(), ChannelSource.CONFIG);
        var member = java.util.UUID.randomUUID();
        configured.addMember(member);
        var dynamic = manager.createChannel(ChannelConfig.builder().id("dynamic").scope(ChannelScope.GLOBAL).build());
        var config = serverConfig("primary", "configured", "New name");
        config.getClients().get(0).getChannels().put("dynamic", new ServerChannelConfig());

        apply(manager, config);

        assertThat(manager.getChannel("configured")).isSameAs(configured);
        assertThat(configured.getDisplayName()).isEqualTo("New name");
        assertThat(configured.getMembers()).containsExactly(member);
        assertThat(configured.getRevision()).isEqualTo(1L);
        assertThat(manager.getChannel("dynamic")).isSameAs(dynamic);
        assertThat(dynamic.getScope()).isEqualTo(ChannelScope.GLOBAL);
    }

    private static NovaLinkConfig serverConfig(String clientId, String channelId, String name) {
        var channel = new ServerChannelConfig();
        channel.setDisplayName(name);
        channel.setScope("SERVER");
        var client = new ClientConfig();
        client.setUsername(clientId);
        client.getChannels().put(channelId, channel);
        var config = new NovaLinkConfig();
        config.getClients().add(client);
        return config;
    }

    @ParameterizedTest
    @EnumSource(value = ChannelScope.class, names = {"GLOBAL", "SERVER"})
    void legacyReloadersAlsoPreserveASnapshotReplacement(ChannelScope scope) {
        var manager = new ChannelManager() {
            @Override
            public List<Channel> getGlobalChannels() {
                return replaceAfterSnapshot(super.getGlobalChannels());
            }

            @Override
            public List<Channel> getChannelsByClient(String clientId) {
                return replaceAfterSnapshot(super.getChannelsByClient(clientId));
            }

            private List<Channel> replaceAfterSnapshot(List<Channel> current) {
                var snapshot = new ArrayList<>(current);
                if (!snapshot.isEmpty()) {
                    deleteChannel("reused");
                    createChannel(ChannelConfig.builder().id("reused").scope(ChannelScope.PRIVATE)
                            .clientId("replacement").build());
                }
                return snapshot;
            }
        };
        manager.createChannel(ChannelConfig.builder().id("reused").scope(scope)
                .clientId(scope == ChannelScope.GLOBAL ? null : "original").build());

        if (scope == ChannelScope.GLOBAL) {
            new GlobalChannelLoader(manager).reloadGlobalChannels(Map.of());
        } else {
            new ServerChannelLoader(manager, new TemplateManager()).reloadClientChannels("original", Map.of());
        }

        assertThat(manager.getChannel("reused")).isNotNull();
        assertThat(manager.getChannel("reused").getClientId()).isEqualTo("replacement");
    }

    private static void apply(ChannelManager manager, NovaLinkConfig config) throws Exception {
        var method = NovaLinkCore.class.getDeclaredMethod("applyConfiguredChannels",
                ChannelManager.class, NovaLinkConfig.class);
        method.setAccessible(true);
        method.invoke(null, manager, config);
    }
}
