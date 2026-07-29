package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
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
            messageHelper.sendSuccess(sender, "调试模式已 &a开启");
            messageHelper.sendMessage(sender, "详细日志将输出到控制台");
        } else {
            messageHelper.sendSuccess(sender, "调试模式已 &c关闭");
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
