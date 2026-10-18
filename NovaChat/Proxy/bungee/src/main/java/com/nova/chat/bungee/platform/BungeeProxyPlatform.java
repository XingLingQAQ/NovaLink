package com.nova.chat.bungee.platform;

import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.bungee.NovaChatBungee;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * BungeeCord implementation of {@link ProxyPlatform}.
 *
 * <p>Owns the only BungeeCord-specific parts of the shared proxy core: md_5
 * chat-component rendering (via the shared {@code MessageFormatService} +
 * {@code ColorRenderer} pipeline), the proxy title API, the plugin scheduler and
 * the JUL logger. BungeeCord has no proxy-level sound or action-bar API, so
 * those primitives degrade exactly as they did before (mention → in-chat line,
 * status bar → no-op).
 */
public final class BungeeProxyPlatform implements ProxyPlatform {

    private final NovaChatBungee plugin;

    /** Shared string → md_5 component renderer (hex expanded to section form). */
    private final ColorRenderer<BaseComponent[]> renderer;

    /**
     * @param plugin the plugin instance
     */
    public BungeeProxyPlatform(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToSection(text != null ? text : "");
            converted = ChatColor.translateAlternateColorCodes('&', converted);
            return TextComponent.fromLegacyText(converted);
        };
    }

    /**
     * Renders a legacy-coded string into md_5 components.
     *
     * @param legacyText the text
     * @return the components, never null
     */
    public BaseComponent[] render(String legacyText) {
        return renderer.render(legacyText);
    }

    // ------------------------------------------------------------ player lookup

    @Override
    public Collection<? extends ProxyPlayer> getOnlinePlayers() {
        List<ProxyPlayer> wrapped = new ArrayList<>();
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            wrapped.add(new BungeeProxyPlayer(player));
        }
        return wrapped;
    }

    @Override
    public ProxyPlayer getPlayer(UUID playerId) {
        ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
        return player != null ? new BungeeProxyPlayer(player) : null;
    }

    // ---------------------------------------------------------------- messaging

    @Override
    public void sendMessage(ProxyPlayer player, String legacyText) {
        ProxiedPlayer handle = handle(player);
        if (handle != null) {
            handle.sendMessage(renderer.render(legacyText));
        }
    }

    @Override
    public void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText) {
        ProxiedPlayer handle = handle(player);
        if (handle == null) {
            return;
        }
        BaseComponent[] line = renderer.render(legacyText);
        BaseComponent[] hover = renderer.render(hoverText);
        TextComponent component = new TextComponent(line);
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        handle.sendMessage(component);
    }

    @Override
    public void showTitle(ProxyPlayer player, String title, String subtitle,
                          int fadeIn, int stay, int fadeOut) {
        ProxiedPlayer handle = handle(player);
        if (handle == null) {
            return;
        }
        // BungeeCord's native proxy title API writes the packets straight to the
        // downstream client. Timings are ticks, matching the packet contract.
        Title bungeeTitle = plugin.getProxy().createTitle()
                .title(renderer.render(title != null ? title : ""))
                .subTitle(renderer.render(subtitle != null ? subtitle : ""))
                .fadeIn(fadeIn)
                .stay(stay)
                .fadeOut(fadeOut);
        bungeeTitle.send(handle);
    }

    @Override
    public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) {
        // BungeeCord has no proxy-level sound API; raw protocol packets would
        // couple us to unstable internals, so this is a documented no-op.
    }

    @Override
    public void notifyMention(ProxyPlayer player, String highlight, String subtitle) {
        ProxiedPlayer handle = handle(player);
        if (handle == null) {
            return;
        }
        // BungeeCord degrades the mention to an in-chat highlighted line.
        handle.sendMessage(renderer.render(highlight + " " + subtitle));
    }

    @Override
    public void showChannelStatusBar(ProxyPlayer player, String channelId) {
        // Bungee is a proxy and has no stable action-bar API that reliably reaches
        // the downstream client; the §7 status bar is intentionally not sent.
    }

    @Override
    public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                               String operator, String durationText) {
        ProxiedPlayer target = handle(player);
        if (target == null) {
            return;
        }
        UUID targetId = player.getUniqueId();
        // Bungee has no reliable proxy title API; the notice is a chat line only.
        if (action == ChannelAction.KICK) {
            target.sendMessage(renderer.render(
                    I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId)));
            return;
        }
        target.sendMessage(renderer.render(
                I18n.tr(targetId, "chat.notice.mute_actionbar", durationText, channelId)));
    }

    // -------------------------------------------------------------- permissions

    @Override
    public boolean consoleHasPermission(String permission) {
        // BungeeCord's console CommandSender grants every node.
        return true;
    }

    // -------------------------------------------------------------- dispatch

    @Override
    public void execute(Runnable task) {
        // BungeeCord has no single "main thread"; the plugin scheduler's async
        // pool is the idiomatic hop off the Netty event loop (see also the
        // historical BungeeChannelResponseAdapter).
        plugin.getProxy().getScheduler().runAsync(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        plugin.getProxy().getScheduler().runAsync(plugin, task);
    }

    @Override
    public void runLater(Runnable task, long delaySeconds) {
        plugin.getProxy().getScheduler().schedule(plugin, task, delaySeconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ logging

    @Override
    public void logInfo(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void logWarn(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void logDebug(String message) {
        plugin.debug(message);
    }

    @Override
    public void logError(String message) {
        plugin.getLogger().severe(message);
    }

    @Override
    public void logError(String message, Throwable cause) {
        if (cause == null) {
            plugin.getLogger().severe(message);
        } else {
            plugin.getLogger().severe(message + ": " + cause.getMessage());
        }
    }

    // ------------------------------------------------------------------ identity

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.BUNGEECORD;
    }

    @Override
    public void reloadConfiguration() {
        plugin.reload();
    }

    /**
     * Unwraps a player handle to its BungeeCord player.
     *
     * @param player the handle
     * @return the BungeeCord player, or null when the handle is not ours
     */
    private static ProxiedPlayer handle(ProxyPlayer player) {
        if (player instanceof BungeeProxyPlayer bungeePlayer) {
            return bungeePlayer.getHandle();
        }
        return null;
    }
}
