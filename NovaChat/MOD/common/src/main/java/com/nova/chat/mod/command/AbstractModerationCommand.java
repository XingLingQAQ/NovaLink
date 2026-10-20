package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;

import java.util.Collection;
import java.util.UUID;

/**
 * Shared shell for the mod moderation commands ({@code mute}, {@code kick},
 * {@code ban}, {@code unban}, {@code announce}, {@code title}) and {@code auth}.
 *
 * <p>Mirrors the Bukkit/Folia implementations: same argument handling, same
 * packet extras, same i18n keys. The differences are the ones the mod layer
 * forces:
 * <ul>
 *   <li><b>Permission gate.</b> The mod layer has no per-node permission
 *       registry — {@link Platform#hasPermission(UUID, String)} defaults to
 *       granting every node — so the only gate that exists is the OP-level
 *       {@link CommandContext#isAdmin()} the registrar already applies to
 *       {@code reload}/{@code debug}. {@link #getPermissionNode()} carries the
 *       node the Bukkit/Folia shell declares so the parity stays checkable in
 *       tests even though mods do not enforce it.</li>
 *   <li><b>Target resolution.</b> The mod {@link Platform} has no name lookup,
 *       so a target UUID is resolved by scanning the online player ids (the
 *       same cross-server fallback the references rely on: an unresolvable name
 *       is still sent as {@code targetName} for the backend to resolve).</li>
 *   <li><b>Console.</b> Mod console senders arrive as the all-zero sentinel
 *       UUID with the {@link #CONSOLE_OPERATOR_NAME} name, matching the
 *       references' {@code console} extra contract.</li>
 * </ul>
 */
public abstract class AbstractModerationCommand implements CommandHandler {

    /**
     * Display name used for console-originated moderation, matching the
     * backend's {@code ConsoleSentinel.CONSOLE_NAME} so routed copy reads the
     * same as console commands issued on the backend itself.
     */
    protected static final String CONSOLE_OPERATOR_NAME = "Console";

    /**
     * @return the permission node the Bukkit/Folia shell gates this command on
     *         (e.g. {@code novachat.mute}); informational on mods, where
     *         {@link #requireAdmin} is the enforced gate
     */
    public abstract String getPermissionNode();

    /**
     * Enforces the mod-layer gate: an OP-level admin. Sends the shared
     * {@link ErrorCode#FORBIDDEN} text and returns false for anyone else.
     *
     * @param context the command context
     * @return true when the sender may run this command
     */
    protected boolean requireAdmin(CommandContext context) {
        if (context.isAdmin()) {
            return true;
        }
        context.sendMessage(ErrorMessageFormatter.format(ErrorCode.FORBIDDEN));
        return false;
    }

    /**
     * Mirrors the references' {@code checkConnection}: the backend must be
     * connected (NC-500) and the handshake authenticated (NC-401).
     *
     * @param context the command context
     * @return true when a packet can be sent
     */
    protected boolean requireConnection(CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getNetworkClient() == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        if (!services.getNetworkClient().isConnected()) {
            context.sendMessage(ErrorMessageFormatter.format(ErrorCode.INTERNAL_ERROR));
            return false;
        }
        if (!services.getNetworkClient().isAuthenticated()) {
            context.sendMessage(ErrorMessageFormatter.format(ErrorCode.UNAUTHORIZED));
            return false;
        }
        return true;
    }

    /**
     * Sends a packet through the shared network client, the same transport the
     * other mod commands use.
     *
     * @param context the command context
     * @param packet  the packet to send
     * @return true when it was handed to a live connection
     */
    protected boolean sendPacket(CommandContext context, Packet packet) {
        ModServices services = context.getServices();
        if (services == null || services.getNetworkClient() == null
                || !services.getNetworkClient().isConnected()) {
            return false;
        }
        services.getNetworkClient().sendPacket(packet);
        return true;
    }

    /**
     * @param context the command context
     * @return true for a console/RCON sender (the all-zero sentinel UUID)
     */
    protected static boolean isConsole(CommandContext context) {
        return NovaConstants.CONSOLE_SENTINEL_UUID.equals(
                context.getPlayerId() != null ? context.getPlayerId().toString() : null);
    }

    /**
     * Stamps the operator extras the backend reads: a player's own UUID, or the
     * console sentinel plus the {@code console} flag. Both branches add
     * {@code operatorName}, exactly like the references.
     *
     * @param packet  the channel-action packet under construction
     * @param context the command context
     */
    protected void applyOperatorExtras(ChannelActionPacket packet, CommandContext context) {
        if (isConsole(context)) {
            packet.addExtra("operatorId", NovaConstants.CONSOLE_SENTINEL_UUID);
            packet.addExtra("console", "true");
        } else {
            packet.addExtra("operatorId", context.getPlayerId().toString());
        }
        packet.addExtra("operatorName", context.getPlayerName());
    }

