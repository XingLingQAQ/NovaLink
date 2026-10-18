package com.nova.chat.velocity.command;

import com.nova.chat.proxy.command.ProxyCommandSender;
import com.nova.chat.velocity.chat.MessageFormatter;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;

/**
 * Velocity {@link CommandSource} wrapped as a platform-neutral
 * {@link ProxyCommandSender}.
 */
public final class VelocityCommandSender implements ProxyCommandSender {

    private final CommandSource source;
    private final MessageFormatter formatter;

    /**
     * @param source the Velocity command source (player or console)
     * @param formatter the plugin's message formatter
     */
    public VelocityCommandSender(CommandSource source, MessageFormatter formatter) {
        this.source = source;
        this.formatter = formatter;
    }

    @Override
    public UUID getPlayerId() {
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    @Override
    public String getName() {
        return source instanceof Player player ? player.getUsername() : "CONSOLE";
    }

    @Override
    public boolean hasPermission(String permission) {
        return source.hasPermission(permission);
    }

    @Override
    public void sendMessage(String legacyText) {
        source.sendMessage(formatter.parseColors(legacyText));
    }
}
