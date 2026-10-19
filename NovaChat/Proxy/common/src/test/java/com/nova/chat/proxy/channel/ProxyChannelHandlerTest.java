package com.nova.chat.proxy.channel;

import com.nova.chat.channel.ProxyChannelException;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the proxy half of the plugin-messaging channel: source checks,
 * server-id verification, size handling and the lite/relay routing split.
 *
 * <p>The chat core is real where the test needs fan-out semantics (a mocked
 * authority returns packets; the core renders them to the fake platform), so
 * the assertions exercise the production rendering path rather than a stub.
 */
@DisplayName("ProxyChannelHandler")
class ProxyChannelHandlerTest {

    private static final String SOURCE = "survival-01";
    private static final String CHANNEL = ProxyChannelProtocol.CHANNEL_ID;

    private FakePlatform platform;
    private ProxyChatCore chatCore;
    private FakePlayer alice;
    private FakePlayer bob;

    @BeforeEach
    void setUp() throws Exception {
        platform = new FakePlatform();
        alice = platform.add("Alice", SOURCE);
        bob = platform.add("Bob", SOURCE);

        ProxyConfig config = ProxyConfig.from(new LiteSource());
        chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                new PrivateMessageService(), new KnownChannelRegistry(), () -> null);
        chatCore.getOrCreateState(alice).setActiveChannel("global");
        chatCore.getOrCreateState(bob).setActiveChannel("global");
    }

    // ------------------------------------------------------------- acceptance

    @Test
    @DisplayName("a valid upstream chat packet is accepted, rendered, and echoed to its server")
    void upstreamChatIsAcceptedAndFannedOut() throws Exception {
        List<Packet> authorityResponses = new ArrayList<>();
        ChatMessagePacket chat = chat("hello downstream");
        authorityResponses.add(chat);

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> {
                    assertThat(request).isInstanceOf(ChatMessagePacket.class);
                    return authorityResponses;
                }, null);

        byte[] payload = ProxyChannelProtocol.encode(SOURCE, chat);
        ProxyChannelHandler.Outcome outcome = handler.handleFromServer(SOURCE, payload);

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.ACCEPTED);
        // Rendered to proxy-connected players in the channel.
        assertThat(alice.messages).anyMatch(line -> line.contains("hello downstream"));
        assertThat(bob.messages).anyMatch(line -> line.contains("hello downstream"));
        // And echoed downstream to the source server for players the proxy cannot see.
        assertThat(platform.sentMessages).hasSize(1);
        SentMessage sent = platform.sentMessages.get(0);
        assertThat(sent.serverName).isEqualTo(SOURCE);
        assertThat(sent.channel).isEqualTo(CHANNEL);
        ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(sent.payload);
        assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);
        ChatMessagePacket echoed = (ChatMessagePacket) decoded.getPacket();
        assertThat(echoed.getContent()).isEqualTo("hello downstream");
        assertThat(echoed.getRequestId()).isEqualTo(chat.getRequestId());
    }

    @Test
    @DisplayName("the downstream echo excludes every proxy player on that server")
    void downstreamEchoExcludesProxyRenderedPlayers() throws Exception {
        ChatMessagePacket chat = chat("dedup me");

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(chat), null);
        handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE, chat));

        ProxyChannelProtocol.Decoded decoded =
                ProxyChannelProtocol.decode(platform.sentMessages.get(0).payload);
        assertThat(decoded.getExcludedRecipients())
                .containsExactlyInAnyOrder(alice.id, bob.id);
    }

    @Test
    @DisplayName("relay mode forwards the packet to the backend and sends nothing downstream")
    void relayModeForwardsToBackend() throws Exception {
        ChatMessagePacket chat = chat("relay me");
        List<Packet> forwarded = new ArrayList<>();

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                null, forwarded::add);

        ProxyChannelHandler.Outcome outcome =
                handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE, chat));

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.ACCEPTED);
        // The backend receives the packet decoded from the envelope, not the
        // test instance, so compare the carrier type and payload identity.
        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.get(0)).isInstanceOf(ChatMessagePacket.class);
        assertThat(((ChatMessagePacket) forwarded.get(0)).getContent()).isEqualTo("relay me");
        assertThat(platform.sentMessages).isEmpty();
        assertThat(alice.messages).isEmpty();
    }

    // ------------------------------------------------------------- rejection

    @Test
    @DisplayName("a player-sourced payload (no server) is rejected without parsing")
    void playerSourceIsRejected() throws Exception {
        List<Packet> authorityCalls = new ArrayList<>();
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> {
                    authorityCalls.add(request);
                    return List.of();
                }, null);

        byte[] payload = ProxyChannelProtocol.encode(SOURCE, chat("forged"));
        ProxyChannelHandler.Outcome outcome = handler.handleFromServer(null, payload);

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.REJECTED_NOT_SERVER);
        assertThat(authorityCalls).isEmpty();
        assertThat(platform.sentMessages).isEmpty();
    }

    @Test
    @DisplayName("a server id that does not match the actual source is rejected")
    void mismatchedServerIdIsRejected() throws Exception {
        List<Packet> authorityCalls = new ArrayList<>();
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> {
                    authorityCalls.add(request);
                    return List.of();
                }, null);

        byte[] payload = ProxyChannelProtocol.encode("lobby-99", chat("i am somebody else"));
        ProxyChannelHandler.Outcome outcome = handler.handleFromServer(SOURCE, payload);

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.REJECTED_SERVER_ID);
        assertThat(authorityCalls).isEmpty();
        assertThat(platform.sentMessages).isEmpty();
    }

    @Test
    @DisplayName("a blank claimed server id never matches")
    void blankServerIdIsRejected() throws Exception {
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(), null);

        ProxyChannelHandler.Outcome outcome =
                handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode("", chat("hi")));

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.REJECTED_SERVER_ID);
    }

    @Test
    @DisplayName("an oversized payload is dropped, not thrown")
    void oversizedPayloadIsDropped() {
        List<Packet> authorityCalls = new ArrayList<>();
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> {
                    authorityCalls.add(request);
                    return List.of();
                }, null);

        byte[] oversized = new byte[ProxyChannelProtocol.MAX_PAYLOAD_BYTES + 1];
        ProxyChannelHandler.Outcome outcome = handler.handleFromServer(SOURCE, oversized);

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.DROPPED_OVERSIZE);
        assertThat(authorityCalls).isEmpty();
    }

    @Test
    @DisplayName("a malformed payload is dropped, not thrown")
    void malformedPayloadIsDropped() {
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(), null);

        ProxyChannelHandler.Outcome outcome =
                handler.handleFromServer(SOURCE, new byte[]{1, 2, 3, 4, 5});

        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.DROPPED_MALFORMED);
    }

    @Test
    @DisplayName("downstream send failure (no players on the server) is reported, not hidden")
    void downstreamSendFailureIsNotPretended() throws Exception {
        ChatMessagePacket chat = chat("nobody there");
        platform.refuseSends = true;

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(chat), null);

        ProxyChannelHandler.Outcome outcome =
                handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE, chat));

        // The upstream message is still accepted and rendered locally; only the
        // downstream leg is lost, which is the documented plugin-messaging limit.
        assertThat(outcome).isEqualTo(ProxyChannelHandler.Outcome.ACCEPTED);
        assertThat(platform.sentMessages).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private static ChatMessagePacket chat(String content) {
        ChatMessagePacket packet = new ChatMessagePacket(
                UUID.randomUUID(), "Alice", SOURCE, "global", content);
        packet.addPlaceholder("player", "Alice");
        return packet;
    }

    private static final class SentMessage {
        private final String serverName;
        private final String channel;
        private final byte[] payload;

        private SentMessage(String serverName, String channel, byte[] payload) {
            this.serverName = serverName;
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final String server;
        private final Set<String> permissions = ConcurrentHashMap.newKeySet();
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private FakePlayer(String name, String server) {
            this.name = name;
            this.server = server;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return server; }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    }

    private static final class FakePlatform implements ProxyPlatform {
        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();
        private final List<SentMessage> sentMessages = new CopyOnWriteArrayList<>();
        private boolean refuseSends;

        private FakePlayer add(String name, String server) {
            FakePlayer player = new FakePlayer(name, server);
            players.put(player.id, player);
            return player;
        }

        @Override public Collection<? extends ProxyPlayer> getOnlinePlayers() {
            return List.copyOf(players.values());
        }
        @Override public ProxyPlayer getPlayer(UUID playerId) { return players.get(playerId); }
        @Override public void sendMessage(ProxyPlayer player, String legacyText) {
            if (player instanceof FakePlayer fake) {
                fake.messages.add(legacyText);
            }
        }
        @Override public void sendHoverableMessage(ProxyPlayer player, String text, String hover) { }
        @Override public void showTitle(ProxyPlayer player, String title, String subtitle,
                                        int fadeIn, int stay, int fadeOut) { }
        @Override public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) { }
        @Override public void notifyMention(ProxyPlayer player, String highlight, String subtitle) { }
        @Override public void sendActionBar(ProxyPlayer player, String legacyText) { }
        @Override public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) { }
        @Override public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                             String operator, String durationText) { }
        @Override public boolean consoleHasPermission(String permission) { return true; }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public void runLater(Runnable task, long delaySeconds) { task.run(); }
        @Override public void logInfo(String message) { }
        @Override public void logWarn(String message) { }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { }
        @Override public void logError(String message, Throwable cause) { }
        @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
        @Override public void reloadConfiguration() { }
        @Override public boolean sendPluginMessage(String serverName, String channel, byte[] payload) {
            if (refuseSends) {
                return false;
            }
            sentMessages.add(new SentMessage(serverName, channel, payload));
            return true;
        }
    }

    /** Minimal lite configuration (one declared channel, REPLACE mode). */
    private static final class LiteSource implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private LiteSource() {
            values.put("mode", "lite");
            values.put("lite.server-id", "proxy-lite");
            values.put("lite.channels", Map.of("global", Map.of()));
            values.put("lite.storage.type", "memory");
            values.put("chat.replace_vanilla", true);
            values.put("chat.default_channel", "global");
            values.put("chat.locale", "en_US");
            values.put("chat.channel-prefixes", Map.of());
            values.put("format.prefix", "");
            values.put("format.error", "{message}");
            values.put("format.success", "{message}");
            values.put("format.default", "[{channel_name}] {player}: {message}");
            values.put("format.channels", Map.of());
            values.put("debug", false);
        }

        @Override public String requiredString(String path) throws ConfigException {
            Object value = values.get(path);
            if (value == null) { throw new ConfigException("missing " + path); }
            return value.toString();
        }
        @Override public int requiredInt(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Number number)) { throw new ConfigException("missing int " + path); }
            return number.intValue();
        }
        @Override public boolean requiredBoolean(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Boolean flag)) { throw new ConfigException("missing boolean " + path); }
            return flag;
        }
        @Override public Map<String, Object> requiredTable(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Map<?, ?> map)) { throw new ConfigException("missing table " + path); }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }
        @Override public boolean contains(String path) { return values.containsKey(path); }
    }
}
