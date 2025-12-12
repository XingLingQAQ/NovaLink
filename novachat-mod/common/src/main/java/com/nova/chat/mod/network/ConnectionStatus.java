package com.nova.chat.mod.network;

/**
 * Enum representing network connection status
 */
public enum ConnectionStatus {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    ERROR("Error");
    
    private final String displayName;
    
    ConnectionStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
