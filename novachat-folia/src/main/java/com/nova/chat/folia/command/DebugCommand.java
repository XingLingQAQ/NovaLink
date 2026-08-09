package com.nova.chat.folia.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Debug command - toggles debug mode.
 * 
 * Requirements: 2.1
 */
public class DebugCommand extends AbstractSubCommand {

    public DebugCommand(NovaChatFolia plugin) {
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
            newState = !plugin.isDebugMode();
        }
        
        plugin.setDebugMode(newState);

        if (newState) {
            messageHelper.sendSuccess(sender, I18n.tr(playerIdOf(sender), "chat.debug.enabled"));

            // Show debug info
            messageHelper.sendRaw(sender, I18n.tr(playerIdOf(sender), "chat.debug.info_header"));
            messageHelper.sendRaw(sender, I18n.tr(playerIdOf(sender), "chat.debug.folia", plugin.getScheduler().isFolia()));
            messageHelper.sendRaw(sender, I18n.tr(playerIdOf(sender), "chat.debug.connected", plugin.getNetworkClient().isConnected()));
            messageHelper.sendRaw(sender, I18n.tr(playerIdOf(sender), "chat.debug.authenticated", plugin.getNetworkClient().isAuthenticated()));
            messageHelper.sendRaw(sender, I18n.tr(playerIdOf(sender), "chat.debug.state_count", plugin.getChatInterceptor().getPlayerStateCount()));
        } else {
            messageHelper.sendSuccess(sender, I18n.tr(playerIdOf(sender), "chat.debug.disabled"));
        }
        
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("on", "off");
        }
        return Collections.emptyList();
    }
}
