package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Reload command - reloads plugin configuration.
 *
 * <p>{@link ChannelCommandService#reload()} is intentionally a no-op on the wire;
 * the platform still owns config reload / reconnect.
 *
 * Requirements: 18.1
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return "重新加载配置";
    }

    @Override
    public String getUsage() {
        return "/nc reload";
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
        try {
            // Signal intent through shared service (documented no-op), then do platform reload.
            ChannelCommandService channelCommands = plugin.getChannelCommandService();
            if (channelCommands != null) {
                channelCommands.reload();
            }
            plugin.reload();
            sendSuccess(sender, "配置已重新加载");
        } catch (Exception e) {
            sendError(sender, "重新加载配置时出错: " + e.getMessage());
            plugin.getLogger().error("Error reloading config", e);
        }

        return true;
    }
}
