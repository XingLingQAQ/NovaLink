package com.nova.chat.bungee.command;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.proxy.command.ProxyCommandCore;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

/**
 * Main command handler for NovaChat BungeeCord plugin.
 * Handles /novachat and /nc commands.
 *
 * <p>All eleven subcommands, their completions and their message copy live in
 * the shared {@link ProxyCommandCore}; this shell only adapts BungeeCord's
 * {@link Command} + {@link TabExecutor} interfaces.
 *
 * <p>Requirements: 26.1-26.4
 */
public class NovaChatCommand extends Command implements TabExecutor {

    private final NovaChatBungee plugin;

    /**
     * Creates a new NovaChatCommand.
     *
     * @param plugin the plugin instance
     */
    public NovaChatCommand(NovaChatBungee plugin) {
        super("novachat", "novachat.use", "nc");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        ProxyCommandCore core = plugin.getCommandCore();
        core.execute(new BungeeCommandSender(sender, plugin.getMessageFormatter()), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        ProxyCommandCore core = plugin.getCommandCore();
        return core.suggest(new BungeeCommandSender(sender, plugin.getMessageFormatter()), args);
    }
}
