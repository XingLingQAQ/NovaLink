package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.Invitation;
import com.nova.link.database.SQLiteProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateChannelInvitationPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void recipientBindingSurvivesSqliteReopenAndGuardsAtomicClaims() throws Exception {
        String file = directory.resolve("invitations.db").toString();
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        ChannelManager channels = new ChannelManager();
        Channel channel = new PrivateChannelManager(channels)
                .createPrivateChannel("Private", "primary", owner, "secret").getChannel();
        SQLiteProvider provider = new SQLiteProvider(file, 1);
        String code;
        String legacy;
        try {
            provider.initialize();
            InvitationManager invitations = new InvitationManager(provider, channels);
            code = invitations.createInvitation(channel.getId(), owner, recipient).getCode();
            legacy = invitations.createInvitation(channel.getId(), owner).getCode();
        } finally {
            provider.shutdown();
        }
        provider = new SQLiteProvider(file, 1);
        try {
            provider.initialize();
            Invitation loaded = provider.loadInvitation(code).orElseThrow();
            assertThat(loaded.getTargetPlayerId()).isEqualTo(recipient);
            assertThat(provider.loadInvitation(legacy).orElseThrow().getTargetPlayerId()).isNull();
            assertThat(provider.claimInvitationUse(code, outsider, System.currentTimeMillis())).isZero();
            assertThat(provider.loadInvitation(code).orElseThrow().getUsedCount()).isZero();
            InvitationManager invitations = new InvitationManager(provider, channels);
            assertThat(invitations.acceptInvitation(code, outsider, "secondary").getErrorCode()).isEqualTo("NC-403");
            assertThat(invitations.acceptInvitation(code, recipient, "secondary").isSuccess()).isTrue();
            assertThat(provider.loadInvitation(code).orElseThrow().getUsedCount()).isEqualTo(1);
            assertThat(invitations.acceptInvitation(code, recipient, "secondary").getErrorCode()).isEqualTo("NC-411");
        } finally {
            provider.shutdown();
        }
    }
    @Test
    void revocationPreservesAClaimCommittedAfterItsRead() throws Exception {
        var provider = new InterleavingSqlite(directory.resolve("revoke.db"));
        try {
            provider.initialize();
            var channels = new ChannelManager();
            channels.createChannel(ChannelConfig.builder().id("global").scope(ChannelScope.GLOBAL).build());
            var manager = new InvitationManager(provider, channels);
            UUID owner = UUID.randomUUID();
            var invitation = manager.createInvitation("global", owner, 60_000L, 2);
            UUID accepter = UUID.randomUUID();
            provider.afterNextRead = () -> assertThat(provider.claimInvitationUse(
                    invitation.getCode(), accepter, System.currentTimeMillis())).isEqualTo(1);
            assertThat(manager.revokeInvitation(invitation.getCode(), owner)).isTrue();
            var stored = provider.loadInvitation(invitation.getCode()).orElseThrow();
            assertThat(stored.isRevoked()).isTrue();
            assertThat(stored.getUsedCount()).isEqualTo(1);
            assertThat(stored.getUsedBy()).isEqualTo(accepter);
            assertThat(provider.claimInvitationUse(invitation.getCode(), UUID.randomUUID(), System.currentTimeMillis())).isZero();
        } finally { provider.shutdown(); }
    }

    @Test
    void forceRevocationCannotRecreateADeletedInvitation() throws Exception {
        var provider = new InterleavingSqlite(directory.resolve("deleted.db"));
        try {
            provider.initialize();
            var channels = new ChannelManager();
            channels.createChannel(ChannelConfig.builder().id("global").scope(ChannelScope.GLOBAL).build());
            var manager = new InvitationManager(provider, channels);
            var invitation = manager.createInvitation("global", UUID.randomUUID());
            provider.afterNextRead = () -> provider.deleteInvitation(invitation.getCode());
            assertThat(manager.forceRevokeInvitation(invitation.getCode())).isFalse();
            assertThat(provider.loadInvitation(invitation.getCode())).isEmpty();
        } finally { provider.shutdown(); }
    }

    @FunctionalInterface
    private interface AfterRead {
        void run() throws DatabaseException;
    }

    private static final class InterleavingSqlite extends SQLiteProvider {
        private AfterRead afterNextRead;

        private InterleavingSqlite(Path file) { super(file.toString(), 1); }

        @Override
        public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
            var snapshot = super.loadInvitation(code);
            var action = afterNextRead;
            afterNextRead = null;
            if (action != null) action.run();
            return snapshot;
        }
    }

}
