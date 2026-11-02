package com.nova.chat.velocity.chat;

import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MentionTabCompleter {

    private static final String MENTION_PERMISSION = "novachat.feature.mention";
    private static final String MENTION_ALL_PERMISSION = "novachat.feature.mention.all";

    private final NovaChatVelocity plugin;
    private final Map<UUID, Set<String>> published = new HashMap<>();

    public MentionTabCompleter(NovaChatVelocity plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().buildTask(plugin, this::refreshCompletions)
                .repeat(Duration.ofMillis(500)).schedule();
    }

    // Modern chat completes locally from custom_chat_completions, without a request.
    public synchronized void refreshCompletions() {
        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.getProtocolVersion().getProtocol() < 760 || player.getCurrentServer().isEmpty()) {
                continue;
            }
            Set<String> current = new HashSet<>(getAllMentionCompletions(player));
            Set<String> previous = published.getOrDefault(player.getUniqueId(), Set.of());
            Set<String> removed = new HashSet<>(previous);
            removed.removeAll(current);
            Set<String> added = new HashSet<>(current);
            added.removeAll(previous);
            if (!removed.isEmpty()) {
                player.removeCustomChatCompletions(removed);
            }
            if (!added.isEmpty()) {
                player.addCustomChatCompletions(added);
            }
            published.put(player.getUniqueId(), current);
        }
    }

    @Subscribe
    public synchronized void onDisconnect(DisconnectEvent event) {
        published.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public synchronized void onServerConnect(ServerPostConnectEvent event) {
        Set<String> previous = published.remove(event.getPlayer().getUniqueId());
        if (previous != null && !previous.isEmpty()) {
            event.getPlayer().removeCustomChatCompletions(previous);
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onTabComplete(TabCompleteEvent event) {
        String partial = extractPartialMention(event.getPartialMessage());
        if (partial == null) {
            return;
        }
        event.getSuggestions().removeIf(candidate -> candidate.startsWith("@"));
        event.getSuggestions().addAll(getMentionCompletions(event.getPlayer(), partial));
    }

    public List<String> getMentionCompletions(Player sender, String partialName) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission(MENTION_PERMISSION)) {
            return completions;
        }
        String prefix = partialName.toLowerCase(Locale.ROOT);
        if (sender.hasPermission(MENTION_ALL_PERMISSION) && "all".startsWith(prefix)) {
            completions.add("@all");
        }
        for (Player candidate : plugin.getServer().getAllPlayers()) {
            if (isVisible(sender, candidate) && candidate.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                completions.add("@" + candidate.getUsername());
            }
        }
        return completions;
    }

    private boolean isVisible(Player sender, Player candidate) {
        if (sender.getUniqueId().equals(candidate.getUniqueId()) || plugin.getChatCore() == null) {
            return false;
        }
        PlayerChannelState senderState = plugin.getChatCore().getPlayerState(sender.getUniqueId());
        PlayerChannelState candidateState = plugin.getChatCore().getPlayerState(candidate.getUniqueId());
        if (senderState == null || candidateState == null || senderState.getActiveChannel() == null
                || !senderState.getActiveChannel().equals(candidateState.getActiveChannel())) {
            return false;
        }
        return plugin.getIgnoreListService() == null
                || (!plugin.getIgnoreListService().isIgnored(sender.getUniqueId(), candidate.getUsername())
                && !plugin.getIgnoreListService().isIgnored(candidate.getUniqueId(), sender.getUsername()));
    }

    public List<String> getAllMentionCompletions(Player sender) {
        return getMentionCompletions(sender, "");
    }

    public String extractPartialMention(String message) {
        if (message == null || message.startsWith("/")) {
            return null;
        }
        String token = message.substring(message.lastIndexOf(' ') + 1);
        return token.startsWith("@") ? token.substring(1) : null;
    }

    public List<String> getCompletionsForMessage(Player sender, String message) {
        String partial = extractPartialMention(message);
        return partial == null ? new ArrayList<>() : getMentionCompletions(sender, partial);
    }

    public List<String> getOnlinePlayerNames(Player sender) {
        return plugin.getServer().getAllPlayers().stream()
                .filter(candidate -> isVisible(sender, candidate))
                .map(Player::getUsername).toList();
    }
}
