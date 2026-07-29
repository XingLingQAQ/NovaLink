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
    UNMUTE(8);

    private final int id;

    ChannelAction(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

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
