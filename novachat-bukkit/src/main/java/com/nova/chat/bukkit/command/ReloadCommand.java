package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.ChannelCommandService;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Reload command - allows admins to reload the plugin configuration.
 *
 * <p>Signals the reload intent through {@link ChannelCommandService#reload}
 * (Architecture B client-core), which is intentionally a no-op on the wire and
 * on state. The platform still owns the actual config reload / reconnect via
 * {@link NovaChatBukkit#reload()}. Keeps the Bukkit command shape, permission
 * check, and Chinese UX copy.
 *
 * Requirements: 18
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return "重新加载配置文件";
    }

    @Override
    public String getUsage() {
        return "/nc reload";
    }

    @Override
    public String getPermission() {
        return "novachat.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        messageHelper.sendMessage(sender, "正在重新加载配置...");

        // Signal intent through the shared service (documented no-op), then do the platform reload.
        plugin.getChannelCommandService().reload();

        try {
            plugin.reload();
            messageHelper.sendSuccess(sender, "配置已重新加载");
        } catch (Exception e) {
            messageHelper.sendError(sender, "重新加载失败: " + e.getMessage());
            plugin.getLogger().severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}

