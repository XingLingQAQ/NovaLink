package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.Invitation;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Proposal 03 invitation enhancements: multi-use
 * invitations ({@code maxUses}/{@code usedCount}) and soft-revocation
 * ({@code revokedAt}).
 *
 * <p>Covers the new {@code createInvitation(..., maxUses)} overload, the
 * single-use backward-compat path, revocation semantics, the permission
 * guard on {@code revokeInvitation}, and the atomic claim that prevents
 * over-acceptance under concurrent {@code acceptInvitation} calls. Backed
 * by {@link MemoryProvider} so no external database is required.
 */
@DisplayName("InvitationManager — multi-use + revocation (Proposal 03)")
class InvitationManagerTest {

    private MemoryProvider db;
    private ChannelManager channelManager;
    private InvitationManager invitationManager;

    private static final String CHANNEL_ID = "test-channel";

    @BeforeEach
    void setUp() throws DatabaseException {
        db = new MemoryProvider();
        db.initialize();
        channelManager = new ChannelManager();
        invitationManager = new InvitationManager(db, channelManager);

        ChannelConfig config = ChannelConfig.builder()
                .id(CHANNEL_ID)
                .displayName("Test Channel")
                .scope(ChannelScope.GLOBAL)
                .build();
        channelManager.createChannel(config);
    }

    @AfterEach
    void tearDown() throws DatabaseException {
        db.shutdown();
    }

