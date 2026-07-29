package com.nova.link.database;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents mute information for a player in a channel.
 * 
 * Requirements: 13.1, 13.2
 */
public class MuteInfo {

    /** Channel ID (null means global mute) */
    private final String channelId;
    
    /** Expiration timestamp (0 means permanent) */
    private final long expireTime;
    
    /** Reason for the mute */
    private final String reason;
    
    /** UUID of the operator who issued the mute */
    private final UUID operatorId;
    
    /** Timestamp when the mute was created */
    private final long createdAt;

    public MuteInfo(String channelId, long expireTime, String reason, UUID operatorId) {
        this.channelId = channelId;
        this.expireTime = expireTime;
        this.reason = reason;
        this.operatorId = operatorId;
        this.createdAt = System.currentTimeMillis();
    }

    public MuteInfo(String channelId, long expireTime, String reason, UUID operatorId, long createdAt) {
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
     * Checks if this mute has expired.
     *
     * @return true if the mute has expired
     */
    public boolean isExpired() {
        return expireTime > 0 && System.currentTimeMillis() > expireTime;
    }

    /**
     * Checks if this is a permanent mute.
     *
     * @return true if the mute is permanent (no expiration)
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
        MuteInfo muteInfo = (MuteInfo) o;
        return expireTime == muteInfo.expireTime &&
                createdAt == muteInfo.createdAt &&
                Objects.equals(channelId, muteInfo.channelId) &&
                Objects.equals(reason, muteInfo.reason) &&
                Objects.equals(operatorId, muteInfo.operatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, expireTime, reason, operatorId, createdAt);
    }

    @Override
    public String toString() {
        return "MuteInfo{" +
                "channelId='" + channelId + '\'' +
                ", expireTime=" + expireTime +
                ", reason='" + reason + '\'' +
                ", operatorId=" + operatorId +
                ", createdAt=" + createdAt +
                '}';
    }
}
