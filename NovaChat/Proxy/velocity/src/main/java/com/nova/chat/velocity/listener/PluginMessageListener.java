package com.nova.chat.velocity.listener;

import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.proxy.channel.ProxyChannelHandler;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;

/**
 * Velocity shell for the {@code novachat:main} plugin-messaging channel.
 *
 * <p>Keeps only what is Velocity-specific: the event subscription, the
 * channel-identifier match and the source unwrap
 * ({@link PluginMessageEvent#getSource()} is a {@link ServerConnection} for
 * downstream-originated messages). Every security decision and both routing
 * modes live in {@link ProxyChannelHandler}.
 *
 * <p><b>Always consumes.</b> The result is set to
 * {@link PluginMessageEvent.ForwardResult#handled()} before the source is
 * examined, so a player writing to this channel can never have their payload
 * forwarded downstream, and a downstream server's payload is never forwarded to
 * a player client.
 *
 * <p>Requirements: 23.2 — plugin messaging between the sub-server plugin and the
 * proxy (lite/relay common prerequisite).
 */
public class PluginMessageListener {

    private final NovaChatVelocity plugin;
    private final ProxyChannelHandler handler;

    /**
     * @param plugin the plugin instance (for the shared channel handler + logs)
     */
    public PluginMessageListener(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.handler = plugin.getProxyChannelHandler();
    }

    /**
     * @param event the plugin message event
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!ProxyChannelProtocol.CHANNEL_ID.equals(event.getIdentifier().getId())) {
            return;
        }
        // Consume first: this is the only way to stop a client-originated
        // payload from reaching the downstream server and a server-originated
        // one from reaching the player's client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        ProxyChannelHandler target = handler;
        if (target == null) {
            // Handler is built during startup; an event this early is dropped.
            plugin.debug("Dropped novachat:main payload: channel handler not initialized");
            return;
        }

        if (event.getSource() instanceof ServerConnection source) {
            target.handleFromServer(source.getServerInfo().getName(), event.getData());
        } else {
            // Player (or another sink): never a sub-server, never trusted.
            target.handleFromServer(null, event.getData());
        }
    }
}
