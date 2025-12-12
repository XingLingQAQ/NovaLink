package com.nova.chat.nukkit.chat;

/**
 * Defines the chat mode for NovaChat.
 * 
 * HYBRID: NovaChat works alongside vanilla chat
 * REPLACE: NovaChat replaces vanilla chat completely
 */
public enum ChatMode {
    /**
     * Hybrid mode - vanilla chat works normally, 
     * NovaChat commands send to channels.
     */
    HYBRID,
    
    /**
     * Replace mode - all chat is intercepted and 
     * sent through NovaChat channels.
     */
    REPLACE
}
