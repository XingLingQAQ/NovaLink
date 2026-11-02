package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.TabCompleteEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final Map<UUID, Set<String>> published = new ConcurrentHashMap<>();
    private final Set<UUID> failedPushes = ConcurrentHashMap.newKeySet();
    private final NativeChatPush nativeChatPush;
    
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
        NativeChatPush adapter = null;
        try {
            adapter = new NativeChatPush();
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("Native mention completion needs the Waterfall 615 PacketWrapper transport: " + error);
        }
        nativeChatPush = adapter;
        if (adapter != null) {
            plugin.getProxy().getScheduler().schedule(plugin, this::refreshCompletions, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    public void refreshCompletions() {
        if (nativeChatPush == null) {
            return;
        }
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            if (player.getPendingConnection().getVersion() != NativeChatPush.PROTOCOL_VERSION) {
                continue;
            }
            try {
                Channel channel = nativeChatPush.channel(player);
                channel.eventLoop().execute(() -> {
                    try {
                        if (!player.isConnected() || !channel.isActive() || !nativeChatPush.inPlay(player)) {
                            return;
                        }
                        Set<String> current = new HashSet<>(getAllMentionCompletions(player));
                        Set<String> previous = published.getOrDefault(player.getUniqueId(), Set.of());
                        Set<String> removed = new HashSet<>(previous);
                        removed.removeAll(current);
                        Set<String> added = new HashSet<>(current);
                        added.removeAll(previous);
                        nativeChatPush.send(player, 1, removed);
                        nativeChatPush.send(player, 0, added);
                        published.put(player.getUniqueId(), current);
                        failedPushes.remove(player.getUniqueId());
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        reportPushFailure(player, error);
                    }
                });
            } catch (ReflectiveOperationException | RuntimeException error) {
                reportPushFailure(player, error);
            }
        }
    }

    private void reportPushFailure(ProxiedPlayer player, Exception error) {
        if (failedPushes.add(player.getUniqueId())) {
            plugin.getLogger().warning("Native mention completion failed for " + player.getName() + ": " + error);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        published.remove(event.getPlayer().getUniqueId());
        failedPushes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        // Retain old entries until the next diff removes anything no longer visible.
        refreshCompletions();
    }

    static final class NativeChatPush {
        static final int PROTOCOL_VERSION = 774;
        static final int CHAT_SUGGESTIONS_PACKET_ID = 0x17;
        private final Method getChannel;
        private final Method getHandle;
        private final Method getEncodeProtocol;
        private final Method getEncodeVersion;
        private final Method sendPacket;

        NativeChatPush() throws ReflectiveOperationException {
            Class<?> connection = Class.forName("net.md_5.bungee.UserConnection");
            getChannel = connection.getMethod("getCh");
            Class<?> wrapper = getChannel.getReturnType();
            getHandle = wrapper.getMethod("getHandle");
            getEncodeProtocol = wrapper.getMethod("getEncodeProtocol");
            getEncodeVersion = wrapper.getMethod("getEncodeVersion");
            sendPacket = connection.getMethod("sendPacket", PacketWrapper.class);
        }

        Channel channel(ProxiedPlayer player) throws ReflectiveOperationException {
            return (Channel) getHandle.invoke(getChannel.invoke(player));
        }

        boolean inPlay(ProxiedPlayer player) throws ReflectiveOperationException {
            Object channel = getChannel.invoke(player);
            return getEncodeProtocol.invoke(channel) == Protocol.GAME
                    && ((Integer) getEncodeVersion.invoke(channel)) == PROTOCOL_VERSION;
        }

        static ByteBuf encode(int action, Collection<String> entries) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                DefinedPacket.writeVarInt(CHAT_SUGGESTIONS_PACKET_ID, buffer);
                DefinedPacket.writeVarInt(action, buffer);
                DefinedPacket.writeVarInt(entries.size(), buffer);
                for (String entry : entries) {
                    DefinedPacket.writeString(entry, buffer);
                }
                return buffer;
            } catch (RuntimeException error) {
                buffer.release();
                throw error;
            }
        }

        void send(ProxiedPlayer player, int action, Collection<String> entries) throws ReflectiveOperationException {
            if (entries.isEmpty()) {
                return;
            }
            // The runtime forwards unregistered clientbound packets through this same path.
            PacketWrapper packet = new PacketWrapper(null, encode(action, entries), Protocol.GAME);
            try {
                sendPacket.invoke(player, packet);
            } finally {
                packet.trySingleRelease();
            }
        }
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
        
        // Check if this is a chat message (not a command)
        if (cursor == null || cursor.startsWith("/")) {
            return;
        }
        
        // Find the last token (word being typed)
        String lastToken = getLastToken(cursor);
        
        // Check if the last token starts with @
        if (!lastToken.startsWith("@")) {
            return;
        }
        
        // Get the partial name after @
        String partialName = lastToken.substring(1).toLowerCase(Locale.ROOT);
        
        plugin.debug("Processing mention completion for partial: '" + partialName + "'");
        
        // Get completions
        List<String> mentionCompletions = getMentionCompletions(player, partialName);
        
        // Add our completions
        event.getSuggestions().removeIf(candidate -> candidate.startsWith("@"));
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
        if (!sender.hasPermission(MENTION_PERMISSION)) {
            return completions;
        }
        partialName = partialName.toLowerCase(Locale.ROOT);
        
        // Add @all if player has permission and it matches
        if (sender.hasPermission(MENTION_ALL_PERMISSION)) {
            if (MentionParser.ALL_MENTION.toLowerCase().startsWith(partialName)) {
                completions.add("@" + MentionParser.ALL_MENTION);
            }
        }
        
        // Add online player names that match
        for (ProxiedPlayer onlinePlayer : plugin.getProxy().getPlayers()) {
            // Don't suggest the sender's own name
            if (!isVisible(sender, onlinePlayer)) {
                continue;
            }
            
            String playerName = onlinePlayer.getName();
            if (playerName.toLowerCase(Locale.ROOT).startsWith(partialName)) {
                completions.add("@" + playerName);
            }
        }
        
        return completions;
    }

    private boolean isVisible(ProxiedPlayer sender, ProxiedPlayer candidate) {
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
                || (!plugin.getIgnoreListService().isIgnored(sender.getUniqueId(), candidate.getName())
                && !plugin.getIgnoreListService().isIgnored(candidate.getUniqueId(), sender.getName()));
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
                .filter(p -> isVisible(sender, p))
                .map(ProxiedPlayer::getName)
                .collect(Collectors.toList());
    }
}
