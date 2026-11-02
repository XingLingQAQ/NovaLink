package com.nova.link.channel;

import com.nova.link.database.Invitation;
import com.nova.link.database.SQLiteProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

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
}
