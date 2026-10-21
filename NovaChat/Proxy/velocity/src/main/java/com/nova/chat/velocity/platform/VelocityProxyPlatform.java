package com.nova.chat.velocity.platform;

import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Velocity implementation of {@link ProxyPlatform}.
 *
 * <p>Owns the only Velocity-specific parts of the shared proxy core: Adventure
 * rendering (via the shared {@code MessageFormatService} + {@code ColorRenderer}
 * pipeline), the title/sound/tab APIs, the plugin scheduler and the SLF logger.
 */
public final class VelocityProxyPlatform implements ProxyPlatform {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private final NovaChatVelocity plugin;

    /** Shared string → Adventure component renderer (hex expanded to ampersand form). */
    private final ColorRenderer<Component> renderer;

    /**
     * @param plugin the plugin instance
     */
    public VelocityProxyPlatform(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.renderer = text -> LEGACY_SERIALIZER.deserialize(
                MessageFormatService.convertHexToAmpersand(text != null ? text : ""));
    }

    /**
     * Renders a legacy-coded string into an Adventure component.
     *
     * @param legacyText the text
     * @return the component, never null
     */
    public Component render(String legacyText) {
        return renderer.render(legacyText);
    }

    // ------------------------------------------------------------ player lookup

    @Override
    public Collection<? extends ProxyPlayer> getOnlinePlayers() {
        List<ProxyPlayer> wrapped = new ArrayList<>();
        for (Player player : plugin.getServer().getAllPlayers()) {
            wrapped.add(new VelocityProxyPlayer(player));
        }
        return wrapped;
    }

    @Override
    public ProxyPlayer getPlayer(UUID playerId) {
        return plugin.getServer().getPlayer(playerId)
                .map(VelocityProxyPlayer::new)
                .orElse(null);
    }

    // ---------------------------------------------------------------- messaging

    @Override
    public void sendMessage(ProxyPlayer player, String legacyText) {
        Player handle = handle(player);
        if (handle != null) {
            handle.sendMessage(renderer.render(legacyText));
        }
    }

