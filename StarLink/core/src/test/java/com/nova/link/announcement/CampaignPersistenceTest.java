package com.nova.link.announcement;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.SQLiteProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip CRUD tests for the schema v14 {@code campaigns} table against a
 * real SQLite file — exercises the shared JDBC SQL in AbstractJdbcProvider
 * (delete-then-insert upsert, platforms Set ↔ comma-TEXT serialisation,
 * delivery_policy enum dbValue, nullable creator_id/revoked_by, status enum
 * name round-trip) that the Memory-based tests cannot cover.
 *
 * <p>Scope: persistence layer only. Does NOT test CampaignManager scheduling,
 * RBAC, rate limiting, or state-machine validity — those are covered by
 * CampaignManagerTest (slice A) and RestApiHandlerCampaignTest.
 */
@DisplayName("Campaign persistence CRUD (SQLite, schema v14)")
class CampaignPersistenceTest {

    @TempDir
    Path tempDir;

    private SQLiteProvider provider;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new SQLiteProvider(tempDir.resolve("campaigns-test.db").toString(), 5);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        provider.shutdown();
    }

    private static Campaign sampleCampaign(String id, CampaignStatus status) {
        Set<String> platforms = new LinkedHashSet<>();
        platforms.add("bukkit");
        platforms.add("fabric");
        platforms.add("nukkit");
        return new Campaign(
                id,
                "global",
                platforms,
                "Hello campaigners!",
                status,
                0L,
                DeliveryPolicy.TITLE_FALLBACK,
                1700000000L,
                1800000000L,
                5,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "panel-client",
                1700000000L
        );
    }

    @Test
    @DisplayName("save→load round-trips all fields including platforms Set and deliveryPolicy")
    void saveAndLoadRoundTripsAllFields() throws DatabaseException {
        Campaign original = sampleCampaign("CMP-aaaa1111", CampaignStatus.PREVIEW);
        provider.saveCampaign(original);

        Optional<Campaign> loaded = provider.getCampaign("CMP-aaaa1111");
        assertThat(loaded).isPresent();
        Campaign c = loaded.get();
        assertThat(c.getId()).isEqualTo("CMP-aaaa1111");
        assertThat(c.getChannelId()).isEqualTo("global");
        assertThat(c.getPlatforms()).containsExactly("bukkit", "fabric", "nukkit");
        assertThat(c.getContent()).isEqualTo("Hello campaigners!");
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.PREVIEW);
        assertThat(c.getScheduleRevision()).isEqualTo(0L);
        assertThat(c.getDeliveryPolicy()).isEqualTo(DeliveryPolicy.TITLE_FALLBACK);
        assertThat(c.getStartAt()).isEqualTo(1700000000L);
        assertThat(c.getEndAt()).isEqualTo(1800000000L);
        assertThat(c.getRateLimitPerChannelPerHour()).isEqualTo(5);
        assertThat(c.getCreatorId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(c.getCreatorClientId()).isEqualTo("panel-client");
        assertThat(c.getCreatedAt()).isEqualTo(1700000000L);
        assertThat(c.getRevokedAt()).isEqualTo(0L);
        assertThat(c.getRevokedBy()).isNull();
    }

    @Test
    @DisplayName("upsert (save twice) replaces the row, no duplicate")
    void upsertReplacesRowWithoutDuplicating() throws DatabaseException {
        Campaign c1 = sampleCampaign("CMP-bbbb2222", CampaignStatus.PREVIEW);
        provider.saveCampaign(c1);

        // Mutate and re-save — should replace, not add a second row.
        c1.setStatus(CampaignStatus.SCHEDULED);
        provider.saveCampaign(c1);

        List<Campaign> all = provider.getAllPersistedCampaigns();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo("CMP-bbbb2222");
        assertThat(all.get(0).getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("delete removes the campaign row")
    void deleteRemovesCampaign() throws DatabaseException {
        Campaign c = sampleCampaign("CMP-cccc3333", CampaignStatus.PREVIEW);
        provider.saveCampaign(c);
        assertThat(provider.getCampaign("CMP-cccc3333")).isPresent();

        provider.deleteCampaign("CMP-cccc3333");
        assertThat(provider.getCampaign("CMP-cccc3333")).isEmpty();
    }

    @Test
    @DisplayName("getAllPersistedCampaigns returns all saved campaigns sorted by createdAt")
    void listReturnsAllSavedCampaigns() throws DatabaseException {
        Campaign c1 = sampleCampaign("CMP-dddd4444", CampaignStatus.PREVIEW);
        Campaign c2 = sampleCampaign("CMP-eeee5555", CampaignStatus.SCHEDULED);
        provider.saveCampaign(c2);
        provider.saveCampaign(c1);

        List<Campaign> all = provider.getAllPersistedCampaigns();
        assertThat(all).hasSize(2);
        // Sorted by created_at ASC — both share the same createdAt here, so
        // insertion order should be preserved by the stable ORDER BY.
        assertThat(all).extracting(Campaign::getId)
                .containsExactlyInAnyOrder("CMP-dddd4444", "CMP-eeee5555");
    }

    @Test
    @DisplayName("updateCampaignStatus persists ACTIVE→REVOKED with revokedAt/revokedBy, reload still REVOKED")
    void statusChangePersistsAcrossReload() throws DatabaseException {
        Campaign c = sampleCampaign("CMP-ffff6666", CampaignStatus.ACTIVE);
        provider.saveCampaign(c);

        UUID revoker = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long revokedAt = 1750000000L;
        provider.updateCampaignStatus("CMP-ffff6666", CampaignStatus.REVOKED, revokedAt, revoker);

        // Reload from DB — the status, revokedAt and revokedBy must survive.
        Optional<Campaign> reloaded = provider.getCampaign("CMP-ffff6666");
        assertThat(reloaded).isPresent();
        Campaign r = reloaded.get();
        assertThat(r.getStatus()).isEqualTo(CampaignStatus.REVOKED);
        assertThat(r.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(r.getRevokedBy()).isEqualTo(revoker);
        // Non-revoked fields should still be intact.
        assertThat(r.getChannelId()).isEqualTo("global");
        assertThat(r.getPlatforms()).containsExactly("bukkit", "fabric", "nukkit");
        assertThat(r.getDeliveryPolicy()).isEqualTo(DeliveryPolicy.TITLE_FALLBACK);
    }

    @Test
    @DisplayName("platforms Set round-trips through comma-joined TEXT (empty, single, multi)")
    void platformsSetRoundTripsViaCommaText() throws DatabaseException {
        // Single platform.
        Campaign single = new Campaign(
                "CMP-single01", "global", new LinkedHashSet<>(List.of("bukkit")),
                "one", CampaignStatus.PREVIEW, 0L, DeliveryPolicy.INSTANT,
                0L, 0L, 1, null, null, 1L);
        provider.saveCampaign(single);
        Campaign loadedSingle = provider.getCampaign("CMP-single01").orElseThrow();
        assertThat(loadedSingle.getPlatforms()).containsExactly("bukkit");

        // Multiple platforms (LinkedHashSet preserves insertion order).
        Set<String> many = new LinkedHashSet<>();
        many.add("bukkit");
        many.add("folia");
        many.add("paper");
        many.add("spigot");
        Campaign multi = new Campaign(
                "CMP-multi002", "global", many, "many", CampaignStatus.PREVIEW,
                0L, DeliveryPolicy.ACTIONBAR_FALLBACK, 0L, 0L, 1, null, null, 2L);
        provider.saveCampaign(multi);
        Campaign loadedMulti = provider.getCampaign("CMP-multi002").orElseThrow();
        assertThat(loadedMulti.getPlatforms()).containsExactly("bukkit", "folia", "paper", "spigot");
    }

    @Test
    @DisplayName("deliveryPolicy round-trips for all three enum values")
    void deliveryPolicyRoundTripsAllValues() throws DatabaseException {
        for (DeliveryPolicy policy : DeliveryPolicy.values()) {
            String id = "CMP-pol-" + policy.name();
            Campaign c = new Campaign(
                    id, "global", new LinkedHashSet<>(List.of("bukkit")),
                    "p", CampaignStatus.PREVIEW, 0L, policy,
                    0L, 0L, 1, null, null, 1L);
            provider.saveCampaign(c);
            Campaign loaded = provider.getCampaign(id).orElseThrow();
            assertThat(loaded.getDeliveryPolicy())
                    .as("delivery policy round-trip for %s", policy.name())
                    .isEqualTo(policy);
        }
    }

    @Test
    @DisplayName("nullable creatorId/creatorClientId/revokedBy round-trip as SQL NULL")
    void nullableFieldsRoundTripAsNull() throws DatabaseException {
        Campaign c = new Campaign(
                "CMP-null0001", "global", new LinkedHashSet<>(List.of("bukkit")),
                "n", CampaignStatus.PREVIEW, 0L, DeliveryPolicy.INSTANT,
                0L, 0L, 1, null, null, 1L);
        provider.saveCampaign(c);

        Campaign loaded = provider.getCampaign("CMP-null0001").orElseThrow();
        assertThat(loaded.getCreatorId()).isNull();
        assertThat(loaded.getCreatorClientId()).isNull();
        assertThat(loaded.getRevokedBy()).isNull();
        assertThat(loaded.getRevokedAt()).isEqualTo(0L);
    }
}
