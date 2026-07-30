package com.nova.chat.bukkit.error;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.client.error.ErrorCode;
import org.bukkit.command.CommandSender;

import java.util.logging.Level;

/**
 * Handles error message display and logging for NovaChat.
 * Provides formatted error messages with error codes and solution suggestions.
 * 
 * Requirements: 27.1-27.4
 */
public class ErrorMessageHandler {

    private final NovaChatBukkit plugin;
    private final MessageHelper messageHelper;

    /** Error message format with code */
    private static final String ERROR_FORMAT = "&c%s &8| &7%s";
    
    /** Suggestion format */
    private static final String SUGGESTION_FORMAT = "  &7提示: &f%s";
    
    /** Server error additional info */
    private static final String SERVER_ERROR_INFO = "  &8错误ID: &7%s";

    public ErrorMessageHandler(NovaChatBukkit plugin, MessageHelper messageHelper) {
        this.plugin = plugin;
        this.messageHelper = messageHelper;
    }

    /**
     * Sends an error message to the sender with formatted error code and suggestion.
     *
     * @param sender the command sender
     * @param error  the error to display
     */
    public void sendError(CommandSender sender, NovaError error) {
        // Send main error message
        String errorLine = String.format(ERROR_FORMAT, error.getCode(), error.getMessage());
        messageHelper.sendRaw(sender, "&8[&cNovaChat&8]&r " + errorLine);
        
        // Send suggestion
        String suggestion = error.getSuggestion();
        if (suggestion != null && !suggestion.isEmpty()) {
            messageHelper.sendRaw(sender, String.format(SUGGESTION_FORMAT, suggestion));
        }
        
        // For server errors, show error ID for tracking
        if (error.isServerError()) {
            String errorId = error.getErrorId().toString().substring(0, 8);
            messageHelper.sendRaw(sender, String.format(SERVER_ERROR_INFO, errorId));
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
            sendError(sender, new NovaError(errorCode,
                "未知错误: " + code,
                "请联系管理员并提供此错误代码"));
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
            sendError(sender, new NovaError(errorCode,
                code + ": " + message,
                "请联系管理员并提供此错误代码"));
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
        sendError(sender, new NovaError(ErrorCode.FORBIDDEN, 
            "权限不足", 
            "需要权限: " + permission));
    }

    /**
     * Sends a "player not found" error.
     */
    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        sendError(sender, NovaError.notFound("玩家 " + playerName));
    }

    /**
     * Sends a "channel not found" error.
     */
    public void sendChannelNotFound(CommandSender sender, String channelId) {
        sendError(sender, NovaError.notFound("频道 " + channelId));
    }

    /**
     * Sends a "player only" error.
     */
    public void sendPlayerOnly(CommandSender sender) {
        sendError(sender, new NovaError(ErrorCode.BAD_REQUEST, 
            "此命令只能由玩家执行", 
            "请在游戏内使用此命令"));
    }

    /**
     * Sends an "invalid arguments" error.
     */
    public void sendInvalidArgs(CommandSender sender, String usage) {
        NovaError error = new NovaError(ErrorCode.BAD_REQUEST, 
            "参数错误", 
            "用法: " + usage);
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
        sendError(sender, new NovaError(ErrorCode.WORLD_RESTRICTED, 
            "无法加入频道 " + channelId, 
            "该频道仅在特定世界可用，请前往对应世界"));
    }

    /**
     * Sends a "rate limited" error.
     */
    public void sendRateLimited(CommandSender sender) {
        sendError(sender, NovaError.rateLimited());
    }
}
