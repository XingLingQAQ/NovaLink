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
        manager.createChannel(ChannelConfig.builder().id("reused").scope(scope).clientId("original").build());

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
