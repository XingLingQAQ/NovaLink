package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.SlowModeTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live-Redis persistence evidence for audit item VERIFY-006 (Redis slice):
 * "Redis/旧数据库共存 — an old Redis database written by a previous DTO version
 * must keep channel owner / slow-mode semantics when read by new code, and
 * '再重启并比较' — the same DB must survive a provider shutdown + re-instantiate."
 *
 * <p>Redis is a non-migrating backend: its schema is the JSON shape of
 * {@code RedisProvider.ChannelDto}. The legacy-tolerance risk is therefore
 * concrete: payloads written before {@code slowModeSeconds} existed must load
 * without crashing and without losing the owner. All tests run against the
 * throwaway local Redis on 127.0.0.1:6390, database 15, flushed in setUp for
 * hermeticity; skipped cleanly via JUnit assumption on hosts without it.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Test A — full-field roundtrip: save → load preserves every persisted
 *       field including ownerId and slowModeSeconds.</li>
 *   <li>Test B — legacy-DTO tolerance: a raw JSON payload missing
 *       {@code slowModeSeconds} (pre-663e7a9 shape) loads fine, keeps the
 *       owner, and documents Gson's actual default for the missing int.</li>
 *   <li>Test C — restart semantics: save → shutdown → fresh provider against
 *       the SAME unflushed database → load: owner and slow mode survive.</li>
 *   <li>Test D — slow-mode boundary semantics at 0 (disabled) vs &gt; 0 via
 *       the pure {@code SlowModeTracker} with an injectable clock.</li>
 * </ul>
 */