    @Override
    public void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText) {
        Player handle = handle(player);
        if (handle != null) {
            Component line = renderer.render(legacyText);
            Component hover = renderer.render(hoverText);
            handle.sendMessage(line.hoverEvent(
                    net.kyori.adventure.text.event.HoverEvent.showText(hover)));
        }
    }

    @Override
    public void showTitle(ProxyPlayer player, String title, String subtitle,
                          int fadeIn, int stay, int fadeOut) {
        Player handle = handle(player);
        if (handle == null) {
            return;
        }
        // Packet timings are in ticks (20/s); Adventure wants durations (50ms/tick).
        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L));
        handle.showTitle(Title.title(
                renderer.render(title != null ? title : ""),
                renderer.render(subtitle != null ? subtitle : ""),
                times));
    }

    @Override
    public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) {
        Player handle = handle(player);
        if (handle == null) {
            return;
        }
        try {
            Key key = Key.key("minecraft", toResourcePath(soundId));
            handle.playSound(Sound.sound(key, Sound.Source.PLAYER, volume, pitch));
        } catch (Exception e) {
            plugin.debug("Failed to play mention sound: " + e.getMessage());
        }
    }

    /**
     * Converts a sound identifier into a valid resource-path.
     *
     * <p>{@link com.nova.chat.common.chat.MentionNotifier#DEFAULT_SOUND} is
     * spelled the way Bukkit spells its {@code Sound} enum members
     * ({@code ENTITY_EXPERIENCE_ORB_PICKUP}), which is not a registry id: the
     * vanilla resource path is {@code entity.experience_orb.pickup}. Lowercasing
     * the enum name produces {@code entity_experience_orb_pickup}, which no
     * client resolves, so the sound was simply silent. The mapping has to be
     * explicit because underscores and dots are not interchangeable - there is
     * no rule that recovers the dots from the enum spelling.
     *
     * <p>An input that is already a resource path (contains a dot) is passed
     * through, so a configured custom sound keeps working.
     *
     * @param soundId a Bukkit-style enum name or an already-valid resource path
     * @return a resource path suitable for {@link Key#key(String, String)}
     */
    private String toResourcePath(String soundId) {
        String stripped = soundId.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (stripped.indexOf('.') >= 0) {
            return stripped;
        }
        String mapped = switch (stripped) {
            case "entity_experience_orb_pickup" -> "entity.experience_orb.pickup";
            case "entity_player_levelup" -> "entity.player.levelup";
            case "block_note_block_pling" -> "block.note_block.pling";
            case "ui_toast_challenge_complete" -> "ui.toast.challenge_complete";
            default -> null;
        };
        if (mapped != null) {
            return mapped;
        }
        // Unmapped enum-style id: fall back to the configured default rather
        // than sending a path the client will ignore in silence.
        plugin.debug("Unknown sound id '" + soundId + "', using the mention default");
        return "entity.experience_orb.pickup";
    }

    @Override
    public void notifyMention(ProxyPlayer player, String highlight, String subtitle) {
        Player handle = handle(player);
        if (handle == null) {
            return;
        }
        // Velocity renders the mention as a title + the default mention sound. The
        // title text is the pre-colored mentioner; Adventure cannot carry a raw
        // "&e" prefix as a component color, so the name is rendered plain yellow
        // exactly like the historical handleMention implementation.
        Component title = Component.text(
                stripColorPrefix(highlight), NamedTextColor.YELLOW);
        Component subtitleComponent = renderer.render(subtitle);
        Title.Times times = Title.Times.times(
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN * 50L),
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY * 50L),
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT * 50L));
        handle.showTitle(Title.title(title, subtitleComponent, times));
        playSound(player,
                "minecraft:" + com.nova.chat.common.chat.MentionNotifier.DEFAULT_SOUND
                        .toLowerCase(Locale.ROOT),
                1.0f, 1.0f);
    }

    /** Strips a leading legacy color prefix from a pre-colored mention string. */
    private static String stripColorPrefix(String text) {
        if (text != null && text.length() > 2 && text.charAt(0) == '&') {
            return text.substring(2);
        }
        return text != null ? text : "";
    }

    @Override
    public void sendActionBar(ProxyPlayer player, String legacyText) {
        Player handle = handle(player);
        if (handle != null) {
            // Velocity's Player extends Adventure's Audience.
            handle.sendActionBar(renderer.render(legacyText));
        }
    }

    @Override
    public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) {
        Player handle = handle(player);
        if (handle == null || channelId == null || channelId.isEmpty()) {
            return;
        }
        // §7 status ("当前频道：X（模式）"): a one-shot Adventure action bar
        // (Audience#sendActionBar(Component)), matching the other platforms'
        // "flash the bar once" semantics. Vanilla fades it out; no polling.
        handle.sendActionBar(renderer.render(
                PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode)));
    }

    @Override
    public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                               String operator, String durationText) {
        Player target = handle(player);
        if (target == null) {
            return;
        }
        UUID targetId = player.getUniqueId();
        Title.Times times = Title.Times.times(
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN * 50L),
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY * 50L),
                Duration.ofMillis(com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT * 50L));
        if (action == ChannelAction.KICK) {
            Component title = renderer.render(
                    com.nova.chat.client.i18n.I18n.tr(targetId, "chat.notice.kick_title"));
            Component subtitle = renderer.render(
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.kick_subtitle", operator, channelId));
            target.showTitle(Title.title(title, subtitle, times));
            target.sendActionBar(renderer.render(
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.kick_actionbar", operator, channelId)));
            return;
        }
        if (action == ChannelAction.BAN) {
            showTargetNotice(target, times, targetId, "chat.notice.ban_title",
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.ban_subtitle", operator, channelId, durationText),
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.ban_actionbar", operator, channelId, durationText));
            return;
        }
        if (action == ChannelAction.UNBAN) {
            showTargetNotice(target, times, targetId, "chat.notice.unban_title",
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.unban_subtitle", operator, channelId),
                    com.nova.chat.client.i18n.I18n.tr(
                            targetId, "chat.notice.unban_actionbar", operator, channelId));
            return;
        }
        Component title = renderer.render(
                com.nova.chat.client.i18n.I18n.tr(targetId, "chat.notice.mute_title"));
        Component subtitle = renderer.render(
                com.nova.chat.client.i18n.I18n.tr(
                        targetId, "chat.notice.mute_subtitle", channelId, durationText));
        target.showTitle(Title.title(title, subtitle, times));
        target.sendActionBar(renderer.render(
                com.nova.chat.client.i18n.I18n.tr(
                        targetId, "chat.notice.mute_actionbar", durationText, channelId)));
    }

    /**
     * Renders the BAN/UNBAN shape: a title plus the action bar repeating the
     * same event. Placeholder order copies the Bukkit/Folia arms.
     *
     * @param target        the Velocity player
     * @param times         the shared title timings
     * @param targetId      their UUID (locale lookup)
     * @param titleKey      i18n key for the title line
     * @param subtitle      already-resolved subtitle text
     * @param actionBarText already-resolved action-bar text
     */
    private void showTargetNotice(Player target, Title.Times times, UUID targetId, String titleKey,
                                  String subtitle, String actionBarText) {
        target.showTitle(Title.title(
                renderer.render(com.nova.chat.client.i18n.I18n.tr(targetId, titleKey)),
                renderer.render(subtitle),
                times));
        target.sendActionBar(renderer.render(actionBarText));
    }

    // -------------------------------------------------------------- permissions

    @Override
    public boolean consoleHasPermission(String permission) {
        // Velocity's console source is not a Player; the plugin container grants
        // the console every node, which is what the historical command handler
        // relied on.
        return true;
    }

    // -------------------------------------------------------------- dispatch

    @Override
    public void execute(Runnable task) {
        plugin.getServer().getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public void runAsync(Runnable task) {
        plugin.getServer().getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public void runLater(Runnable task, long delaySeconds) {
        plugin.getServer().getScheduler()
                .buildTask(plugin, task)
                .delay(delaySeconds, TimeUnit.SECONDS)
                .schedule();
    }

    // ------------------------------------------------------------------ logging

    @Override
    public void logInfo(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void logWarn(String message) {
        plugin.getLogger().warn(message);
    }

    @Override
    public void logDebug(String message) {
        plugin.debug(message);
    }

    @Override
    public void logError(String message) {
        plugin.getLogger().error(message);
    }

    @Override
    public void logError(String message, Throwable cause) {
        if (cause == null) {
            plugin.getLogger().error(message);
        } else {
            plugin.getLogger().error(message, cause);
        }
    }

    // ------------------------------------------------------------------ identity

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.VELOCITY;
    }

    @Override
    public void reloadConfiguration() {
        plugin.reload();
    }

    /**
     * Sends one plugin message to a downstream server through one of its
     * players' connections.
     *
     * <p>Velocity's {@code RegisteredServer#sendPluginMessage} writes to
     * <em>every</em> player on the server, which would deliver one copy of the
     * payload per player to the downstream plugin. Picking a single
     * {@code ServerConnection} keeps it to exactly one delivery. With no players
     * on the server there is no connection to write to at all: return
     * {@code false} so the caller reports the drop (plugin messaging's inherent
     * limitation) instead of pretending the message was sent.
     */
    @Override
    public boolean sendPluginMessage(String serverName, String channel, byte[] payload) {
        if (serverName == null || channel == null || payload == null) {
            return false;
        }
        RegisteredServer target = plugin.getServer().getServer(serverName).orElse(null);
        if (target == null) {
            logDebug("Cannot send plugin message to unknown server '" + serverName + "'");
            return false;
        }
        MinecraftChannelIdentifier identifier;
        try {
            identifier = MinecraftChannelIdentifier.from(channel);
        } catch (RuntimeException e) {
            logWarn("Cannot send plugin message on invalid channel '" + channel + "'");
            return false;
        }
        for (Player player : target.getPlayersConnected()) {
            ServerConnection connection = player.getCurrentServer().orElse(null);
            if (connection == null
                    || !serverName.equalsIgnoreCase(connection.getServerInfo().getName())) {
                continue;
            }
            try {
                return connection.sendPluginMessage(identifier, payload);
            } catch (RuntimeException e) {
                logDebug("Plugin message write to '" + serverName + "' failed: "
                        + e.getMessage());
                return false;
            }
        }
        // No player on that server: the downstream connection does not exist.
        logDebug("Cannot send plugin message to '" + serverName + "': no players online");
        return false;
    }

    /**
     * Unwraps a player handle to its Velocity player.
     *
     * @param player the handle
     * @return the Velocity player, or null when the handle is not ours
     */
    private static Player handle(ProxyPlayer player) {
        if (player instanceof VelocityProxyPlayer velocityPlayer) {
            return velocityPlayer.getHandle();
        }
        return null;
    }
}
