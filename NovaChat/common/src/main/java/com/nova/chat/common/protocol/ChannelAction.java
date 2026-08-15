package com.nova.chat.common.protocol;

/**
 * Enum representing channel operations.
 */
public enum ChannelAction {
    /** Join an existing channel */
    JOIN(0),
    
    /** Leave a channel */
    LEAVE(1),
    
    /** Create a new channel */
    CREATE(2),
    
    /** Delete a channel */
    DELETE(3),
    
    /** Invite a player to a channel */
    INVITE(4),
    
    /** Accept a channel invitation */
    ACCEPT(5),
    
    /** Kick a player from a channel */
    KICK(6),
    
    /** Mute a player in a channel */
    MUTE(7),
    
    /** Unmute a player in a channel */
    UNMUTE(8),

    /** Ban a player from a channel (null channelId = global ban) */
    BAN(9),

    /** Unban a player from a channel */
    UNBAN(10),

    /** List the online members of a channel (read-only query; no state change) */
    WHO(11);

    private final int id;

    ChannelAction(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Resolves a channel action by its numeric wire ID.
     *
     * <p>As a backward-compatibility fallback, if no exact match is found this
     * method retries with {@code id - 1}, but only when {@code id} falls within
     * the known legacy 1-based range {@code [1, 12]}. This prevents an unknown
     * ID (e.g. 13+) from being silently mapped to an existing action.
     *
     * @param id the wire ID
     * @return the matching channel action
     * @throws IllegalArgumentException if {@code id} does not match a canonical
     *         action and is outside the legacy 1-based range
     */
    public static ChannelAction fromId(int id) {
        for (ChannelAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        // Backward compatibility: some legacy implementations used 1-based IDs.
        // Restrict to the known legacy range [1, values().length] so that IDs
        // outside this range don't silently map to an existing action.
        int maxLegacyId = values().length; // 0-based max + 1 == 1-based max
        if (id >= 1 && id <= maxLegacyId) {
            for (ChannelAction action : values()) {
                if (action.id == id - 1) {
                    return action;
                }
            }
        }
        throw new IllegalArgumentException("Unknown channel action ID: " + id);
    }
}
