package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.proxy.channel.ProxyChannelHandler;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * BungeeCord shell for the {@code novachat:main} plugin-messaging channel.
 *
 * <p>Keeps only what is BungeeCord-specific: the event subscription, the tag
 * match and the source unwrap ({@link PluginMessageEvent#getSender()} is a
 * {@link Server} for downstream-originated messages). Every security decision
 * and both routing modes live in {@link ProxyChannelHandler}.
 *
 * <p><b>Always consumes.</b> The event is cancelled before the source is
 * examined, so a player writing to this channel is never forwarded to the
 * downstream server and a downstream payload is never forwarded to the player's
 * client.
 *
 * <p>Requirements: 23.3 — plugin messaging between the sub-server plugin and the
 * proxy (lite/relay common prerequisite).
 */
public class PluginMessageListener implements Listener {

    private final NovaChatBungee plugin;
    private final ProxyChannelHandler handler;

    /**
     * @param plugin the plugin instance (for the shared channel handler + logs)
     */
    public PluginMessageListener(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.handler = plugin.getProxyChannelHandler();
    }

    /**
     * @param event the plugin message event
     */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!ProxyChannelProtocol.CHANNEL_ID.equals(event.getTag())) {
            return;
        }
        // Consume first: a player-sourced payload must never reach the backend
        // server, and a server-sourced payload must never reach a client.
        event.setCancelled(true);

        ProxyChannelHandler target = handler;
        if (target == null) {
            // Handler is built during startup; an event this early is dropped.
            plugin.debug("Dropped novachat:main payload: channel handler not initialized");
            return;
        }

        if (event.getSender() instanceof Server server) {
            target.handleFromServer(server.getInfo().getName(), event.getData());
        } else {
            // Player (or a proxy-internal connection): never a sub-server.
            target.handleFromServer(null, event.getData());
        }
    }
}
