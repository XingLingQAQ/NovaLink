package com.nova.chat.client.command;

import com.nova.chat.client.channel.KnownChannelRegistry;

import java.util.List;
import java.util.Set;

/**
 * Formats the {@code /nc list} channel-discovery output (UX-DESIGN §2.2).
 *
 * <p>Pure function: combines the backend-advertised channel roster
 * ({@link KnownChannelRegistry}) with the player's joined channels and renders
 * a one-line-per-channel list with a join marker. No platform / IO dependency,
 * so each platform command can call it and send the result through its own
 * message helper.
 *
 * <p>Channel IDs from the registry are listed sorted; any joined channel that
 * the registry does not yet advertise (e.g. a freshly joined channel before the
 * next ConfigSync) is appended after the known ones so it still shows up.
 */
public final class ListCommandService {

    /** Marker prepended to a channel the player has already joined. */
    private static final String JOINED_MARKER = "&a✓&r";

    /** Marker prepended to a channel the player has not joined. */
    private static final String NOT_JOINED_MARKER = "&7○&r";

    /** Prompt shown when the backend has not advertised any channels yet. */
    private static final String EMPTY_PROMPT = "暂无已知频道，请等待服务器下发频道列表";

    private ListCommandService() {
        // Utility class — not instantiated.
    }

    /**
     * Formats the list of known channels with join markers.
     *
     * @param registry       the backend-advertised channel roster (may be empty)
     * @param joinedChannels the player's joined channels (null treated as empty)
     * @return formatted lines, or a single-element list with the empty prompt
     */
    public static List<String> formatChannelList(KnownChannelRegistry registry, Set<String> joinedChannels) {
        List<String> known = registry != null ? registry.getKnownChannelIds(null) : List.of();
        Set<String> joined = joinedChannels != null ? joinedChannels : Set.of();

        if (known.isEmpty() && joined.isEmpty()) {
            return List.of(EMPTY_PROMPT);
        }

        List<String> lines = new java.util.ArrayList<>(known.size() + joined.size());
        for (String channelId : known) {
            lines.add(formatLine(channelId, joined.contains(channelId)));
        }

        // Append joined-but-unknown channels so the player's own membership is
        // always reflected even when the backend roster has not caught up.
        // `known` is a List, so build a Set for O(1) membership here.
        java.util.Set<String> knownSet = new java.util.HashSet<>(known);
        for (String channelId : joined) {
            if (!knownSet.contains(channelId)) {
                lines.add(formatLine(channelId, true));
            }
        }

        return lines;
    }

    private static String formatLine(String channelId, boolean isJoined) {
        String marker = isJoined ? JOINED_MARKER : NOT_JOINED_MARKER;
        return marker + " &f" + channelId;
    }
}
