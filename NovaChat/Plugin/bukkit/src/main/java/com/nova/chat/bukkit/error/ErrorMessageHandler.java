package com.nova.chat.bukkit.error;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles error message display and logging for NovaChat.
 * Provides formatted error messages with error codes and solution suggestions.
 *
 * <p>User-facing copy is resolved through the shared {@link I18n} service so it
 * follows the player's locale (zh_CN default, en_US secondary). Error codes
 * that map to {@link ErrorCode} resolve via the shared bundle
 * ({@code error.NC-*}); custom platform messages use the {@code chat.error.*}
 * keys.
 *
 * Requirements: 27.1-27.4
 */
public class ErrorMessageHandler {

    private final NovaChatBukkit plugin;
    private final MessageHelper messageHelper;

    /** Error message format with code */
    private static final String ERROR_FORMAT = "&c%s &8| &7%s";

    public ErrorMessageHandler(NovaChatBukkit plugin, MessageHelper messageHelper) {
        this.plugin = plugin;
        this.messageHelper = messageHelper;
    }

    /** Resolves the player UUID of a sender (null for console → default locale). */
    private static UUID playerIdOf(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    /**
     * Sends an error message to the sender with formatted error code and suggestion.
     *
     * @param sender the command sender
     * @param error  the error to display
     */
    public void sendError(CommandSender sender, NovaError error) {
        UUID playerId = playerIdOf(sender);
        // Send main error message
        String errorLine = String.format(ERROR_FORMAT, error.getCode(), error.getMessage());
        messageHelper.sendRaw(sender, "&8[&cNovaChat&8]&r " + errorLine);

        // Send suggestion
        String suggestion = error.getSuggestion();
        if (suggestion != null && !suggestion.isEmpty()) {
            messageHelper.sendRaw(sender, "  &7" + I18n.tr(playerId, "error.suggestion_prefix") + " &f" + suggestion);
        }

        // For server errors, show error ID for tracking
        if (error.isServerError()) {
            String errorId = error.getErrorId().toString().substring(0, 8);
            messageHelper.sendRaw(sender, "  &8" + I18n.tr(playerId, "chat.error.error_id") + " &7" + errorId);
        }

        // Log the error
        logError(error);
    }

    /**
     * Sends an error message using an ErrorCode directly.
     *
     * @param sender    the command sender
     * @param errorCode the error code
     */
    public void sendError(CommandSender sender, ErrorCode errorCode) {
        sendError(sender, new NovaError(errorCode));
    }

    /**
     * Sends an error message with a custom message.
     *
     * @param sender        the command sender
     * @param errorCode     the error code
     * @param customMessage custom message
     */
    public void sendError(CommandSender sender, ErrorCode errorCode, String customMessage) {
        sendError(sender, new NovaError(errorCode, customMessage));
    }

    /**
     * Sends an error message with custom message and suggestion.
     *
     * @param sender           the command sender
     * @param errorCode        the error code
     * @param customMessage    custom message
     * @param customSuggestion custom suggestion
     */
    public void sendError(CommandSender sender, ErrorCode errorCode, String customMessage, String customSuggestion) {
        sendError(sender, new NovaError(errorCode, customMessage, customSuggestion));
    }

    /**
     * Sends an error from a backend response code.
     *
     * <p>Uses the shared {@link ErrorCode#fromCode(String)} semantics: unknown
     * or null codes resolve to {@link ErrorCode#INTERNAL_ERROR}, so callers
     * always get a renderable error. When the backend supplies an unrecognized
     * code, we surface the raw code in the message so operators can still
     * diagnose it.
     *
     * @param sender the command sender
     * @param code   the error code string (e.g., "NC-401")
     */
    public void sendErrorFromCode(CommandSender sender, String code) {
        ErrorCode errorCode = ErrorCode.fromCode(code);
        if (code != null && !code.equals(errorCode.getCode())) {
            // Unknown backend code — preserve it in the message for diagnosis.
            UUID playerId = playerIdOf(sender);
            sendError(sender, new NovaError(errorCode,
                I18n.tr(playerId, "chat.error.unknown_code", code),
                I18n.tr(playerId, "chat.error.contact_admin_code")));
        } else {
            sendError(sender, errorCode);
        }
    }

    /**
     * Sends an error from a backend response code with additional message.
     *
     * @param sender  the command sender
     * @param code    the error code string
     * @param message additional message from backend
     */
    public void sendErrorFromCode(CommandSender sender, String code, String message) {
        ErrorCode errorCode = ErrorCode.fromCode(code);
        if (code != null && !code.equals(errorCode.getCode())) {
            // Unknown backend code — preserve it in the message for diagnosis.
            UUID playerId = playerIdOf(sender);
            sendError(sender, new NovaError(errorCode,
                code + ": " + message,
                I18n.tr(playerId, "chat.error.contact_admin_code")));
        } else {
            sendError(sender, new NovaError(errorCode, message));
        }
    }

    /**
     * Logs an error to the plugin logger.
     *
     * @param error the error to log
     */
    private void logError(NovaError error) {
        Level level = error.isServerError() ? Level.WARNING : Level.INFO;
        plugin.getLogger().log(level, error.toLogString());
    }

    /**
     * Logs an error with additional context.
     *
     * @param error   the error
     * @param context additional context information
     */
    public void logError(NovaError error, String context) {
        error.withContext("additionalInfo", context);
        logError(error);
    }

    // ==========================================
    // Convenience Methods for Common Errors
    // ==========================================

    /**
     * Sends a "not connected" error.
     */
    public void sendNotConnected(CommandSender sender) {
        sendError(sender, NovaError.serviceUnavailable());
    }

    /**
     * Sends a "no permission" error.
     */
    public void sendNoPermission(CommandSender sender) {
        sendError(sender, NovaError.forbidden());
    }

    /**
     * Sends a "no permission" error with specific permission.
     */
    public void sendNoPermission(CommandSender sender, String permission) {
        UUID playerId = playerIdOf(sender);
        sendError(sender, new NovaError(ErrorCode.FORBIDDEN,
            I18n.tr(playerId, "error.NC-403.message"),
            I18n.tr(playerId, "chat.error.need_permission", permission)));
    }

    /**
     * Sends a "player not found" error.
     */
    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        UUID playerId = playerIdOf(sender);
        sendError(sender, NovaError.notFound(I18n.tr(playerId, "chat.error.player_prefix", playerName)));
    }

