package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandExecutor;
import cn.nukkit.command.CommandSender;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat-PNX.
 * Supports subcommands: help, join, leave, toggle, reload, debug, channel
 *
 * <p>PowerNukkitX's {@code PluginManager.parseYamlCommands} reads the plugin
 * descriptor ({@code plugin.yml}) and pre-registers a {@code PluginCommand} for
 * every entry under {@code commands:}, including our {@code novachat}/{@code nc}
 * alias. That pre-registered {@code PluginCommand} wins the {@code knownCommands}
 * slot, so a separate {@code getCommandMap().register(...)} call is silently
 * rejected (returns false) and never dispatches. To actually receive command
 * execution this class also implements {@link CommandExecutor} and is attached as
 * the executor of the existing {@code PluginCommand} from the descriptor
 * (see {@link NovaChatPNX#registerCommands()}).
 *
 * Requirements: 29.1, 29.2
 */
public class NovaChatCommand extends Command implements CommandExecutor {

    private final NovaChatPNX plugin;
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public NovaChatCommand(NovaChatPNX plugin) {
        super("novachat", "NovaChat main command", "/novachat [subcommand] [args]", new String[]{"nc"});
        this.plugin = plugin;
        this.setPermission("novachat.use");

        registerSubCommands();
    }

    /**
     * Registers all sub-commands.
     */
    private void registerSubCommands() {
        // Player commands
        subCommands.put("help", new HelpCommand(plugin, this));
        subCommands.put("join", new JoinCommand(plugin));
        subCommands.put("leave", new LeaveCommand(plugin));
        subCommands.put("list", new ListCommand(plugin));
        subCommands.put("who", new WhoCommand(plugin));
        subCommands.put("toggle", new ToggleCommand(plugin));
        subCommands.put("channel", new ChannelCommand(plugin)); // Opens Form GUI
        
        // Admin commands
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("debug", new DebugCommand(plugin));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        plugin.debug("NovaChatCommand.execute invoked: label=" + label + " args=" + java.util.Arrays.toString(args) + " sender=" + (sender == null ? "null" : sender.getName()));
        if (args.length == 0) {
            // Show help by default
            return subCommands.get("help").execute(sender, args);
        }

        String subCommandName = args[0].toLowerCase();
        SubCommand subCommand = subCommands.get(subCommandName);

        if (subCommand == null) {
            sendError(sender, "未知命令: " + subCommandName);
            sendMessage(sender, "使用 §e/" + label + " help §7查看可用命令");
            return true;
        }

        // Check permission
        if (!subCommand.hasPermission(sender)) {
            sendError(sender, "你没有权限执行此命令 (NC-403)");
            return true;
        }

        // Check if player-only command
        if (subCommand.isPlayerOnly() && !(sender instanceof Player)) {
            sendError(sender, "此命令只能由玩家执行");
            return true;
        }

        // Execute with remaining args
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return subCommand.execute(sender, subArgs);
    }

    /**
     * {@link CommandExecutor} bridge used when this handler is attached as the
     * executor of the {@code PluginCommand} that PowerNukkitX pre-registered from
     * the plugin descriptor (see class javadoc). {@code PluginCommand.execute}
     * performs the permission test against the descriptor's permission node and
     * then calls {@code executor.onCommand(...)}; we delegate to
     * {@link #execute(CommandSender, String, String[])} which owns the
     * per-subcommand routing.
     *
     * <p>Returns {@code true} on success so {@code PluginCommand} does not emit
     * the generic {@code commands.generic.usage} fallback.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, label, args);
    }

    /**
     * Gets the list of sub-commands available to a sender based on permissions.
     * Hidden commands are excluded.
     *
     * @param sender the command sender
     * @return list of available sub-command names
     */
    public List<String> getAvailableSubCommands(CommandSender sender) {
        return subCommands.entrySet().stream()
                .filter(entry -> !entry.getValue().isHidden())
                .filter(entry -> entry.getValue().hasPermission(sender))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets all registered sub-commands.
     *
     * @return map of sub-command name to SubCommand
     */
    public Map<String, SubCommand> getSubCommands() {
        return Collections.unmodifiableMap(subCommands);
    }

    private void sendError(CommandSender sender, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatError();
        sender.sendMessage(prefix + format.replace("{message}", message));
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(message);
    }
}
