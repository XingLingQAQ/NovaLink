package com.nova.link.database;

import com.nova.link.announcement.Announcement;
import com.nova.link.announcement.AnnouncementType;
import com.nova.link.api.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip CRUD tests for the schema v5 {@code announcements} and
 * {@code webhooks} tables against a real SQLite file — exercises the shared
 * JDBC SQL in AbstractJdbcProvider (delete-then-insert upsert, field mapping)
 * that the Memory-based tests cannot cover.
 */
@DisplayName("Schema v5 announcements/webhooks CRUD (SQLite)")
class SchemaV5SqlitePersistenceTest {

    @TempDir
    Path tempDir;

    private SQLiteProvider provider;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new SQLiteProvider(tempDir.resolve("v5-test.db").toString(), 5);
        provider.initialize();
    }

    @AfterEach
    void tearDown() {
        provider.shutdown();
    }

    @Test
    @DisplayName("announcement round-trips all fields, upserts, and deletes")
    void announcementCrudRoundTrip() throws DatabaseException {
        UUID creator = UUID.randomUUID();
        Announcement cron = new Announcement("ann-1", "global", "Vote now!",
                AnnouncementType.SCHEDULED, creator, null, 12345L, true);
        cron.setCronExpression("0 12 * * *");
        provider.saveAnnouncement(cron);

        Announcement join = new Announcement("ann-2", "staff", "Welcome",
                AnnouncementType.JOIN, null, null, 67890L, false);
        provider.saveAnnouncement(join);

        List<Announcement> all = provider.getAllPersistedAnnouncements();
        assertThat(all).hasSize(2);

        Announcement loadedCron = all.stream().filter(a -> a.getId().equals("ann-1")).findFirst().orElseThrow();
        assertThat(loadedCron.getType()).isEqualTo(AnnouncementType.SCHEDULED);
        assertThat(loadedCron.getChannelId()).isEqualTo("global");
        assertThat(loadedCron.getContent()).isEqualTo("Vote now!");
        assertThat(loadedCron.getCronExpression()).isEqualTo("0 12 * * *");
        assertThat(loadedCron.isEnabled()).isTrue();
        assertThat(loadedCron.getCreatedAt()).isEqualTo(12345L);

        Announcement loadedJoin = all.stream().filter(a -> a.getId().equals("ann-2")).findFirst().orElseThrow();
        assertThat(loadedJoin.getType()).isEqualTo(AnnouncementType.JOIN);
        assertThat(loadedJoin.getCronExpression()).isNull();
        assertThat(loadedJoin.isEnabled()).isFalse();

        // Upsert: saving the same id replaces, not duplicates.
        cron.setEnabled(false);
        provider.saveAnnouncement(cron);
        List<Announcement> afterUpdate = provider.getAllPersistedAnnouncements();
        assertThat(afterUpdate).hasSize(2);
        assertThat(afterUpdate.stream().filter(a -> a.getId().equals("ann-1")).findFirst().orElseThrow()
                .isEnabled()).isFalse();

        provider.deleteAnnouncement("ann-1");
        assertThat(provider.getAllPersistedAnnouncements())
                .extracting(Announcement::getId)
                .containsExactly("ann-2");
    }

    @Test
    @DisplayName("webhook round-trips all fields, upserts, and deletes")
    void webhookCrudRoundTrip() throws DatabaseException {
        Webhook webhook = new Webhook("wh-1", "https://example.com/hook", "message.sent",
                "s3cret", true, 111L, 0L);
        provider.saveWebhook(webhook);
        provider.saveWebhook(new Webhook("wh-2", "https://example.com/other", "player.join",
                null, false, 222L, 333L));

        List<Webhook> all = provider.getAllPersistedWebhooks();
        assertThat(all).hasSize(2);

        Webhook loaded = all.stream().filter(w -> w.getId().equals("wh-1")).findFirst().orElseThrow();
        assertThat(loaded.getUrl()).isEqualTo("https://example.com/hook");
        assertThat(loaded.getEvent()).isEqualTo("message.sent");
        assertThat(loaded.getSecret()).isEqualTo("s3cret");
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isEqualTo(111L);
        assertThat(loaded.getLastTriggered()).isZero();

        Webhook inactive = all.stream().filter(w -> w.getId().equals("wh-2")).findFirst().orElseThrow();
        assertThat(inactive.isActive()).isFalse();
        assertThat(inactive.getLastTriggered()).isEqualTo(333L);

        // Upsert: update active + lastTriggered under the same id.
        webhook.setActive(false);
        webhook.setLastTriggered(999L);
        provider.saveWebhook(webhook);
        List<Webhook> afterUpdate = provider.getAllPersistedWebhooks();
        assertThat(afterUpdate).hasSize(2);
        Webhook updated = afterUpdate.stream().filter(w -> w.getId().equals("wh-1")).findFirst().orElseThrow();
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getLastTriggered()).isEqualTo(999L);

        provider.deleteWebhook("wh-2");
        assertThat(provider.getAllPersistedWebhooks())
                .extracting(Webhook::getId)
                .containsExactly("wh-1");
    }
}