    /**
     * Sends a "channel not found" error.
     */
    public void sendChannelNotFound(CommandSender sender, String channelId) {
        UUID playerId = playerIdOf(sender);
        sendError(sender, NovaError.notFound(I18n.tr(playerId, "chat.error.channel_prefix", channelId)));
    }

    /**
     * Sends a "player only" error.
     */
    public void sendPlayerOnly(CommandSender sender) {
        UUID playerId = playerIdOf(sender);
        sendError(sender, new NovaError(ErrorCode.BAD_REQUEST,
            I18n.tr(playerId, "chat.command.player_only"),
            I18n.tr(playerId, "chat.error.player_only_suggestion")));
    }

    /**
     * Sends an "invalid arguments" error.
     */
    public void sendInvalidArgs(CommandSender sender, String usage) {
        UUID playerId = playerIdOf(sender);
        NovaError error = new NovaError(ErrorCode.BAD_REQUEST,
            I18n.tr(playerId, "chat.error.invalid_args"),
            I18n.tr(playerId, "chat.error.usage_prefix", usage));
        sendError(sender, error);
    }

    /**
     * Sends a "request failed" error (generic network error).
     */
    public void sendRequestFailed(CommandSender sender) {
        sendError(sender, NovaError.serviceUnavailable());
    }

    /**
     * Sends a "muted" error with remaining time.
     */
    public void sendMuted(CommandSender sender, String remainingTime) {
        sendError(sender, NovaError.muted(remainingTime));
    }

    /**
     * Sends a "wrong password" error.
     */
    public void sendWrongPassword(CommandSender sender) {
        sendError(sender, NovaError.wrongPassword());
    }

    /**
     * Sends an "invite expired" error.
     */
    public void sendInviteExpired(CommandSender sender) {
        sendError(sender, NovaError.inviteExpired());
    }

    /**
     * Sends an "invite used" error.
     */
    public void sendInviteUsed(CommandSender sender) {
        sendError(sender, NovaError.inviteUsed());
    }

    /**
     * Sends a "world restricted" error.
     */
    public void sendWorldRestricted(CommandSender sender, String channelId) {
        UUID playerId = playerIdOf(sender);
        sendError(sender, new NovaError(ErrorCode.WORLD_RESTRICTED,
            I18n.tr(playerId, "chat.error.world_restricted_join", channelId),
            I18n.tr(playerId, "error.NC-435.suggestion")));
    }

    /**
     * Sends a "rate limited" error.
     */
    public void sendRateLimited(CommandSender sender) {
        sendError(sender, NovaError.rateLimited());
    }
}
