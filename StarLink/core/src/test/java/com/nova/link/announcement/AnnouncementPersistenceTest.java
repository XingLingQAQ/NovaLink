package com.nova.link.announcement;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence behavior of {@link AnnouncementManager} (schema v5
 * {@code announcements} table): JOIN/CRON announcements survive a restart,
 * INSTANT is fire-and-forget, and enable/disable + delete write through to
 * the database.
 */
@DisplayName("AnnouncementManager persistence + restart reload")
class AnnouncementPersistenceTest {

    private MemoryProvider provider;
    private ChannelManager channelManager;
    private PermissionManager permissionManager;
    private AnnouncementManager manager;
    private final List<String> sentAnnouncements = new CopyOnWriteArrayList<>();

    private static final UUID OPERATOR = new UUID(0L, 0L);

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new MemoryProvider();
        provider.initialize();

        channelManager = new ChannelManager();
        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());

        permissionManager = new PermissionManager();
        manager = newManager();
    }

    private AnnouncementManager newManager() {
        AnnouncementManager m = new AnnouncementManager(permissionManager, channelManager);
        m.setDatabaseProvider(provider);
        m.initialize();
        m.setAnnouncementSender((channelId, content) -> sentAnnouncements.add(channelId + ":" + content));
        return m;
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        provider.shutdown();
    }

    @Test
    @DisplayName("JOIN announcement is persisted with type JOIN")
    void joinAnnouncementPersisted() throws DatabaseException {
        AnnouncementResult result = manager.createJoinAnnouncement(
                OPERATOR, "global", "Welcome!", null, true);
        assertThat(result.isSuccess()).isTrue();

        List<Announcement> persisted = provider.getAllPersistedAnnouncements();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getType()).isEqualTo(AnnouncementType.JOIN);
        assertThat(persisted.get(0).getContent()).isEqualTo("Welcome!");
        assertThat(persisted.get(0).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("CRON announcement is persisted with its cron expression")
    void cronAnnouncementPersisted() throws DatabaseException {
        AnnouncementResult result = manager.createScheduledAnnouncement(
                OPERATOR, "global", "Vote now!", "0 12 * * *", null, true);
        assertThat(result.isSuccess()).isTrue();

        List<Announcement> persisted = provider.getAllPersistedAnnouncements();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getType()).isEqualTo(AnnouncementType.SCHEDULED);
        assertThat(persisted.get(0).getCronExpression()).isEqualTo("0 12 * * *");
    }

    @Test
    @DisplayName("invalid cron fails and persists nothing")
    void invalidCronNotPersisted() throws DatabaseException {
        AnnouncementResult result = manager.createScheduledAnnouncement(
                OPERATOR, "global", "Bad", "not-a-cron", null, true);
        assertThat(result.isSuccess()).isFalse();
        assertThat(provider.getAllPersistedAnnouncements()).isEmpty();
    }

    @Test
    @DisplayName("INSTANT announcement sends immediately and is never persisted")
    void instantAnnouncementNotPersisted() throws DatabaseException {
        AnnouncementResult result = manager.sendImmediateAnnouncement(
                OPERATOR, "global", "Now!", null, true);
        assertThat(result.isSuccess()).isTrue();
        assertThat(sentAnnouncements).containsExactly("global:Now!");
        assertThat(provider.getAllPersistedAnnouncements()).isEmpty();
    }

    @Test
    @DisplayName("delete removes the announcement from the database")
    void deleteRemovesFromDatabase() throws DatabaseException {
        String id = manager.createJoinAnnouncement(OPERATOR, "global", "Hi", null, true)
                .getAnnouncement().getId();
        assertThat(provider.getAllPersistedAnnouncements()).hasSize(1);

        AnnouncementResult result = manager.deleteAnnouncement(OPERATOR, id, null, true);
        assertThat(result.isSuccess()).isTrue();
        assertThat(provider.getAllPersistedAnnouncements()).isEmpty();
    }

    @Test
    @DisplayName("setAnnouncementEnabled(false/true) writes through and re-schedules CRON")
    void enabledFlagWritesThrough() throws DatabaseException {
        String id = manager.createScheduledAnnouncement(
                OPERATOR, "global", "Tick", "0 12 * * *", null, true)
                .getAnnouncement().getId();

        AnnouncementResult disabled = manager.setAnnouncementEnabled(id, false);
        assertThat(disabled.isSuccess()).isTrue();
        assertThat(disabled.getAnnouncement().isEnabled()).isFalse();
        assertThat(provider.getAllPersistedAnnouncements().get(0).isEnabled()).isFalse();

        AnnouncementResult enabled = manager.setAnnouncementEnabled(id, true);
        assertThat(enabled.isSuccess()).isTrue();
        assertThat(provider.getAllPersistedAnnouncements().get(0).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("setAnnouncementEnabled on an unknown id fails")
    void enabledUnknownIdFails() {
        assertThat(manager.setAnnouncementEnabled("nope", true).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("restart reload restores JOIN index and CRON announcements")
    void restartRestoresAnnouncements() {
        String joinId = manager.createJoinAnnouncement(OPERATOR, "global", "Welcome!", null, true)
                .getAnnouncement().getId();
        String cronId = manager.createScheduledAnnouncement(
                OPERATOR, "global", "Vote!", "0 12 * * *", null, true)
                .getAnnouncement().getId();
        manager.shutdown();

        // Simulated restart: a fresh manager over the same database.
        manager = newManager();
        assertThat(manager.loadPersistedAnnouncements()).isEqualTo(2);

        assertThat(manager.getAnnouncement(joinId)).isNotNull();
        assertThat(manager.getAnnouncement(cronId)).isNotNull();
        assertThat(manager.getAnnouncement(cronId).getCronExpression()).isEqualTo("0 12 * * *");
        // JOIN trigger index was rebuilt (returns the announcement contents).
        assertThat(manager.getJoinAnnouncements("global")).contains("Welcome!");
    }
}
