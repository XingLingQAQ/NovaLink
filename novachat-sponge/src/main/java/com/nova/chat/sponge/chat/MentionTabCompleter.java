package com.nova.chat.sponge.chat;

import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.common.chat.MentionParser;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.message.PlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides Tab completion suggestions for @mentions in chat.
 * 
 * Note: Sponge API 8+ does not have a direct chat Tab completion event like Bukkit.
 * This class provides utility methods that can be used by command completers
 * or integrated with chat preview systems.
 * 
 * Requirements: 11.3 - THE 提及功能 SHALL 支持 Tab 补全玩家名称
 */
public class MentionTabCompleter {

    private final NovaChatSponge plugin;
    
    /** Permission required to use mention feature */
    private static final String MENTION_PERMISSION = "novachat.feature.mention";
    
    /** Permission required to use @all mention */
    private static final String MENTION_ALL_PERMISSION = "novachat.feature.mention.all";

    /**
     * Creates a new MentionTabCompleter.
     *
     * @param plugin the plugin instance
     */
    public MentionTabCompleter(NovaChatSponge plugin) {
        this.plugin = plugin;
    }

    /**
     * Gets mention completions for a partial name.
     * This method can be called from command completers or other systems.
     *
     * @param sender the player requesting completions
     * @param partialName the partial name to complete (without @)
     * @return list of completion strings (with @ prefix)
     */
    public List<String> getMentionCompletions(ServerPlayer sender, String partialName) {
        List<String> completions = new ArrayList<>();
        
        // Check if player has mention permission
        if (!sender.hasPermission(MENTION_PERMISSION)) {
            plugin.debug("Player " + sender.name() + " lacks mention permission");
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
        for (ServerPlayer onlinePlayer : Sponge.server().onlinePlayers()) {
            // Don't suggest the sender's own name
            if (onlinePlayer.equals(sender)) {
                continue;
            }
            
            String playerName = onlinePlayer.name();
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
    public List<String> getAllMentionCompletions(ServerPlayer sender) {
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
    public List<String> getCompletionsForMessage(ServerPlayer sender, String message) {
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
    public List<String> getOnlinePlayerNames(ServerPlayer sender) {
        return Sponge.server().onlinePlayers().stream()
                .filter(p -> !p.equals(sender))
                .map(ServerPlayer::name)
                .collect(Collectors.toList());
    }
}
