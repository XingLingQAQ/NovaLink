package com.nova.link.database;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents ban information for a player in a channel or globally.
 *
 * <p>Mirrors {@link MuteInfo}: channelId {@code null} means a global ban,
 * expireTime {@code 0} means permanent. Ban semantics differ from mute in
 * that a banned player is also removed from the channel membership and
 * cannot rejoin while the ban is active.
 *
 * Requirements: ban feature — player ban management
 */
public class BanInfo {

    /** Channel ID (null means global ban) */
    private final String channelId;

    /** Expiration timestamp (0 means permanent) */
    private final long expireTime;

    /** Reason for the ban */
    private final String reason;

    /** UUID of the operator who issued the ban */
    private final UUID operatorId;

    /** Timestamp when the ban was created */
    private final long createdAt;

    public BanInfo(String channelId, long expireTime, String reason, UUID operatorId) {
        this.channelId = channelId;
        this.expireTime = expireTime;
        this.reason = reason;
        this.operatorId = operatorId;
        this.createdAt = System.currentTimeMillis();
    }

    public BanInfo(String channelId, long expireTime, String reason, UUID operatorId, long createdAt) {
        this.channelId = channelId;
        this.expireTime = expireTime;
        this.reason = reason;
        this.operatorId = operatorId;
        this.createdAt = createdAt;
    }

    public String getChannelId() {
        return channelId;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public String getReason() {
        return reason;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Checks if this ban has expired.
     *
     * @return true if the ban has expired
     */
    public boolean isExpired() {
        return expireTime > 0 && System.currentTimeMillis() > expireTime;
    }

    /**
     * Checks if this is a permanent ban.
     *
     * @return true if the ban is permanent (no expiration)
     */
    public boolean isPermanent() {
        return expireTime <= 0;
    }

    /**
     * Gets the remaining time in milliseconds.
     *
     * @return remaining time, or -1 if permanent, or 0 if expired
     */
    public long getRemainingTime() {
        if (isPermanent()) {
            return -1;
        }
        long remaining = expireTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BanInfo banInfo = (BanInfo) o;
        return expireTime == banInfo.expireTime &&
                createdAt == banInfo.createdAt &&
                Objects.equals(channelId, banInfo.channelId) &&
                Objects.equals(reason, banInfo.reason) &&
                Objects.equals(operatorId, banInfo.operatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, expireTime, reason, operatorId, createdAt);
    }

    @Override
    public String toString() {
        return "BanInfo{" +
                "channelId='" + channelId + '\'' +
                ", expireTime=" + expireTime +
                ", reason='" + reason + '\'' +
                ", operatorId=" + operatorId +
                ", createdAt=" + createdAt +
                '}';
    }
}
