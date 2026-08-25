package com.nova.chat.client.channel;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERIFY-001 reproducible evidence (docs/PRODUCTION_READINESS_AND_PRODUCT_PLAN.md §7):
 * pins the {@link ConfigSyncHandlerRegistrar} reload contract around the
 * {@code thisClientUsername} captured once at registration time.
 *
 * <p>The registrar closes over the username, so a handler registered before a
 * config reload that changes the client identity keeps filtering per-client
 * channels against the OLD username forever. The scenarios below prove both
 * halves of the contract:
 * <ol>
 *   <li><strong>Stale-username hazard is real</strong> if a caller keeps using a
 *       handler registered under the previous identity (Test A).</li>
 *   <li><strong>The documented reload flow closes the hazard</strong>: every
 *       platform facade's reload builds a fresh client and re-registers with the
 *       fresh username (Test B).</li>
 * </ol>
 *
 * <h2>Platform reload survey (code-level, records WHY the hazard is closed)</h2>
 * <ul>
 *   <li>bungee ({@code NovaChatBungee.reload}), velocity ({@code NovaChatVelocity.reload}),
 *       nukkit ({@code NovaChatNukkit.reload}), pnx ({@code NovaChatPNX.reload}),
 *       folia ({@code NovaChatFolia.reload}), sponge ({@code NovaChatSponge.reload}):
 *       each ends with {@code disconnect()} + {@code initializeNetworkClient()},
 *       which constructs a <em>fresh</em> {@link KnownChannelRegistry} and a
 *       <em>fresh</em> network client, then calls
 *       {@link ConfigSyncHandlerRegistrar#register} with the freshly-loaded
 *       username. The stale closure is dropped together with the old client.</li>
 *   <li>bukkit does not use the registrar: {@code NetworkClient.handleConfigSync}
 *       reads {@code plugin.getNovaChatConfig().getUsername()} live on every
 *       dispatch, so it is safe by construction.</li>
 *   <li>MOD loaders (fabric/quilt/neoforge via {@code NovaChatMod.bootstrap})
 *       register once with the configured client id; their {@code reloadConfig()}
 *       does not rebuild the network client nor re-register (and is not wired to
 *       any command today — the {@code /nc reload} RELOAD intent is discarded),
 *       so changing a mod's username requires a server restart, which rebuilds
 *       everything. Recorded here as a known caveat, not a reachable defect.</li>
 * </ul>
 *
 * <h2>Honest boundary</h2>
 * These tests exercise the registrar closure directly through a recording fake
 * client (mirroring the Python FakeNet pattern in
 * {@code Bedrock/endstone/tests/test_admin_action_response.py}). They prove the
 * mechanism and the documented reload flow at unit level; they do <strong>not</strong>
 * boot any platform or execute any platform's real {@code reload()} /
 * {@code bootstrap} — that requires live servers and remains a manual/E2E gap.
 */
@DisplayName("ConfigSyncHandlerRegistrar reload contract (VERIFY-001)")
class ConfigSyncHandlerRegistrarTest {

    /**
     * Minimal recording stand-in for a platform network client: captures the
     * registered handler instead of forwarding to {@code CoreNetworkClient}, so
     * the test can invoke the exact {@link Consumer} the registrar captured,
     * with no Netty, connection state or generation bookkeeping involved.
     */
    private static final class RecordingNetworkClient extends AbstractPlatformNetworkClient {

        final Map<Class<?>, Consumer<?>> handlers = new LinkedHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
            handlers.put(packetClass, (Consumer<?>) handler);
        }

        @SuppressWarnings("unchecked")
        Consumer<ConfigSyncPacket> configSyncHandler() {
            return (Consumer<ConfigSyncPacket>) handlers.get(ConfigSyncPacket.class);
        }
    }

    /**
     * Roster payload advertising globals plus per-client channels for BOTH the
     * old and the new identity (as sent right after the backend learns about the
     * rename window), and one where only the new identity has per-client channels.
     * Shape mirrors {@code ConfigSyncChannelsTest}.
     */
    private static final String PAYLOAD_BOTH_CLIENTS =
            "{\"global_channels\":{\"global\":{},\"announce\":{}},\"clients\":["
                    + "{\"username\":\"old\",\"channels\":{\"old-private\":{}}},"
                    + "{\"username\":\"new\",\"channels\":{\"new-private\":{}}}"
                    + "]}";

    private static final String PAYLOAD_NEW_ONLY =
            "{\"global_channels\":{\"global\":{},\"announce\":{}},\"clients\":["
                    + "{\"username\":\"new\",\"channels\":{\"new-private-2\":{}}}"
                    + "]}";

    @Nested
    @DisplayName("stale-username hazard (handler kept from before the rename)")
    class StaleUsernameHazard {

        @Test
        @DisplayName("handler registered as 'old' sees 'old' channels but never 'new' channels")
        void staleHandlerKeepsOldIdentityView() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            RecordingNetworkClient client = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(client, registry, "old");
            Consumer<ConfigSyncPacket> handler = client.configSyncHandler();

            // Payload carries per-client channels for both identities.
            handler.accept(new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L));

            // The stale closure filters against "old": it takes old's channels
            // and the globals, and must not pick up new's private channels.
            assertThat(registry.getAll())
                    .containsExactlyInAnyOrder("global", "announce", "old-private");

            // After the rename settles, the backend stops advertising an "old"
            // roster entry at all — the stale handler degrades to globals-only
            // and can never see the new identity's private channels.
            handler.accept(new ConfigSyncPacket(PAYLOAD_NEW_ONLY, 2L));

            assertThat(registry.getAll())
                    .containsExactlyInAnyOrder("global", "announce")
                    .doesNotContain("new-private-2");
        }
    }

    @Nested
    @DisplayName("documented reload flow (fresh client + fresh registration)")
    class FreshClientReloadFlow {

        @Test
        @DisplayName("re-registering on a fresh client as 'new' reflects new's per-client channels")
        void freshClientRegistrationTracksNewIdentity() {
            // Simulates what every surveyed facade's reload() does: drop the old
            // client/registry pair, construct fresh ones, re-register with the
            // freshly-loaded username.
            KnownChannelRegistry freshRegistry = new KnownChannelRegistry();
            RecordingNetworkClient freshClient = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(freshClient, freshRegistry, "new");
            Consumer<ConfigSyncPacket> handler = freshClient.configSyncHandler();

            handler.accept(new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L));

            assertThat(freshRegistry.getAll())
                    .containsExactlyInAnyOrder("global", "announce", "new-private")
                    .doesNotContain("old-private");

            handler.accept(new ConfigSyncPacket(PAYLOAD_NEW_ONLY, 2L));

            assertThat(freshRegistry.getAll())
                    .containsExactlyInAnyOrder("global", "announce", "new-private-2");
        }

        @Test
        @DisplayName("fresh and stale handlers are independent closures on the same payload")
        void freshAndStaleHandlersAreIndependent() {
            KnownChannelRegistry staleRegistry = new KnownChannelRegistry();
            RecordingNetworkClient staleClient = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(staleClient, staleRegistry, "old");

            KnownChannelRegistry freshRegistry = new KnownChannelRegistry();
            RecordingNetworkClient freshClient = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(freshClient, freshRegistry, "new");

            ConfigSyncPacket packet = new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L);
            staleClient.configSyncHandler().accept(packet);
            freshClient.configSyncHandler().accept(packet);

            assertThat(staleRegistry.getAll()).containsExactlyInAnyOrder("global", "announce", "old-private");
            assertThat(freshRegistry.getAll()).containsExactlyInAnyOrder("global", "announce", "new-private");
        }
    }

    @Nested
    @DisplayName("null / blank username through the registered handler")
    class NullBlankUsername {

        @Test
        @DisplayName("null username handler keeps only globals even when its row is advertised")
        void nullUsernameKeepsOnlyGlobals() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            RecordingNetworkClient client = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(client, registry, null);

            client.configSyncHandler().accept(new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L));

            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "announce");
        }

        @Test
        @DisplayName("blank username handler keeps only globals")
        void blankUsernameKeepsOnlyGlobals() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            RecordingNetworkClient client = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(client, registry, "   ");

            client.configSyncHandler().accept(new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L));

            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "announce");
        }

        @Test
        @DisplayName("null / blank payload JSON is a no-op: registry left untouched")
        void nullOrBlankJsonLeavesRegistryUntouched() {
            KnownChannelRegistry registry = new KnownChannelRegistry();
            RecordingNetworkClient client = new RecordingNetworkClient();
            ConfigSyncHandlerRegistrar.register(client, registry, "old");
            client.configSyncHandler().accept(new ConfigSyncPacket(PAYLOAD_BOTH_CLIENTS, 1L));
            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "announce", "old-private");

            client.configSyncHandler().accept(new ConfigSyncPacket(null, 2L));
            client.configSyncHandler().accept(new ConfigSyncPacket("   ", 3L));

            assertThat(registry.getAll()).containsExactlyInAnyOrder("global", "announce", "old-private");
        }
    }
}
