package com.nova.chat.velocity.chat;

import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.common.chat.MentionParser;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides Tab completion suggestions for @mentions in chat.
 *
 * <p>GAP-1 fix: Velocity 4.1.0 <strong>does</strong> expose
 * {@link TabCompleteEvent} (com.velocitypowered.api.event.player.TabCompleteEvent),
 * with {@code getPlayer()}, {@code getPartialMessage()} and a mutable
 * {@code getSuggestions()} list. The earlier class-level note claiming "Velocity
 * does not have a direct chat Tab complete event" was incorrect — that left the
 * instance created in {@link NovaChatVelocity#registerListeners()} unregistered
 * and the @mention Tab completion completely non-functional.
 *
 * <p>This class is now a Velocity event listener: it subscribes to
 * {@link TabCompleteEvent} at {@link PostOrder#LATE} (so other plugins can
 * populate first) and, when the partial message starts with {@code @}, appends
 * {@code @<name>} / {@code @all} suggestions for online players the sender may
 * mention. It also still exposes the pure helper methods
 * ({@link #getMentionCompletions}, {@link #extractPartialMention},
 * {@link #getCompletionsForMessage}) so command completers and tests can reuse
 * the logic without an event.
 *
 * <p>Registration is performed by
 * {@code NovaChatVelocity.registerListeners()} via
 * {@code server.getEventManager().register(this, mentionTabCompleter)}.
 *
 * <p>Thread hop: {@link TabCompleteEvent} fires on the Netty event loop. The
 * suggestion computation only reads the player list (snapshot via
 * {@code getAllPlayers()}) and the sender's permissions, both thread-safe on
 * Velocity. The shared {@code playerStates} set is not touched here, so no main
 * thread hop is needed; this mirrors how Velocity's own plugins handle the
 * event.
 *
 * <p>Requirements: 11.3 - THE 提及功能 SHALL 支持 Tab 补全玩家名称
 */
public class MentionTabCompleter {

    private final NovaChatVelocity plugin;

    /** Permission required to use mention feature */
    private static final String MENTION_PERMISSION = "novachat.feature.mention";

    /** Permission required to use @all mention */
    private static final String MENTION_ALL_PERMISSION = "novachat.feature.mention.all";

    /**
     * Creates a new MentionTabCompleter.
     *
     * @param plugin the plugin instance
     */
    public MentionTabCompleter(NovaChatVelocity plugin) {
        this.plugin = plugin;
    }

    /**
     * Subscribes to {@link TabCompleteEvent} to append @mention candidates when
     * the player is typing a mention prefix.
     *
     * <p>Only augments suggestions when:
     * <ul>
     *   <li>the partial message starts with {@code @} (mention in progress), and</li>
     *   <li>the sender holds {@code novachat.feature.mention}.</li>
     * </ul>
     * Existing suggestions from other plugins are preserved; new candidates are
     * appended (de-duplicated against the existing list so we never double-add
     * a name that another completer already proposed).
     *
     * @param event the tab complete event
     */
    @Subscribe(order = PostOrder.LATE)
    public void onTabComplete(TabCompleteEvent event) {
        String partial = event.getPartialMessage();
        if (partial == null || !partial.startsWith("@")) {
            return;
        }
        Player sender = event.getPlayer();
        if (!sender.hasPermission(MENTION_PERMISSION)) {
            plugin.debug("Player " + sender.getUsername() + " lacks mention permission");
            return;
        }
        // Strip the leading '@' for matching, but keep the '@' prefix on the
        // emitted suggestions so they read as mentions when inserted.
        String partialName = partial.length() > 1 ? partial.substring(1) : "";
        List<String> mentionSuggestions = getMentionCompletions(sender, partialName);
        if (mentionSuggestions.isEmpty()) {
            return;
        }
        List<String> current = new ArrayList<>(event.getSuggestions());
        for (String suggestion : mentionSuggestions) {
            if (!current.contains(suggestion)) {
                current.add(suggestion);
            }
        }
        // Replace the suggestion list with the augmented one. Velocity's
        // TabCompleteEvent exposes a mutable list; we mutate it in place when
        // possible, and fall back to clearing + addAll for safety.
        event.getSuggestions().clear();
        event.getSuggestions().addAll(current);
    }

    /**
     * Gets mention completions for a partial name.
     * This method can be called from command completers or other systems.
     *
     * @param sender the player requesting completions
     * @param partialName the partial name to complete (without @)
     * @return list of completion strings (with @ prefix)
     */
    public List<String> getMentionCompletions(Player sender, String partialName) {
        List<String> completions = new ArrayList<>();

        // Check if player has mention permission
        if (!sender.hasPermission(MENTION_PERMISSION)) {
            plugin.debug("Player " + sender.getUsername() + " lacks mention permission");
            return completions;
        }

        String lowerPartial = partialName.toLowerCase();

        // Add @all if player has permission and it matches
        if (sender.hasPermission(MENTION_ALL_PERMISSION)) {
            if (MentionParser.ALL_MENTION.toLowerCase().startsWith(lowerPartial)) {
                completions.add("@" + MentionParser.ALL_MENTION);
            }
        }

        // Add online player names that match
        for (Player onlinePlayer : plugin.getServer().getAllPlayers()) {
            // Don't suggest the sender's own name
            if (onlinePlayer.equals(sender)) {
                continue;
            }

            String playerName = onlinePlayer.getUsername();
            if (playerName.toLowerCase().startsWith(lowerPartial)) {
                completions.add("@" + playerName);
            }
        }

        return completions;
    }

    /**
     * Gets all possible mention completions (for empty partial).
     *
     * @param sender the player requesting completions
     * @return list of all possible mention completions
     */
    public List<String> getAllMentionCompletions(Player sender) {
        return getMentionCompletions(sender, "");
    }

    /**
     * Extracts the partial mention from a chat message.
     * Looks for the last @ symbol and returns the text after it.
     *
     * @param message the chat message
     * @return the partial mention text (without @), or null if no @ found
     */
    public String extractPartialMention(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        int lastAtIndex = message.lastIndexOf('@');
        if (lastAtIndex == -1) {
            return null;
        }

        // Get text after @
        String afterAt = message.substring(lastAtIndex + 1);

        // Check if there's a space after the @ (completed mention)
        int spaceIndex = afterAt.indexOf(' ');
        if (spaceIndex != -1) {
            return null; // Already completed
        }

        return afterAt;
    }

    /**
     * Gets completions for a chat message being typed.
     * Automatically extracts the partial mention from the message.
     *
     * @param sender the player typing
     * @param message the current chat message
     * @return list of completion suggestions, or empty list if not applicable
     */
    public List<String> getCompletionsForMessage(Player sender, String message) {
        String partial = extractPartialMention(message);
        if (partial == null) {
            return new ArrayList<>();
        }
        return getMentionCompletions(sender, partial);
    }

    /**
     * Gets all online player names for mention suggestions.
     * Excludes the sender from the list.
     *
     * @param sender the player requesting suggestions
     * @return list of online player names
     */
    public List<String> getOnlinePlayerNames(Player sender) {
        return plugin.getServer().getAllPlayers().stream()
                .filter(p -> !p.equals(sender))
                .map(Player::getUsername)
                .collect(Collectors.toList());
    }
}
