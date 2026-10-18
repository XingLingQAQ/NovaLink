package com.nova.chat.velocity.platform;

import com.nova.chat.proxy.platform.ProxyPlayer;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;

/**
 * Velocity {@link Player} wrapped as a platform-neutral {@link ProxyPlayer}.
 */
public final class VelocityProxyPlayer implements ProxyPlayer {

    private final Player player;

    /**
     * @param player the Velocity player
     */
    public VelocityProxyPlayer(Player player) {
        this.player = player;
    }

    /**
     * @return the wrapped Velocity player
     */
    public Player getHandle() {
        return player;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getUsername();
    }

    @Override
    public String getDisplayName() {
        return player.getUsername();
    }

    @Override
    public String getCurrentServer() {
        return player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }
}
