package com.nova.chat.folia.network;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Renders {@code AdminActionResponsePacket}s back to whoever issued the command.
 *
 * <p>Without this the Folia {@code /nc announce}, {@code /nc title} and
 * {@code /nc auth} shells send their request and then report nothing: the
 * backend answers on {@code AdminActionResponsePacket}, which previously had no
 * handler registered. Bukkit's {@code NetworkClient#handleAdminActionResponse}
 * is the reference; this class mirrors its branches — success, error, and the
 * NC-403 "super admin session required" guidance — plus the console/RCON
 * branch for the all-zeros sentinel sender.
 *
 * <p><b>Threading.</b> The response arrives on the async scheduler (see
 * {@link AsyncNetworkClient#registerHandler}), so this class never touches the
 * Bukkit API directly. Player-facing output hops to that player's region thread
 * through {@link FoliaSchedulerAdapter#runForPlayer}; console output needs no
 * region and is logged straight from the async thread.
 */
public final class AdminActionResponseHandler implements Consumer<AdminActionResponsePacket> {

    /** Console/RCON-sentinel prefix on rendered console lines. */
    private static final String CONSOLE_TAG = "[NovaChat console] ";

    private final NovaChatFolia plugin;
    private final FoliaSchedulerAdapter scheduler;
    private final AsyncNetworkClient networkClient;
    private final MessageHelper messageHelper;
    private final Logger logger;

    /**
     * @param plugin        the plugin instance
     * @param scheduler     the Folia scheduler used for the region-thread hop
     * @param networkClient the client holding the pending-request correlation
     */
    public AdminActionResponseHandler(NovaChatFolia plugin, FoliaSchedulerAdapter scheduler,
                                      AsyncNetworkClient networkClient) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.networkClient = networkClient;
        this.messageHelper = plugin.getMessageHelper();
        this.logger = plugin.getLogger();
    }

    @Override
    public void accept(AdminActionResponsePacket response) {
        UUID senderId = networkClient.consumePendingAdminRequest(response.getRequestId());
        if (senderId == null) {
            // Unsolicited, already consumed, or a response whose request went
            // out before this client tracked admin actions. Nothing to render,
            // and above all nothing to throw.
            plugin.debug("Received AdminActionResponsePacket with no pending request: " + response);
            return;
        }

        if (NovaConstants.CONSOLE_SENTINEL_UUID.equals(senderId.toString())) {
            renderToConsole(response, senderId);
            return;
        }

        Player player = plugin.getServer().getPlayer(senderId);
        if (player == null) {
            plugin.debug("AdminActionResponsePacket target player not online: " + senderId);
            return;
        }

        // Resolve the copy before the hop; I18n and ErrorCode lookups do not
        // touch the Bukkit API, so they stay on the async thread.
        final String successText = successText(response, senderId);
        final String errorText = errorText(response, senderId);
        final String guidanceText = I18n.tr(senderId, "chat.error.super_admin_required");
        final String suggestionText = I18n.tr(senderId, "chat.error.super_admin_required_suggestion");
        final boolean forbidden = isSuperAdminRequired(response);

        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (response.isSuccess()) {
                messageHelper.sendSuccess(player, successText);
                return;
            }
            messageHelper.sendError(player, forbidden ? guidanceText : errorText);
            if (forbidden) {
                messageHelper.sendSuggestion(player, suggestionText);
            }
        });
    }

    /**
     * Console/RCON branch: there is no {@link Player} behind the all-zeros
     * sentinel, so the outcome goes to the server log instead of being dropped.
     * No region hop is involved.
     */
    private void renderToConsole(AdminActionResponsePacket response, UUID senderId) {
        if (response.isSuccess()) {
            logger.info(CONSOLE_TAG + successText(response, senderId));
            return;
        }
        if (isSuperAdminRequired(response)) {
            logger.warning(CONSOLE_TAG + I18n.tr((UUID) null, "chat.error.super_admin_required"));
            logger.warning(CONSOLE_TAG
                    + I18n.tr((UUID) null, "chat.error.super_admin_required_suggestion"));
            return;
        }
        logger.warning(CONSOLE_TAG + errorText(response, senderId));
    }

    /**
     * Success copy: the backend's message when it sent one, otherwise the shared
     * generic key.
     */
    private static String successText(AdminActionResponsePacket response, UUID senderId) {
        String message = response.getMessage();
        return message != null && !message.isEmpty()
                ? message : I18n.tr(senderId, "chat.action.success");
    }

    /**
     * Error copy, matching the Bukkit reference: the shared
     * {@link ErrorCode} text (with the backend's message substituted when
     * supplied) for a known code, otherwise the raw code so operators can still
     * diagnose it.
     */
    private static String errorText(AdminActionResponsePacket response, UUID senderId) {
        String code = response.getErrorCode();
        String message = response.getMessage();
        boolean hasMessage = message != null && !message.isEmpty();

        if (code == null || code.isEmpty()) {
            return hasMessage ? message : I18n.tr(senderId, "chat.action.failed");
        }

        ErrorCode resolved = ErrorCode.fromCode(code);
        if (!resolved.getCode().equals(code)) {
            // Unknown to the shared enum: keep the raw backend code visible.
            return "[" + code + "] "
                    + (hasMessage ? message : I18n.tr(senderId, "chat.action.failed"));
        }
        return hasMessage
                ? ErrorMessageFormatter.format(resolved, message)
                : ErrorMessageFormatter.format(resolved);
    }

    /**
     * True when the backend rejected a STATUS (ANNOUNCE/TITLE) request with
     * NC-403, which is how it signals a missing super-admin session
     * ({@code AdminActionHandler#handleStatus}). The caller renders the
     * "run /nc auth" guidance instead of the generic FORBIDDEN text.
     *
     * @param response the admin action response
     * @return true if this is the super-admin-session-required path
     */
    static boolean isSuperAdminRequired(AdminActionResponsePacket response) {
        return !response.isSuccess()
                && response.getAction() == AdminAction.STATUS
                && "NC-403".equals(response.getErrorCode());
    }
}
