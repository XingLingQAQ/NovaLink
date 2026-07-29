package com.nova.link.channel;

/**
 * Enum representing the scope of a channel.
 * Determines how messages are routed.
 */
public enum ChannelScope {
    /**
     * Global scope - messages are routed to all connected clients.
     * Only super admins can create/modify global channels.
     */
    GLOBAL,
    
    /**
     * Server scope - messages are routed only within the same client.
     * Client admins can create/modify server channels.
     */
    SERVER,
    
    /**
     * Private scope - messages are routed only to channel members within the same client.
     * Players can create private channels.
     */
    PRIVATE
}
