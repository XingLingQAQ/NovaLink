package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.state.ChatMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Toggle command - allows players to toggle between chat modes.
 * 
 * Requirements: 11
 */
public class ToggleCommand extends AbstractSubCommand {

    public ToggleCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "toggle";
    }

    @Override
    public String getDescription() {
        return "切换聊天模式";
    }

    @Override
    public String getUsage() {
        return "/nc toggle";
    }

    @Override
    public String getPermission() {
        return "novachat.toggle";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        PlayerChannelState state = getPlayerState(player);

        if (state == null) {
            // Create new state with default values
            ChatMode defaultMode = plugin.getNovaChatConfig().isReplaceVanilla() 
                    ? ChatMode.REPLACE : ChatMode.HYBRID;
            state = new PlayerChannelState(
                    player.getUniqueId(),
                    plugin.getNovaChatConfig().getDefaultChannel(),
                    defaultMode
            );
            plugin.getChatInterceptor().setPlayerState(player.getUniqueId(), state);
        }

        // Toggle the mode
        ChatMode newMode = state.toggleMode();

        if (newMode == ChatMode.REPLACE) {
            messageHelper.sendSuccess(sender, "聊天模式已切换为 &e频道模式");
            messageHelper.sendMessage(sender, "所有聊天消息将发送到当前频道");
        } else {
            messageHelper.sendSuccess(sender, "聊天模式已切换为 &e混合模式");
            messageHelper.sendMessage(sender, "原版聊天保留，使用命令发送频道消息");
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
