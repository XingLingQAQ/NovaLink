package com.nova.link.database;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisInvitationRevocationTest {
    @Test
    void revocationPreservesUsesAndExpiryAndNeverRecreatesAMissingKey() throws Exception {
        String port = System.getenv("NOVALINK_TEST_REDIS_PORT");
        assumeTrue(port != null, "Set NOVALINK_TEST_REDIS_PORT to an isolated local Redis");
        var provider = new RedisProvider("127.0.0.1", Integer.parseInt(port), null, 0);
        String code = "test-" + UUID.randomUUID();
        var workers = Executors.newFixedThreadPool(2);
        long now = System.currentTimeMillis();
        long expiry = now + 60_000L;
        try {
            provider.initialize();
            provider.saveInvitation(new Invitation(code, "global", UUID.randomUUID(), expiry,
                    now, false, null, 0L, 100, 0, null));
            assertThat(provider.claimInvitationUse(code, UUID.randomUUID(), now)).isEqualTo(1);
            CountDownLatch start = new CountDownLatch(1);
            var claiming = workers.submit(() -> {
                start.await();
                int successes = 0;
                for (int i = 0; i < 8; i++) {
                    successes += provider.claimInvitationUse(code, UUID.randomUUID(), System.currentTimeMillis());
                }
                return successes;
            });
            var revoking = workers.submit(() -> {
                start.await();
                return provider.markInvitationRevoked(code, now + 1L);
            });
            start.countDown();
            assertThat(revoking.get(5, TimeUnit.SECONDS)).isTrue();
            int uses = claiming.get(5, TimeUnit.SECONDS) + 1;
            var stored = provider.loadInvitation(code).orElseThrow();
            assertThat(stored.getUsedCount()).isEqualTo(uses);
            assertThat(stored.getRevokedAt()).isEqualTo(now + 1L);
            assertThat(provider.markInvitationRevoked(code, now + 2L)).isTrue();
            assertThat(provider.loadInvitation(code).orElseThrow().getRevokedAt()).isEqualTo(now + 1L);
            assertThat(provider.claimInvitationUse(code, UUID.randomUUID(), now + 3L)).isZero();
            try (Jedis redis = new Jedis("127.0.0.1", Integer.parseInt(port))) {
                long remaining = redis.pttl("novalink:invitation:" + code);
                assertThat(remaining).isPositive().isLessThanOrEqualTo(expiry - System.currentTimeMillis() + 100L);
            }
            provider.deleteInvitation(code);
            assertThat(provider.markInvitationRevoked(code, now + 4L)).isFalse();
            assertThat(provider.loadInvitation(code)).isEmpty();
        } finally {
            workers.shutdownNow();
            try {
                if (provider.isConnected()) provider.deleteInvitation(code);
            } finally { provider.shutdown(); }
        }
    }
}