@DisplayName("RedisProvider channel slow-mode/owner persistence + legacy-DTO tolerance (VERIFY-006)")
class RedisProviderSlowModePersistenceTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6390;
    private static final int DATABASE = 15;

    /** Must mirror RedisProvider's internal CHANNEL_PREFIX ("novalink:channel:"). */
    private static final String CHANNEL_KEY_PREFIX = "novalink:channel:";

    private RedisProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        // Skip gracefully when the throwaway Redis instance is not running.
        assumeTrue(isTestRedisUp(),
                "No Redis on " + HOST + ":" + PORT + " — skipping live integration test");

        provider = new RedisProvider(HOST, PORT, null, DATABASE);
        provider.initialize();
        // Hermetic fixture: wipe only this test's logical database.
        provider.clearAllForTests();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            try {
                provider.clearAllForTests();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            provider.shutdown();
        }
    }

    private boolean isTestRedisUp() {
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds a fully populated PRIVATE channel: owner UUID, permission,
     * password, allowed worlds and a 30-second slow-mode window.
     */
    private Channel fullyPopulatedChannel(String id) {
        Channel channel = new Channel(id, "Legacy Check", ChannelScope.PRIVATE, "client-1");
        channel.setOwnerId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        channel.setPermission("novalink.channel.legacy");
        channel.setPassword("secret-pass");
        channel.setAllowedWorlds(Arrays.asList("world", "world_nether"));
        channel.setSlowModeSeconds(30);
        return channel;
    }

    @Test
    @DisplayName("Test A: save→load roundtrip preserves every field incl ownerId and slowModeSeconds")
    void testARoundTripPreservesAllFields() throws DatabaseException {
        Channel original = fullyPopulatedChannel("verify006-a");
        provider.saveChannel(original);

        Optional<Channel> loaded = provider.loadChannel("verify006-a");
        assertThat(loaded).as("channel key novalink:channel:verify006-a must exist").isPresent();

        Channel c = loaded.get();
        assertThat(c.getId()).isEqualTo("verify006-a");
        assertThat(c.getDisplayName()).isEqualTo("Legacy Check");
        assertThat(c.getScope()).isEqualTo(ChannelScope.PRIVATE);
        assertThat(c.getClientId()).isEqualTo("client-1");
        assertThat(c.getPermission()).isEqualTo("novalink.channel.legacy");
        assertThat(c.getPassword()).isEqualTo("secret-pass");
        assertThat(c.getMaxCapacity()).isEqualTo(100); // Channel default survives the JSON trip
        assertThat(c.getAllowedWorlds()).containsExactly("world", "world_nether");

        // KNOWN SYSTEMIC LIMITATION (pinned current behavior, not a Redis-only
        // defect): createdAt is NOT restored by ANY provider on load. Every
        // loadChannel (SQLite/MySQL/PostgreSQL/Redis) constructs a fresh
        // Channel whose final createdAt re-stamps from the wall clock;
        // Channel has no setCreatedAt. Startup hydration
        // (NovaLinkMain.loadPersistedChannels) rebuilds channels through
        // createChannel anyway, and no production code consumes
        // Channel.getCreatedAt(), so the reset is behaviorally inert today.
        // Deliberately NOT asserted equal here.

        // The two fields this VERIFY slice exists for.
        assertThat(c.getOwnerId())
                .as("owner UUID must survive the JSON roundtrip")
                .isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        assertThat(c.getSlowModeSeconds())
                .as("slow-mode window must survive the JSON roundtrip")
                .isEqualTo(30);
    }

    @Test
    @DisplayName("Test B: legacy payload without slowModeSeconds loads fine, owner kept, default documented")
    void testBLegacyDtoWithoutSlowModeSecondsTolerated() throws DatabaseException {
        String channelId = "verify006-b";

        // Simulate the pre-slow-mode DTO shape (before commit 663e7a9): write
        // the old field subset directly into the exact Redis key the provider
        // reads, bypassing saveChannel entirely.
        String legacyJson = "{"
                + "\"id\":\"" + channelId + "\","
                + "\"displayName\":\"Old Channel\","
                + "\"scope\":\"PRIVATE\","
                + "\"clientId\":\"client-old\","
                + "\"permission\":\"novalink.channel.old\","
                + "\"maxCapacity\":50,"
                + "\"allowedWorlds\":[\"world\"],"
                + "\"password\":\"old-pass\","
                + "\"ownerId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
                + "\"createdAt\":1700000000000"
                + "}"; // no slowModeSeconds — the legacy shape

        try (Jedis jedis = new Jedis(HOST, PORT)) {
            jedis.select(DATABASE);
            jedis.set(CHANNEL_KEY_PREFIX + channelId, legacyJson);
        }

        Optional<Channel> loaded = provider.loadChannel(channelId);
        assertThat(loaded)
                .as("legacy payload must load without crashing (Gson treats the "
                        + "missing primitive int as its 0 default)")
                .isPresent();

        Channel c = loaded.get();
        // Owner preservation is the hard requirement of VERIFY-006.
        assertThat(c.getOwnerId())
                .as("owner must survive even when newer fields are absent")
                .isEqualTo(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(c.getId()).isEqualTo(channelId);
        assertThat(c.getDisplayName()).isEqualTo("Old Channel");
        assertThat(c.getScope()).isEqualTo(ChannelScope.PRIVATE);
        assertThat(c.getClientId()).isEqualTo("client-old");
        assertThat(c.getPermission()).isEqualTo("novalink.channel.old");
        assertThat(c.getMaxCapacity()).isEqualTo(50);
        assertThat(c.getPassword()).isEqualTo("old-pass");

        // Document the ACTUAL default Gson produces for the missing field:
        // a missing primitive int deserializes to 0, which Channel maps to
        // "slow mode disabled" — semantically safe (no throttling invented),
        // never a crash.
        assertThat(c.getSlowModeSeconds())
                .as("Gson missing-primitive-int default for slowModeSeconds; "
                        + "0 means slow mode disabled, matching pre-feature behavior")
                .isZero();

        // Re-saving through the NEW code upgrades the stored payload in place.
        provider.saveChannel(c);
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            jedis.select(DATABASE);
            String upgraded = jedis.get(CHANNEL_KEY_PREFIX + channelId);
            assertThat(upgraded).contains("slowModeSeconds");
        }
    }

    @Test
    @DisplayName("Test C: shutdown + re-instantiate against same DB ('再重启') keeps owner/slow mode")
    void testCRestartSemanticsPreserveOwnerAndSlowMode() throws DatabaseException {
        Channel original = fullyPopulatedChannel("verify006-c");
        provider.saveChannel(original);

        // Literal doc step: shut the provider down...
        provider.shutdown();

        // ...then re-instantiate a brand-new provider against the SAME Redis
        // database, deliberately NOT flushed in between.
        RedisProvider restarted = new RedisProvider(HOST, PORT, null, DATABASE);
        try {
            restarted.initialize();
            assertThat(restarted.isConnected()).isTrue();

            List<Channel> all = restarted.getAllChannels();
            assertThat(all).as("index set must survive the provider restart").hasSize(1);

            Optional<Channel> loaded = restarted.loadChannel("verify006-c");
            assertThat(loaded).isPresent();
            Channel c = loaded.get();

            assertThat(c.getOwnerId())
                    .as("owner must survive shutdown/restart cycle")
                    .isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"));
            assertThat(c.getSlowModeSeconds())
                    .as("slow-mode window must survive shutdown/restart cycle")
                    .isEqualTo(30);
            assertThat(c.getPassword()).isEqualTo("secret-pass");
            assertThat(c.getPermission()).isEqualTo("novalink.channel.legacy");
            assertThat(c.getAllowedWorlds()).containsExactly("world", "world_nether");
            // createdAt intentionally not asserted: see the systemic-limitation
            // comment in Test A — no provider restores it (fresh Channel on
            // load, no setCreatedAt), and nothing consumes it post-creation.
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    @DisplayName("Test D: slow-mode boundary — 0 disables throttling, >0 blocks within window (injectable clock)")
    void testDSlowModeTrackerBoundaryZeroVsPositive() {
        // Pure unit check of the consumption side of the persisted slow-mode
        // value: the tracker is clock-injected so no sleeping is needed.
        LongSupplier fixedClock = () -> 1_000_000L;
        SlowModeTracker tracker = new SlowModeTracker(fixedClock);
        UUID player = UUID.fromString("99999999-8888-7777-6666-555555555555");
        String channelId = "verify006-d";

        try {
            // Boundary 0 = slow mode disabled: every message passes, and
            // nothing enters the tracking map.
            assertThat(tracker.tryAcquire(player, channelId, 0)).isZero();
            assertThat(tracker.tryAcquire(player, channelId, 0)).isZero();

            // A persisted positive window (e.g. 30 from Test A) must block a
            // second message inside the window, reporting remaining >= 1s.
            long firstWait = tracker.tryAcquire(player, "verify006-a", 30);
            assertThat(firstWait).as("first message inside an active window always passes").isZero();
            long secondWait = tracker.tryAcquire(player, "verify006-a", 30);
            assertThat(secondWait).as("second immediate message blocked with remaining wait").isEqualTo(30);

            // After the window elapses (clock advanced past now + 30s) the
            // next message passes again.
            LongSupplier later = () -> 1_000_000L + 31_000L;
            SlowModeTracker after = new SlowModeTracker(later);
            // Fresh tracker at the advanced time: window from the old tracker
            // is irrelevant; this asserts a cold start passes immediately.
            assertThat(after.tryAcquire(player, channelId, 30)).isZero();
        } finally {
            tracker.shutdown();
        }
    }
}
