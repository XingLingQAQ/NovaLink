package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Debug command - toggles debug mode.
 * 
 * Requirements: 19.3
 */
public class DebugCommand extends AbstractSubCommand {

    public DebugCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.debug");
    }

    @Override
    public String getUsage() {
        return "/nc debug [on|off]";
    }

    @Override
    public String getPermission() {
        return "novachat.admin";
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
                sendError(sender, I18n.tr("chat.error.usage_prefix", getUsage()));
                return true;
            }
        } else {
            // Toggle current state
            newState = !plugin.isDebugMode();
        }

        plugin.setDebugMode(newState);

        if (newState) {
            sendSuccess(sender, I18n.tr("chat.debug.mode_on"));
        } else {
            sendSuccess(sender, I18n.tr("chat.debug.mode_off"));
        }

        return true;
    }
}