    /**
     * Resolves a target to a UUID when possible: a UUID argument first (the
     * unban path accepts one), then an exact name match over the online
     * players. A name that resolves to nobody stays unresolved — callers still
     * send it as {@code targetName} so the backend can resolve it across every
     * sub-server.
     *
     * @param platform  the mod platform (online roster), may be null in tests
     * @param targetArg the name or UUID argument
     * @return the UUID string, or null when it cannot be resolved locally
     */
    protected static String resolveTargetId(Platform platform, String targetArg) {
        if (targetArg == null || targetArg.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(targetArg).toString();
        } catch (IllegalArgumentException ignored) {
            // not a UUID — fall through to the online roster
        }
        if (platform == null) {
            return null;
        }
        Collection<UUID> online = platform.getOnlinePlayerIds();
        if (online == null) {
            return null;
        }
        for (UUID id : online) {
            if (id == null) {
                continue;
            }
            if (targetArg.equalsIgnoreCase(platform.getPlayerName(id))) {
                return id.toString();
            }
        }
        return null;
    }

    /**
     * @param context the command context
     * @return the sender's active channel, or null when unknown (console, or a
     *         player with no state yet)
     */
    protected static String activeChannel(CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getChatInterceptor() == null
                || context.getPlayerId() == null) {
            return null;
        }
        PlayerChannelState state = services.getChatInterceptor().getState(context.getPlayerId());
        if (state == null) {
            return null;
        }
        String active = state.getActiveChannel();
        return active == null || active.isBlank() ? null : active;
    }

    /**
     * Parses a duration string ({@code 30s}, {@code 10m}, {@code 1h},
     * {@code 1d}) into seconds. Identical semantics to the references'
     * {@code AbstractSubCommand#parseDuration}: a bare number is NOT accepted
     * (the last character is always treated as the unit).
     *
     * @param duration the duration token
     * @return seconds, or -1 when unparseable
     */
    protected static long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }
        try {
            char unit = Character.toLowerCase(duration.charAt(duration.length() - 1));
            long value = Long.parseLong(duration.substring(0, duration.length() - 1));
            switch (unit) {
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
     * @param value a command argument
     * @return whether it looks like a duration rather than a channel id
     *         (matches the references' {@code isDuration})
     */
    protected static boolean isDurationToken(String value) {
        return value != null && value.matches("\\d+[smhdw]?");
    }

    /**
     * @param seconds the duration in seconds
     * @return the localized short duration phrase
     */
    protected String formatDuration(long seconds) {
        return DurationFormatter.formatSeconds(seconds);
    }

    /**
     * Sends the command's usage line through the platform's localized
     * {@code chat.error.usage_prefix}.
     *
     * @param context the command context
     */
    protected void sendUsage(CommandContext context) {
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.error.usage_prefix", getUsage()));
    }

    /**
     * Sends a suggestion line (e.g. the accepted duration formats).
     *
     * @param context the command context
     * @param hint    the hint text
     */
    protected void sendHint(CommandContext context, String hint) {
        context.sendMessage(I18n.tr(context.getPlayerId(), "error.suggestion_prefix") + " " + hint);
    }

    /**
     * Sends an error built from a shared {@link ErrorCode} plus a custom
     * message, keeping the code's default suggestion.
     *
     * @param context the command context
     * @param code    the error code
     * @param message the message to show instead of the code's default
     */
    protected void sendError(CommandContext context, ErrorCode code, String message) {
        context.sendMessage(ErrorMessageFormatter.format(code, message));
    }

    /**
     * Sends an error with a custom message and a custom suggestion line.
     *
     * @param context    the command context
     * @param code       the error code
     * @param message    the message
     * @param suggestion the suggestion (blank/null omits the line)
     */
    protected void sendError(CommandContext context, ErrorCode code, String message,
                             String suggestion) {
        StringBuilder formatted = new StringBuilder()
                .append('[').append(code.getCode()).append("] ").append(message);
        if (suggestion != null && !suggestion.isBlank()) {
            formatted.append('\n')
                    .append(I18n.tr(context.getPlayerId(), "error.suggestion_prefix"))
                    .append(' ').append(suggestion);
        }
        context.sendMessage(formatted.toString());
    }

    /**
     * Sends the generic "backend request failed" error (NC-503), matching the
     * references' {@code sendRequestFailed}.
     *
     * @param context the command context
     */
    protected void sendRequestFailed(CommandContext context) {
        context.sendMessage(ErrorMessageFormatter.format(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
