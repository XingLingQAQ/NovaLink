package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;

import java.util.UUID;

/**
 * Shared {@code /nc who} service (UX-DESIGN §8.2).
 *
 * <p>{@code /nc who [频道]} lists the online members of a channel. The
 * backend now answers a {@link com.nova.chat.common.protocol.ChannelAction#WHO}
 * request, populating {@link com.nova.chat.common.protocol.packets.ChannelActionResponsePacket#getExtra(String)}
 * with {@code members} (comma-separated display names), {@code memberCount}
 * and {@code displayName}. The client plugin sends the WHO request from the
 * platform {@code WhoCommand} and renders the asynchronous response in its
 * {@code ChannelActionResponsePacket} handler via {@link #formatMemberList}.
 *
 * <p>Because the response travels over a server-scoped backend connection (not
 * a per-player channel), the request stamps a {@code requesterId} extra so the
 * response handler can route the rendered list to the player who ran the
 * command. The originating command also shows a {@code chat.who.fetching}
 * interim prompt since the response is asynchronous.
 *
 * <p>Member-list text is resolved through {@link I18n} in the requester's
 * locale so it follows the configured default locale (or the player's
 * registered client locale when available).
 */
public final class WhoCommandService {

    private WhoCommandService() {
        // Utility class — no instances.
    }

    /**
     * Returns whether real channel-member listing is supported.
     *
     * <p>{@code true} since the backend {@code ChannelActionHandler} now
     * handles {@link com.nova.chat.common.protocol.ChannelAction#WHO}.
     *
     * @return {@code true}
     */
    public static boolean isMemberListingSupported() {
        return true;
    }

    /**
     * Returns the degraded prompt to show for {@code /nc who} when the backend
     * is unreachable (e.g. the plugin is not connected / authenticated), in the
     * default locale.
     *
     * @return the unavailable prompt (plain text)
     */
    public static String getUnavailablePrompt() {
        return I18n.tr("chat.who.unavailable");
    }

    /**
     * Returns the degraded prompt resolved in a specific requester's locale.
     *
     * @param requesterId the requesting player UUID (may be {@code null} for
     *                    console, which uses the default locale)
     * @return the localized unavailable prompt
     */
    public static String getUnavailablePrompt(UUID requesterId) {
        return I18n.tr(requesterId, "chat.who.unavailable");
    }

    /**
     * Returns the interim "fetching" prompt shown when a WHO request is sent.
     *
     * @param channelId the channel being queried (may be blank when resolving
     *                  from the player's active channel)
     * @return the localized fetching prompt
     */
    public static String getFetchingPrompt(String channelId) {
        String id = channelId != null ? channelId : "";
        return I18n.tr("chat.who.fetching", id);
    }

    /**
     * Formats the member-list result for display to the requester. Resolved in
     * the requester's locale.
     *
     * <p>Layout: a header line naming the channel + count, followed by the
     * comma-separated member names; an "empty" line is substituted when the
     * backend reports zero online members.
     *
     * @param requesterId the requesting player UUID (may be {@code null})
     * @param channelId   the channel id returned by the backend
     * @param displayName the channel display name (may be blank → channelId)
     * @param membersCsv  comma-separated member names from the backend
     * @param memberCount the online member count string from the backend
     * @return the formatted multi-line message (color codes embedded)
     */
    public static String formatMemberList(UUID requesterId,
                                          String channelId,
                                          String displayName,
                                          String membersCsv,
                                          String memberCount) {
        String headerName = (displayName != null && !displayName.isBlank())
                ? displayName
                : (channelId != null ? channelId : "");
        int count = parseCount(memberCount);
        if (count <= 0 || membersCsv == null || membersCsv.isBlank()) {
            return I18n.tr(requesterId, "chat.who.list_header", headerName, 0)
                    + "\n" + I18n.tr(requesterId, "chat.who.list_empty");
        }
        return I18n.tr(requesterId, "chat.who.list_header", headerName, count)
                + "\n" + I18n.tr(requesterId, "chat.who.list_body", membersCsv);
    }

    /**
     * Formats a failure response for the requester: maps the backend error code
     * through the shared error formatter so WHO errors share the same copy as
     * other channel actions.
     *
     * @param requesterId the requesting player UUID (may be {@code null})
     * @param errorCode   the backend error code (e.g. "NC-404")
     * @return the localized error text, or the code itself if unknown
     */
    public static String formatError(UUID requesterId, String errorCode) {
        return com.nova.chat.client.error.ErrorMessageFormatter.format(errorCode);
    }

    private static int parseCount(String memberCount) {
        if (memberCount == null || memberCount.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(memberCount.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
