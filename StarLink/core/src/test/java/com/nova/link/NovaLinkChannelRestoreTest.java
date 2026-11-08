package com.nova.link;

import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelSource;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.SQLiteProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NovaLinkChannelRestoreTest {

    @TempDir
    Path directory;

    @Test
    void startupRestoresSlowModeWithoutOverwritingItsPersistedValue() throws Exception {
        var provider = new SQLiteProvider(directory.resolve("channels.db").toString(), 1);
        try {
            provider.initialize();
            new ChannelManager(provider).createChannel(ChannelConfig.builder().id("limited")
                    .scope(ChannelScope.GLOBAL).slowModeSeconds(45).build());

            var restored = new ChannelManager(provider);
            var load = NovaLinkCore.class.getDeclaredMethod("loadPersistedChannels",
                    ChannelManager.class, DatabaseProvider.class);
            load.setAccessible(true);
            load.invoke(null, restored, provider);

            assertThat(restored.getChannel("limited").getSlowModeSeconds()).isEqualTo(45);
            assertThat(restored.getChannel("limited").getSource()).isEqualTo(ChannelSource.DATABASE);
            assertThat(provider.loadChannel("limited").orElseThrow().getSlowModeSeconds()).isEqualTo(45);
        } finally {
            provider.shutdown();
        }
    }
}
