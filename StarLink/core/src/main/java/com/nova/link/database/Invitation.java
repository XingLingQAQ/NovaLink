package com.nova.link.database;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a channel invitation.
 * 
 * Requirements: 8.1, 8.2
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
    
    /** Whether the invitation has been used */
    private boolean used;
    
    /** UUID of the player who used the invitation */
    private UUID usedBy;
    
    /** Timestamp when the invitation was used */
    private long usedAt;

    public Invitation(String code, String channelId, UUID inviterId, long expireTime) {
        this.code = Objects.requireNonNull(code, "Code cannot be null");
        this.channelId = Objects.requireNonNull(channelId, "Channel ID cannot be null");
        this.inviterId = Objects.requireNonNull(inviterId, "Inviter ID cannot be null");
        this.expireTime = expireTime;
        this.createdAt = System.currentTimeMillis();
        this.used = false;
    }

    public Invitation(String code, String channelId, UUID inviterId, long expireTime, 
                      long createdAt, boolean used, UUID usedBy, long usedAt) {
        this.code = code;
        this.channelId = channelId;
        this.inviterId = inviterId;
        this.expireTime = expireTime;
        this.createdAt = createdAt;
        this.used = used;
        this.usedBy = usedBy;
        this.usedAt = usedAt;
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
     * Marks this invitation as used.
     *
     * @param usedBy the UUID of the player who used it
     */
    public void markUsed(UUID usedBy) {
        this.used = true;
        this.usedBy = usedBy;
        this.usedAt = System.currentTimeMillis();
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
     * Checks if this invitation is valid (not used and not expired).
     *
     * @return true if valid
     */
    public boolean isValid() {
        return !used && !isExpired();
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
                '}';
    }
}
