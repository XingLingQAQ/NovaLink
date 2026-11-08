package com.nova.link.database;

import com.google.gson.Gson;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisInvitationExpiryTest {

    enum Write { SAVE, CLAIM, LEGACY_MARK }

    @ParameterizedTest
    @EnumSource(Write.class)
    void writesKeepTheInvitationsAbsoluteMillisecondDeadline(Write write) throws Exception {
        String port = System.getenv("NOVALINK_TEST_REDIS_PORT");
        assumeTrue(port != null, "Set NOVALINK_TEST_REDIS_PORT to an isolated local Redis");
        var provider = new RedisProvider("127.0.0.1", Integer.parseInt(port), null, 0);
        String code = "expiry-" + UUID.randomUUID();
        String key = "novalink:invitation:" + code;
        try (Jedis redis = new Jedis("127.0.0.1", Integer.parseInt(port))) {
            provider.initialize();
            long now = System.currentTimeMillis();
            long expiry = now + 60_987L;
            var invitation = new Invitation(code, "global", UUID.randomUUID(), expiry,
                    now, false, null, 0L, 3, 0, null);
            if (write == Write.SAVE) {
                provider.saveInvitation(invitation);
            } else {
                redis.set(key, new Gson().toJson(invitation), SetParams.setParams().pxAt(expiry));
                if (write == Write.CLAIM) {
                    assertThat(provider.claimInvitationUse(code, UUID.randomUUID(), now)).isEqualTo(1);
                } else {
                    assertThat(provider.markInvitationUsed(code, UUID.randomUUID())).isTrue();
                }
            }

            assertThat(redis.pexpireTime(key)).isEqualTo(expiry);
            assertThat(provider.loadInvitation(code).orElseThrow().getExpireTime()).isEqualTo(expiry);
        } finally {
            try {
                if (provider.isConnected()) provider.deleteInvitation(code);
            } finally {
                provider.shutdown();
            }
        }
    }
}
