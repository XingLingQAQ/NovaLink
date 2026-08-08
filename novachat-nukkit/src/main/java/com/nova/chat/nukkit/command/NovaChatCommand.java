package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandExecutor;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat Nukkit plugin.
 * Implements command execution with permission filtering.
 *
 * <p>Nukkit's {@code PluginManager.parseYamlCommands} reads the plugin
 * descriptor (this branch uses {@code nukkit.yml} at the jar root) and
 * pre-registers a {@code PluginCommand} for every entry under {@code commands:},
 * including our {@code novachat}/{@code nc} alias. That pre-registered
 * {@code PluginCommand} wins the {@code knownCommands} slot, so a separate
 * {@code getCommandMap().register(...)} call is silently rejected (returns
 * false) and never dispatches. To actually receive command execution this
 * class also implements {@link CommandExecutor} and is attached as the
 * executor of the existing {@code PluginCommand} from the descriptor
 * (see {@link NovaChatNukkit#registerCommands()}).
 *
 * Adapted from Bukkit version for Nukkit API.
 *
 * Requirements: 26.1-26.4, 23.4
 */
public class NovaChatCommand extends Command implements CommandExecutor {

    private final NovaChatNukkit plugin;
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();
    private final MessageHelper messageHelper;

    public NovaChatCommand(NovaChatNukkit plugin) {
        super("novachat", "NovaChat main command", "/novachat [subcommand] [args]", new String[]{"nc"});
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();

        // Set permission
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
            messageHelper.sendError(sender, "未知命令: " + subCommandName);
            messageHelper.sendMessage(sender, "使用 &e/" + label + " help &7查看可用命令");
            return true;
        }

        // Check permission
        if (!subCommand.hasPermission(sender)) {
            messageHelper.sendError(sender, "你没有权限执行此命令 (NC-403)");
            return true;
        }

        // Check if player-only command
        if (subCommand.isPlayerOnly() && !(sender instanceof Player)) {
            messageHelper.sendError(sender, "此命令只能由玩家执行");
            return true;
        }

        // Execute with remaining args
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return subCommand.execute(sender, subArgs);
    }

    /**
     * {@link CommandExecutor} bridge used when this handler is attached as the
     * executor of the {@code PluginCommand} that Nukkit pre-registered from the
     * plugin descriptor (see class javadoc). {@code PluginCommand.execute}
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
     * Dispatches tab completion to the matching subcommand (UX-DESIGN §2.3).
     *
     * <p>Nukkit's {@code Command} base does not call this from the Bedrock client
     * completion path (client-side completion is driven by {@code CommandParameter}
     * overloads), but the per-subcommand {@code tabComplete} contract is kept
     * consistent with the other platforms and wired to the shared
     * {@code KnownChannelRegistry} so it works wherever a dispatcher invokes it.
     */
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return getAvailableSubCommands(sender).stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        String subCommandName = args[0].toLowerCase();
        SubCommand subCommand = subCommands.get(subCommandName);
        if (subCommand != null && subCommand.hasPermission(sender)) {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            List<String> completions = subCommand.tabComplete(sender, subArgs);
            if (completions != null) {
                return completions;
            }
        }
        return Collections.emptyList();
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

    /**
     * Gets the message helper for formatting messages.
     *
     * @return the message helper
     */
    public MessageHelper getMessageHelper() {
        return messageHelper;
    }
}
