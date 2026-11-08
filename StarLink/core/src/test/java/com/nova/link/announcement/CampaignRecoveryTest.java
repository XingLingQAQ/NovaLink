package com.nova.link.announcement;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.*;
import com.nova.link.database.*;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CampaignRecoveryTest {
    private CampaignManager manager(DatabaseProvider db) {
        ChannelManager channels = new ChannelManager();
        channels.createChannel(ChannelConfig.builder().id("global").displayName("Global").scope(ChannelScope.GLOBAL).build());
        var manager = new CampaignManager(new PermissionManager(), channels);
        manager.setDatabaseProvider(db);
        manager.initialize();
        return manager;
    }

    private Campaign preview(CampaignManager manager, int limit) {
        return manager.createCampaign(null, "global", "content", Set.of("BUKKIT"),
                DeliveryPolicy.INSTANT, 0L, 0L, limit, null, true).getCampaign();
    }

    @Test
    void failedSenderDoesNotConsumeQuotaOrPreventRetry() {
        var manager = manager(null);
        try {
            var campaign = preview(manager, 1);
            assertThat(manager.scheduleCampaign(campaign.getId(), null, true).getErrorCode()).isEqualTo(CampaignResult.CODE_INTERNAL_ERROR);
            manager.setAnnouncementSender((channel, content) -> { throw new IllegalStateException("injected"); });
            assertThat(manager.scheduleCampaign(campaign.getId(), null, true).getErrorCode()).isEqualTo(CampaignResult.CODE_INTERNAL_ERROR);
            assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PREVIEW);
            assertThat(manager.currentHourDeliveryCount("global")).isZero();
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            assertThat(manager.scheduleCampaign(campaign.getId(), null, true).isSuccess()).isTrue();
            assertThat(manager.activateCampaign(campaign.getId(), null, true).isSuccess()).isFalse();
            assertThat(sent).hasValue(1);
        } finally { manager.shutdown(); }
    }

    @Test
    void scheduledDeliveryFailureCanBeRetriedWithoutDuplicatingSuccess() throws Exception {
        var manager = manager(null);
        CountDownLatch attempted = new CountDownLatch(1);
        try {
            manager.setAnnouncementSender((channel, content) -> {
                attempted.countDown();
                throw new IllegalStateException("injected scheduled failure");
            });
            long now = System.currentTimeMillis();
            var campaign = manager.createCampaign(null, "global", "scheduled", Set.of("BUKKIT"),
                    DeliveryPolicy.INSTANT, now - 1000, now + 60_000, 1, null, true).getCampaign();
            assertThat(manager.scheduleCampaign(campaign.getId(), null, true).isSuccess()).isTrue();
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            assertThat(manager.activateCampaign(campaign.getId(), null, true).isSuccess()).isTrue();
            assertThat(manager.activateCampaign(campaign.getId(), null, true).isSuccess()).isFalse();
            assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
            assertThat(manager.currentHourDeliveryCount("global")).isEqualTo(1);
            assertThat(sent).hasValue(1);
        } finally { manager.shutdown(); }
    }

    @Test
    void rateLimitedCampaignStaysRetryable() {
        var manager = manager(null);
        try {
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            var first = preview(manager, 1);
            var second = preview(manager, 1);
            assertThat(manager.scheduleCampaign(first.getId(), null, true).isSuccess()).isTrue();
            var limited = manager.scheduleCampaign(second.getId(), null, true);
            assertThat(limited.getErrorCode()).isEqualTo(CampaignResult.CODE_RATE_LIMITED);
            assertThat(second.getStatus()).isEqualTo(CampaignStatus.PREVIEW);
            assertThat(sent).hasValue(1);
        } finally { manager.shutdown(); }
    }

    @Test
    void concurrentActivationSendsOnlyOnce() throws Exception {
        var manager = manager(null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var campaign = preview(manager, 10);
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> action = () -> { start.await(); return manager.activateCampaign(campaign.getId(), null, true).isSuccess(); };
            var first = pool.submit(action);
            var second = pool.submit(action);
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sent).hasValue(1);
        } finally { pool.shutdownNow(); manager.shutdown(); }
    }

    private Campaign recorded(String id, CampaignStatus status, long start, long end) {
        return new Campaign(id, "global", Set.of("BUKKIT"), "recorded", status, 2L,
                DeliveryPolicy.INSTANT, start, end, 10, null, null, 1L);
    }

    @Test
    void restoringPastWindowsExpiresWithoutSending() throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        long past = System.currentTimeMillis() - 10_000L;
        db.saveCampaign(recorded("CMP-active", CampaignStatus.ACTIVE, past - 10_000L, past));
        db.saveCampaign(recorded("CMP-scheduled", CampaignStatus.SCHEDULED, past - 10_000L, past));
        var manager = manager(db);
        try {
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            assertThat(manager.loadPersistedCampaigns()).isEqualTo(2);
            assertThat(manager.getCampaign("CMP-active").getStatus()).isEqualTo(CampaignStatus.EXPIRED);
            assertThat(manager.getCampaign("CMP-scheduled").getStatus()).isEqualTo(CampaignStatus.EXPIRED);
            assertThat(db.getCampaign("CMP-active").orElseThrow().getStatus()).isEqualTo(CampaignStatus.EXPIRED);
            assertThat(manager.getScheduledTaskCount()).isZero();
            assertThat(sent).hasValue(0);
        } finally { manager.shutdown(); db.shutdown(); }
    }

    @Test
    void restoredActiveExpiresAndScheduledKeepsItsTask() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        var db = new MemoryProvider() {
            @Override public void saveCampaign(Campaign campaign) throws DatabaseException {
                super.saveCampaign(campaign);
                if (campaign.getId().equals("CMP-active") && campaign.getStatus() == CampaignStatus.EXPIRED) expired.countDown();
            }
        };
        db.initialize();
        long now = System.currentTimeMillis();
        db.saveCampaign(recorded("CMP-active", CampaignStatus.ACTIVE, now - 1000, now + 1500));
        db.saveCampaign(recorded("CMP-scheduled", CampaignStatus.SCHEDULED, now + 60_000, now + 120_000));
        var manager = manager(db);
        try {
            AtomicInteger sent = new AtomicInteger();
            manager.setAnnouncementSender((channel, content) -> sent.incrementAndGet());
            assertThat(manager.loadPersistedCampaigns()).isEqualTo(2);
            assertThat(manager.getScheduledTaskCount()).isEqualTo(2);
            assertThat(expired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.getScheduledTaskCount()).isEqualTo(1);
            assertThat(manager.getCampaign("CMP-scheduled").getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
            assertThat(sent).hasValue(0);
        } finally { manager.shutdown(); db.shutdown(); }
    }
}
