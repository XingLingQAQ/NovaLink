package com.nova.link.database;

import com.nova.link.audit.AuditEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisHistoryPaginationTest {
    enum Feed { NOTIFICATIONS, USER_NOTIFICATIONS, AUDIT, MESSAGES }

    @ParameterizedTest
    @EnumSource(Feed.class)
    void pagesAndTotalsFilterAcrossBatchesWithoutDownloadingTheWholeIndex(Feed feed) throws Exception {
        String portText = System.getenv("NOVALINK_TEST_REDIS_PORT");
        assumeTrue(portText != null, "Set NOVALINK_TEST_REDIS_PORT to an isolated local Redis");
        int port = Integer.parseInt(portText);
        var provider = new RedisProvider("127.0.0.1", port, null, 0);
        var poolField = RedisProvider.class.getDeclaredField("jedisPool");
        poolField.setAccessible(true);
        var maxBatch = new AtomicInteger();
        var downloaded = new AtomicInteger();
        var appendAfterRead = new AtomicBoolean();
        var deleteGhostsAfterRead = new AtomicBoolean();
        JedisPool originalPool = null;
        JedisPool meteredPool = null;
        try {
            provider.initialize();
            provider.clearAllForTests();
            originalPool = (JedisPool) poolField.get(provider);
            List<Long> all = new ArrayList<>();
            List<Long> matching = new ArrayList<>();
            for (int i = 1; i <= 384; i++) {
                long id;
                boolean matches;
                if (feed == Feed.AUDIT) {
                    var event = new AuditEvent(UUID.randomUUID().toString(), null, i % 3 == 0 ? "Alice" : "Bob",
                            "ADMIN", null, i % 2 == 0 ? "channel.update" : "channel.create", "channel:global",
                            null, null, null, "success", 1000L);
                    provider.saveAuditEvent(event);
                    id = event.getId();
                    matches = i % 6 == 0;
                } else if (feed == Feed.MESSAGES) {
                    var message = new ChatMessageRecord(i % 3 == 0 ? "staff" : "global", null,
                            i % 2 == 0 ? "Alice" : "Bob", "Survival", "message " + i, 1000L);
                    provider.saveMessage(message);
                    id = message.getId();
                    matches = i % 6 == 0;
                } else {
                    String recipient = null;
                    if (i % 3 == 1) recipient = " ALICE ";
                    if (i % 3 == 2) recipient = "bob";
                    var notification = new Notification(0L, "notice " + i, "message", "info", 1000L,
                            i % 4 == 0, recipient);
                    provider.saveNotification(notification);
                    id = notification.getId();
                    if (feed == Feed.USER_NOTIFICATIONS && i % 5 == 0) {
                        provider.markNotificationRead(id, "alice");
                    }
                    matches = i % 4 != 0 && (feed == Feed.NOTIFICATIONS || (i % 3 != 2 && i % 5 != 0));
                    if (feed == Feed.USER_NOTIFICATIONS && i % 3 == 2) continue;
                }
                all.add(id);
                if (matches) matching.add(id);
            }
            Comparator<Long> order = Comparator.comparing(String::valueOf, Comparator.reverseOrder());
            all.sort(order);
            matching.sort(order);
            String index = switch (feed) {
                case NOTIFICATIONS, USER_NOTIFICATIONS -> "novalink:notifications";
                case AUDIT -> "novalink:audit_events";
                case MESSAGES -> "novalink:messages";
            };
            try (Jedis redis = new Jedis("127.0.0.1", port)) {
                redis.zadd(index, 1000L, "9999");
            }
            meteredPool = new JedisPool("127.0.0.1", port) {
                @Override public Jedis getResource() {
                    return new Jedis("127.0.0.1", port) {
                        private List<String> measure(List<String> ids) {
                            maxBatch.accumulateAndGet(ids.size(), Math::max);
                            downloaded.addAndGet(ids.size());
                            return ids;
                        }
                        @Override public List<String> zrevrangeByScore(String key, String max, String min) {
                            return measure(super.zrevrangeByScore(key, max, min));
                        }
                        @Override public List<String> zrevrangeByScore(String key, String max, String min, int offset, int count) {
                            return measure(super.zrevrangeByScore(key, max, min, offset, count));
                        }
                        @Override public List<String> zrangeByScore(String key, String min, String max) {
                            return measure(super.zrangeByScore(key, min, max));
                        }
                        @Override public List<Tuple> zrevrangeByScoreWithScores(String key, String max, String min,
                                                                              int offset, int count) {
                            List<Tuple> rows = super.zrevrangeByScoreWithScores(key, max, min, offset, count);
                            maxBatch.accumulateAndGet(rows.size(), Math::max);
                            downloaded.addAndGet(rows.size());
                            if (appendAfterRead.compareAndSet(true, false)) {
                                try (Jedis other = new Jedis("127.0.0.1", port)) {
                                    other.zadd(key, 2000L, "new-after-read");
                                }
                            }
                            if (deleteGhostsAfterRead.compareAndSet(true, false)) {
                                try (Jedis other = new Jedis("127.0.0.1", port)) {
                                    other.zrem(key, "9999", "new-after-read");
                                }
                            }
                            return rows;
                        }
                    };
                }
            };
            poolField.set(provider, meteredPool);

            assertThat(page(provider, feed, false, 0, 3)).containsExactlyElementsOf(all.subList(0, 3));
            assertThat(downloaded.get()).as("IDs transferred for a three-row first page").isLessThan(385);
            assertThat(maxBatch.get()).isLessThanOrEqualTo(256);

            downloaded.set(0);
            maxBatch.set(0);
            appendAfterRead.set(true);
            assertThat(page(provider, feed, true, 40, 17)).containsExactlyElementsOf(matching.subList(40, 57));
            deleteGhostsAfterRead.set(true);
            assertThat(total(provider, feed)).isEqualTo(matching.size());
            assertThat(downloaded.get()).isGreaterThan(256);
            assertThat(maxBatch.get()).isLessThanOrEqualTo(256);
        } finally {
            poolField.set(provider, originalPool);
            if (meteredPool != null) meteredPool.close();
            try {
                if (provider.isConnected()) provider.clearAllForTests();
            } finally {
                provider.shutdown();
            }
        }
    }

    private static MessageFilter messageFilter() {
        return new MessageFilter(null, null, "ALICE", null, 1000L, 1000L, Set.of("staff"));
    }

    private static List<Long> page(DatabaseProvider provider, Feed feed, boolean filtered, int offset, int limit)
            throws DatabaseException {
        return switch (feed) {
            case NOTIFICATIONS -> provider.getNotifications(offset, limit, filtered).stream().map(Notification::getId).toList();
            case USER_NOTIFICATIONS -> provider.getNotifications(offset, limit, filtered, "alice")
                    .stream().map(Notification::getId).toList();
            case AUDIT -> provider.getAuditEvents(offset, limit, filtered ? "ALICE" : null,
                    filtered ? "CHANNEL.UPDATE" : null).stream().map(AuditEvent::getId).toList();
            case MESSAGES -> provider.searchMessages(filtered ? messageFilter() : MessageFilter.any(), offset, limit)
                    .stream().map(ChatMessageRecord::getId).toList();
        };
    }

    private static int total(DatabaseProvider provider, Feed feed) throws DatabaseException {
        return switch (feed) {
            case NOTIFICATIONS -> provider.countNotifications(true);
            case USER_NOTIFICATIONS -> provider.countNotifications(true, "alice");
            case AUDIT -> provider.countAuditEvents("ALICE", "CHANNEL.UPDATE");
            case MESSAGES -> provider.countMessages(messageFilter());
        };
    }
}
