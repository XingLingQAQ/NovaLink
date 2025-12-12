package com.nova.chat.mod.chat;

/**
 * Enum representing different chat modes
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
