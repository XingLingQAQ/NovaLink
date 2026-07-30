package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat Nukkit plugin.
 * Implements command execution with permission filtering.
 * 
 * Adapted from Bukkit version for Nukkit API.
 * 
 * Requirements: 26.1-26.4, 23.4
 */
public class NovaChatCommand extends Command {

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
        subCommands.put("toggle", new ToggleCommand(plugin));
        subCommands.put("channel", new ChannelCommand(plugin)); // Opens Form GUI
        
        // Admin commands
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("debug", new DebugCommand(plugin));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
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
