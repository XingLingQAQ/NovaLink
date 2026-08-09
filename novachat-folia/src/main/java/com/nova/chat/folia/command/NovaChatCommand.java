package com.nova.chat.folia.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat Folia plugin.
 * Implements both CommandExecutor and TabCompleter with permission filtering.
 * 
 * Requirements: 2.1
 */
public class NovaChatCommand implements CommandExecutor, TabCompleter {

    private final NovaChatFolia plugin;
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();
    private final MessageHelper messageHelper;

    public NovaChatCommand(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();
        registerSubCommands();
    }

    /** Resolves the player UUID of a sender (null for console → default locale). */
    private static java.util.UUID playerIdOf(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
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
        
        // Admin commands
        subCommands.put("reload", new ReloadCommand(plugin));
        subCommands.put("debug", new DebugCommand(plugin));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return subCommands.get("help").execute(sender, args);
        }

        String subCommandName = args[0].toLowerCase();
        SubCommand subCommand = subCommands.get(subCommandName);

        if (subCommand == null) {
            messageHelper.sendError(sender, I18n.tr(playerIdOf(sender), "chat.command.unknown", subCommandName));
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.command.unknown_hint", label));
            return true;
        }


        if (!subCommand.hasPermission(sender)) {
            messageHelper.sendError(sender, I18n.tr(playerIdOf(sender), "chat.command.no_permission_code"));
            return true;
        }

        if (subCommand.isPlayerOnly() && !(sender instanceof Player)) {
            messageHelper.sendError(sender, I18n.tr(playerIdOf(sender), "chat.command.player_only"));
            return true;
        }

        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        return subCommand.execute(sender, subArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return getAvailableSubCommands(sender).stream()
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length > 1) {
            String subCommandName = args[0].toLowerCase();
            SubCommand subCommand = subCommands.get(subCommandName);

            if (subCommand != null && subCommand.hasPermission(sender)) {
                String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                List<String> completions = subCommand.tabComplete(sender, subArgs);
                if (completions != null) {
                    return completions;
                }
            }
        }

        return Collections.emptyList();
    }

    /**
     * Gets the list of sub-commands available to a sender based on permissions.
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
