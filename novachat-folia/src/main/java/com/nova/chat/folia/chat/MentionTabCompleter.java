package com.nova.chat.folia.chat;

import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.common.chat.MentionParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provides Tab completion for @mentions in chat.
 * 
 * When a player types @ followed by partial text and presses Tab,
 * this listener provides completions for online player names.
 * 
 * Requirements: 11.3 - THE 提及功能 SHALL 支持 Tab 补全玩家名称
 */
public class MentionTabCompleter implements Listener {

    private final NovaChatFolia plugin;
    
    /** Permission required to use mention feature */
    private static final String MENTION_PERMISSION = "novachat.feature.mention";
    
    /** Permission required to use @all mention */
    private static final String MENTION_ALL_PERMISSION = "novachat.feature.mention.all";

    /**
     * Creates a new MentionTabCompleter.
     *
     * @param plugin the plugin instance
     */
    public MentionTabCompleter(NovaChatFolia plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles chat Tab completion events.
     * Provides @mention completions when the player types @ followed by text.
     *
     * @param event the chat Tab complete event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onChatTabComplete(PlayerChatTabCompleteEvent event) {
        Player player = event.getPlayer();
        String chatMessage = event.getChatMessage();
        String lastToken = event.getLastToken();
        
        plugin.debug("Tab complete event - message: '" + chatMessage + "', lastToken: '" + lastToken + "'");
        
        // Check if player has mention permission
        if (!player.hasPermission(MENTION_PERMISSION)) {
            plugin.debug("Player " + player.getName() + " lacks mention permission");
            return;
        }
        
        // Check if the last token starts with @
        if (!lastToken.startsWith("@")) {
            return;
        }
        
        // Get the partial name after @
        String partialName = lastToken.substring(1).toLowerCase();
        
        plugin.debug("Processing mention completion for partial: '" + partialName + "'");
        
        // Get completions
        Collection<String> completions = event.getTabCompletions();
        List<String> mentionCompletions = getMentionCompletions(player, partialName);
        
        // Add our completions
        completions.addAll(mentionCompletions);
        
        plugin.debug("Added " + mentionCompletions.size() + " mention completions");
    }

    /**
     * Gets mention completions for a partial name.
     *
     * @param sender the player requesting completions
     * @param partialName the partial name to complete (without @)
     * @return list of completion strings (with @ prefix)
     */
    public List<String> getMentionCompletions(Player sender, String partialName) {
        List<String> completions = new ArrayList<>();
        
        // Add @all if player has permission and it matches
        if (sender.hasPermission(MENTION_ALL_PERMISSION)) {
            if (MentionParser.ALL_MENTION.toLowerCase().startsWith(partialName)) {
                completions.add("@" + MentionParser.ALL_MENTION);
            }
        }
        
        // Add online player names that match
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
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
    public List<String> getAllMentionCompletions(Player sender) {
        return getMentionCompletions(sender, "");
    }
}
