package com.nova.chat.common.protocol;

/**
 * Enum representing admin action types.
 * 
 * Requirements:
 * - 2.2: Super admin authentication via `/nc auth <password>`
 */
public enum AdminAction {
    /** Super admin authentication */
    AUTH(0),
    
    /** Revoke super admin session */
    LOGOUT(1),
    
    /** Start spy mode on a channel */
    SPY_START(2),
    
    /** Stop spy mode */
    SPY_STOP(3),
    
    /** Reload configuration */
    RELOAD(4),
    
    /** Get system status */
    STATUS(5);

    private final int id;

    AdminAction(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Gets an AdminAction from its ID.
     *
     * @param id the action ID
     * @return the AdminAction, or null if not found
     */
    public static AdminAction fromId(int id) {
        for (AdminAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        return null;
    }
}
