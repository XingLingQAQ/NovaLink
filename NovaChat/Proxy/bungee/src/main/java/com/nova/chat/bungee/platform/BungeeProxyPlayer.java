package com.nova.chat.bungee.platform;

import com.nova.chat.proxy.platform.ProxyPlayer;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.UUID;

/**
 * BungeeCord {@link ProxiedPlayer} wrapped as a platform-neutral
 * {@link ProxyPlayer}.
 */
public final class BungeeProxyPlayer implements ProxyPlayer {

    private final ProxiedPlayer player;

    /**
     * @param player the BungeeCord player
     */
    public BungeeProxyPlayer(ProxiedPlayer player) {
        this.player = player;
    }

    /**
     * @return the wrapped BungeeCord player
     */
    public ProxiedPlayer getHandle() {
        return player;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public String getDisplayName() {
        return player.getDisplayName();
    }

    @Override
    public String getCurrentServer() {
        return player.getServer() != null ? player.getServer().getInfo().getName() : null;
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }
}
