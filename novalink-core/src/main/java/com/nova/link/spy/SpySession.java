package com.nova.link.spy;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a spy session for a super admin monitoring a channel.
 * 
 * Requirements: 17.1-17.5
 * - Super admin can monitor any channel remotely
 * - Can monitor multiple channels simultaneously
 */
public class SpySession {

    /** The super admin's UUID */
    private final UUID adminId;
    
    /** The channel being monitored */
    private final String channelId;
    
    /** Optional: specific server/client to monitor (null = all) */
    private final String targetClientId;
    
    /** When the spy session was started */
    private final long startedAt;
    
    /** Whether the admin can send messages to the monitored channel */
    private boolean canSend;

    public SpySession(UUID adminId, String channelId, String targetClientId) {
        this.adminId = Objects.requireNonNull(adminId, "Admin ID cannot be null");
        this.channelId = Objects.requireNonNull(channelId, "Channel ID cannot be null");
        this.targetClientId = targetClientId;
        this.startedAt = System.currentTimeMillis();
        this.canSend = true;
    }

    public UUID getAdminId() {
        return adminId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getTargetClientId() {
        return targetClientId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean canSend() {
        return canSend;
    }

    public void setCanSend(boolean canSend) {
        this.canSend = canSend;
    }

    /**
     * Gets the duration of this spy session in milliseconds.
     *
     * @return duration in milliseconds
     */
    public long getDuration() {
        return System.currentTimeMillis() - startedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SpySession that = (SpySession) o;
        return adminId.equals(that.adminId) && channelId.equals(that.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(adminId, channelId);
    }

    @Override
    public String toString() {
        return "SpySession{" +
                "adminId=" + adminId +
                ", channelId='" + channelId + '\'' +
                ", targetClientId='" + targetClientId + '\'' +
                ", startedAt=" + startedAt +
                ", canSend=" + canSend +
                '}';
    }
}
