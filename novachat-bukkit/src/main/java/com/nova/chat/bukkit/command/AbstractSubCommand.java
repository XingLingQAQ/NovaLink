package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.common.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Requirements: 26.1-26.4
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatBukkit plugin;
    protected final MessageHelper messageHelper;
    protected final ErrorMessageHandler errorHandler;

    protected AbstractSubCommand(NovaChatBukkit plugin) {
        this.plugin = plugin;
        this.messageHelper = new MessageHelper(plugin);
        this.errorHandler = plugin.getErrorHandler();
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
            errorHandler.sendNotConnected(sender);
            return false;
        }
        if (!client.isAuthenticated()) {
            errorHandler.sendError(sender, ErrorCode.UNAUTHORIZED);
            return false;
        }
        return true;
    }
    
    /**
     * Sends an error from a backend response code.
     *
     * @param sender the sender
     * @param code   the error code string (e.g., "NC-401")
     */
    protected void sendBackendError(CommandSender sender, String code) {
        errorHandler.sendErrorFromCode(sender, code);
    }
    
    /**
     * Sends an error from a backend response code with message.
     *
     * @param sender  the sender
     * @param code    the error code string
     * @param message additional message
     */
    protected void sendBackendError(CommandSender sender, String code, String message) {
        errorHandler.sendErrorFromCode(sender, code, message);
    }

    /**
     * Gets the player's chat state.
     *
     * @param player the player
     * @return the chat state, or null if not found
     */
    protected PlayerChannelState getPlayerState(Player player) {
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
     * Gets known channel IDs from the backend ConfigSync cache for tab completion.
     *
     * @param prefix the prefix to filter by
     * @return list of matching channel IDs
     */
    protected List<String> getKnownChannelIds(String prefix) {
        com.nova.chat.client.channel.KnownChannelRegistry registry = plugin.getKnownChannelRegistry();
        if (registry == null) {
            return Collections.emptyList();
        }
        return registry.getKnownChannelIds(prefix);
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
                    // Try parsing as pure seconds
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
