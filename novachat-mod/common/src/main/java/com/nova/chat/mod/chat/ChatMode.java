package com.nova.chat.mod.chat;

/**
 * Enum representing different chat modes.
 *
 * <p>Intentionally NOT migrated to {@code com.nova.chat.client.state.ChatMode}
 * (Architecture B / HYBRID|REPLACE). Mod uses a GLOBAL/LOCAL/PRIVATE/ADMIN model.
 */
public enum ChatMode {
    GLOBAL("Global"),
    LOCAL("Local"),
    PRIVATE("Private"),
    ADMIN("Admin");
    
    private final String displayName;
    
    ChatMode(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
