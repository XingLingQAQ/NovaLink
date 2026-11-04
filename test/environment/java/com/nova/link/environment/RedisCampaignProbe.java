package com.nova.link.environment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.announcement.Campaign;
import com.nova.link.announcement.CampaignStatus;
import com.nova.link.announcement.DeliveryPolicy;
import com.nova.link.database.RedisProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RedisCampaignProbe {
    public static void main(String[] args) throws Exception {
        verify(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject());
        System.out.println("Redis campaign persistence checks passed");
    }

    static void verify(JsonObject config) throws Exception {
        String host = config.get("host").getAsString();
        int port = config.get("port").getAsInt();
        String password = config.get("password").getAsString();
        int database = config.get("database").getAsInt();
        String first = "probe-" + UUID.randomUUID();
        String second = "probe-" + UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        UUID revoker = UUID.randomUUID();
        Campaign expected = new Campaign(first, "probe-channel", Set.of("bukkit", "fabric"), "probe content",
                CampaignStatus.SCHEDULED, 7, DeliveryPolicy.ACTIONBAR_FALLBACK, 101, 202, 4,
                creator, "probe-client", 303, 0, null);
        Campaign later = new Campaign(second, "probe-channel", Set.of(), "later", CampaignStatus.PREVIEW,
                0, DeliveryPolicy.INSTANT, 0, 0, 1, null, null, 404);
        RedisProvider provider = new RedisProvider(host, port, password, database);
        try {
            provider.initialize();
            provider.saveCampaign(new Campaign(first, "old-channel", Set.of("old"), "old", CampaignStatus.PREVIEW,
                    0, DeliveryPolicy.INSTANT, 0, 0, 1, null, null, 1));
            provider.saveCampaign(later);
            provider.saveCampaign(expected);
            provider.shutdown();
            provider = new RedisProvider(host, port, password, database);
            provider.initialize();
            Campaign loaded = provider.getCampaign(first).orElseThrow();
            check(loaded.getId().equals(first) && loaded.getChannelId().equals("probe-channel")
                    && loaded.getPlatforms().equals(expected.getPlatforms()) && loaded.getContent().equals("probe content")
                    && loaded.getStatus() == CampaignStatus.SCHEDULED && loaded.getScheduleRevision() == 7
                    && loaded.getDeliveryPolicy() == DeliveryPolicy.ACTIONBAR_FALLBACK
                    && loaded.getStartAt() == 101 && loaded.getEndAt() == 202
                    && loaded.getRateLimitPerChannelPerHour() == 4 && creator.equals(loaded.getCreatorId())
                    && "probe-client".equals(loaded.getCreatorClientId()) && loaded.getCreatedAt() == 303
                    && loaded.getRevokedAt() == 0 && loaded.getRevokedBy() == null, "upsert and full-field reconnect");
            List<Campaign> entries = provider.getAllPersistedCampaigns().stream()
                    .filter(c -> c.getId().equals(first) || c.getId().equals(second)).toList();
            check(entries.size() == 2 && entries.get(0).getId().equals(first) && entries.get(1).getId().equals(second),
                    "ordered list without duplicates");
            provider.updateCampaignStatus(first, CampaignStatus.REVOKED, 505, revoker);
            provider.updateCampaignStatus(second, CampaignStatus.ACTIVE, 0, null);
            provider.updateCampaignStatus("missing-" + first, CampaignStatus.ACTIVE, 0, null);
            provider.shutdown();
            provider = new RedisProvider(host, port, password, database);
            provider.initialize();
            Campaign revoked = provider.getCampaign(first).orElseThrow();
            Campaign emptyPlatforms = provider.getCampaign(second).orElseThrow();
            check(revoked.getStatus() == CampaignStatus.REVOKED && revoked.getRevokedAt() == 505
                    && revoker.equals(revoked.getRevokedBy()) && revoked.getPlatforms().equals(expected.getPlatforms())
                    && emptyPlatforms.getStatus() == CampaignStatus.ACTIVE && emptyPlatforms.getPlatforms().isEmpty()
                    && revoked.getScheduleRevision() == 7 && revoked.getDeliveryPolicy() == DeliveryPolicy.ACTIONBAR_FALLBACK
                    && emptyPlatforms.getCreatorId() == null && emptyPlatforms.getRevokedBy() == null,
                    "status updates and second reconnect");
            provider.deleteCampaign(first);
            provider.deleteCampaign(first);
            check(provider.getCampaign(first).isEmpty()
                    && provider.getAllPersistedCampaigns().stream().noneMatch(c -> c.getId().equals(first)), "delete");
        } finally {
            try {
                if (provider.isConnected()) {
                    provider.deleteCampaign(first);
                    provider.deleteCampaign(second);
                }
            } finally {
                provider.shutdown();
            }
        }
    }

    private static void check(boolean condition, String operation) {
        if (!condition) throw new IllegalStateException("Redis campaign persistence failed: " + operation);
    }
}
