package com.nova.link.announcement;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable campaign record (§11.6 提案 06 — slice A: in-memory only).
 *
 * <p>A campaign is an orchestrated, scheduled, revocable announcement with a
 * target channel + platform set, a delivery policy, a time window and a
 * per-channel/per-hour rate limit. Unlike a CRON announcement, a campaign
 * never self-replicates: it fires at most once per activation (one-shot
 * {@code schedule}, no {@code scheduleAtFixedRate}) and transitions through a
 * strict PREVIEW → SCHEDULED → ACTIVE → {EXPIRED, REVOKED} state machine.
 *
 * <p>All mutating operations (status transitions, {@link #bumpScheduleRevision})
 * are package-private so that {@link CampaignManager} remains the single
 * state-mutation authority; callers receive an immutable view.
 *
 * <p>Requirements: §11.6 提案 06 (campaign orchestration, no infinite cron).
 */
public final class Campaign {

    /** Prefix for campaign IDs. */
    public static final String ID_PREFIX = "CMP-";

    private final String id;
    private final String channelId;
    private final Set<String> platforms;
    private final String content;
    private volatile CampaignStatus status;
    private volatile long scheduleRevision;
    private final DeliveryPolicy deliveryPolicy;
    private final long startAt;
    private final long endAt;
    private final int rateLimitPerChannelPerHour;
    private final UUID creatorId;
    private final String creatorClientId;
    private final long createdAt;
    private volatile long revokedAt;
    private volatile UUID revokedBy;

    /**
     * Creates a new campaign. Used by {@link CampaignManager#createCampaign}.
     *
     * <p>Delegates to {@link #Campaign(String, String, Set, String, CampaignStatus,
     * long, DeliveryPolicy, long, long, int, UUID, String, long, long, UUID)}
     * with {@code revokedAt = 0} and {@code revokedBy = null} — a freshly
     * created campaign has not been revoked.
     *
     * @param id                        unique campaign ID (CMP- + 8 hex)
     * @param channelId                 target channel ID
     * @param platforms                 immutable target platform set (never null)
     * @param content                   campaign content
     * @param status                    initial status (typically PREVIEW)
     * @param scheduleRevision          initial schedule revision (0 for new)
     * @param deliveryPolicy            delivery policy
     * @param startAt                   epoch ms activation time (0 = immediate)
     * @param endAt                     epoch ms expiry (0 = no expiry)
     * @param rateLimitPerChannelPerHour per-channel/per-hour delivery cap
     * @param creatorId                 creator UUID (may be null for trusted ops)
     * @param creatorClientId           creator client ID (may be null)
     * @param createdAt                 creation epoch ms
     */
    public Campaign(String id, String channelId, Set<String> platforms, String content,
                    CampaignStatus status, long scheduleRevision, DeliveryPolicy deliveryPolicy,
                    long startAt, long endAt, int rateLimitPerChannelPerHour,
                    UUID creatorId, String creatorClientId, long createdAt) {
        this(id, channelId, platforms, content, status, scheduleRevision, deliveryPolicy,
                startAt, endAt, rateLimitPerChannelPerHour, creatorId, creatorClientId,
                createdAt, 0L, null);
    }

    /**
     * Reconstitutes a campaign from persisted state. Used by database
     * providers (JDBC {@code mapRow}, memory reload) to reconstruct a
     * Campaign that preserves the {@code revokedAt}/{@code revokedBy}
     * stamps of a previously-revoked campaign. The 13-argument constructor
     * delegates here with {@code revokedAt = 0} and {@code revokedBy = null}.
     *
     * <p>This constructor does NOT validate the {@code status}/{@code revokedAt}
     * consistency (a REVOKED campaign should have a non-zero {@code revokedAt});
     * it trusts the persisted state. State-machine validity is enforced by
     * {@link CampaignManager} on new transitions, not on rehydration.
     *
     * @param id                        unique campaign ID
     * @param channelId                 target channel ID
     * @param platforms                 immutable target platform set (never null)
     * @param content                   campaign content
     * @param status                    persisted status
     * @param scheduleRevision          persisted schedule revision
     * @param deliveryPolicy            delivery policy
     * @param startAt                   epoch ms activation time
     * @param endAt                     epoch ms expiry
     * @param rateLimitPerChannelPerHour per-channel/per-hour delivery cap
     * @param creatorId                 creator UUID (may be null)
     * @param creatorClientId           creator client ID (may be null)
     * @param createdAt                 creation epoch ms
     * @param revokedAt                 revoke timestamp (0 if not revoked)
     * @param revokedBy                 revoker UUID (null if not revoked)
     */
    public Campaign(String id, String channelId, Set<String> platforms, String content,
                    CampaignStatus status, long scheduleRevision, DeliveryPolicy deliveryPolicy,
                    long startAt, long endAt, int rateLimitPerChannelPerHour,
                    UUID creatorId, String creatorClientId, long createdAt,
                    long revokedAt, UUID revokedBy) {
        this.id = Objects.requireNonNull(id, "Campaign ID cannot be null");
        this.channelId = Objects.requireNonNull(channelId, "Channel ID cannot be null");
        this.platforms = Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(
                        Objects.requireNonNull(platforms, "Platforms cannot be null")));
        this.content = Objects.requireNonNull(content, "Content cannot be null");
        this.status = Objects.requireNonNull(status, "Status cannot be null");
        this.scheduleRevision = scheduleRevision;
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "Delivery policy cannot be null");
        this.startAt = startAt;
        this.endAt = endAt;
        this.rateLimitPerChannelPerHour = rateLimitPerChannelPerHour;
        this.creatorId = creatorId;
        this.creatorClientId = creatorClientId;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
    }

    public String getId() {
        return id;
    }

    public String getChannelId() {
        return channelId;
    }

    /** Immutable target platform set. */
    public Set<String> getPlatforms() {
        return platforms;
    }

    public String getContent() {
        return content;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public long getScheduleRevision() {
        return scheduleRevision;
    }

    public DeliveryPolicy getDeliveryPolicy() {
        return deliveryPolicy;
    }

    public long getStartAt() {
        return startAt;
    }

    public long getEndAt() {
        return endAt;
    }

    public int getRateLimitPerChannelPerHour() {
        return rateLimitPerChannelPerHour;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getCreatorClientId() {
        return creatorClientId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    // ===== state mutations (package-private — CampaignManager is the authority) =====

    void setStatus(CampaignStatus next) {
        this.status = Objects.requireNonNull(next);
    }

    /**
     * Atomically increments the schedule revision and returns the new value.
     * Called by {@link CampaignManager} on each schedule/activate transition
     * so observers can detect staleness.
     *
     * @return the new (post-increment) schedule revision
     */
    synchronized long bumpScheduleRevision() {
        this.scheduleRevision++;
        return this.scheduleRevision;
    }

    void markRevoked(long revokedAt, UUID revokedBy) {
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
        this.status = CampaignStatus.REVOKED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Campaign)) return false;
        return Objects.equals(id, ((Campaign) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Campaign{"
                + "id='" + id + '\''
                + ", channelId='" + channelId + '\''
                + ", status=" + status
                + ", scheduleRevision=" + scheduleRevision
                + ", deliveryPolicy=" + deliveryPolicy
                + '}';
    }
}
