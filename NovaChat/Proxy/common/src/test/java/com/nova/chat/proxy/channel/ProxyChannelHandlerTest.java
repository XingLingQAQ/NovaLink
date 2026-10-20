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
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
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
    @DisplayName("a moderation response reaches the target's server, not just the source")
    void moderationResponseReachesTheTargetServer() throws Exception {
        // The muted player sits on a different sub-server than the operator.
        FakePlayer target = platform.add("Carol", "creative-02");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.MUTE, "global", "", "Player muted");
        response.addExtra("targetId", target.getUniqueId().toString());

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(response), null);

        handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE,
                chat("trigger")));

        assertThat(platform.sentMessages)
                .as("the target's server must receive the notice too")
                .anyMatch(sent -> sent.serverName.equals("creative-02"));
    }

    @Test
    @DisplayName("a rejected moderation command is not broadcast")
    void rejectedModerationIsNotBroadcast() throws Exception {
        FakePlayer target = platform.add("Carol", "creative-02");
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.MUTE, "global", "NC-403", "Insufficient permissions");
        response.addExtra("targetId", target.getUniqueId().toString());

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(response), null);

        handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE,
                chat("trigger")));

        assertThat(platform.sentMessages)
                .as("only the source server gets the error back")
                .allMatch(sent -> sent.serverName.equals(SOURCE));
    }
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

    // ------------------------------------------------------ proxy-side fan-out

    @Test
    @DisplayName("a proxy player's chat is pushed down to every server with players")
    void proxyChatIsBroadcastDownstream() throws Exception {
        FakePlayer carol = platform.add("Carol", "survival-02");
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(), null);

        ChatMessagePacket chat = chat("from the proxy");
        handler.broadcastProxyChat(chat);

        assertThat(platform.sentMessages).hasSize(2);
        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        for (SentMessage sent : platform.sentMessages) {
            ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(sent.payload);
            assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);
            assertThat(((ChatMessagePacket) decoded.getPacket()).getContent())
                    .isEqualTo("from the proxy");
            // Each server excludes exactly its own proxy players: those are the
            // ones the proxy already rendered the line to.
            Set<UUID> expected = SOURCE.equals(sent.serverName)
                    ? Set.of(alice.id, bob.id) : Set.of(carol.id);
            assertThat(decoded.getExcludedRecipients()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("chat from a sub-server reaches the other sub-servers' direct players")
    void upstreamChatIsBroadcastToOtherServers() throws Exception {
        FakePlayer carol = platform.add("Carol", "survival-02");
        ChatMessagePacket chat = chat("network wide");

        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(chat), null);
        handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE, chat));

        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        ProxyChannelProtocol.Decoded other = platform.sentMessages.stream()
                .filter(sent -> "survival-02".equals(sent.serverName))
                .map(sent -> {
                    try {
                        return ProxyChannelProtocol.decode(sent.payload);
                    } catch (ProxyChannelException e) {
                        throw new AssertionError(e);
                    }
                })
                .findFirst()
                .orElseThrow();
        // Only Carol is a proxy player on that server; the proxy rendered it to
        // her, so the sub-server must not render it again.
        assertThat(other.getExcludedRecipients()).containsExactly(carol.id);
    }

    @Test
    @DisplayName("relay mode still broadcasts a proxy player's chat downstream")
    void relayModeBroadcastsProxyChatDownstream() throws Exception {
        FakePlayer carol = platform.add("Carol", "survival-02");
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                null, packet -> { });

        handler.broadcastProxyChat(chat("relay chat"));

        // The relay backend only sees connections that exist on it; a sub-server
        // has none, so the proxy must carry the line down itself.
        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        Set<UUID> excludedOnSource =
                ProxyChannelProtocol.decode(platform.sentMessages.get(0).payload)
                        .getExcludedRecipients();
        assertThat(excludedOnSource).containsExactlyInAnyOrder(alice.id, bob.id);
        Set<UUID> excludedOnCarol =
                ProxyChannelProtocol.decode(platform.sentMessages.get(1).payload)
                        .getExcludedRecipients();
        assertThat(excludedOnCarol).containsExactly(carol.id);
    }

    @Test
    @DisplayName("a server whose send fails does not stop the other servers")
    void broadcastContinuesAfterOneFailedServer() throws Exception {
        platform.add("Carol", "survival-02");
        platform.refuseServer = SOURCE;
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(), null);

        handler.broadcastProxyChat(chat("one server is empty"));

        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactly("survival-02");
    }

    @Test
    @DisplayName("no downstream servers means no broadcast attempts")
    void broadcastWithoutPlayersDoesNothing() {
        platform.players.clear();
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(), null);

        handler.broadcastProxyChat(chat("nobody anywhere"));

        assertThat(platform.sentMessages).isEmpty();
    }

    // ------------------------------------------------- relay downstream fan-out

    /**
     * Builds the production relay graph: the shell binds the chat core's
     * down-path to the channel handler's broadcast (no lite authority, a relay
     * sink that goes nowhere in these tests).
     */
    private ProxyChannelHandler relayHandler() {
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                null, packet -> { });
        chatCore.bindDownstreamFanOut(handler::broadcastDownstream);
        return handler;
    }

    @Test
    @DisplayName("relay: a backend chat line is pushed down to every server with players")
    void relayBackendChatReachesEveryServer() throws Exception {
        FakePlayer carol = platform.add("Carol", "survival-02");
        relayHandler();

        chatCore.handleIncomingMessage(chat("from the backend"));

        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        for (SentMessage sent : platform.sentMessages) {
            ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(sent.payload);
            assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);
            assertThat(((ChatMessagePacket) decoded.getPacket()).getContent())
                    .isEqualTo("from the backend");
        }
        assertThat(carol.id).isNotNull();
    }

    @Test
    @DisplayName("relay: a proxy player sees the line once and is excluded downstream")
    void proxyPlayerDoesNotSeeTheLineTwice() throws Exception {
        FakePlayer carol = platform.add("Carol", "survival-02");
        relayHandler();

        chatCore.handleIncomingMessage(chat("dedup in relay"));

        // Rendered exactly once by the proxy's own loop...
        assertThat(alice.messages).hasSize(1);
        // ...and every server is told to skip precisely its own proxy players,
        // which is what stops the sub-server from rendering it again.
        for (SentMessage sent : platform.sentMessages) {
            Set<UUID> excluded =
                    ProxyChannelProtocol.decode(sent.payload).getExcludedRecipients();
            Set<UUID> expected = SOURCE.equals(sent.serverName)
                    ? Set.of(alice.id, bob.id) : Set.of(carol.id);
            assertThat(excluded)
                    .as("exclusion is per server for %s", sent.serverName)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("relay: a player proxied on server A does not suppress server B")
    void exclusionSetIsPerServer() throws Exception {
        // Move Bob to a second server; Alice stays proxied on the source.
        FakePlayer bobOnB = platform.add("Bob2", "survival-02");
        relayHandler();

        chatCore.handleIncomingMessage(chat("two servers, different rosters"));

        Set<UUID> excludedOnSource = ProxyChannelProtocol.decode(
                sentTo(SOURCE).payload).getExcludedRecipients();
        Set<UUID> excludedOnB = ProxyChannelProtocol.decode(
                sentTo("survival-02").payload).getExcludedRecipients();

        assertThat(excludedOnSource)
                .as("both proxy players on the source are excluded there")
                .containsExactlyInAnyOrder(alice.id, bob.id);
        assertThat(excludedOnB)
                .as("only the player on server B is excluded there")
                .containsExactly(bobOnB.id);
    }

    /** @param serverName the target server
     *  @return the single message sent to that server */
    private SentMessage sentTo(String serverName) {
        return platform.sentMessages.stream()
                .filter(sent -> serverName.equals(sent.serverName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing sent to " + serverName));
    }

    @Test
    @DisplayName("relay: a backend title is pushed down to every server with players")
    void relayBackendTitleReachesEveryServer() throws Exception {
        platform.add("Carol", "survival-02");
        relayHandler();

        chatCore.handleTitle(new TitlePacket("global", "Hello", "World",
                UUID.randomUUID()));

        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        assertThat(platform.sentMessages)
                .allSatisfy(sent -> assertThat(ProxyChannelProtocol.decode(sent.payload)
                        .getPacket()).isInstanceOf(TitlePacket.class));
    }

    @Test
    @DisplayName("relay: a backend mention is pushed down so a direct player is notified")
    void relayBackendMentionReachesEveryServer() throws Exception {
        platform.add("Carol", "survival-02");
        relayHandler();

        chatCore.handleMention(new MentionPacket(UUID.randomUUID(), "Alice",
                UUID.randomUUID(), "global", "hi @Bob", System.currentTimeMillis()));

        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        assertThat(platform.sentMessages)
                .allSatisfy(sent -> assertThat(ProxyChannelProtocol.decode(sent.payload)
                        .getPacket()).isInstanceOf(MentionPacket.class));
    }

    @Test
    @DisplayName("relay: a backend private message is pushed down for a direct target")
    void relayBackendPrivateMessageReachesEveryServer() throws Exception {
        platform.add("Carol", "survival-02");
        relayHandler();

        UUID senderId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        chatCore.handlePrivateMessage(new PrivateMessagePacket(senderId, "Alice",
                SOURCE, "Dave", targetId, "psst", System.currentTimeMillis()));

        // Neither party is on the proxy; the sub-servers are the only way this
        // DM can reach anybody.
        assertThat(platform.sentMessages)
                .extracting(sent -> sent.serverName)
                .containsExactlyInAnyOrder(SOURCE, "survival-02");
        assertThat(platform.sentMessages)
                .allSatisfy(sent -> assertThat(ProxyChannelProtocol.decode(sent.payload)
                        .getPacket()).isInstanceOf(PrivateMessagePacket.class));
    }

    @Test
    @DisplayName("relay: a failing downstream send warns once, then rate-limits")
    void droppedDeliveryWarnsOncePerServer() throws Exception {
        platform.refuseServer = SOURCE;
        relayHandler();

        for (int i = 0; i < 5; i++) {
            chatCore.handleIncomingMessage(chat("line " + i));
        }

        assertThat(platform.warnings)
                .as("one warning per server per interval, not one per line")
                .hasSize(1);
        assertThat(platform.warnings.get(0)).contains(SOURCE);
    }

    @Test
    @DisplayName("lite renders through the authority without a core fan-out binding")
    void litePathIsUnaffectedByTheRelayHook() throws Exception {
        ChatMessagePacket chat = chat("lite line");
        ProxyChannelHandler handler = new ProxyChannelHandler(platform, chatCore,
                request -> List.of(chat), null);

        handler.handleFromServer(SOURCE, ProxyChannelProtocol.encode(SOURCE, chat));

        // Exactly one downstream copy per server: the authority path broadcasts
        // for chat, and no core hook is bound, so nothing is sent twice.
        assertThat(platform.sentMessages)
                .as("lite chat is fanned out once, by the authority path")
                .hasSize(1);
        assertThat(platform.sentMessages.get(0).serverName).isEqualTo(SOURCE);
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
        private final List<String> warnings = new CopyOnWriteArrayList<>();
        private boolean refuseSends;
        /** Server whose sends fail (simulates "no players on that server"). */
        private String refuseServer;

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
        @Override public void logWarn(String message) { warnings.add(message); }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { }
        @Override public void logError(String message, Throwable cause) { }
        @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
        @Override public void reloadConfiguration() { }
        @Override public boolean sendPluginMessage(String serverName, String channel, byte[] payload) {
            if (refuseSends || (refuseServer != null && refuseServer.equals(serverName))) {
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
