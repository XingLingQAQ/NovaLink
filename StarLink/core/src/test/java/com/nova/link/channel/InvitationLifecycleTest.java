package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.Invitation;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.RedisProvider;
import com.nova.link.database.SQLiteProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InvitationLifecycleTest {
    @TempDir Path directory;

    enum Mode { MEMORY_ONLY, MEMORY, SQLITE, REDIS, CONFIG }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void deletingAChannelInvalidatesItsInvitesBeforeTheIdCanBeReused(Mode mode) throws Exception {
        DatabaseProvider provider;
        if (mode == Mode.REDIS) {
            String port = System.getenv("NOVALINK_TEST_REDIS_PORT");
            assumeTrue(port != null, "Set NOVALINK_TEST_REDIS_PORT to an isolated local Redis");
            provider = new RedisProvider("127.0.0.1", Integer.parseInt(port), null, 0);
        } else if (mode == Mode.SQLITE || mode == Mode.CONFIG) {
            provider = new SQLiteProvider(directory.resolve("channels.db").toString(), 1);
        } else {
            provider = new MemoryProvider();
        }
        provider.initialize();
        var channels = new ChannelManager(mode == Mode.MEMORY_ONLY ? null : provider);
        var invitations = new InvitationManager(provider, channels);
        String id = "retired-" + UUID.randomUUID();
        String other = "kept-" + UUID.randomUUID();
        var config = ChannelConfig.builder().id(id).scope(ChannelScope.GLOBAL).build();
        try {
            channels.createChannel(config, mode == Mode.CONFIG ? ChannelSource.CONFIG : ChannelSource.RUNTIME);
            channels.createChannel(ChannelConfig.builder().id(other).scope(ChannelScope.GLOBAL).build());
            var invitation = invitations.createInvitation(id, UUID.randomUUID());
            var retained = invitations.createInvitation(other, UUID.randomUUID());

            assertThat(channels.deleteChannel(id)).isTrue();
            var replacement = channels.createChannel(config);
            UUID newcomer = UUID.randomUUID();
            assertThat(invitations.acceptInvitation(invitation.getCode(), newcomer, null).isSuccess()).isFalse();
            assertThat(replacement.isMember(newcomer)).isFalse();
            assertThat(invitations.validateInvitation(retained.getCode()).isSuccess()).isTrue();
            var fresh = invitations.createInvitation(id, UUID.randomUUID());
            assertThat(invitations.acceptInvitation(fresh.getCode(), newcomer, null).isSuccess()).isTrue();
        } finally {
            provider.deleteChannel(id);
            provider.deleteChannel(other);
            provider.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"channels", "invitations"})
    void failedSqliteDeletionKeepsTheChannelAndItsInvites(String table) throws Exception {
        var provider = new SQLiteProvider(directory.resolve("rollback.db").toString(), 1);
        provider.initialize();
        var channels = new ChannelManager(provider);
        var invitations = new InvitationManager(provider, channels);
        try {
            var channel = channels.createChannel(ChannelConfig.builder().id("retained").scope(ChannelScope.GLOBAL).build());
            var invitation = invitations.createInvitation("retained", UUID.randomUUID());
            UUID member = UUID.randomUUID();
            channel.addMember(member);
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("rollback.db"));
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON " + table
                        + " BEGIN SELECT RAISE(ABORT, 'simulated delete failure'); END");
            }
            assertThatThrownBy(() -> channels.deleteChannel("retained")).isInstanceOf(IllegalStateException.class);
            assertThat(channels.getChannel("retained")).isSameAs(channel);
            assertThat(channel.isMember(member)).isTrue();
            assertThat(provider.loadChannel("retained")).isPresent();
            assertThat(invitations.validateInvitation(invitation.getCode()).isSuccess()).isTrue();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void invitationCreationCannotFinishAfterItsChannelWasDeleted() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = new MemoryProvider() {
            boolean gated = true;
            @Override public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
                if (gated) {
                    gated = false;
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new DatabaseException("create gate timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new DatabaseException("create interrupted", e);
                    }
                }
                return super.loadInvitation(code);
            }
        };
        provider.initialize();
        var channels = new ChannelManager(provider);
        var invitations = new InvitationManager(provider, channels);
        var config = ChannelConfig.builder().id("raced").scope(ChannelScope.GLOBAL).build();
        channels.createChannel(config);
        var worker = Executors.newSingleThreadExecutor();
        var replacing = new FutureTask<>(() -> {
            channels.deleteChannel("raced");
            return channels.createChannel(config);
        });
        var replacer = new Thread(replacing, "invitation-channel-replace-test");
        try {
            var creating = worker.submit(() -> invitations.createInvitation("raced", UUID.randomUUID()));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            replacer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!replacing.isDone() && replacer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertThat(replacing.isDone() || replacer.getState() == Thread.State.BLOCKED).isTrue();
            release.countDown();
            var invitation = creating.get(5, TimeUnit.SECONDS);
            assertThat(replacing.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(invitations.validateInvitation(invitation.getCode()).isSuccess()).isFalse();
        } finally {
            release.countDown();
            worker.shutdownNow();
            replacer.join(5000L);
            provider.shutdown();
        }
    }
}