    @Test
    @DisplayName("maxUses=3 allows three accepts then marks the invitation used")
    void multiUseInvitationAllowsUpToMaxUses() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId,
                InvitationManager.DEFAULT_TTL_MILLIS, 3);
        String code = invitation.getCode();
        assertThat(invitation.getMaxUses()).isEqualTo(3);
        assertThat(invitation.getUsedCount()).isZero();
        assertThat(invitation.isUsed()).isFalse();

        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();

        assertThat(invitationManager.acceptInvitation(code, a1, null).isSuccess()).isTrue();
        assertThat(invitationManager.acceptInvitation(code, a2, null).isSuccess()).isTrue();
        assertThat(invitationManager.acceptInvitation(code, a3, null).isSuccess()).isTrue();

        Invitation exhausted = invitationManager.getInvitation(code).orElseThrow();
        assertThat(exhausted.getUsedCount()).isEqualTo(3);
        assertThat(exhausted.isUsed()).isTrue();

        UUID fourth = UUID.randomUUID();
        InvitationResult fourthResult = invitationManager.acceptInvitation(code, fourth, null);
        assertThat(fourthResult.isSuccess()).isFalse();
        assertThat(fourthResult.getErrorCode()).isEqualTo("NC-411");
    }

    @Test
    @DisplayName("maxUses=1 (default) is single-use — second accept is NC-411")
    void defaultMaxUsesIsSingleUse() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId);
        String code = invitation.getCode();
        assertThat(invitation.getMaxUses()).isEqualTo(1);

        UUID accepter = UUID.randomUUID();
        assertThat(invitationManager.acceptInvitation(code, accepter, null).isSuccess()).isTrue();

        InvitationResult second = invitationManager.acceptInvitation(code, UUID.randomUUID(), null);
        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getErrorCode()).isEqualTo("NC-411");
    }

    @Test
    @DisplayName("a revoked invitation fails validation with NC-410")
    void revokedInvitationIsInvalid() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId);
        String code = invitation.getCode();

        assertThat(invitationManager.validateInvitation(code).isSuccess()).isTrue();
        assertThat(invitationManager.revokeInvitation(code, inviterId)).isTrue();

        InvitationResult result = invitationManager.validateInvitation(code);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-410");

        Invitation persisted = invitationManager.getInvitation(code).orElseThrow();
        assertThat(persisted.isRevoked()).isTrue();
        assertThat(persisted.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("accepting a revoked invitation fails")
    void acceptingRevokedInvitationFails() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId);
        String code = invitation.getCode();

        assertThat(invitationManager.revokeInvitation(code, inviterId)).isTrue();

        InvitationResult result = invitationManager.acceptInvitation(code, UUID.randomUUID(), null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-410");
    }

    @Test
    @DisplayName("revokeInvitation by a non-inviter is refused")
    void nonInviterCannotRevoke() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId);
        String code = invitation.getCode();

        UUID stranger = UUID.randomUUID();
        assertThat(invitationManager.revokeInvitation(code, stranger)).isFalse();

        // Still valid — revocation was refused.
        assertThat(invitationManager.validateInvitation(code).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("maxUses < 1 is rejected with IllegalArgumentException")
    void maxUsesMustBeAtLeastOne() {
        UUID inviterId = UUID.randomUUID();
        assertThatThrownBy(() -> invitationManager.createInvitation(CHANNEL_ID, inviterId,
                InvitationManager.DEFAULT_TTL_MILLIS, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("multi-use invitation: accept past maxUses returns NC-411 (not NC-410)")
    void multiUseExhaustionReturnsUsedNotRevoked() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId,
                InvitationManager.DEFAULT_TTL_MILLIS, 2);
        String code = invitation.getCode();

        assertThat(invitationManager.acceptInvitation(code, UUID.randomUUID(), null).isSuccess()).isTrue();
        assertThat(invitationManager.acceptInvitation(code, UUID.randomUUID(), null).isSuccess()).isTrue();

        // Third accept must be rejected as USED (NC-411), never NC-410 (revoked).
        InvitationResult third = invitationManager.acceptInvitation(code, UUID.randomUUID(), null);
        assertThat(third.isSuccess()).isFalse();
        assertThat(third.getErrorCode()).isEqualTo("NC-411");
    }

    @Test
    @DisplayName("a revoked multi-use invitation fails accept with NC-410 even before exhaustion")
    void revokedMultiUseInvitationFailsAccept() throws DatabaseException {
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId,
                InvitationManager.DEFAULT_TTL_MILLIS, 5);
        String code = invitation.getCode();

        // One use succeeds, leaving quota remaining.
        assertThat(invitationManager.acceptInvitation(code, UUID.randomUUID(), null).isSuccess()).isTrue();

        // Revoke after the first use — remaining quota must be unusable.
        assertThat(invitationManager.revokeInvitation(code, inviterId)).isTrue();

        InvitationResult afterRevoke = invitationManager.acceptInvitation(code, UUID.randomUUID(), null);
        assertThat(afterRevoke.isSuccess()).isFalse();
        // validateInvitation sees revoked → NC-410 before the claim even runs.
        assertThat(afterRevoke.getErrorCode()).isEqualTo("NC-410");
    }

    @Test
    @DisplayName("concurrent accepts never exceed maxUses (atomic claim)")
    void concurrentAcceptsNeverExceedMaxUses() throws Exception {
        // maxUses=3, 16 concurrent accepters. The atomic claim must guarantee
        // exactly 3 successes and exactly maxUses channel members, regardless
        // of thread interleaving. This is the regression test for the
        // load → incrementUse → save race the atomic claim replaces.
        int maxUses = 3;
        int threadCount = 16;
        UUID inviterId = UUID.randomUUID();
        Invitation invitation = invitationManager.createInvitation(CHANNEL_ID, inviterId,
                InvitationManager.DEFAULT_TTL_MILLIS, maxUses);
        String code = invitation.getCode();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger nc411 = new AtomicInteger();
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        InvitationResult r = invitationManager.acceptInvitation(code, UUID.randomUUID(), null);
                        if (r.isSuccess()) {
                            successes.incrementAndGet();
                        } else if ("NC-411".equals(r.getErrorCode())) {
                            nc411.incrementAndGet();
                        }
                    } catch (DatabaseException e) {
                        // surface as test failure below
                        throw new RuntimeException(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(successes.get())
                .as("exactly maxUses accepts must succeed under concurrency")
                .isEqualTo(maxUses);
        assertThat(successes.get() + nc411.get())
                .as("every contender must resolve to success or NC-411")
                .isEqualTo(threadCount);

        // The persisted invitation is exhausted and its usedCount is exactly
        // maxUses — not maxUses + the over-accept count the old race produced.
        Invitation exhausted = invitationManager.getInvitation(code).orElseThrow();
        assertThat(exhausted.getUsedCount()).isEqualTo(maxUses);
        assertThat(exhausted.isUsed()).isTrue();
    }
}
