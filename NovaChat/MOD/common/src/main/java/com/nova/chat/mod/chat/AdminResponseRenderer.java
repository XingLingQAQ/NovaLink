package com.nova.chat.mod.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.mod.platform.Platform;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Renders {@link AdminActionResponsePacket}s back to the operator who issued
 * {@code /nc auth}, {@code /nc announce} or {@code /nc title}.
 *
 * <p>Mirrors the Folia {@code AdminActionResponseHandler} and the Bukkit
 * {@code NetworkClient#handleAdminActionResponse} branches: success copy, the
 * shared error text, and the NC-403 "super admin session required" guidance.
 * Without it those commands send their request and report nothing back — the
 * operator cannot tell whether the super-admin session was established or why
 * an announcement failed.
 *
 * <p>Runs on the mod platform's server thread (the mod {@code NetworkClient}
 * hops every inbound handler there), so touching {@link Platform#sendMessage}
 * is safe.
 */
public final class AdminResponseRenderer implements Consumer<AdminActionResponsePacket> {

    private final Platform platform;
    private final java.util.function.Function<UUID, UUID> pendingRequestConsumer;
    private final Consumer<String> consoleSink;

    /**
     * @param platform               the platform bridge used to reach the sender
     * @param pendingRequestConsumer resolves the response's requestId to the
     *                               originating sender (removing the entry), or
     *                               null when the request was not tracked
     * @param consoleSink            receives the rendered line when the sender
     *                               was the console sentinel (there is no player
     *                               to message), or null to drop it
     */
    public AdminResponseRenderer(Platform platform,
                                 java.util.function.Function<UUID, UUID> pendingRequestConsumer,
                                 Consumer<String> consoleSink) {
        this.platform = platform;
        this.pendingRequestConsumer = pendingRequestConsumer;
        this.consoleSink = consoleSink;
    }

    @Override
    public void accept(AdminActionResponsePacket response) {
        if (response == null || pendingRequestConsumer == null) {
            return;
        }
        UUID senderId = pendingRequestConsumer.apply(response.getRequestId());
        if (senderId == null) {
            // Unsolicited, already consumed, or sent before tracking: nothing to render.
            return;
        }

        boolean superAdminRequired = isSuperAdminRequired(response);
        String line;
        if (response.isSuccess()) {
            String message = response.getMessage();
            line = message != null && !message.isEmpty()
                    ? message : I18n.tr(senderId, "chat.action.success");
        } else if (superAdminRequired) {
            line = I18n.tr(senderId, "chat.error.super_admin_required")
                    + " " + I18n.tr(senderId, "chat.error.super_admin_required_suggestion");
        } else {
            line = errorText(response, senderId);
        }

        if (NovaConstants.CONSOLE_SENTINEL_UUID.equals(senderId.toString())) {
            if (consoleSink != null) {
                consoleSink.accept(line);
            }
            return;
        }
        if (platform != null) {
            platform.sendMessage(senderId, line);
        }
    }

    /**
     * Error copy, matching the Folia/Bukkit reference: the shared
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
     * ({@code AdminActionHandler#handleStatus}).
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
