package com.nova.link.database;

import com.nova.link.social.NotificationPreference;
import com.nova.link.social.SocialRelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip CRUD tests for the schema v13 {@code social_relations} and
 * {@code notification_preferences} tables against a real on-disk SQLite file.
 *
 * <p>Exercises {@link AbstractJdbcProvider}'s dialect-neutral social-relation
 * SQL — DELETE+INSERT upsert on the composite key, SELECT COUNT for
 * {@link DatabaseProvider#isIgnored}, and the single-row preference upsert —
 * that the {@link MemoryProvider}-based tests cannot cover. Mirrors the
 * scenarios in {@code MemoryProviderSocialRelationsTest} so the JDBC path
 * receives the same contract coverage as the in-memory path.
 *
 * <p>Pattern follows {@link SchemaV5SqlitePersistenceTest}: {@code @TempDir} +
 * {@code new SQLiteProvider(path, 5)} + {@code initialize()/shutdown()}.
 * Migration runs to CURRENT_VERSION=13, so both tables exist automatically.
 *
 * <p>Requirements: §11.6 item-18 (social relations &amp; ignore)
 */
@DisplayName("Schema v13 social_relations / notification_preferences CRUD (SQLite)")
class SocialRelationsJdbcPersistenceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @TempDir
    Path tempDir;

    private SQLiteProvider provider;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new SQLiteProvider(tempDir.resolve("social-test.db").toString(), 5);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        provider.shutdown();
    }

    // ==================== social_relations ====================

    @Test
    @DisplayName("save then getSocialRelations round-trips all fields (sourceId/targetId/type/createdAt/updatedAt)")
    void saveThenGetRoundTripsAllFields() throws DatabaseException {
        long createdAt = 1234567890L;
        long updatedAt = 1234567999L;
        SocialRelation relation = new SocialRelation(ALICE, BOB,
                SocialRelation.RelationType.IGNORE, createdAt, updatedAt);
        provider.saveSocialRelation(relation);

        List<SocialRelation> ignores = provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);

        assertThat(ignores).hasSize(1);
        SocialRelation loaded = ignores.get(0);
        assertThat(loaded.getSourceId()).isEqualTo(ALICE);
        assertThat(loaded.getTargetId()).isEqualTo(BOB);
        assertThat(loaded.getType()).isEqualTo(SocialRelation.RelationType.IGNORE);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("isIgnored returns true only for the stored direction (A→B true, B→A false, A→C false)")
    void isIgnoredIsDirectional() throws DatabaseException {
        assertThat(provider.isIgnored(ALICE, BOB)).isFalse();

        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));

        assertThat(provider.isIgnored(ALICE, BOB)).isTrue();
        // Directional: reverse direction is NOT implied.
        assertThat(provider.isIgnored(BOB, ALICE)).isFalse();
        // A different target is not ignored.
        assertThat(provider.isIgnored(ALICE, CAROL)).isFalse();
    }

    @Test
    @DisplayName("isIgnored is null-safe (returns false, does not throw)")
    void isIgnoredNullSafe() throws DatabaseException {
        assertThat(provider.isIgnored(null, BOB)).isFalse();
        assertThat(provider.isIgnored(ALICE, null)).isFalse();
        assertThat(provider.isIgnored(null, null)).isFalse();
    }

    @Test
    @DisplayName("save upserts on the composite key: second save replaces createdAt (DELETE+INSERT semantics)")
    void saveIsAnUpsert() throws DatabaseException {
        SocialRelation first = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);
        provider.saveSocialRelation(first);
        SocialRelation second = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 5L, 5L);
        provider.saveSocialRelation(second);

        List<SocialRelation> ignores = provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(1);
        // DELETE+INSERT replaces the row, so the new createdAt wins (matches MemoryProvider behaviour).
        assertThat(ignores.get(0).getCreatedAt()).isEqualTo(5L);
        assertThat(ignores.get(0).getUpdatedAt()).isEqualTo(5L);
        assertThat(provider.isIgnored(ALICE, BOB)).isTrue();
    }

    @Test
    @DisplayName("save does not collide across relation types for the same ordered pair")
    void saveDistinctTypesCoexist() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 10L, 10L));
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE, 20L, 20L));

        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).hasSize(1);
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE)).hasSize(1);
    }

    @Test
    @DisplayName("removeSocialRelation drops the stored ignore; missing-key remove is a no-op")
    void removeDropsIgnore() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        assertThat(provider.isIgnored(ALICE, BOB)).isTrue();

        provider.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);

        assertThat(provider.isIgnored(ALICE, BOB)).isFalse();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        // Removing again must not throw.
        provider.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);
    }

    @Test
    @DisplayName("removeSocialRelation is type-scoped: removing IGNORE leaves FAVORITE untouched")
    void removeIsTypeScoped() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE));

        provider.removeSocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);

        assertThat(provider.isIgnored(ALICE, BOB)).isFalse();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE)).hasSize(1);
    }

    @Test
    @DisplayName("removeSocialRelation with null arguments is a no-op (does not throw)")
    void removeNullSafe() throws DatabaseException {
        provider.removeSocialRelation(null, BOB, SocialRelation.RelationType.IGNORE);
        provider.removeSocialRelation(ALICE, null, SocialRelation.RelationType.IGNORE);
        provider.removeSocialRelation(ALICE, BOB, null);
    }

    @Test
    @DisplayName("getSocialRelations filters by type (IGNORE returns only IGNORE, never FAVORITE)")
    void getSocialRelationsFiltersByType() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 100L, 100L));
        provider.saveSocialRelation(new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.FAVORITE, 200L, 200L));
        provider.saveSocialRelation(new SocialRelation(ALICE, DAVE, SocialRelation.RelationType.IGNORE, 300L, 300L));

        List<SocialRelation> ignores = provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(2);
        assertThat(ignores).extracting(SocialRelation::getType)
                .containsOnly(SocialRelation.RelationType.IGNORE);
        assertThat(ignores).extracting(SocialRelation::getTargetId)
                .containsExactlyInAnyOrder(BOB, DAVE);

        List<SocialRelation> favorites = provider.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE);
        assertThat(favorites).hasSize(1);
        assertThat(favorites.get(0).getTargetId()).isEqualTo(CAROL);
    }

    @Test
    @DisplayName("getSocialRelations returns newest-first (ORDER BY created_at DESC)")
    void getSocialRelationsNewestFirst() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L));
        provider.saveSocialRelation(new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.IGNORE, 2L, 2L));
        provider.saveSocialRelation(new SocialRelation(ALICE, DAVE, SocialRelation.RelationType.IGNORE, 3L, 3L));

        List<SocialRelation> ignores = provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(3);
        assertThat(ignores.get(0).getTargetId()).isEqualTo(DAVE);
        assertThat(ignores.get(1).getTargetId()).isEqualTo(CAROL);
        assertThat(ignores.get(2).getTargetId()).isEqualTo(BOB);
    }

    @Test
    @DisplayName("getSocialRelations returns an empty list (never null) for a source with no relations")
    void getSocialRelationsEmptyForUnknownSource() throws DatabaseException {
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(provider.getSocialRelations(UUID.randomUUID(), SocialRelation.RelationType.FAVORITE)).isEmpty();
    }

    @Test
    @DisplayName("getSocialRelations with null arguments returns an empty list (does not throw)")
    void getSocialRelationsNullSafe() throws DatabaseException {
        assertThat(provider.getSocialRelations(null, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(provider.getSocialRelations(ALICE, null)).isEmpty();
        assertThat(provider.getSocialRelations(null, null)).isEmpty();
    }

    @Test
    @DisplayName("a source with multiple ignore targets lists all of them")
    void multipleIgnoreTargetsListed() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));
        provider.saveSocialRelation(new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.IGNORE));
        provider.saveSocialRelation(new SocialRelation(ALICE, DAVE, SocialRelation.RelationType.IGNORE));

        List<SocialRelation> ignores = provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
        assertThat(ignores).hasSize(3);
        assertThat(ignores).extracting(SocialRelation::getTargetId)
                .containsExactlyInAnyOrder(BOB, CAROL, DAVE);

        // Every target is ignored; a non-listed player is not.
        assertThat(provider.isIgnored(ALICE, BOB)).isTrue();
        assertThat(provider.isIgnored(ALICE, CAROL)).isTrue();
        assertThat(provider.isIgnored(ALICE, DAVE)).isTrue();
        UUID stranger = UUID.randomUUID();
        assertThat(provider.isIgnored(ALICE, stranger)).isFalse();
    }

    @Test
    @DisplayName("a source with no relations: isIgnored false and getSocialRelations empty")
    void emptySourceHasNoRelations() throws DatabaseException {
        assertThat(provider.isIgnored(ALICE, BOB)).isFalse();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE)).isEmpty();
    }

    @Test
    @DisplayName("save rejects null relation and relations with null key components")
    void saveRejectsNull() {
        assertThatThrownBy(() -> provider.saveSocialRelation(null))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> provider.saveSocialRelation(
                new SocialRelation(null, BOB, SocialRelation.RelationType.IGNORE)))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> provider.saveSocialRelation(
                new SocialRelation(ALICE, null, SocialRelation.RelationType.IGNORE)))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> provider.saveSocialRelation(
                new SocialRelation(ALICE, BOB, null)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    @DisplayName("FAVORITE relations do not affect isIgnored (only IGNORE does)")
    void favoriteDoesNotAffectIsIgnored() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE));

        assertThat(provider.isIgnored(ALICE, BOB)).isFalse();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE)).isEmpty();
        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE)).hasSize(1);
    }

    @Test
    @DisplayName("independent sources do not cross-contaminate (Alice's ignore does not affect Bob's view)")
    void independentSourcesDoNotLeak() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE));

        // Bob has no relations of his own.
        assertThat(provider.isIgnored(BOB, ALICE)).isFalse();
        assertThat(provider.getSocialRelations(BOB, SocialRelation.RelationType.IGNORE)).isEmpty();
        // Bob saving his own ignore toward Carol does not touch Alice's row.
        provider.saveSocialRelation(new SocialRelation(BOB, CAROL, SocialRelation.RelationType.IGNORE));

        assertThat(provider.isIgnored(ALICE, BOB)).isTrue();
        assertThat(provider.isIgnored(BOB, CAROL)).isTrue();
        assertThat(provider.isIgnored(BOB, ALICE)).isFalse();

        assertThat(provider.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE))
                .extracting(SocialRelation::getTargetId).containsExactly(BOB);
        assertThat(provider.getSocialRelations(BOB, SocialRelation.RelationType.IGNORE))
                .extracting(SocialRelation::getTargetId).containsExactly(CAROL);
    }

    @Test
    @DisplayName("reload after shutdown persists: relations survive a fresh provider on the same db file")
    void relationsPersistAcrossReopen() throws DatabaseException {
        provider.saveSocialRelation(new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 42L, 42L));
        provider.saveSocialRelation(new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.FAVORITE, 84L, 84L));
        provider.shutdown();

        // Reopen the same file — migrations are idempotent (CREATE TABLE IF NOT EXISTS).
        SQLiteProvider reopened = new SQLiteProvider(tempDir.resolve("social-test.db").toString(), 5);
        reopened.initialize();
        try {
            assertThat(reopened.isIgnored(ALICE, BOB)).isTrue();
            assertThat(reopened.isIgnored(ALICE, CAROL)).isFalse();

            List<SocialRelation> ignores = reopened.getSocialRelations(ALICE, SocialRelation.RelationType.IGNORE);
            assertThat(ignores).hasSize(1);
            assertThat(ignores.get(0).getTargetId()).isEqualTo(BOB);
            assertThat(ignores.get(0).getCreatedAt()).isEqualTo(42L);

            List<SocialRelation> favorites = reopened.getSocialRelations(ALICE, SocialRelation.RelationType.FAVORITE);
            assertThat(favorites).hasSize(1);
            assertThat(favorites.get(0).getTargetId()).isEqualTo(CAROL);
            assertThat(favorites.get(0).getCreatedAt()).isEqualTo(84L);
        } finally {
            reopened.shutdown();
        }
    }

    // ==================== notification_preferences ====================

    @Test
    @DisplayName("getNotificationPreference returns defaults (mentions enabled) when row absent")
    void preferenceDefaultsWhenAbsent() throws DatabaseException {
        NotificationPreference preference = provider.getNotificationPreference(ALICE);

        assertThat(preference).isNotNull();
        assertThat(preference.getPlayerId()).isEqualTo(ALICE);
        assertThat(preference.isMentionsEnabled()).isTrue();
    }

    @Test
    @DisplayName("saveNotificationPreference persists and getNotificationPreference returns it")
    void preferenceRoundTrip() throws DatabaseException {
        provider.saveNotificationPreference(new NotificationPreference(ALICE, false, 123L));

        NotificationPreference preference = provider.getNotificationPreference(ALICE);
        assertThat(preference.getPlayerId()).isEqualTo(ALICE);
        assertThat(preference.isMentionsEnabled()).isFalse();
        assertThat(preference.getUpdatedAt()).isEqualTo(123L);
    }

    @Test
    @DisplayName("saveNotificationPreference upserts on playerId (no duplicate rows)")
    void preferenceUpsert() throws DatabaseException {
        provider.saveNotificationPreference(new NotificationPreference(ALICE, false, 1L));
        provider.saveNotificationPreference(new NotificationPreference(ALICE, true, 2L));

        NotificationPreference preference = provider.getNotificationPreference(ALICE);
        assertThat(preference.isMentionsEnabled()).isTrue();
        assertThat(preference.getUpdatedAt()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getNotificationPreference(null) returns defaults rather than throwing")
    void preferenceNullSafe() throws DatabaseException {
        NotificationPreference preference = provider.getNotificationPreference(null);

        assertThat(preference).isNotNull();
        assertThat(preference.isMentionsEnabled()).isTrue();
    }

    @Test
    @DisplayName("saveNotificationPreference rejects null preference and null playerId")
    void preferenceSaveRejectsNull() {
        assertThatThrownBy(() -> provider.saveNotificationPreference(null))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> provider.saveNotificationPreference(new NotificationPreference(null, true, 1L)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    @DisplayName("preferences for distinct players are independent")
    void preferencesArePerPlayer() throws DatabaseException {
        provider.saveNotificationPreference(new NotificationPreference(ALICE, false, 10L));
        provider.saveNotificationPreference(new NotificationPreference(BOB, true, 20L));

        assertThat(provider.getNotificationPreference(ALICE).isMentionsEnabled()).isFalse();
        assertThat(provider.getNotificationPreference(BOB).isMentionsEnabled()).isTrue();
        // Carol has no row → defaults.
        assertThat(provider.getNotificationPreference(CAROL).isMentionsEnabled()).isTrue();
    }
}
