package com.nova.chat.proxy.relay;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.nova.chat.proxy.relay.RelayTestFixture.FakeFactory;
import static com.nova.chat.proxy.relay.RelayTestFixture.FakeLinkClient;
import static com.nova.chat.proxy.relay.RelayTestFixture.FakePlatform;
import static com.nova.chat.proxy.relay.RelayTestFixture.FakePlayer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relay link set: one backend connection per downstream server.
 *
 * <p>These tests pin the defect this layer fixes. The backend attributes a
 * player and every packet on a connection to that connection's client id, so a
 * single shared proxy connection made every sub-server one client: mentions,
 * titles and DMs looked up the wrong connection, and SERVER/PRIVATE channel
 * ownership collapsed. The assertions here cover both directions plus the
 * lifecycle and the backward-compatible single-link configuration.
 */
@DisplayName("RelayBackendLinks")
class RelayBackendLinksTest {

    private FakePlatform platform;
    private FakeFactory factory;
    private ProxyChatCore chatCore;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        factory = new FakeFactory();
        chatCore = new ProxyChatCore(platform, relayConfig(), new IgnoreListService(),
                new PrivateMessageService(), new KnownChannelRegistry(), () -> null);
    }

    /**
     * Relay config with the two per-server identities used by most tests.
     *
     * @return the parsed configuration
     */
    private static ProxyConfig relayConfig()
            throws com.nova.chat.proxy.config.ConfigSource.ConfigException {
        return ProxyConfig.from(new MapSource()
                .put("mode", "relay")
                .put("backend.host", "backend.internal")
                .put("backend.port", 8888L)
                .put("backend.username", "")
                .put("backend.password", "secret")
                .put("backend.reconnect-delay", 5L)
                .put("backend.identities", new LinkedHashMap<>(Map.of(
                        "survival-01", "survival-01",
                        "creative-02", "creative-02")))
                .put("chat.replace_vanilla", true)
                .put("chat.default_channel", "global")
                .put("chat.locale", "en_US")
                .put("chat.channel-prefixes", new LinkedHashMap<String, Object>())
                .put("format.prefix", "")
                .put("format.error", "{message}")
                .put("format.success", "{message}")
                .put("format.default", "[{channel_name}] {player}: {message}")
                .put("format.channels", new LinkedHashMap<String, Object>())
                .put("debug", false));
    }

    /**
     * @return the two-server identity map, in declaration order (the config is
     *         an ordered map, so the link order is deterministic)
     */
    private static Map<String, String> identities() {
        Map<String, String> identities = new LinkedHashMap<>();
        identities.put("survival-01", "survival-01");
        identities.put("creative-02", "creative-02");
        return identities;
    }

    private RelayBackendLinks links(String defaultIdentity, Map<String, String> identities) {
        RelayBackendLinks links = new RelayBackendLinks(platform, factory,
                defaultIdentity, identities);
        links.bindInbound(chatCore::handleInbound);
        return links;
    }

    // ------------------------------------------------------------ connections

    @Test
    @DisplayName("two downstream servers each get their own connection with a distinct id")
    void twoServersGetDistinctConnections() {
        RelayBackendLinks links = links(null, identities());
        links.start("backend.internal", 8888);

        List<RelayBackendLinks.Link> created = links.links();
        assertThat(created).hasSize(2);
        assertThat(created).extracting(RelayBackendLinks.Link::getIdentity)
                .containsExactly("survival-01", "creative-02");
        assertThat(links.clientFor("survival-01"))
                .isNotSameAs(links.clientFor("creative-02"));
        assertThat(links.identityFor("survival-01")).isEqualTo("survival-01");
        assertThat(links.identityFor("creative-02")).isEqualTo("creative-02");
        // Server names arrive case-insensitively from the plugin-message
        // envelope's source check.
        assertThat(links.clientFor("SURVIVAL-01")).isSameAs(links.clientFor("survival-01"));
    }

    @Test
    @DisplayName("an upstream packet from server X goes out on X's connection only")
    void upstreamIsSentOnTheSourceServersLink() {
        RelayBackendLinks links = links(null, identities());
        links.start("backend.internal", 8888);

        ChatMessagePacket chat = new ChatMessagePacket(UUID.randomUUID(), "Alice",
                "survival-01", "global", "hi");
        assertThat(links.sendUpstream("survival-01", chat)).isTrue();

        assertThat(factory.created.get("survival-01").sent).containsExactly(chat);
        assertThat(factory.created.get("creative-02").sent).isEmpty();
    }

    @Test
    @DisplayName("an upstream packet from an unmapped server uses the fallback link")
    void unmappedServerUsesFallbackLink() {
        RelayBackendLinks links = links("proxy", identities());
        links.start("backend.internal", 8888);

        assertThat(links.sendUpstream("lobby-03", chat("hi"))).isTrue();
        assertThat(factory.created.get("proxy").sent).hasSize(1);
    }

    @Test
    @DisplayName("a failed connection for one server does not stop the others")
    void oneFailedLinkDoesNotAffectTheOthers() {
        RelayBackendLinks links = links(null, identities());
        links.start("backend.internal", 8888);
        FakeLinkClient failed = factory.created.get("survival-01");
        failed.authenticated.set(false);

        assertThat(links.sendUpstream("survival-01", chat("lost"))).isFalse();
        assertThat(links.sendUpstream("creative-02", chat("still works"))).isTrue();
        assertThat(factory.created.get("creative-02").sent).hasSize(1);
        assertThat(failed.sent).isEmpty();
    }

    @Test
    @DisplayName("closing one link leaves the others usable")
    void closingOneLinkLeavesTheOthersUsable() {
        RelayBackendLinks links = links(null, identities());
        links.start("backend.internal", 8888);

        factory.created.get("survival-01").disconnect();

        assertThat(links.sendUpstream("survival-01", chat("down"))).isFalse();
        assertThat(links.sendUpstream("creative-02", chat("up"))).isTrue();
    }

    @Test
    @DisplayName("close() disconnects every link")
    void closeDisconnectsEveryLink() {
        RelayBackendLinks links = links("proxy", identities());
        links.start("backend.internal", 8888);
        // Fallback plus one per mapped server.
        assertThat(factory.order).hasSize(3);

        links.close();

        assertThat(factory.order).allMatch(client -> client.disconnected.get());
        assertThat(links.links()).isEmpty();
        assertThat(links.clientFor("survival-01")).isNull();
    }

    @Test
    @DisplayName("an empty identity map yields exactly one connection, addressed as before")
    void singleServerConfigProducesOneConnection() {
        RelayBackendLinks links = links("proxy", Map.of());
        links.start("backend.internal", 8888);

        assertThat(links.links()).hasSize(1);
        assertThat(links.getFallbackLink().getIdentity()).isEqualTo("proxy");
        assertThat(links.hasDedicatedLinks()).isFalse();
        assertThat(links.clientFor("anything")).isSameAs(links.getFallbackLink().getClient());
        assertThat(links.identityFor("anything")).isEqualTo("proxy");
        // The legacy identity still connects to the configured backend.
        assertThat(factory.created.get("proxy").connectHosts)
                .containsExactly("backend.internal:8888");
    }

    @Test
    @DisplayName("a server with no players still keeps its link (traffic keeps it alive)")
    void emptyPopulationDoesNotTearTheLinkDown() {
        RelayBackendLinks links = links(null, Map.of("survival-01", "survival-01"));
        links.start("backend.internal", 8888);

        // No players anywhere; the link is still connected and usable.
        assertThat(platform.getOnlinePlayers()).isEmpty();
        assertThat(links.sendUpstream("survival-01", chat("console command"))).isTrue();
        assertThat(factory.created.get("survival-01").sent).hasSize(1);
    }

    // --------------------------------------------------------------- downstream

    /** Collects targeted downstream sends. */
    private static final class TargetRecorder implements RelayBackendLinks.TargetedSender {
        private final List<String> servers = new CopyOnWriteArrayList<>();
        private final List<Packet> packets = new CopyOnWriteArrayList<>();

        @Override
        public boolean send(String serverName, Packet packet) {
            servers.add(serverName);
            packets.add(packet);
            return true;
        }
    }

    private static final class BroadcastRecorder implements RelayBackendLinks.BroadcastSender {
        private final List<Packet> packets = new CopyOnWriteArrayList<>();

        @Override
        public void send(Packet packet) {
            packets.add(packet);
        }
    }

    @Test
    @DisplayName("a downstream packet on X's connection is delivered to X, not broadcast")
    void downstreamOnXGoesToXOnly() {
        FakePlayer alice = platform.add("Alice", "survival-01");
        FakePlayer bob = platform.add("Bob", "creative-02");
        chatCore.getOrCreateState(alice).setActiveChannel("global");
        chatCore.getOrCreateState(bob).setActiveChannel("global");

        RelayBackendLinks links = links(null, identities());
        TargetRecorder targeted = new TargetRecorder();
        BroadcastRecorder broadcast = new BroadcastRecorder();
        links.bindDownstream(targeted, broadcast);
        links.start("backend.internal", 8888);

        ChatMessagePacket chat = chat("from the backend");
        // The backend pushed this copy on survival-01's connection.
        factory.created.get("survival-01").deliver(chat);

        assertThat(targeted.servers)
                .as("the packet belongs to survival-01 only")
                .containsExactly("survival-01");
        assertThat(broadcast.packets)
                .as("a per-server copy must never be rebroadcast")
                .isEmpty();
        // The proxy renders from that same copy, but only to the players on
        // the server whose link carried it: Bob gets the backend's own copy on
        // creative-02's link, not this one.
        assertThat(alice.messages).hasSize(1);
        assertThat(bob.messages).isEmpty();
    }

    @Test
    @DisplayName("a fallback-connection packet covers the servers without a link")
    void fallbackConnectionCoversUnroutedServers() {
        FakePlayer alice = platform.add("Alice", "survival-01");
        FakePlayer lobby = platform.add("Lobby", "lobby-03");
        chatCore.getOrCreateState(alice).setActiveChannel("global");
        chatCore.getOrCreateState(lobby).setActiveChannel("global");

        RelayBackendLinks links = links("proxy", identities());
        TargetRecorder targeted = new TargetRecorder();
        BroadcastRecorder broadcast = new BroadcastRecorder();
        links.bindDownstream(targeted, broadcast);
        links.start("backend.internal", 8888);

        factory.created.get("proxy").deliver(chat("fallback copy"));

        assertThat(targeted.servers)
                .as("only the server without its own link, not the routed one")
                .containsExactly("lobby-03");
        assertThat(broadcast.packets).isEmpty();
    }

    @Test
    @DisplayName("with no identities at all the fallback link broadcasts, as before")
    void legacyFallbackBroadcastsToEveryServer() {
        platform.add("Alice", "survival-01");
        platform.add("Bob", "creative-02");

        RelayBackendLinks links = links("proxy", Map.of());
        TargetRecorder targeted = new TargetRecorder();
        BroadcastRecorder broadcast = new BroadcastRecorder();
        links.bindDownstream(targeted, broadcast);
        links.start("backend.internal", 8888);

        factory.created.get("proxy").deliver(chat("legacy line"));

        assertThat(broadcast.packets).hasSize(1);
        assertThat(targeted.servers).isEmpty();
    }

    @Test
    @DisplayName("a scoped copy renders only to the proxy players on that server")
    void scopedRenderTargetsThatServersPlayers() {
        FakePlayer alice = platform.add("Alice", "survival-01");
        FakePlayer bob = platform.add("Bob", "creative-02");
        chatCore.getOrCreateState(alice).setActiveChannel("global");
        chatCore.getOrCreateState(bob).setActiveChannel("global");

        RelayBackendLinks links = links(null, identities());
        links.bindDownstream(new TargetRecorder(), new BroadcastRecorder());
        links.start("backend.internal", 8888);

        chatCore.handleInbound(factory.created.get("survival-01"), Set.of("survival-01"),
                chat("scoped"));

        // Only Alice is on survival-01, and only she may render this copy. Bob
        // is on creative-02 and receives the backend's own copy on that link.
        assertThat(alice.messages).hasSize(1);
        assertThat(bob.messages).isEmpty();
    }

    @Test
    @DisplayName("a mention arriving on X's connection is delivered to X only")
    void mentionDownstreamIsScopedToTheTargetServer() {
        platform.add("Alice", "survival-01");
        platform.add("Bob", "creative-02");

        RelayBackendLinks links = links(null, identities());
        TargetRecorder targeted = new TargetRecorder();
        links.bindDownstream(targeted, new BroadcastRecorder());
        links.start("backend.internal", 8888);

        MentionPacket mention = new MentionPacket(UUID.randomUUID(), "Alice",
                UUID.randomUUID(), "global", "hi", System.currentTimeMillis());
        factory.created.get("creative-02").deliver(mention);

        assertThat(targeted.servers).containsExactly("creative-02");
    }

    @Test
    @DisplayName("a private message copy is delivered to the connection's server")
    void privateMessageDownstreamIsScoped() {
        platform.add("Alice", "survival-01");
        platform.add("Bob", "creative-02");

        RelayBackendLinks links = links(null, identities());
        TargetRecorder targeted = new TargetRecorder();
        links.bindDownstream(targeted, new BroadcastRecorder());
        links.start("backend.internal", 8888);

        PrivateMessagePacket pm = new PrivateMessagePacket(UUID.randomUUID(), "Alice",
                "survival-01", "Bob", UUID.randomUUID(), "psst", System.currentTimeMillis());
        factory.created.get("survival-01").deliver(pm);

        assertThat(targeted.servers).containsExactly("survival-01");
    }

    // ------------------------------------------------- player → server attribution

    @Test
    @DisplayName("a player who switches server is attributed to the new server afterwards")
    void playerSwitchIsAttributedToTheNewServer() {
        FakePlayer alice = platform.add("Alice", "survival-01");
        chatCore.getOrCreateState(alice);

        RelayBackendLinks links = links(null, identities());
        links.bindDownstream(new TargetRecorder(), new BroadcastRecorder());
        links.start("backend.internal", 8888);
        chatCore.setBackendRouter(links);

        com.nova.chat.proxy.engine.ProxyServerSwitchCore switchCore =
                new com.nova.chat.proxy.engine.ProxyServerSwitchCore(platform, chatCore);

        // First connection: the default-channel JOIN rides survival-01's link.
        switchCore.onServerConnected(alice, "survival-01");
        assertThat(joinPackets(factory.created.get("survival-01")))
                .as("the first JOIN is carried by the server the player was on")
                .hasSize(1);
        assertThat(factory.created.get("creative-02").sent).isEmpty();

        // Server switch: the re-JOIN must ride creative-02's link. The backend
        // sets PlayerState#clientId from the connection that carried the JOIN
        // (ChannelActionHandler#upsertPlayerState), so this is what moves the
        // player's attribution to the new server.
        alice.server = "creative-02";
        switchCore.onServerConnected(alice, "creative-02");

        List<com.nova.chat.common.protocol.packets.ChannelActionPacket> secondServer =
                joinPackets(factory.created.get("creative-02"));
        assertThat(secondServer)
                .as("the post-switch JOIN is carried by creative-02, not the old link")
                .hasSize(1);
        assertThat(secondServer.get(0).getExtra("server")).isEqualTo("creative-02");
        // The old link keeps exactly the original JOIN; the new attribution did
        // not go through it.
        assertThat(joinPackets(factory.created.get("survival-01"))).hasSize(1);
        // The identity stamped on the player's packets follows the new server.
        assertThat(chatCore.getIdentityForPlayer(alice.id)).isEqualTo("creative-02");
        assertThat(chatCore.getNetworkClientForPlayer(alice.id))
                .isSameAs(factory.created.get("creative-02"));
    }

    private static List<com.nova.chat.common.protocol.packets.ChannelActionPacket> joinPackets(
            FakeLinkClient client) {
        List<com.nova.chat.common.protocol.packets.ChannelActionPacket> joins =
                new java.util.ArrayList<>();
        for (Packet packet : client.sent) {
            if (packet instanceof com.nova.chat.common.protocol.packets.ChannelActionPacket action
                    && action.getAction() == com.nova.chat.common.protocol.ChannelAction.JOIN) {
                joins.add(action);
            }
        }
        return joins;
    }

    // ------------------------------------------------------- minimal config source

    private static ChatMessagePacket chat(String content) {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", "survival-01",
                "global", content);
    }

    /** Flat dot-path config source (same shape the other proxy tests use). */
    private static final class MapSource implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        MapSource put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override public String requiredString(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof String text)) {
                throw new ConfigException("missing " + key);
            }
            return text;
        }
        @Override public int requiredInt(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Number number)) {
                throw new ConfigException("missing " + key);
            }
            return number.intValue();
        }
        @Override public boolean requiredBoolean(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Boolean flag)) {
                throw new ConfigException("missing " + key);
            }
            return flag;
        }
        @Override public Map<String, Object> requiredTable(String key) throws ConfigException {
            Object value = values.get(key);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException("missing " + key);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }
    }
}
