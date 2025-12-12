package com.nova.chat.multipaper.command;

import com.nova.chat.multipaper.NovaChatMultiPaper;
import com.nova.chat.multipaper.chat.PlayerChatState;
import com.nova.chat.multipaper.network.NetworkClient;
import com.nova.chat.common.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Requirements: 1.1
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatMultiPaper plugin;
    protected final MessageHelper messageHelper;

    protected AbstractSubCommand(NovaChatMultiPaper plugin) {
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();
    }

    /**
     * Sends a packet to the backend.
     *
     * @param packet the packet to send
     * @return true if the packet was sent
     */
    protected boolean sendPacket(Packet packet) {
        NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            return false;
        }
        client.sendPacket(packet);
        return true;
    }

    /**
     * Checks if the plugin is connected to the backend.
     *
     * @param sender the sender to notify if not connected
     * @return true if connected
     */
    protected boolean checkConnection(CommandSender sender) {
        NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            messageHelper.sendError(sender, "未连接到聊天服务器 (NC-500)");
            return false;
        }
        if (!client.isAuthenticated()) {
            messageHelper.sendError(sender, "未通过身份验证 (NC-401)");
            return false;
        }
        return true;
    }

    /**
     * Gets the player's chat state.
     *
     * @param player the player
     * @return the chat state, or null if not found
     */
    protected PlayerChatState getPlayerState(Player player) {
        return plugin.getChatInterceptor().getPlayerState(player.getUniqueId());
    }

    /**
     * Gets a list of online player names for tab completion.
     *
     * @param prefix the prefix to filter by
     * @return list of matching player names
     */
    protected List<String> getOnlinePlayerNames(String prefix) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    /**
     * Parses a player name to a Player object.
     *
     * @param name the player name
     * @return the Player, or null if not found
     */
    protected Player parsePlayer(String name) {
        return Bukkit.getPlayer(name);
    }

    /**
     * Parses a duration string (e.g., "1h", "30m", "1d") to seconds.
     *
     * @param duration the duration string
     * @return the duration in seconds, or -1 if invalid
     */
    protected long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }

        try {
            char unit = duration.charAt(duration.length() - 1);
            long value = Long.parseLong(duration.substring(0, duration.length() - 1));

            switch (Character.toLowerCase(unit)) {
                case 's':
                    return value;
                case 'm':
                    return value * 60;
                case 'h':
                    return value * 3600;
                case 'd':
                    return value * 86400;
                default:
                    return Long.parseLong(duration);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Formats a duration in seconds to a human-readable string.
     *
     * @param seconds the duration in seconds
     * @return the formatted string
     */
    protected String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分钟";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "小时";
        } else {
            return (seconds / 86400) + "天";
        }
    }
}
