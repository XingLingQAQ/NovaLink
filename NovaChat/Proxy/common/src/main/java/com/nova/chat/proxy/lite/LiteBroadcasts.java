package com.nova.chat.proxy.lite;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * ANNOUNCE and TITLE for {@code lite} mode.
 *
 * <p>Both commands send an {@link AdminActionPacket} with action
 * {@code STATUS} and a {@code type} extra rather than a packet of their own, so
 * the two live together here: the {@code type} extra is what selects the
 * branch, and splitting them across classes would duplicate that dispatch.
 *
 * <p>This class only validates and converts. Rendering stays where it already
 * lives — {@code ProxyChatCore.handleIncomingMessage} for an announcement and
 * {@code handleTitle} for a title — so recipient selection (players whose
 * active channel matches) is never reimplemented.
 */
final class LiteBroadcasts {

    /** The node the platform title command gates on. */
    private static final String TITLE_PERMISSION = "novachat.title";

    /** The node the platform announce command gates on. */
    private static final String ANNOUNCE_PERMISSION = "novachat.announce";

    /** Prefix the backend puts in front of an announcement body. */
    static final String ANNOUNCE_PREFIX = "【公告】 ";

    /** Shown as the sender when the request carries no operator name. */
    private static final String ANNOUNCE_FALLBACK_NAME = "公告";

    private final ProxyPlatform platform;
    private final LitePermissions permissions;

    /**
     * @param platform the platform adapter (logging)
     * @param permissions the moderation gate
     */
    LiteBroadcasts(ProxyPlatform platform, LitePermissions permissions) {
        this.platform = platform;
        this.permissions = permissions;
    }

    /**
     * Dispatches one admin action to its broadcast subtype.
     *
     * <p>An unknown or absent type answers a plain STATUS success instead of an
     * error, matching the backend, so a future subtype does not start failing
     * the whole action.
     *
     * @param packet the admin action
     * @param channels the live channel definitions
     * @return the packets to fan out
     */
    List<Packet> handle(AdminActionPacket packet, Map<String, LiteChannelConfig> channels) {
        String type = packet.getExtra("type");
        String normalized = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "TITLE" -> title(packet, channels);
            case "ANNOUNCE" -> announce(packet, channels);
            default -> List.of(AdminActionResponsePacket.success(
                    AdminAction.STATUS, "System status: OK"));
        };
    }

    /**
     * Converts a TITLE action into a {@link TitlePacket}.
     *
     * <p>Timing overrides are optional and only applied when they parse as
     * non-negative, so a malformed extra cannot make the title invisible by
     * setting a zero duration.
     */
    private List<Packet> title(AdminActionPacket packet, Map<String, LiteChannelConfig> channels) {
        String channelId = packet.getTarget();
        String title = packet.getExtra("title");
        if (channelId == null || channelId.isBlank()) {
            return failure(ErrorCode.BAD_REQUEST, "Channel ID is required");
        }
        if (title == null || title.isBlank()) {
            return failure(ErrorCode.BAD_REQUEST, "Title is required");
        }
        if (!channels.containsKey(channelId)) {
            return failure(ErrorCode.NOT_FOUND, "Channel not found: " + channelId);
        }
        UUID operatorId = packet.getPlayerId();
        if (!permissions.canModerate(operatorId, TITLE_PERMISSION)) {
            return failure(ErrorCode.FORBIDDEN, "Insufficient permissions");
        }

        TitlePacket out = new TitlePacket(channelId, title, packet.getExtra("subtitle"),
                operatorId);
        out.setRequestId(packet.getRequestId());
        applyTicks(packet, "fadeIn", out::setFadeIn);
        applyTicks(packet, "stay", out::setStay);
        applyTicks(packet, "fadeOut", out::setFadeOut);

        platform.logDebug("Lite authority sent a title to " + channelId
                + " by " + operatorId + " (" + title + ")");
        return List.of(out, AdminActionResponsePacket.success(AdminAction.STATUS, "Title sent"));
    }

    /**
     * Converts an ANNOUNCE action into a chat line for the channel.
     *
     * <p>The backend routes this through {@code messageRouter}; lite has no
     * router, so it builds the {@link ChatMessagePacket} the chat core already
     * knows how to fan out. The prefix matches the backend so an operator sees
     * identical output in lite and relay mode.
     */
    private List<Packet> announce(AdminActionPacket packet, Map<String, LiteChannelConfig> channels) {
        String channelId = packet.getTarget();
        String content = packet.getExtra("content");
        if (channelId == null || channelId.isBlank()) {
            return failure(ErrorCode.BAD_REQUEST, "Channel ID is required");
        }
        if (content == null || content.isBlank()) {
            return failure(ErrorCode.BAD_REQUEST, "Announcement content is required");
        }
        LiteChannelConfig channel = channels.get(channelId);
        if (channel == null) {
            return failure(ErrorCode.NOT_FOUND, "Channel not found: " + channelId);
        }
        UUID operatorId = packet.getPlayerId();
        if (!permissions.canModerate(operatorId, ANNOUNCE_PERMISSION)) {
            return failure(ErrorCode.FORBIDDEN, "Insufficient permissions");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_MESSAGE_CONTENT) {
            return failure(ErrorCode.BAD_REQUEST, "Announcement is too long");
        }

        String operatorName = packet.getExtra("operatorName");
        if (operatorName == null || operatorName.isBlank()) {
            operatorName = ANNOUNCE_FALLBACK_NAME;
        }
        ChatMessagePacket out = new ChatMessagePacket(
                operatorId, operatorName, null, channelId, ANNOUNCE_PREFIX + content);
        out.setRequestId(packet.getRequestId());
        out.addPlaceholder("player", operatorName);
        out.addPlaceholder("display_name", operatorName);
        out.addPlaceholder("channel_name", channel.getDisplayName());

        platform.logDebug("Lite authority announced to " + channelId
                + " by " + operatorId + " (" + content.length() + " chars)");
        return List.of(out, AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent"));
    }

    private static List<Packet> failure(ErrorCode code, String message) {
        return List.of(AdminActionResponsePacket.failure(
                AdminAction.STATUS, code.getCode(), message));
    }

    private static void applyTicks(AdminActionPacket packet, String key, IntConsumer setter) {
        String raw = packet.getExtra(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value >= 0) {
                setter.accept((int) Math.min(Integer.MAX_VALUE, value));
            }
        } catch (NumberFormatException ignored) {
            // Keep the default rather than setting a zero-length title.
        }
    }
}
