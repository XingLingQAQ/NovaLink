package com.nova.chat.client.command;

/**
 * Shared {@code /nc who} service (UX-DESIGN §8.2).
 *
 * <p>{@code /nc who [频道]} lists the online members of a channel. This
 * requires the backend to push channel-member data to MC clients, which
 * the current backend protocol does not provide:
 * <ul>
 *   <li>There is no {@code ChannelMembersPacket} / {@code ChannelInfoPacket}
 *       in {@code novachat-common}.</li>
 *   <li>{@link com.nova.chat.common.protocol.packets.ChannelActionResponsePacket}
 *       carries an {@code extra} string-string map, but the backend only
 *       populates it with password / invite / target-id fields — never member
 *       lists.</li>
 * </ul>
 *
 * <p>Per the design doc, when the backend does not supply the data we
 * <b>do not fabricate it</b>; {@code /nc who} degrades to an explanatory
 * prompt. This class owns that prompt so every platform renders the same
 * copy. When a future backend protocol adds member delivery, platforms can
 * stop calling {@link #getUnavailablePrompt()} and render the real list.
 *
 * <p>TODO(backend): add a {@code ChannelMembersPacket} (or populate
 * {@code ChannelActionResponsePacket.extra} with member names) and wire
 * real member listing here.
 */
public final class WhoCommandService {

    /**
     * Prompt shown when channel-member data is not available from the
     * backend (the current state). Plain text, no color codes — platforms
     * apply their own error styling.
     */
    public static final String UNAVAILABLE_PROMPT =
            "频道成员查询暂不可用（需后端支持）";

    private WhoCommandService() {
        // Utility class — no instances.
    }

    /**
     * Returns whether real channel-member listing is supported.
     *
     * <p>Always {@code false} today; will flip to {@code true} once the
     * backend protocol delivers member data.
     *
     * @return {@code false} until backend support lands
     */
    public static boolean isMemberListingSupported() {
        return false;
    }

    /**
     * Returns the degraded prompt to show for {@code /nc who}.
     *
     * @return the unavailable prompt (plain text)
     */
    public static String getUnavailablePrompt() {
        return UNAVAILABLE_PROMPT;
    }
}
