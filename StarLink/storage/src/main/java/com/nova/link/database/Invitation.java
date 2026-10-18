package com.nova.link.database;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a channel invitation.
 *
 * <p>An invitation is single-use by default (matching the historical
 * {@code used} boolean semantics), but supports a configurable maximum number
 * of uses via {@code maxUses}. Once {@code usedCount} reaches {@code maxUses}
 * the invitation is marked exhausted ({@code used = true}). An invitation may
 * also be explicitly revoked via {@link #markRevoked(UUID)}; a revoked
 * invitation is permanently invalid regardless of remaining uses.
 *
 * Requirements: 8.1, 8.2, 8.4, 8.5
 */
public class Invitation {

    /** 6-character alphanumeric invitation code */
    private final String code;

    /** Channel ID this invitation is for */
    private final String channelId;

    /** UUID of the player who created the invitation */
    private final UUID inviterId;

    /** Expiration timestamp */
    private final long expireTime;

    /** Creation timestamp */
    private final long createdAt;

    /** Whether the invitation has been used (exhausted when usedCount >= maxUses) */
    private boolean used;

    /** UUID of the player who last used the invitation */
    private UUID usedBy;

    /** Timestamp when the invitation was last used */
    private long usedAt;

    /** Maximum number of times this invitation can be used (default 1) */
    private int maxUses = 1;

    /** Number of times this invitation has been used so far */
    private int usedCount = 0;

    /** Timestamp when the invitation was revoked, or null if not revoked */
    private Long revokedAt = null;

    public Invitation(String code, String channelId, UUID inviterId, long expireTime) {
        this.code = Objects.requireNonNull(code, "Code cannot be null");
        this.channelId = Objects.requireNonNull(channelId, "Channel ID cannot be null");
        this.inviterId = Objects.requireNonNull(inviterId, "Inviter ID cannot be null");
        this.expireTime = expireTime;
        this.createdAt = System.currentTimeMillis();
        this.used = false;
    }

    public Invitation(String code, String channelId, UUID inviterId, long expireTime,
                      long createdAt, boolean used, UUID usedBy, long usedAt,
                      int maxUses, int usedCount, Long revokedAt) {
        this.code = code;
        this.channelId = channelId;
        this.inviterId = inviterId;
        this.expireTime = expireTime;
        this.createdAt = createdAt;
        this.used = used;
        this.usedBy = usedBy;
        this.usedAt = usedAt;
        this.maxUses = maxUses;
        this.usedCount = usedCount;
        this.revokedAt = revokedAt;
    }

    public String getCode() {
        return code;
    }

    public String getChannelId() {
        return channelId;
    }

    public UUID getInviterId() {
        return inviterId;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isUsed() {
        return used;
    }

    public UUID getUsedBy() {
        return usedBy;
    }

    public long getUsedAt() {
        return usedAt;
    }

    /**
     * @return the maximum number of times this invitation can be used
     */
    public int getMaxUses() {
        return maxUses;
    }

    /**
     * @return the number of times this invitation has been used
     */
    public int getUsedCount() {
        return usedCount;
    }

    /**
     * @return the timestamp the invitation was revoked, or null if not revoked
     */
    public Long getRevokedAt() {
        return revokedAt;
    }

    /**
     * Marks this invitation as used. Single-use convenience that preserves the
     * historical behaviour: it flips {@code used = true} and records the
     * accepter. For multi-use invitations prefer {@link #incrementUse(UUID)},
     * which advances {@code usedCount} and only flips {@code used} once the
     * maximum is reached.
     *
     * @param usedBy the UUID of the player who used it
     */
    public void markUsed(UUID usedBy) {
        this.used = true;
        this.usedBy = usedBy;
        this.usedAt = System.currentTimeMillis();
        // Keep the count consistent: a markUsed() call consumes the invitation,
        // so usedCount should reach at least maxUses (single-use default keeps
        // it at 1, which equals the default maxUses of 1).
        this.usedCount = Math.max(this.usedCount, Math.max(1, this.maxUses));
    }

    /**
     * Records one use of this invitation. Increments {@code usedCount}, tracks
     * the accepter/timestamp, and — once {@code usedCount >= maxUses} — marks
     * the invitation as exhausted ({@code used = true}).
     *
     * @param usedBy the UUID of the player who accepted the invitation
     */
    public void incrementUse(UUID usedBy) {
        this.usedCount++;
        this.usedBy = usedBy;
        this.usedAt = System.currentTimeMillis();
        if (this.usedCount >= this.maxUses) {
            this.used = true;
        }
    }

    /**
     * Marks this invitation as revoked. A revoked invitation is permanently
     * invalid regardless of remaining uses or expiry.
     *
     * @param revoker the UUID of the player who revoked it (ignored; kept for
     *                signature symmetry with future {@code revokedBy} tracking)
     */
    public void markRevoked(UUID revoker) {
        this.revokedAt = System.currentTimeMillis();
    }

    /**
     * @return true if this invitation has been revoked
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Checks if this invitation has expired.
     *
     * @return true if expired
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expireTime;
    }

    /**
     * Checks if this invitation is valid (not used, not expired, and not revoked).
     *
     * @return true if valid
     */
    public boolean isValid() {
        return !used && !isExpired() && revokedAt == null;
    }

    /**
     * Gets the remaining time in milliseconds.
     *
     * @return remaining time, or 0 if expired
     */
    public long getRemainingTime() {
        long remaining = expireTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Invitation that = (Invitation) o;
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Invitation{" +
                "code='" + code + '\'' +
                ", channelId='" + channelId + '\'' +
                ", inviterId=" + inviterId +
                ", expireTime=" + expireTime +
                ", used=" + used +
                ", maxUses=" + maxUses +
                ", usedCount=" + usedCount +
                ", revokedAt=" + revokedAt +
                '}';
    }
}
