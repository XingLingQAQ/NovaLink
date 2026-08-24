package com.nova.link.social;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the in-memory social-relation store added by §11.6 item-18 /
 * PANEL proposal 08 to {@link MemoryProvider}.
 *
 * <p>Contract: save→isIgnored true; remove→false; ignore is directional
 * (A→B does NOT imply B→A); preference defaults are returned when absent; the
 * per-source {@code compute} upsert is linearized under concurrency; and
 * shutdown clears all stored state.
 */
@DisplayName("MemoryProvider social relations")
class MemoryProviderSocialRelationsTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private MemoryProvider db;

    @BeforeEach
    void setUp() throws DatabaseException {
        db = new MemoryProvider();
        db.initialize();
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    @DisplayName("save then isIgnored returns true for the stored direction only")
    void saveThenIsIgnored() throws DatabaseException {
        assertThat(db.isIgnored(ALICE, BOB)).isFalse();

        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));

        assertThat(db.isIgnored(ALICE, BOB)).isTrue();
        // Directional: the reverse direction is NOT implied.
        assertThat(db.isIgnored(BOB, ALICE)).isFalse();
        // A different target is not ignored.
        assertThat(db.isIgnored(ALICE, CAROL)).isFalse();
    }

    @Test
    @DisplayName("isIgnored is null-safe (returns false, does not throw)")
    void isIgnoredNullSafe() {
        assertThat(db.isIgnored(null, BOB)).isFalse();
        assertThat(db.isIgnored(ALICE, null)).isFalse();
        assertThat(db.isIgnored(null, null)).isFalse();
    }

    @Test
    @DisplayName("remove drops the stored ignore; missing-key remove is a no-op")
    void removeDropsIgnore() throws DatabaseException {
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        assertThat(db.isIgnored(ALICE, BOB)).isTrue();

        db.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);

        assertThat(db.isIgnored(ALICE, BOB)).isFalse();
        // Removing again must not throw.
        db.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);
    }

    @Test
    @DisplayName("remove does not touch a different relation type for the same pair")
    void removeIsTypeScoped() throws DatabaseException {
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE));

        db.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);

        assertThat(db.isIgnored(ALICE, BOB)).isFalse();
        assertThat(db.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE))
                .hasSize(1);
    }

    @Test
    @DisplayName("getSocialRelations returns newest-first and only the requested type")
    void getSocialRelationsNewestFirst() throws DatabaseException, InterruptedException {
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L));
        Thread.sleep(2);
        db.saveSocialRelation(new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.IGNORE, 2L, 2L));
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE, 3L, 3L));

        List<SocialRelation> ignores = db.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(2);
        assertThat(ignores.get(0).getTargetId()).isEqualTo(CAROL);
        assertThat(ignores.get(1).getTargetId()).isEqualTo(BOB);

        List<SocialRelation> favorites = db.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE);
        assertThat(favorites).hasSize(1);
        assertThat(favorites.get(0).getTargetId()).isEqualTo(BOB);
    }

    @Test
    @DisplayName("getSocialRelations returns an empty list (never null) for an unknown source")
    void getSocialRelationsUnknownSource() {
        assertThat(db.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
    }

    @Test
    @DisplayName("save upserts on the composite key (no duplicate rows)")
    void saveIsAnUpsert() throws DatabaseException {
        SocialRelation first = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);
        db.saveSocialRelation(first);
        SocialRelation second = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 5L, 5L);
        db.saveSocialRelation(second);

        List<SocialRelation> ignores = db.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(1);
        // The upsert replaced the prior row.
        assertThat(ignores.get(0).getCreatedAt()).isEqualTo(5L);
    }

    @Test
    @DisplayName("save rejects null and relations with null key components")
    void saveRejectsNull() {
        assertThatThrownBy(() -> db.saveSocialRelation(null))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> db.saveSocialRelation(
                new SocialRelation(null, BOB, SocialRelation.RelationType.IGNORE)))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> db.saveSocialRelation(
                new SocialRelation(ALICE, BOB, null)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    @DisplayName("save rejects when the provider is not initialized")
    void saveRequiresInitialization() {
        MemoryProvider uninitialised = new MemoryProvider();
        assertThatThrownBy(() -> uninitialised.saveSocialRelation(
                new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    @DisplayName("getNotificationPreference returns defaults (mentions enabled) when absent")
    void preferenceDefaultsWhenAbsent() throws DatabaseException {
        NotificationPreference preference = db.getNotificationPreference(ALICE);

        assertThat(preference).isNotNull();
        assertThat(preference.getPlayerId()).isEqualTo(ALICE);
        assertThat(preference.isMentionsEnabled()).isTrue();
    }

    @Test
    @DisplayName("saveNotificationPreference persists and getNotificationPreference returns it")
    void preferenceRoundTrip() throws DatabaseException {
        db.saveNotificationPreference(new NotificationPreference(ALICE, false, 123L));

        NotificationPreference preference = db.getNotificationPreference(ALICE);
        assertThat(preference.isMentionsEnabled()).isFalse();
        assertThat(preference.getUpdatedAt()).isEqualTo(123L);
    }

    @Test
    @DisplayName("saveNotificationPreference upserts on playerId")
    void preferenceUpsert() throws DatabaseException {
        db.saveNotificationPreference(new NotificationPreference(ALICE, false, 1L));
        db.saveNotificationPreference(new NotificationPreference(ALICE, true, 2L));

        NotificationPreference preference = db.getNotificationPreference(ALICE);
        assertThat(preference.isMentionsEnabled()).isTrue();
        assertThat(preference.getUpdatedAt()).isEqualTo(2L);
    }

    @Test
    @DisplayName("saveNotificationPreference rejects null and null playerId")
    void preferenceSaveRejectsNull() {
        assertThatThrownBy(() -> db.saveNotificationPreference(null))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> db.saveNotificationPreference(new NotificationPreference(null, true, 1L)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    @DisplayName("getNotificationPreference(null) returns defaults rather than throwing")
    void preferenceNullSafe() {
        NotificationPreference preference = db.getNotificationPreference(null);

        assertThat(preference).isNotNull();
        assertThat(preference.isMentionsEnabled()).isTrue();
    }

    @Test
    @DisplayName("concurrent saves of the same composite key are linearized (one winner)")
    void concurrentUpsertIsLinearized() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<SocialRelation> winner = new AtomicReference<>();
        try {
            for (int i = 0; i < threads; i++) {
                final long ts = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        SocialRelation relation = new SocialRelation(
                                ALICE, BOB, SocialRelation.RelationType.IGNORE, ts, ts);
                        db.saveSocialRelation(relation);
                        winner.set(relation);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // Exactly one IGNORE relation survives the concurrent upserts.
        List<SocialRelation> ignores = db.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(1);
        assertThat(db.isIgnored(ALICE, BOB)).isTrue();
    }

    @Test
    @DisplayName("shutdown clears all stored relations and preferences")
    void shutdownClearsState() throws DatabaseException {
        db.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        db.saveNotificationPreference(new NotificationPreference(ALICE, false, 1L));

        db.shutdown();
        // Reinitialize to observe cleared state.
        db.initialize();

        assertThat(db.isIgnored(ALICE, BOB)).isFalse();
        assertThat(db.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(db.getNotificationPreference(ALICE).isMentionsEnabled()).isTrue();
    }
}
