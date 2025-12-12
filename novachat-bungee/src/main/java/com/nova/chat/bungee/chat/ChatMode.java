package com.nova.chat.bungee.chat;

/**
 * Defines how the plugin handles vanilla chat messages.
 * 
 * Requirements: 11.1, 11.2
 */
public enum ChatMode {
    
    /**
     * HYBRID mode: Vanilla chat is preserved, only command messages go to channels.
     * When replace_vanilla is false, players can use both vanilla chat and /nc commands.
     */
    HYBRID,
    
    /**
     * REPLACE mode: All chat messages are intercepted and sent to the current channel.
     * When replace_vanilla is true, vanilla chat is completely replaced.
     */
    REPLACE
}
