package com.nova.chat.folia.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.common.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Requirements: 2.1
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatFolia plugin;
    protected final MessageHelper messageHelper;

    protected AbstractSubCommand(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();
    }

    /**
     * Resolves the player UUID of a command sender, or {@code null} for console/RCON
     * (so {@link I18n#tr(UUID, String, Object...)} falls back to the default locale).
     *
     * @param sender the command sender
     * @return the sender's UUID if it is a player, otherwise {@code null}
     */
    protected static UUID playerIdOf(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    /**
     * Sends a packet to the backend.
     *
     * @param packet the packet to send
     * @return true if the packet was sent
     */
    protected boolean sendPacket(Packet packet) {
        AsyncNetworkClient client = plugin.getNetworkClient();
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
        AsyncNetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            messageHelper.sendError(sender, ErrorMessageFormatter.format("NC-500"));
            return false;
        }
        if (!client.isAuthenticated()) {
            messageHelper.sendError(sender, ErrorMessageFormatter.format("NC-401"));
            return false;
        }
        return true;
    }

    /**
     * Sends an error built from a shared {@link ErrorCode} plus a platform message.
     *
     * @param sender  the sender
     * @param code    the error code
     * @param message the message to show instead of the code's default
     */
    protected void sendError(CommandSender sender, ErrorCode code, String message) {
        messageHelper.sendError(sender, ErrorMessageFormatter.format(code, message));
    }

    /**
     * Sends an error with a custom message and suggestion, e.g. an invalid
     * duration followed by the accepted formats.
     *
     * @param sender     the sender
     * @param code       the error code
     * @param message    the message to show instead of the code's default
     * @param suggestion the hint line shown instead of the code's default (blank/null omits it)
     */
    protected void sendError(CommandSender sender, ErrorCode code, String message, String suggestion) {
        // Built here rather than via ErrorMessageFormatter because that class has
        // no custom-suggestion overload and would keep the code's default
        // suggestion. Bukkit's NovaError replaces it; the shape is the same.
        StringBuilder formatted = new StringBuilder()
                .append('[').append(code.getCode()).append("] ").append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            formatted.append('\n')
                    .append(I18n.tr(playerIdOf(sender), "error.suggestion_prefix"))
                    .append(' ').append(suggestion);
        }
        messageHelper.sendError(sender, formatted.toString());
    }

    /**
     * Sends the generic "backend request failed" error (NC-503), matching the
     * Bukkit shell that uses {@code NovaError.serviceUnavailable()}.
     *
     * @param sender the sender
     */
    protected void sendRequestFailed(CommandSender sender) {
        messageHelper.sendError(sender, ErrorMessageFormatter.format(ErrorCode.SERVICE_UNAVAILABLE));
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
     * Gets known channel IDs from the shared {@code KnownChannelRegistry} for
     * tab completion (UX-DESIGN §2.3).
     *
     * @param prefix the prefix to filter by (null / empty = all)
     * @return sorted list of matching channel IDs
     */
    protected List<String> getKnownChannelIds(String prefix) {
        com.nova.chat.client.channel.KnownChannelRegistry registry = plugin.getKnownChannelRegistry();
        if (registry == null) {
            return java.util.Collections.emptyList();
        }
        return registry.getKnownChannelIds(prefix);
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
        return com.nova.chat.client.format.DurationFormatter.formatSeconds(seconds);
    }
}
