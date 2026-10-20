package com.nova.chat.proxy.lite;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.proxy.config.ConfigSource;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory doubles and config fixture shared by the lite authority tests.
 *
 * <p>Every {@code LocalAuthority*Test} needs the same three things: players the
 * authority can look up, a platform that records what it renders, and a config
 * it can parse. Keeping one copy here means a change to {@link ProxyPlatform}
 * touches one file instead of eight, and a test that wants to assert on a
 * rendering needs no local plumbing.
 */
final class LiteTestFixture {

    private LiteTestFixture() {
    }

    /**
     * A player the authority can resolve, with recorded fan-out.
     */
    static final class FakePlayer implements ProxyPlayer {

        final UUID id = UUID.randomUUID();
        final String name;
        final Set<String> permissions = ConcurrentHashMap.newKeySet();

        /** Chat lines and hoverable lines, in delivery order. */
        final List<String> messages = new ArrayList<>();

        /** Titles rendered as {@code "title|subtitle"}. */
        final List<String> titles = new ArrayList<>();

        /** Mention notices rendered as {@code "highlight|subtitle"}. */
        final List<String> mentions = new ArrayList<>();

        /** Title timings rendered as {@code "fadeIn/stay/fadeOut"}. */
        final List<String> titleTimings = new ArrayList<>();

        FakePlayer(String name) {
            this.name = name;
        }

        @Override
        public UUID getUniqueId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public String getCurrentServer() {
            return null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }
    }

    /**
     * Platform double: an online roster plus capture of everything rendered.
     */
    static final class FakePlatform implements ProxyPlatform {

        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();

        FakePlayer add(String name) {
            FakePlayer player = new FakePlayer(name);
            players.put(player.id, player);
            return player;
        }

        /**
         * Drops a player from the online roster.
         *
         * <p>Needed by tests of offline-only paths: a name lookup resolves
         * against this roster first, so as long as the player is present the
         * fallback being tested is never reached.
         *
         * @param player the player to remove
         */
        void remove(FakePlayer player) {
            players.remove(player.id);
        }

        @Override
        public Collection<? extends ProxyPlayer> getOnlinePlayers() {
            return List.copyOf(players.values());
        }

        @Override
        public ProxyPlayer getPlayer(UUID playerId) {
            return players.get(playerId);
        }

        @Override
        public void sendMessage(ProxyPlayer player, String legacyText) {
            ((FakePlayer) player).messages.add(legacyText);
        }

        @Override
        public void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText) {
            ((FakePlayer) player).messages.add(legacyText);
        }

        @Override
        public void showTitle(ProxyPlayer player, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut) {
            FakePlayer fake = (FakePlayer) player;
            fake.titles.add(title + "|" + subtitle);
            fake.titleTimings.add(fadeIn + "/" + stay + "/" + fadeOut);
        }

        @Override
        public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) {
        }

        @Override
        public void notifyMention(ProxyPlayer player, String highlight, String subtitle) {
            ((FakePlayer) player).mentions.add(highlight + "|" + subtitle);
        }

        @Override
        public void sendActionBar(ProxyPlayer player, String legacyText) {
        }

        @Override
        public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) {
        }

        @Override
        public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                   String operator, String durationText) {
        }

        @Override
        public boolean consoleHasPermission(String permission) {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            task.run();
        }

        @Override
        public void logInfo(String message) {
        }

        @Override
        public void logWarn(String message) {
        }

        @Override
        public void logDebug(String message) {
        }

        @Override
        public void logError(String message) {
        }

        @Override
        public void logError(String message, Throwable cause) {
        }

        @Override
        public PlatformType getPlatformType() {
            return PlatformType.VELOCITY;
        }

        @Override
        public void reloadConfiguration() {
        }
    }

    /**
     * Flat-key config source mirroring the bundled lite template.
     *
     * <p>Resolves a dotted path against the longest flat-key prefix, then walks
     * the remaining segments through nested maps, matching the real dialect
     * sources.
     */
    static final class MapSource implements ConfigSource {

        private final Map<String, Object> values = new LinkedHashMap<>();

        MapSource(Map<String, Object> values) {
            this.values.putAll(values);
        }

        private Object lookup(String path) {
            String[] segments = path.split("\\.");
            for (int length = segments.length; length > 0; length--) {
                String prefix = String.join(".", Arrays.copyOf(segments, length));
                if (!values.containsKey(prefix)) {
                    continue;
                }
                Object current = values.get(prefix);
                for (int i = length; i < segments.length; i++) {
                    if (!(current instanceof Map<?, ?> map)) {
                        return null;
                    }
                    current = map.get(segments[i]);
                    if (current == null) {
                        return null;
                    }
                }
                return current;
            }
            return null;
        }

        @Override
        public String requiredString(String path) throws ConfigException {
            Object value = lookup(path);
            if (value == null) {
                throw new ConfigException("missing " + path);
            }
            return value.toString();
        }

        @Override
        public String optionalString(String path) {
            Object value = lookup(path);
            return value != null ? value.toString() : null;
        }

        @Override
        public int requiredInt(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Number number)) {
                throw new ConfigException("missing int " + path);
            }
            return number.intValue();
        }

        @Override
        public boolean requiredBoolean(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Boolean flag)) {
                throw new ConfigException("missing boolean " + path);
            }
            return flag;
        }

        @Override
        public Map<String, Object> requiredTable(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException("missing table " + path);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }

        @Override
        public boolean contains(String path) {
            return lookup(path) != null;
        }
    }

    /**
     * Builds the config every authority test parses.
     *
     * @param channelIds the channels to declare, each open and unlimited
     * @return the config map
     */
    static Map<String, Object> config(String... channelIds) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", "lite");
        values.put("lite.server-id", "proxy-1");
        values.put("chat.replace_vanilla", true);
        values.put("chat.default_channel", channelIds[0]);
        values.put("chat.locale", "zh_CN");
        values.put("chat.channel-prefixes", Map.of());
        values.put("format.prefix", "");
        values.put("format.error", "{message}");
        values.put("format.success", "{message}");
        values.put("format.default", "[{channel_name}] {player}: {message}");

        Map<String, Object> formats = new LinkedHashMap<>();
        Map<String, Object> channels = new LinkedHashMap<>();
        for (String channelId : channelIds) {
            formats.put(channelId, "[{channel_name}] {player}: {message}");
            channels.put(channelId, channel(channelId));
        }
        values.put("format.channels", formats);
        values.put("debug", false);
        values.put("lite.channels", channels);
        values.put("lite.storage", Map.of("type", "memory"));
        return values;
    }

    /**
     * @param id the channel id, also used as its display name
     * @return an open, unlimited channel definition
     */
    static LiteChannelConfig channelDefinition(String id) {
        return new LiteChannelConfig(id, id, null, 0, 0);
    }

    private static Map<String, Object> channel(String id) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("display-name", id);
        channel.put("max-capacity", 0L);
        channel.put("slow-mode", 0L);
        return channel;
    }
}
