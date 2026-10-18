package com.nova.chat.velocity.command;

import com.nova.chat.proxy.command.ProxyCommandCore;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.List;

/**
 * Main command handler for NovaChat Velocity plugin.
 * Handles /novachat and /nc commands.
 *
 * <p>All eleven subcommands, their completions and their message copy live in
 * the shared {@link ProxyCommandCore}; this shell only adapts Velocity's
 * {@link SimpleCommand} interface (execute / suggest / hasPermission).
 *
 * <p>Requirements: 26.1-26.4
 */
public class NovaChatCommand implements SimpleCommand {

    private final NovaChatVelocity plugin;

    /**
     * Creates a new NovaChatCommand.
     *
     * @param plugin the plugin instance
     */
    public NovaChatCommand(NovaChatVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        ProxyCommandCore core = plugin.getCommandCore();
        core.execute(new VelocityCommandSender(invocation.source(), plugin.getMessageFormatter()),
                invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        ProxyCommandCore core = plugin.getCommandCore();
        return core.suggest(new VelocityCommandSender(invocation.source(), plugin.getMessageFormatter()),
                invocation.arguments());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // `novachat.use` is a basic-user permission that must default to TRUE for
        // all players (mirrors BungeeCord's default-grant-all permission provider).
        //
        // Velocity 4.1.0 changed Brigadier dispatch: when a SimpleCommand's
        // hasPermission returns false for a sender, Velocity prunes the command
        // node's children from that sender's dispatch tree (3.4.0 did not). An
        // offline-mode player has NO granted permissions, so the previous
        // `hasPermission("novachat.use")` check returned false, which pruned the
        // `nc` node's subcommand children (help/join/leave/...) and made every
        // `/nc <sub>` report "Incorrect argument at position 3: nc <--[HERE]" —
        // a permission denial masquerading as a syntax error.
        //
        // The admin subcommand `/nc reload` independently gates on
        // `novachat.admin` inside handleReload, so the admin gate is preserved.
        // Default-allowing here only restores basic-user dispatch.
        return true;
    }
}
