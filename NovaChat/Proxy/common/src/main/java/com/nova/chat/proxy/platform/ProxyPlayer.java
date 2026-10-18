package com.nova.chat.proxy.platform;

import java.util.UUID;

/**
 * Platform-neutral handle for one player connected to the proxy.
 *
 * <p>The shared proxy core works exclusively against this interface: Velocity
 * wraps its {@code com.velocitypowered.api.proxy.Player}, BungeeCord wraps its
 * {@code net.md_5.bungee.api.connection.ProxiedPlayer}. That keeps the core free
 * of either API while still letting the platform implementation send to the real
 * player object (and, in tests, to the mock the test wired into the platform).
 */
public interface ProxyPlayer {

    /**
     * @return the player's UUID
     */
    UUID getUniqueId();

    /**
     * @return the account name (Velocity {@code getUsername}, Bungee {@code getName})
     */
    String getName();

    /**
     * @return the display name used in chat placeholders (Velocity uses the
     *         account name; Bungee uses {@code getDisplayName}, which honours
     *         scoreboard/nickname plugins)
     */
    String getDisplayName();

    /**
     * @return the name of the downstream server the player is on, or
     *         {@code null} when not connected to one yet
     */
    String getCurrentServer();

    /**
     * @param permission the permission node
     * @return {@code true} when the node is granted
     */
    boolean hasPermission(String permission);
}
