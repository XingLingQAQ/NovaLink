package com.nova.link.auth;

/**
 * Defines the four-level permission hierarchy for NovaChat & NovaLink.
 * 
 * Requirements:
 * - 2.1: Four-level permission hierarchy: SuperAdmin > ClientAdmin > ChannelAdmin > Player
 * - 2.7: Lower permission users receive NC-403 error when attempting higher-level operations
 */
public enum PermissionLevel {
    /**
     * Regular player with basic permissions.
     * Can join/leave channels, create private channels.
     */
    PLAYER(0),

    /**
     * Channel administrator (owner of private channel or granted).
     * Can kick members, modify password, invite players within their channels.
     */
    CHANNEL_ADMIN(1),

    /**
     * Client administrator (has novachat.admin permission node).
     * Can manage all channels within their client, mute players up to 24 hours.
     */
    CLIENT_ADMIN(2),

    /**
     * Super administrator (authenticated via backend password).
     * Has full system access, can monitor any channel, no time limits on mutes.
     */
    SUPER_ADMIN(3);

    private final int level;

    PermissionLevel(int level) {
        this.level = level;
    }

    /**
     * Gets the numeric level value for comparison.
     *
     * @return the level value (higher = more permissions)
     */
    public int getLevel() {
        return level;
    }

    /**
     * Checks if this permission level is at least as high as the required level.
     *
     * @param required the required permission level
     * @return true if this level >= required level
     */
    public boolean hasAtLeast(PermissionLevel required) {
        return this.level >= required.level;
    }

    /**
     * Checks if this permission level is higher than the other level.
     *
     * @param other the other permission level
     * @return true if this level > other level
     */
    public boolean isHigherThan(PermissionLevel other) {
        return this.level > other.level;
    }
}
