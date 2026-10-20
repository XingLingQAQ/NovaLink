package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.network.NetworkClient;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Requirements: 29.1, 29.2
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatPNX plugin;

    public AbstractSubCommand(NovaChatPNX plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    /**
     * Gets the player from a command sender, or null if not a player.
     *
     * @param sender the command sender
     * @return the player, or null
     */
    protected Player getPlayer(CommandSender sender) {
        return sender instanceof Player ? (Player) sender : null;
    }

    /**
     * Checks if the sender is a player.
     *
     * @param sender the command sender
     * @return true if the sender is a player
     */
    protected boolean isPlayer(CommandSender sender) {
        return sender instanceof Player;
    }

    /**
     * Sends an error message to the sender.
     *
     * @param sender  the command sender
     * @param message the error message
     */
    protected void sendError(CommandSender sender, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatError();
        sender.sendMessage(colorize(prefix + format.replace("{message}", message)));
    }

    /**
     * Sends a success message to the sender.
     *
     * @param sender  the command sender
     * @param message the success message
     */
    protected void sendSuccess(CommandSender sender, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatSuccess();
        sender.sendMessage(colorize(prefix + format.replace("{message}", message)));
    }

    /**
     * Sends a message to the sender.
     *
     * @param sender  the command sender
     * @param message the message
     */
    protected void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(colorize(message));
    }

    /**
     * Colorizes a message using Minecraft color codes.
     *
     * @param message the message to colorize
     * @return the colorized message
     */
    protected String colorize(String message) {
        return TextFormat.colorize('&', message);
    }

    /**
     * Sends the usage line for a sub-command.
     *
     * @param sender the command sender
     * @param usage  the usage string
     */
    protected void sendUsage(CommandSender sender, String usage) {
        sender.sendMessage(colorize("&c"
                + I18n.tr(playerIdOf(sender), "chat.error.usage_prefix", usage)));
    }

    /**
     * Sends a hint line under a previous message.
     *
     * @param sender     the command sender
     * @param suggestion the hint
     */
    protected void sendSuggestion(CommandSender sender, String suggestion) {
        sender.sendMessage(colorize("  &7"
                + I18n.tr(playerIdOf(sender), "error.suggestion_prefix") + " &f" + suggestion));
    }

    // ==================== moderation helpers ====================

    /**
     * Resolves a sender's player UUID, or {@code null} for console/RCON so
     * {@link I18n#tr(java.util.UUID, String, Object...)} falls back to the
     * default locale.
     *
     * @param sender the command sender
     * @return the UUID for a player, otherwise {@code null}
     */
    protected static java.util.UUID playerIdOf(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    /**
     * Sends an error built from a shared {@link ErrorCode}.
     *
     * @param sender  the sender
     * @param code    the error code
     * @param message the message shown instead of the code's default
     */
    protected void sendError(CommandSender sender, ErrorCode code, String message) {
        sendError(sender, code, message, null);
    }

    /**
     * Sends an error built from a shared {@link ErrorCode} and a custom
     * suggestion (e.g. the accepted duration formats after a parse failure).
     *
     * @param sender     the sender
     * @param code       the error code
     * @param message    the message shown instead of the code's default
     * @param suggestion the hint shown instead of the code's default (blank omits it)
     */
    protected void sendError(CommandSender sender, ErrorCode code, String message, String suggestion) {
        StringBuilder formatted = new StringBuilder()
                .append('[').append(code.getCode()).append("] ").append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            formatted.append('\n')
                    .append(I18n.tr(playerIdOf(sender), "error.suggestion_prefix"))
                    .append(' ').append(suggestion);
        }
        sendError(sender, formatted.toString());
    }

    /**
     * Sends the generic "backend request failed" error (NC-503).
     *
     * @param sender the sender
     */
    protected void sendRequestFailed(CommandSender sender) {
        sendError(sender, ErrorMessageFormatter.format(ErrorCode.SERVICE_UNAVAILABLE));
    }

    /**
     * Sends a packet when the backend link is up.
     *
     * @param packet the packet to send
     * @return true if it was handed to a connected client
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
     * Verifies the backend link is connected and authenticated.
     *
     * @param sender the sender to notify on failure
     * @return true when the packet path is usable
     */
    protected boolean checkConnection(CommandSender sender) {
        NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            sendError(sender, ErrorMessageFormatter.format(ErrorCode.SERVICE_UNAVAILABLE));
            return false;
        }
        if (!client.isAuthenticated()) {
            sendError(sender, ErrorMessageFormatter.format(ErrorCode.UNAUTHORIZED));
            return false;
        }
        return true;
    }

    /**
     * Stamps the operator identity extras every moderation packet carries.
     * Console/RCON uses the all-zeros sentinel so the backend can grant
     * console authority, and is tagged {@code console=true}.
     *
     * @param packet the packet to stamp
     * @param sender the issuing sender
     */
    protected void addOperatorExtras(ChannelActionPacket packet, CommandSender sender) {
        if (sender instanceof Player) {
            packet.addExtra("operatorId", ((Player) sender).getUniqueId().toString());
        } else {
            packet.addExtra("operatorId", NovaConstants.CONSOLE_SENTINEL_UUID);
            packet.addExtra("console", "true");
        }
        packet.addExtra("operatorName", sender.getName());
    }

    /**
     * Resolves a locally online target's UUID. Returns null when the target is
     * on another sub-server; the caller then sends the name and lets the
     * backend resolve it.
     *
     * @param name the target's name
     * @return the UUID string, or null
     */
    protected String resolveTargetId(String name) {
        Player target = plugin.getServer().getPlayerExact(name);
        return target != null ? target.getUniqueId().toString() : null;
    }

    /**
     * Parses a duration string (e.g. "1h", "30m", "1d") to seconds.
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
            if (!Character.isDigit(unit)) {
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
                        return -1;
                }
            }
            return Long.parseLong(duration);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Formats a duration in seconds to a short localized phrase.
     *
     * @param seconds the duration in seconds
     * @return the formatted string
     */
    protected String formatDuration(long seconds) {
        return DurationFormatter.formatSeconds(seconds);
    }

    /**
     * Joins the arguments from {@code start} onward, or null when empty.
     *
     * @param args  the command arguments
     * @param start the first index to include
     * @return the joined text, or null
     */
    protected static String joinArgs(String[] args, int start) {
        if (args.length <= start) {
            return null;
        }
        String joined = String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).trim();
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Gets online player names matching a prefix for tab completion.
     *
     * @param prefix the prefix to filter by
     * @return matching player names
     */
    protected java.util.List<String> getOnlinePlayerNames(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        return plugin.getServer().getOnlinePlayers().values().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lower))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Gets a player's chat state without creating one.
     *
     * @param player the player
     * @return the state, or null
     */
    protected PlayerChannelState getPlayerState(Player player) {
        if (player == null || plugin.getChatInterceptor() == null) {
            return null;
        }
        return plugin.getChatInterceptor().getPlayerState(player.getUniqueId());
    }

    /**
     * Gets known channel IDs from the shared {@code KnownChannelRegistry} for
     * tab completion (UX-DESIGN §2.3).
     *
     * @param prefix the prefix to filter by (null / empty = all)
     * @return sorted list of matching channel IDs
     */
    protected java.util.List<String> getKnownChannelIds(String prefix) {
        com.nova.chat.client.channel.KnownChannelRegistry registry = plugin.getKnownChannelRegistry();
        if (registry == null) {
            return java.util.Collections.emptyList();
        }
        return registry.getKnownChannelIds(prefix);
    }
}
