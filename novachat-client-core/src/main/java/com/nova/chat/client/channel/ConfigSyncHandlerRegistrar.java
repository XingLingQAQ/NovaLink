package com.nova.chat.client.channel;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;

/**
 * Registers the standard minimal {@link ConfigSyncPacket} handler that keeps a
 * {@link KnownChannelRegistry} in sync with the backend's roster push
 * (UX-DESIGN §2.1).
 *
 * <p>Six of the seven platform bootstraps (bungee, velocity, nukkit, folia, pnx,
 * sponge) register an identical handler: parse the JSON, extract this client's
 * channel IDs via {@link ConfigSyncChannels#extract}, and atomically
 * {@link KnownChannelRegistry#replaceAll replace} the registry. This helper
 * centralises that handler so the platform bootstrap is one line.
 *
 * <p>Bukkit is intentionally not driven through this helper: its ConfigSync
 * handler additionally parses world-restricted channels and mutates a
 * {@code WorldMonitor}, which is genuinely Bukkit-specific.
 */
public final class ConfigSyncHandlerRegistrar {

    private ConfigSyncHandlerRegistrar() {
        // Utility class — not instantiated.
    }

    /**
     * Registers the standard ConfigSync handler on the given client.
     *
     * <p>The handler is a no-op when the payload JSON is null/blank. The username
     * is captured once at registration; callers should re-register (or pass a
     * fresh username) after a config reload that changes the client identity.
     *
     * @param client  the platform network client to register the handler on
     * @param registry the known-channel registry to populate
     * @param thisClientUsername this client's username filter, or null to skip
     *                           per-client channels and keep only globals
     */
    public static void register(
            AbstractPlatformNetworkClient client,
            KnownChannelRegistry registry,
            String thisClientUsername
    ) {
        java.util.Objects.requireNonNull(client, "client");
        java.util.Objects.requireNonNull(registry, "registry");
        client.registerHandler(
                ConfigSyncPacket.class,
                packet -> {
                    String json = packet.getConfigJson();
                    if (json == null || json.isBlank()) {
                        return;
                    }
                    registry.replaceAll(ConfigSyncChannels.extract(json, thisClientUsername));
                });
    }
}
