package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared core of the {@code /nc ignore} family of subcommands.
 *
 * <p>Validates arguments, drives {@link IgnoreListService} and renders the
 * localized receipt lines (pattern follows {@link ListCommandService}): each
 * platform command shell only forwards the raw arguments here and sends the
 * returned lines through its own message helper.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code /nc ignore <player>} — add a name to the ignore list</li>
 *   <li>{@code /nc ignore list} — show the ignore list</li>
 *   <li>{@code /nc unignore <player>} — remove a name from the ignore list</li>
 * </ul>
 *
 * <p>All copy is resolved through {@link I18n} with the player's locale
 * ({@code chat.ignore.*} keys, aligned across zh_CN / en_US).
 */
public final class IgnoreCommandService {

    /** Literal subargument that renders the ignore list. */
    public static final String LIST_ARG = "list";

    private IgnoreCommandService() {
        // Utility class — not instantiated.
    }

    /**
     * Handles {@code /nc ignore [<player>|list]}.
     *
     * @param service    the ignore list service
     * @param playerId   the invoking player's UUID (locale + list owner)
     * @param playerName the invoking player's name (for the self check)
     * @param args       the arguments after the {@code ignore} literal
     * @return localized receipt lines to send to the player
     */
    public static List<String> ignore(IgnoreListService service, UUID playerId,
                                      String playerName, String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].trim().isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.ignore.usage"));
        }
        String target = args[0].trim();
        if (LIST_ARG.equalsIgnoreCase(target)) {
            return list(service, playerId);
        }

        IgnoreListService.AddResult result = service.ignore(playerId, playerName, target);
        switch (result) {
            case ADDED:
                return List.of(I18n.tr(playerId, "chat.ignore.added", target));
            case ALREADY_IGNORED:
                return List.of(I18n.tr(playerId, "chat.ignore.already_ignored", target));
            case LIMIT_REACHED:
                return List.of(I18n.tr(playerId, "chat.ignore.limit_reached",
                        IgnoreListService.MAX_IGNORES_PER_PLAYER));
            case SELF:
            default:
                return List.of(I18n.tr(playerId, "chat.ignore.cannot_self"));
        }
    }

    /**
     * Handles {@code /nc unignore <player>}.
     *
     * @param service  the ignore list service
     * @param playerId the invoking player's UUID
     * @param args     the arguments after the {@code unignore} literal
     * @return localized receipt lines to send to the player
     */
    public static List<String> unignore(IgnoreListService service, UUID playerId, String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].trim().isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.ignore.usage"));
        }
        String target = args[0].trim();
        if (service.unignore(playerId, target)) {
            return List.of(I18n.tr(playerId, "chat.ignore.removed", target));
        }
        return List.of(I18n.tr(playerId, "chat.ignore.not_ignored", target));
    }

    /**
     * Renders the player's ignore list ({@code /nc ignore list}).
     *
     * @param service  the ignore list service
     * @param playerId the invoking player's UUID
     * @return localized lines: header + one line per entry, or the empty prompt
     */
    public static List<String> list(IgnoreListService service, UUID playerId) {
        List<String> ignored = service.listIgnored(playerId);
        if (ignored.isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.ignore.list_empty"));
        }
        List<String> lines = new ArrayList<>(ignored.size() + 1);
        lines.add(I18n.tr(playerId, "chat.ignore.list_header", ignored.size()));
        for (String name : ignored) {
            lines.add(I18n.tr(playerId, "chat.ignore.list_item", name));
        }
        return lines;
    }
}
