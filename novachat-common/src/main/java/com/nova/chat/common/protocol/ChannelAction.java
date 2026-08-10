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
     * method retries with {@code id - 1}, accommodating legacy implementations
     * that used 1-based IDs. Callers should be aware that a legacy ID may
     * therefore map to a different action than intended.
     *
     * @param id the wire ID
     * @return the matching channel action
     * @throws IllegalArgumentException if no action matches either {@code id} or {@code id - 1}
     */
    public static ChannelAction fromId(int id) {
        for (ChannelAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        // Backward compatibility: some legacy implementations used 1-based IDs.
        for (ChannelAction action : values()) {
            if (action.id == id - 1) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown channel action ID: " + id);
    }
}
