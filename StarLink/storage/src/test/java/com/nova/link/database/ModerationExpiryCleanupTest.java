package com.nova.link.database;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModerationExpiryCleanupTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryCleanupKeepsRenewalsAfterReadingAnExpiredRecord(boolean ban) throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        UUID player = UUID.randomUUID();
        Runnable renew = renewal(provider, ban, player);
        try {
            seedOtherRecords(provider, ban, player);
            if (ban) {
                provider.saveBan(player, new BanInfo(null, 1L, "old", player) {
                    @Override public long getExpireTime() {
                        renew.run();
                        return super.getExpireTime();
                    }
                });
            } else {
                provider.saveMute(player, new MuteInfo(null, 1L, "old", player) {
                    @Override public long getExpireTime() {
                        renew.run();
                        return super.getExpireTime();
                    }
                });
            }

            int removed = ban ? provider.cleanupExpiredBans() : provider.cleanupExpiredMutes();
            assertThat(reasons(provider, ban, player)).containsExactlyInAnyOrder("renewed", "permanent", "future");
            assertThat(removed).isEqualTo(1);
        } finally {
            provider.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redisCleanupKeepsRenewalsAfterReadingAnExpiredSnapshot(boolean ban) throws Exception {
        String portText = System.getenv("NOVALINK_TEST_REDIS_PORT");
        assumeTrue(portText != null, "Set NOVALINK_TEST_REDIS_PORT to an isolated local Redis");
        int port = Integer.parseInt(portText);
        var provider = new RedisProvider("127.0.0.1", port, null, 0);
        UUID player = UUID.randomUUID();
        String key = "novalink:" + (ban ? "ban:" : "mute:") + player;
        var poolField = RedisProvider.class.getDeclaredField("jedisPool");
        poolField.setAccessible(true);
        JedisPool originalPool = null;
        JedisPool interceptingPool = null;
        try {
            provider.initialize();
            originalPool = (JedisPool) poolField.get(provider);
            seedOtherRecords(provider, ban, player);
            save(provider, ban, player, null, 1L, "old");
            Runnable renew = renewal(provider, ban, player);
            interceptingPool = new JedisPool("127.0.0.1", port) {
                @Override public Jedis getResource() {
                    return new Jedis("127.0.0.1", port) {
                        @Override public Map<String, String> hgetAll(String hash) {
                            Map<String, String> snapshot = super.hgetAll(hash);
                            if (hash.equals(key)) renew.run();
                            return snapshot;
                        }
                    };
                }
            };
            poolField.set(provider, interceptingPool);

            int removed = ban ? provider.cleanupExpiredBans() : provider.cleanupExpiredMutes();
            assertThat(reasons(provider, ban, player)).containsExactlyInAnyOrder("renewed", "permanent", "future");
            assertThat(removed).isEqualTo(1);
        } finally {
            try (Jedis redis = new Jedis("127.0.0.1", port)) {
                redis.del(key);
            } finally {
                poolField.set(provider, originalPool);
                if (interceptingPool != null) interceptingPool.close();
                provider.shutdown();
            }
        }
    }

    private static Runnable renewal(DatabaseProvider provider, boolean ban, UUID player) {
        var pending = new AtomicBoolean(true);
        return () -> {
            if (pending.compareAndSet(true, false)) {
                try {
                    save(provider, ban, player, null, 0L, "renewed");
                } catch (DatabaseException e) {
                    throw new AssertionError("renewal failed", e);
                }
            }
        };
    }

    private static void seedOtherRecords(DatabaseProvider provider, boolean ban, UUID player) throws DatabaseException {
        save(provider, ban, player, "expired", 1L, "expired");
        save(provider, ban, player, "permanent", 0L, "permanent");
        save(provider, ban, player, "future", System.currentTimeMillis() + 60_000L, "future");
    }

    private static void save(DatabaseProvider provider, boolean ban, UUID player,
                             String channel, long expiry, String reason) throws DatabaseException {
        if (ban) provider.saveBan(player, new BanInfo(channel, expiry, reason, player));
        else provider.saveMute(player, new MuteInfo(channel, expiry, reason, player));
    }

    private static List<String> reasons(DatabaseProvider provider, boolean ban, UUID player) throws DatabaseException {
        if (ban) return provider.loadBans(player).stream().map(BanInfo::getReason).toList();
        return provider.loadMutes(player).stream().map(MuteInfo::getReason).toList();
    }
}
