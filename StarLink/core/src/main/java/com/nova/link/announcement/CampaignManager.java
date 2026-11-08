package com.nova.link.announcement;

import com.nova.link.lifecycle.ShutdownBudget;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.PermissionResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Campaign manager with optional persistence through {@link DatabaseProvider}.
 *
 * <p>A campaign is an orchestrated, scheduled, revocable announcement with a
 * strict PREVIEW → SCHEDULED → ACTIVE → {EXPIRED, REVOKED} state machine.
 * Campaigns never self-replicate: activation uses a one-shot
 * {@link ScheduledExecutorService#schedule} (no {@code scheduleAtFixedRate}),
 * with transitions and task replacement serialized on each campaign.
 *
 * <p><b>RBAC reality (honest gap):</b> docs 提案 06 requires separated
 * edit / publish / revoke capabilities. The current {@link PermissionManager}
 * is a 4-level hierarchy enum (PLAYER &lt; CHANNEL_ADMIN &lt; CLIENT_ADMIN
 * &lt; SUPER_ADMIN) with no capability-string checks. Slice A maps the three
 * campaign operations onto the existing hierarchy as a temporary
 * approximation:
 * <ul>
 *   <li>{@code campaign.edit}   (create) → {@link PermissionLevel#CHANNEL_ADMIN}</li>
 *   <li>{@code campaign.publish} (schedule/activate) → {@link PermissionLevel#CLIENT_ADMIN}</li>
 *   <li>{@code campaign.revoke}  → {@link PermissionLevel#SUPER_ADMIN}</li>
 * </ul>
 * True three-way capability separation requires extending PermissionManager
 * (a capability dimension or a dedicated CampaignPermission model) — out of
 * scope for slice A and recorded as architecture debt.
 *
 * <p><b>Audit:</b> each operation records an event via the injected canonical
 * {@link AuditStore} (setter-injected). When the store is null, audit calls
 * are skipped (the business operation still succeeds), mirroring
 * {@link com.nova.link.moderation.ModerationManager}.
 *
 * <p><b>Rate limit:</b> per-channel/per-hour delivery cap, enforced via a
 * fixed-hour counter ({@link #deliveryHourCounter}). The current hour is
 * computed from {@link System#currentTimeMillis()} / 3_600_000 (no java.time
 * dependency). When the cap is exceeded, {@link CampaignResult#rateLimited}
 * is returned and the delivery is skipped.
 *
 * <p>Requirements: §11.6 提案 06.
 */
public class CampaignManager {

    private static final Logger logger = LoggerFactory.getLogger(CampaignManager.class);

    /** Hex characters for ID generation. */
    private static final String HEX = "0123456789abcdef";
    /** Length of the random hex part of a campaign ID. */
    private static final int ID_HEX_LENGTH = 8;
    /** Default per-channel/per-hour delivery cap. */
    public static final int DEFAULT_RATE_LIMIT_PER_CHANNEL_PER_HOUR = 10;

    /** All campaigns indexed by ID (in-memory, slice A). */
    private final Map<String, Campaign> campaigns = new ConcurrentHashMap<>();
    /** One-shot scheduled activation tasks, keyed by campaign ID. */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    /** Per-channel delivery-hour counter key → {hourBucket, count}. */
    private final Map<String, long[]> deliveryHourCounter = new ConcurrentHashMap<>();

    private final PermissionManager permissionManager;
    private final ChannelManager channelManager;
    private final SecureRandom random = new SecureRandom();

    /** Optional canonical audit store; when null, audit calls are skipped. */
    private volatile AuditStore auditStore;
    /** Delivery callback: (channelId, content) → void. Injected by the wiring layer. */
    private volatile BiConsumer<String, String> announcementSender;
    /** Scheduler for one-shot activation/expiry tasks. */
    private volatile ScheduledExecutorService scheduler;
    /**
     * Optional persistence backend (schema v14 campaigns table). When set,
     * campaigns are written through on create/schedule/activate/revoke and
     * restored via {@link #loadPersistedCampaigns()} at startup. When null,
     * CampaignManager degrades to pure in-memory (slice A behaviour, fail-open).
     */
    private volatile DatabaseProvider databaseProvider;

    public CampaignManager(PermissionManager permissionManager, ChannelManager channelManager) {
        this.permissionManager = Objects.requireNonNull(permissionManager,
                "PermissionManager cannot be null");
        this.channelManager = Objects.requireNonNull(channelManager,
                "ChannelManager cannot be null");
    }

    /**
     * Wires the canonical {@link AuditStore} (called after construction).
     * Null = audit disabled (business operations still succeed, mirroring
     * {@link com.nova.link.moderation.ModerationManager}).
     */
    public void setAuditStore(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * Wires the delivery callback. Content is routed through the trusted
     * {@code MessageRouter} path by the wiring layer (same pattern as
     * {@link AnnouncementManager#setAnnouncementSender(BiConsumer)}).
     *
     * @param sender (channelId, content) → void
     */
    public void setAnnouncementSender(BiConsumer<String, String> sender) {
        this.announcementSender = sender;
    }

    /**
     * Wires the optional persistence backend (schema v14 campaigns table).
     * Must be called before {@link #initialize()} for
     * {@link #loadPersistedCampaigns()} to take effect. Null = degrade to
     * pure in-memory (slice A behaviour, fail-open) — persistence failures
     * never block the authoritative in-memory state.
     */
    public void setDatabaseProvider(DatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
    }

    /**
     * Starts the internal scheduler (2 daemon threads, same shape as
     * {@link AnnouncementManager#initialize()}).
     */
    public void initialize() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "CampaignManager-Scheduler");
            t.setDaemon(true);
            return t;
        });
        logger.info("CampaignManager initialized");
    }

    /**
     * Shuts down the scheduler and cancels all armed one-shot tasks.
     */
    public void shutdown() {
        shutdown(new ShutdownBudget(Duration.ofSeconds(5)));
    }

    public void shutdown(ShutdownBudget budget) {
        for (ScheduledFuture<?> task : scheduledTasks.values()) {
            task.cancel(false);
        }
        scheduledTasks.clear();
        if (scheduler != null) {
            budget.stop(scheduler, Duration.ofSeconds(5));
        }
        campaigns.clear();
        deliveryHourCounter.clear();
        logger.info("CampaignManager shutdown");
    }

    // ====================== CRUD + state machine ======================

    /**
     * Creates a new campaign in PREVIEW status.
     *
     * @param operatorId       creator UUID
     * @param channelId        target channel ID (must exist)
     * @param content          campaign content
     * @param platforms        target platform set (defensive-copied, unmodifiable)
     * @param deliveryPolicy   delivery policy
     * @param startAt          epoch ms activation (0 = immediate on activate)
     * @param endAt            epoch ms expiry (0 = no natural expiry)
     * @param rateLimitPerHour per-channel/per-hour delivery cap (0/negative → default)
     * @param operatorClientId creator client ID (for scope validation)
     * @param trustedOperator  when true, skip the internal permission check (REST layer vouches)
     * @return result with the created campaign on success
     */
    public CampaignResult createCampaign(UUID operatorId, String channelId, String content,
                                          Set<String> platforms, DeliveryPolicy deliveryPolicy,
                                          long startAt, long endAt, int rateLimitPerHour,
                                          String operatorClientId, boolean trustedOperator) {
        if (operatorId == null && !trustedOperator) {
            return CampaignResult.badRequest("Operator ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return CampaignResult.badRequest("Channel ID is required");
        }
        if (content == null || content.isBlank()) {
            return CampaignResult.badRequest("Content is required");
        }
        if (platforms == null || platforms.isEmpty()) {
            return CampaignResult.badRequest("At least one platform is required");
        }
        // Filter out blank platform entries before the empty-set check.
        Set<String> sanitizedPlatforms = new HashSet<>();
        for (String p : platforms) {
            if (p != null && !p.isBlank()) {
                sanitizedPlatforms.add(p);
            }
        }
        if (sanitizedPlatforms.isEmpty()) {
            return CampaignResult.badRequest("At least one platform is required");
        }
        platforms = sanitizedPlatforms;
        if (deliveryPolicy == null) {
            deliveryPolicy = DeliveryPolicy.INSTANT;
        }
        if (startAt < 0 || endAt < 0 || (endAt > 0 && endAt < startAt)) {
            return CampaignResult.badRequest("Invalid time window: startAt/endAt");
        }
        if (rateLimitPerHour <= 0) {
            rateLimitPerHour = DEFAULT_RATE_LIMIT_PER_CHANNEL_PER_HOUR;
        }
        if (!channelManager.channelExists(channelId)) {
            return CampaignResult.notFound("Channel not found: " + channelId);
        }

        // Permission: campaign.edit → CHANNEL_ADMIN (approximation; see class javadoc).
        if (!trustedOperator) {
            CampaignResult denied = validatePermission(operatorId, channelId,
                    PermissionLevel.CHANNEL_ADMIN, "campaign.edit");
            if (denied != null) {
                return denied;
            }
        }

        String id = generateCampaignId();
        Campaign campaign = new Campaign(
                id, channelId, new HashSet<>(platforms), content,
                CampaignStatus.PREVIEW, 0L, deliveryPolicy,
                startAt, endAt, rateLimitPerHour,
                operatorId, operatorClientId, System.currentTimeMillis());
        campaigns.put(id, campaign);

        persistCampaign(campaign);
        recordAudit(operatorId == null ? null : operatorId.toString(),
                "campaign.create", "campaign:" + id, null, null, null, "success");
        logger.info("Campaign created: {} by {} for channel {}", id, operatorId, channelId);
        return CampaignResult.success("Campaign created", campaign);
    }

    /**
     * Schedules a PREVIEW campaign: PREVIEW → SCHEDULED, bumps scheduleRevision,
     * and if {@code startAt > 0} arms a one-shot activation task. When
     * {@code startAt == 0} the campaign transitions straight to ACTIVE.
     */
    public CampaignResult scheduleCampaign(String campaignId, UUID operatorId, boolean trustedOperator) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return CampaignResult.notFound("Campaign not found: " + campaignId);
        }
        synchronized (campaign) {
            if (!trustedOperator) {
                CampaignResult denied = validatePermission(operatorId, campaign.getChannelId(),
                        PermissionLevel.CLIENT_ADMIN, "campaign.publish");
                if (denied != null) {
                    return denied;
                }
            }
            if (expireIfPast(campaign)) return CampaignResult.badRequest("Campaign has expired");
            // Immediate activation path: startAt <= 0 → go straight to ACTIVE
            // (PREVIEW → ACTIVE). This MUST be checked before the SCHEDULED
            // transition check, because PREVIEW → SCHEDULED is also valid and
            // would otherwise swallow the immediate-activation path.
            if (campaign.getStartAt() <= 0) {
                if (!CampaignStatus.isValidTransition(campaign.getStatus(), CampaignStatus.ACTIVE)) {
                    return CampaignResult.badRequest(
                            "Cannot schedule campaign in status " + campaign.getStatus());
                }
                return doActivate(campaign, operatorId);
            }
            if (!CampaignStatus.isValidTransition(campaign.getStatus(), CampaignStatus.SCHEDULED)) {
                return CampaignResult.badRequest(
                        "Cannot schedule campaign in status " + campaign.getStatus());
            }
            campaign.setStatus(CampaignStatus.SCHEDULED);
            campaign.bumpScheduleRevision();
            armActivation(campaign, operatorId);
            persistCampaign(campaign);
            recordAudit(operatorId == null ? null : operatorId.toString(),
                    "campaign.schedule", "campaign:" + campaignId, null, null, null, "success");
            logger.info("Campaign scheduled: {} by {} (rev={})",
                    campaignId, operatorId, campaign.getScheduleRevision());
            return CampaignResult.success("Campaign scheduled", campaign);
        }
    }

    /**
     * Activates a PREVIEW or SCHEDULED campaign after delivery succeeds.
     * Failure preserves the source state for retry and does not consume quota.
     */
    public CampaignResult activateCampaign(String campaignId, UUID operatorId, boolean trustedOperator) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return CampaignResult.notFound("Campaign not found: " + campaignId);
        }
        synchronized (campaign) {
            if (!trustedOperator) {
                CampaignResult denied = validatePermission(operatorId, campaign.getChannelId(),
                        PermissionLevel.CLIENT_ADMIN, "campaign.publish");
                if (denied != null) {
                    return denied;
                }
            }
            if (!CampaignStatus.isValidTransition(campaign.getStatus(), CampaignStatus.ACTIVE)) {
                return CampaignResult.badRequest(
                        "Cannot activate campaign in status " + campaign.getStatus());
            }
            return doActivate(campaign, operatorId);
        }
    }

    private CampaignResult doActivate(Campaign campaign, UUID operatorId) {
        if (expireIfPast(campaign)) return CampaignResult.badRequest("Campaign has expired");
        CampaignResult deliveryResult = deliverOnce(campaign);
        if (!deliveryResult.isSuccess()) {
            cancelTask(campaign.getId());
            armExpiry(campaign);
            recordAudit(operatorId == null ? null : operatorId.toString(),
                    "campaign.activate", "campaign:" + campaign.getId(), null, null, deliveryResult.getMessage(), "failure");
            return deliveryResult;
        }
        campaign.setStatus(CampaignStatus.ACTIVE);
        campaign.bumpScheduleRevision();
        cancelTask(campaign.getId());
        armExpiry(campaign);
        persistCampaign(campaign);
        recordAudit(operatorId == null ? null : operatorId.toString(),
                "campaign.activate", "campaign:" + campaign.getId(), null, null, null, "success");
        return CampaignResult.success("Campaign activated", campaign);
    }

    private void cancelTask(String campaignId) {
        ScheduledFuture<?> task = scheduledTasks.remove(campaignId);
        if (task != null) task.cancel(false);
    }

    private boolean expireIfPast(Campaign campaign) {
        if (campaign.getEndAt() <= 0 || campaign.getEndAt() > System.currentTimeMillis()) return false;
        if (campaign.getStatus() == CampaignStatus.REVOKED || campaign.getStatus() == CampaignStatus.EXPIRED) return true;
        campaign.setStatus(CampaignStatus.EXPIRED);
        campaign.bumpScheduleRevision();
        cancelTask(campaign.getId());
        persistCampaign(campaign);
        recordAudit(null, "campaign.expire", "campaign:" + campaign.getId(), null, null, null, "success");
        return true;
    }

    private void armExpiry(Campaign campaign) {
        if (campaign.getEndAt() <= 0 || scheduler == null || scheduler.isShutdown()) return;
        if (expireIfPast(campaign)) return;
        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> {
                synchronized (campaign) {
                    expireIfPast(campaign);
                }
            }, Math.max(0, campaign.getEndAt() - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = scheduledTasks.put(campaign.getId(), task);
            if (previous != null) previous.cancel(false);
        } catch (RejectedExecutionException error) {
            logger.debug("Scheduler stopped before expiry was armed for {}", campaign.getId());
        }
    }

    /**
     * Revokes a campaign from any non-terminal state. Cancels the armed
     * scheduled task (computeIfPresent, anti-orphan), stamps revokedAt/revokedBy.
     */
    public CampaignResult revokeCampaign(String campaignId, UUID operatorId, boolean trustedOperator) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return CampaignResult.notFound("Campaign not found: " + campaignId);
        }
        synchronized (campaign) {
            if (!trustedOperator) {
                CampaignResult denied = validatePermission(operatorId, campaign.getChannelId(),
                        PermissionLevel.SUPER_ADMIN, "campaign.revoke");
                if (denied != null) {
                    return denied;
                }
            }
            if (!CampaignStatus.isValidTransition(campaign.getStatus(), CampaignStatus.REVOKED)) {
                return CampaignResult.badRequest(
                        "Cannot revoke campaign in terminal status " + campaign.getStatus());
            }
            campaign.markRevoked(System.currentTimeMillis(), operatorId);
            // Cancel any armed one-shot task (anti-orphan: if a concurrent path
            // already removed the slot, there's nothing to cancel).
            ScheduledFuture<?> task = scheduledTasks.remove(campaignId);
            if (task != null) {
                task.cancel(false);
            }
            persistCampaign(campaign);
            recordAudit(operatorId == null ? null : operatorId.toString(),
                    "campaign.revoke", "campaign:" + campaignId, null, null, null, "success");
            logger.info("Campaign revoked: {} by {}", campaignId, operatorId);
            return CampaignResult.success("Campaign revoked", campaign);
        }
    }

    // ====================== read-only views ======================

    public Campaign getCampaign(String campaignId) {
        return campaigns.get(campaignId);
    }

    public List<Campaign> listCampaigns(String channelId) {
        List<Campaign> out = new ArrayList<>();
        for (Campaign c : campaigns.values()) {
            if (channelId == null || Objects.equals(c.getChannelId(), channelId)) {
                out.add(c);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Collection<Campaign> getAllCampaigns() {
        return Collections.unmodifiableCollection(campaigns.values());
    }

    // ====================== internal helpers ======================

    /** Restores unexpired state and its next task without repeating an ACTIVE campaign's delivery. */
    public int loadPersistedCampaigns() {
        if (databaseProvider == null) {
            return 0;
        }
        int restored = 0;
        try {
            for (Campaign campaign : databaseProvider.getAllPersistedCampaigns()) {
                CampaignStatus status = campaign.getStatus();
                // Skip terminal campaigns — their audit trail stays in the DB
                // but they do not re-enter the active in-memory map.
                if (status == CampaignStatus.EXPIRED || status == CampaignStatus.REVOKED) {
                    continue;
                }
                if (campaigns.putIfAbsent(campaign.getId(), campaign) != null) continue;
                synchronized (campaign) {
                    if (!expireIfPast(campaign)) {
                        if (status == CampaignStatus.SCHEDULED) armActivation(campaign, null);
                        else if (status == CampaignStatus.ACTIVE) armExpiry(campaign);
                    }
                }
                restored++;
            }
            if (restored > 0) {
                logger.info("Loaded {} persisted campaign(s) from database", restored);
            }
        } catch (DatabaseException e) {
            logger.warn("Failed to load persisted campaigns (degrading to in-memory): {}",
                    e.getMessage());
        }
        return restored;
    }

    /**
     * Best-effort persist of a campaign snapshot to the database. Fail-open:
     * when {@code databaseProvider} is null (slice A mode) or the save throws,
     * the in-memory authoritative state is unaffected — the DB write failure
     * is only logged. Mirrors
     * {@link AnnouncementManager#persistAnnouncement(Announcement)}.
     */
    private void persistCampaign(Campaign campaign) {
        if (databaseProvider == null) {
            return;
        }
        try {
            databaseProvider.saveCampaign(campaign);
        } catch (DatabaseException e) {
            logger.warn("Failed to persist campaign {}: {}", campaign.getId(), e.getMessage());
        }
    }

    /**
     * Delivers the campaign content once, subject to the per-channel/per-hour
     * rate limit. Returns a rate-limited result (without throwing) when the
     * cap is exceeded.
     */
    private CampaignResult deliverOnce(Campaign campaign) {
        if (announcementSender == null) {
            return CampaignResult.internalError("Campaign delivery is unavailable");
        }
        long now = System.currentTimeMillis();
        long hourBucket = now / 3_600_000L;
        String key = campaign.getChannelId();
        long[] slot = deliveryHourCounter.computeIfAbsent(key, k -> new long[]{hourBucket, 0L});
        synchronized (slot) {
            if (slot[0] != hourBucket) {
                slot[0] = hourBucket;
                slot[1] = 0L;
            }
            if (slot[1] >= campaign.getRateLimitPerChannelPerHour()) {
                return CampaignResult.rateLimited(
                        "Per-channel/per-hour rate limit exceeded for channel " + key);
            }
            try {
                announcementSender.accept(campaign.getChannelId(), campaign.getContent());
                slot[1]++;
            } catch (RuntimeException e) {
                logger.warn("Campaign {} delivery failed: {}", campaign.getId(), e.toString());
                return CampaignResult.internalError("Campaign delivery failed");
            }
        }
        return CampaignResult.success("Delivered");
    }

    private void armActivation(Campaign campaign, UUID operatorId) {
        if (scheduler == null || scheduler.isShutdown() || expireIfPast(campaign)) return;
        long delay = Math.max(0, campaign.getStartAt() - System.currentTimeMillis());
        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> {
                synchronized (campaign) {
                    if (campaign.getStatus() == CampaignStatus.SCHEDULED) doActivate(campaign, operatorId);
                }
            }, delay, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = scheduledTasks.put(campaign.getId(), task);
            if (previous != null) previous.cancel(false);
        } catch (RejectedExecutionException error) {
            logger.debug("Scheduler stopped before activation was armed for {}", campaign.getId());
        }
    }

    /**
     * Permission check mapped onto the 4-level hierarchy. Returns a
     * {@link CampaignResult#forbidden(String)} when denied, or {@code null}
     * when allowed (so the caller can early-return only on denial).
     */
    private CampaignResult validatePermission(UUID operatorId, String channelId,
                                               PermissionLevel required, String capability) {
        PermissionResult pr = permissionManager.checkPermission(operatorId, channelId, required);
        if (!pr.isAllowed()) {
            return CampaignResult.forbidden(
                    "Insufficient permissions for " + capability
                            + " (required: " + required.name() + "): " + pr.getMessage());
        }
        return null;
    }

    private void recordAudit(String actor, String action, String resource,
                             String beforeHash, String afterHash,
                             String reason, String result) {
        if (auditStore == null) {
            return;
        }
        try {
            String eventId = UUID.randomUUID().toString();
            AuditEvent event = new AuditEvent(
                    eventId, null, actor, null, null, action, resource,
                    beforeHash, afterHash, reason, result, System.currentTimeMillis());
            auditStore.record(event);
        } catch (Exception e) {
            logger.warn("Failed to record campaign audit event action={}: {}", action, e.getMessage());
        }
    }

    /**
     * Generates a unique campaign ID: "CMP-" + 8 lowercase hex chars.
     */
    private String generateCampaignId() {
        String id;
        int attempts = 0;
        do {
            StringBuilder sb = new StringBuilder(Campaign.ID_PREFIX);
            for (int i = 0; i < ID_HEX_LENGTH; i++) {
                sb.append(HEX.charAt(random.nextInt(HEX.length())));
            }
            id = sb.toString();
            attempts++;
            if (attempts > 1000) {
                throw new IllegalStateException("Unable to generate unique campaign ID");
            }
        } while (campaigns.containsKey(id));
        return id;
    }

    // ====================== test accessors ======================

    public int getCampaignCount() {
        return campaigns.size();
    }

    public int getScheduledTaskCount() {
        return scheduledTasks.size();
    }

    /** Clears all campaigns and cancels armed tasks (for testing). */
    public void clear() {
        for (ScheduledFuture<?> task : scheduledTasks.values()) {
            task.cancel(false);
        }
        scheduledTasks.clear();
        campaigns.clear();
        deliveryHourCounter.clear();
    }

    /**
     * Exposes the current per-channel/per-hour delivery count for a channel
     * (for testing the rate-limit path). Returns 0 when the channel has no
     * counter slot yet.
     */
    public int currentHourDeliveryCount(String channelId) {
        long[] slot = deliveryHourCounter.get(channelId);
        if (slot == null) {
            return 0;
        }
        synchronized (slot) {
            long nowBucket = System.currentTimeMillis() / 3_600_000L;
            return slot[0] == nowBucket ? (int) slot[1] : 0;
        }
    }
}
