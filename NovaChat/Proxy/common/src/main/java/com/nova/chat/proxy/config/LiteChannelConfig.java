package com.nova.chat.proxy.config;

import java.util.Objects;

/**
 * One channel declared under {@code lite.channels}.
 *
 * <p>The field names mirror the backend's {@code global_channels} section of
 * {@code novalink.yml} ({@code display_name}, {@code permission},
 * {@code max_capacity}, {@code slow_mode}), with kebab-case spelling in the
 * proxy configuration files.
 */
public final class LiteChannelConfig {

    private final String id;
    private final String displayName;
    private final String permission;
    private final int maxCapacity;
    private final int slowMode;

    /**
     * @param id channel id (non-blank, unique within {@code lite.channels})
     * @param displayName display name shown to players; never null
     * @param permission permission node required to join, or null for none
     * @param maxCapacity maximum member count; {@code 0} means unlimited
     * @param slowMode minimum seconds between two messages from one player;
     *                 {@code 0} disables slow mode
     */
    public LiteChannelConfig(String id, String displayName, String permission,
                             int maxCapacity, int slowMode) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.permission = permission;
        this.maxCapacity = maxCapacity;
        this.slowMode = slowMode;
    }

    /**
     * @return the channel id
     */
    public String getId() {
        return id;
    }

    /**
     * @return the display name (defaults to the id when not configured)
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return the required permission node, or null when the channel is open
     */
    public String getPermission() {
        return permission;
    }

    /**
     * @return the member limit; {@code 0} means unlimited
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * @return the slow-mode interval in seconds; {@code 0} disables it
     */
    public int getSlowMode() {
        return slowMode;
    }
}
