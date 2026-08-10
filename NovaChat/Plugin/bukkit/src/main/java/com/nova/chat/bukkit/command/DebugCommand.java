package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Debug command - allows admins to toggle debug mode.
 * 
 * Requirements: 19
 */
public class DebugCommand extends AbstractSubCommand {

    public DebugCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return "切换调试模式";
    }

    @Override
    public String getUsage() {
        return "/nc debug [on|off]";
    }

    @Override
    public String getPermission() {
        return "novachat.debug";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        boolean newState;

        if (args.length > 0) {
            String arg = args[0].toLowerCase();
            if (arg.equals("on") || arg.equals("true") || arg.equals("1")) {
                newState = true;
            } else if (arg.equals("off") || arg.equals("false") || arg.equals("0")) {
                newState = false;
            } else {
                messageHelper.sendUsage(sender, getUsage());
                return true;
            }
        } else {
            // Toggle current state
            newState = !plugin.isDebugMode();
        }

        plugin.setDebugMode(newState);

        if (newState) {
            messageHelper.sendSuccess(sender, I18n.tr(playerIdOf(sender), "chat.debug.enabled"));
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.debug.log_hint"));
        } else {
            messageHelper.sendSuccess(sender, I18n.tr(playerIdOf(sender), "chat.debug.disabled"));
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("on", "off").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
