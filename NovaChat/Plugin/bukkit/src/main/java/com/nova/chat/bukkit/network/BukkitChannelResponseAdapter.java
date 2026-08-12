package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseDispatcher.ChannelResponseAdapter;
import com.nova.chat.client.network.ChannelResponseDispatcher.KickMuteNotice;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.UUID;

/**
 * Bukkit {@link ChannelResponseAdapter} for the shared
 * {@link ChannelResponseDispatcher}.
 *
 * <p>Every callback hops to the Bukkit main thread via
 * {@code plugin.getServer().getScheduler().runTask(plugin, ...)} before touching
 * any Bukkit API (player lookup, state mutation, message/title/action-bar sends),
 * mirroring how {@code PNXChannelResponseAdapter} hops to the PNX main thread. The
 * dispatcher calls these synchronously from the Netty event loop; the hops make the
 * rendering thread-safe and compliant with the Bukkit API's main-thread contract.
 *
 * <p>The adapter only owns the shared JOIN/LEAVE/WHO success routing, JOIN rollback,
 * error routing, and KICK/MUTE target notice that the dispatcher drives. All
 * rendering is driven by {@link NetworkClient#handleChannelActionResponse} as a
 * thin delegate to {@link ChannelResponseDispatcher#handle}, matching the other
 * 6 platforms' shape.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
final class BukkitChannelResponseAdapter implements ChannelResponseAdapter {

    private final NetworkClient client;

    /**
     * @param client the owning bukkit NetworkClient facade (provides the plugin
     *               instance and the bukkit rendering helpers: messageHelper,
     *               errorHandler, chatInterceptor, novaChatConfig)
     */
    BukkitChannelResponseAdapter(NetworkClient client) {
        this.client = client;
    }

    private NovaChatBukkit plugin() {
        return client.plugin;
    }

    @Override
    public void setActiveChannel(UUID playerId, String channelId) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            plugin().getChatInterceptor().setPlayerChannel(player, channelId);
        });
    }

    @Override
    public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            PlayerChannelState state = plugin().getChatInterceptor().getState(player.getUniqueId());
            String current = state != null ? state.getActiveChannel() : null;
            // Only roll back if the optimistic switch is still in place — the player
            // may have switched channels manually in the meantime (BUG-H2).
            if (current != null && current.equals(attemptedChannel)) {
                plugin().getChatInterceptor().setPlayerChannel(player, previousChannel);
            }
        });
    }

    @Override
    public void sendJoinSuccess(UUID playerId, String channelId) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            plugin().getMessageHelper().sendSuccess(player,
                    PlayerMessages.joined(playerId, channelId));
        });
    }

    @Override
    public void sendLeaveSuccess(UUID playerId, String channelId) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            String defaultChannel = plugin().getNovaChatConfig().getDefaultChannel();
            plugin().getMessageHelper().sendSuccess(player,
                    PlayerMessages.left(playerId, channelId, defaultChannel));
        });
    }

    @Override
    public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            client.sendChannelStatusBar(player, channelId);
        });
    }

    @Override
    public void sendLeaveChannelStatusBar(UUID playerId) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            PlayerChannelState state = plugin().getChatInterceptor().getState(player.getUniqueId());
            String channelId = state != null ? state.getActiveChannel() : null;
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            client.sendChannelStatusBar(player, channelId);
        });
    }

    @Override
    public void sendErrorMessage(UUID playerId, String text) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            plugin().getMessageHelper().sendError(player, text);
        });
    }

    @Override
    public void sendWhoResult(UUID playerId, String channelId, String displayName,
                              String membersCsv, String memberCount) {
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player player = plugin().getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            String text = WhoCommandService.formatMemberList(
                    playerId, channelId, displayName, membersCsv, memberCount);
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) {
                    plugin().getMessageHelper().sendMessage(player, MessageHelper.colorize(line));
                }
            }
        });
    }

    @Override
    public void notifyKickMuteTarget(KickMuteNotice notice) {
        final String operator = notice.getOperator();
        final String durationText = notice.getDurationText();
        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            org.bukkit.entity.Player target = plugin().getServer().getPlayer(notice.getTargetId());
            if (target == null) {
                return; // not on this server
            }
            UUID targetId = target.getUniqueId();
            String channelId = notice.getChannelId() != null ? notice.getChannelId() : "";

            if (notice.getAction() == ChannelAction.KICK) {
                String title = MessageHelper.colorize(
                        I18n.tr(targetId, "chat.notice.kick_title"));
                String subtitle = MessageHelper.colorize(
                        I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId));
                target.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN,
                        MentionNotifier.DEFAULT_STAY,
                        MentionNotifier.DEFAULT_FADE_OUT);
                target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(MessageHelper.colorize(
                                I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId))));
                return;
            }

            // MUTE
            String title = MessageHelper.colorize(
                    I18n.tr(targetId, "chat.notice.mute_title"));
            String subtitle = MessageHelper.colorize(
                    I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText));
            target.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN,
                    MentionNotifier.DEFAULT_STAY,
                    MentionNotifier.DEFAULT_FADE_OUT);
            target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(MessageHelper.colorize(
                            I18n.tr(targetId, "chat.notice.mute_actionbar", durationText, channelId))));
        });
    }
}
