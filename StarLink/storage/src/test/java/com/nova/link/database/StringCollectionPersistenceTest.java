package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.dialect.MigrationDialect;
import com.nova.link.database.dialect.SQLiteDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringCollectionPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void playerChannelIdsSurviveReopenWithoutSplitting() throws Exception {
        String file = tempDir.resolve("players.db").toString();
        var provider = new SQLiteProvider(file, 1);
        provider.initialize();
        UUID player = UUID.randomUUID();
        var channels = Set.of("guild,red", "[\"literal\"]", "slash\\quote\"");
        try {
            var state = new PlayerState(player, "Player");
            state.setJoinedChannels(channels);
            state.setActiveChannel("guild,red");
            provider.savePlayerState(state);
            provider.shutdown();
            provider = new SQLiteProvider(file, 1);
            provider.initialize();

            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels()).isEqualTo(channels);
            assertThat(provider.loadPlayerState(player).orElseThrow().getActiveChannel()).isEqualTo("guild,red");
            assertThat(provider.getAllPlayerStates()).singleElement()
                    .satisfies(value -> assertThat(value.getJoinedChannels()).isEqualTo(channels));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void restoringAWorldFilterDoesNotAuthorizePartsOfAWorldName() throws Exception {
        String file = tempDir.resolve("worlds.db").toString();
        var provider = new SQLiteProvider(file, 1);
        provider.initialize();
        var worlds = List.of("lobby,staff", "quote\"world", "世界", "");
        try {
            var channel = new Channel("worlds", "Worlds", ChannelScope.GLOBAL, null);
            channel.setAllowedWorlds(worlds);
            provider.saveChannel(channel);
            provider.shutdown();
            provider = new SQLiteProvider(file, 1);
            provider.initialize();

            var restored = provider.loadChannel("worlds").orElseThrow();
            assertThat(restored.getAllowedWorlds()).isEqualTo(worlds);
            assertThat(restored.isWorldAllowed("lobby")).isFalse();
            assertThat(restored.isWorldAllowed("staff")).isFalse();
            assertThat(restored.isWorldAllowed("lobby,staff")).isTrue();
            assertThat(provider.getAllChannels()).singleElement()
                    .satisfies(value -> assertThat(value.getAllowedWorlds()).isEqualTo(worlds));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void invalidVersionedCollectionsFailInsteadOfDisablingTheFilterOrUsingCommaParsing() throws Exception {
        var provider = new SQLiteProvider(tempDir.resolve("invalid.db").toString(), 1);
        provider.initialize();
        UUID player = UUID.randomUUID();
        try {
            provider.savePlayerState(new PlayerState(player, "Player"));
            provider.saveChannel(new Channel("restricted", "Restricted", ChannelScope.GLOBAL, null));
            try (var connection = provider.getDataSource().getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE players SET joined_channels = '[1]' WHERE joined_channels_format = 1");
                statement.executeUpdate("UPDATE channels SET allowed_worlds = '[' WHERE allowed_worlds_format = 1");
            }
            assertThatThrownBy(() -> provider.loadPlayerState(player)).isInstanceOf(DatabaseException.class);
            assertThatThrownBy(() -> provider.loadChannel("restricted")).isInstanceOf(DatabaseException.class);
            try (var connection = provider.getDataSource().getConnection();
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE channels SET allowed_worlds = '[]', allowed_worlds_format = 2");
            }
            assertThatThrownBy(() -> provider.loadChannel("restricted")).isInstanceOf(DatabaseException.class);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void version16RowsKeepTheirLiteralMeaningAndUpgradeOnWrite() throws Exception {
        String file = tempDir.resolve("legacy.db").toString();
        var previous = new SQLiteProvider(file, 1) {
            @Override
            protected MigrationDialect dialect() {
                return new SQLiteDialect() {
                    @Override
                    public int getCurrentVersion() {
                        return 16;
                    }
                };
            }
        };
        previous.initialize();
        UUID player = UUID.randomUUID();
        try (var connection = previous.getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO players (player_id, joined_channels, last_seen) VALUES (?, ?, 0)")) {
                statement.setString(1, player.toString());
                statement.setString(2, "[\"literal\"]");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO channels (channel_id, scope, allowed_worlds) VALUES (?, ?, ?)")) {
                statement.setString(1, "legacy");
                statement.setString(2, "GLOBAL");
                statement.setString(3, "[\"literal\"],staff");
                statement.executeUpdate();
            }
        } finally {
            previous.shutdown();
        }
        var provider = new SQLiteProvider(file, 1);
        provider.initialize();
        try {
            var state = provider.loadPlayerState(player).orElseThrow();
            var channel = provider.loadChannel("legacy").orElseThrow();
            assertThat(state.getJoinedChannels()).containsExactly("[\"literal\"]");
            assertThat(channel.getAllowedWorlds()).containsExactly("[\"literal\"]", "staff");
            state.addJoinedChannel("new,exact");
            channel.setAllowedWorlds(List.of("new,exact"));
            provider.savePlayerState(state);
            provider.saveChannel(channel);
            assertThat(provider.loadPlayerState(player).orElseThrow().getJoinedChannels())
                    .containsExactlyInAnyOrder("[\"literal\"]", "new,exact");
            assertThat(provider.loadChannel("legacy").orElseThrow().getAllowedWorlds()).containsExactly("new,exact");
        } finally {
            provider.shutdown();
        }
    }
}
