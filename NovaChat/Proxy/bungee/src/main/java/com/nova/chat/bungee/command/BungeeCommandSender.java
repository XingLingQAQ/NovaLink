package com.nova.chat.bungee.command;

import com.nova.chat.bungee.chat.MessageFormatter;
import com.nova.chat.proxy.command.ProxyCommandSender;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.UUID;

/**
 * BungeeCord {@link CommandSender} wrapped as a platform-neutral
 * {@link ProxyCommandSender}.
 */
public final class BungeeCommandSender implements ProxyCommandSender {

    private final CommandSender sender;
    private final MessageFormatter formatter;

    /**
     * @param sender the BungeeCord command sender (player or console)
     * @param formatter the plugin's message formatter
     */
    public BungeeCommandSender(CommandSender sender, MessageFormatter formatter) {
        this.sender = sender;
        this.formatter = formatter;
    }

    @Override
    public UUID getPlayerId() {
        return sender instanceof ProxiedPlayer player ? player.getUniqueId() : null;
    }

    @Override
    public String getName() {
        return sender.getName();
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void sendMessage(String legacyText) {
        sender.sendMessage(formatter.parseColors(legacyText));
    }
}
