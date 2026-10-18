package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.config.ProxyMode;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relay-mode regression guard for the lite additions.
 *
 * <p>Lite must be additive: a relay configuration yields an empty
 * {@code lite.channels} map, so wiring a {@link LocalAuthority} into a relay
 * process (which the shells never do) would still treat every channel as
 * unknown. More importantly, {@link LocalAuthorityClient} is only created by
 * the lite branch — the relay graph (Netty {@code NetworkClient}) is untouched
 * by these classes.
 *
 * <p>The assertions here pin the two behaviours that make that safe: the relay
 * config view exposes no lite channels, and the authority rejects channel
 * traffic when driven against it (rather than silently inventing channels).
 */
@DisplayName("Relay mode stays unaffected by the lite local authority")
class RelayModeRegressionTest {

    private FakePlatform platform;
    private FakePlayer alice;
    private ProxyChatCore chatCore;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws com.nova.chat.proxy.config.ConfigSource.ConfigException {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        platform = new FakePlatform();
        alice = platform.add("Alice");
        alice.permissions.add("novachat.channel.global");

        ProxyConfig config = ProxyConfig.from(new RelaySource());
        chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                new PrivateMessageService(), new KnownChannelRegistry(), () -> null);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    @Test
    @DisplayName("relay config exposes no lite channels")
    void relayConfigHasNoLiteChannels() {
        ProxyConfigView view = chatCore.getStates().getConfig();

        assertThat(view.getMode()).isEqualTo(ProxyMode.RELAY);
        assertThat(view.getLiteChannels()).isEmpty();
        assertThat(view.getClientId()).isEqualTo("relay-username");
    }

    @Test
    @DisplayName("the authority never treats a relay channel as locally declared")
    void authorityHasNoChannelsInRelay() {
        LocalAuthority authority = new LocalAuthority(platform, chatCore, new IgnoreListService());

        List<Packet> downstream = authority.handle(new ChatMessagePacket(
                alice.id, alice.name, "relay-username", "global", "hello"));

        assertThat(downstream)
                .as("no chat fan-out without lite.channels")
                .isEmpty();
        assertThat(alice.messages)
                .as("the sender is told the channel does not exist (NC-404)")
                .anyMatch(line -> line.contains("NC-404"));
    }

    @Test
    @DisplayName("chat mode defaults are derived from relay config, not lite")
    void relayChatModeIsUnchanged() {
        // replace_vanilla=false in relay → HYBRID, exactly as before lite existed.
        assertThat(chatCore.getGlobalMode()).isEqualTo(ChatMode.HYBRID);
    }

    // ---------------------------------------------------------------- fixtures

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Set<String> permissions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final List<String> messages = new ArrayList<>();

        private FakePlayer(String name) {
            this.name = name;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return null; }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    }

    private static final class FakePlatform implements ProxyPlatform {
        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();

        private FakePlayer add(String name) {
            FakePlayer player = new FakePlayer(name);
            players.put(player.id, player);
            return player;
        }

        @Override public Collection<? extends ProxyPlayer> getOnlinePlayers() { return List.copyOf(players.values()); }
        @Override public ProxyPlayer getPlayer(UUID playerId) { return players.get(playerId); }
        @Override public void sendMessage(ProxyPlayer player, String legacyText) {
            ((FakePlayer) player).messages.add(legacyText);
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
        @Override public void execute(Runnable task) { }
        @Override public void runAsync(Runnable task) { }
        @Override public void runLater(Runnable task, long delaySeconds) { }
        @Override public void logInfo(String message) { }
        @Override public void logWarn(String message) { }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { }
        @Override public void logError(String message, Throwable cause) { }
        @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
        @Override public void reloadConfiguration() { }
    }

    /** The bundled relay template shape (no lite section at all). */
    private static final class RelaySource implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private RelaySource() {
            values.put("mode", "relay");
            values.put("backend.host", "127.0.0.1");
            values.put("backend.port", 8888L);
            values.put("backend.username", "relay-username");
            values.put("backend.password", "secret");
            values.put("backend.reconnect-delay", 5L);
            values.put("chat.replace_vanilla", false);
            values.put("chat.default_channel", "local");
            values.put("chat.locale", "zh_CN");
            values.put("chat.channel-prefixes", Map.of());
            values.put("format.prefix", "");
            values.put("format.error", "{message}");
            values.put("format.success", "{message}");
            values.put("format.default", "[{channel_name}] {player}: {message}");
            values.put("format.channels", Map.of("global", "[{channel_name}] {player}: {message}"));
            values.put("debug", false);
        }

        @Override public String requiredString(String path) throws ConfigException {
            Object value = values.get(path);
            if (value == null) {
                throw new ConfigException("missing " + path);
            }
            return value.toString();
        }
        @Override public int requiredInt(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Number number)) {
                throw new ConfigException("missing int " + path);
            }
            return number.intValue();
        }
        @Override public boolean requiredBoolean(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Boolean flag)) {
                throw new ConfigException("missing boolean " + path);
            }
            return flag;
        }
        @Override public Map<String, Object> requiredTable(String path) throws ConfigException {
            Object value = values.get(path);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException("missing table " + path);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }
        @Override public boolean contains(String path) { return values.containsKey(path); }
    }
}
