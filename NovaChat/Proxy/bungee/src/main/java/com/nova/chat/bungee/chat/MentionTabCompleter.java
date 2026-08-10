package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.common.chat.MentionParser;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides Tab completion for @mentions in chat.
 * 
 * When a player types @ followed by partial text and presses Tab,
 * this listener provides completions for online player names.
 * 
 * Requirements: 11.3 - THE 提及功能 SHALL 支持 Tab 补全玩家名称
 */
public class MentionTabCompleter implements Listener {

    private final NovaChatBungee plugin;
    
    /** Permission required to use mention feature */
    private static final String MENTION_PERMISSION = "novachat.feature.mention";
    
    /** Permission required to use @all mention */
    private static final String MENTION_ALL_PERMISSION = "novachat.feature.mention.all";

    /**
     * Creates a new MentionTabCompleter.
     *
     * @param plugin the plugin instance
     */
    public MentionTabCompleter(NovaChatBungee plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles Tab completion events.
     * Provides @mention completions when the player types @ followed by text in chat.
     *
     * @param event the Tab complete event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String cursor = event.getCursor();
        
        plugin.debug("Tab complete event - cursor: '" + cursor + "'");
        
        // Check if player has mention permission
        if (!player.hasPermission(MENTION_PERMISSION)) {
            plugin.debug("Player " + player.getName() + " lacks mention permission");
            return;
        }
        
        // Check if this is a chat message (not a command)
        if (cursor.startsWith("/")) {
            return;
        }
        
        // Find the last token (word being typed)
        String lastToken = getLastToken(cursor);
        
        // Check if the last token starts with @
        if (!lastToken.startsWith("@")) {
            return;
        }
        
        // Get the partial name after @
        String partialName = lastToken.substring(1).toLowerCase();
        
        plugin.debug("Processing mention completion for partial: '" + partialName + "'");
        
        // Get completions
        List<String> mentionCompletions = getMentionCompletions(player, partialName);
        
        // Add our completions
        event.getSuggestions().addAll(mentionCompletions);
        
        plugin.debug("Added " + mentionCompletions.size() + " mention completions");
    }

    /**
     * Gets the last token (word) from the cursor position.
     *
     * @param cursor the current cursor text
     * @return the last token
     */
    private String getLastToken(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return "";
        }
        
        int lastSpace = cursor.lastIndexOf(' ');
        if (lastSpace == -1) {
            return cursor;
        }
        
        return cursor.substring(lastSpace + 1);
    }

    /**
     * Gets mention completions for a partial name.
     *
     * @param sender the player requesting completions
     * @param partialName the partial name to complete (without @)
     * @return list of completion strings (with @ prefix)
     */
    public List<String> getMentionCompletions(ProxiedPlayer sender, String partialName) {
        List<String> completions = new ArrayList<>();
        
        // Add @all if player has permission and it matches
        if (sender.hasPermission(MENTION_ALL_PERMISSION)) {
            if (MentionParser.ALL_MENTION.toLowerCase().startsWith(partialName)) {
                completions.add("@" + MentionParser.ALL_MENTION);
            }
        }
        
        // Add online player names that match
        for (ProxiedPlayer onlinePlayer : plugin.getProxy().getPlayers()) {
            // Don't suggest the sender's own name
            if (onlinePlayer.equals(sender)) {
                continue;
            }
            
            String playerName = onlinePlayer.getName();
            if (playerName.toLowerCase().startsWith(partialName)) {
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
    public List<String> getAllMentionCompletions(ProxiedPlayer sender) {
        return getMentionCompletions(sender, "");
    }

    /**
     * Gets all online player names for mention suggestions.
     * Excludes the sender from the list.
     *
     * @param sender the player requesting suggestions
     * @return list of online player names
     */
    public List<String> getOnlinePlayerNames(ProxiedPlayer sender) {
        return plugin.getProxy().getPlayers().stream()
                .filter(p -> !p.equals(sender))
                .map(ProxiedPlayer::getName)
                .collect(Collectors.toList());
    }
}
