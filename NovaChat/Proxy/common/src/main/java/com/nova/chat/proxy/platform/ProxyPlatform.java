package com.nova.chat.proxy.platform;

import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;

import java.util.Collection;
import java.util.UUID;

/**
 * Platform abstraction for the NovaChat proxy family (Velocity, BungeeCord, and
 * the forthcoming lite/relay variants).
 *
 * <p>This is the proxy analogue of {@code com.nova.chat.mod.platform.Platform}
 * (which serves fabric/neoforge/quilt): every operation the platform-agnostic
 * proxy core needs that differs between proxies lives here, and each proxy shell
 * implements it once. The shared core ({@code ProxyChatCore},
 * {@code ProxyCommandCore}, {@code ProxyServerSwitchCore}) then holds the
 * control flow, message text and packet semantics that used to be duplicated
 * line-for-line in both plugins.
 *
 * <p><b>Rendering contract.</b> All text handed to this interface uses legacy
 * {@code &}/{@code &#RRGGBB} color codes. Each implementation renders through
 * the shared {@code MessageFormatService} + {@code ColorRenderer} pipeline, so
 * the exact same string produces the platform's native object (Adventure
 * {@code Component} on Velocity, {@code BaseComponent[]} on Bungee) without the
 * core knowing anything about either API. Where the two proxies genuinely render
 * a notification differently (for example BungeeCord writes action bars through
 * {@code ChatMessageType.ACTION_BAR} while Velocity uses Adventure), that
 * difference is captured by a dedicated primitive — never by the core branching
 * on platform identity.
 *
 * <p><b>Threading contract.</b> {@link #execute(Runnable)} owns the thread hop:
 * the core calls it from whatever thread it is on (Netty event loop for inbound
 * packets, the proxy main thread for events) and the implementation decides
 * where the body runs. Read-only lookups ({@link #getOnlinePlayers()},
 * {@link #getPlayer(UUID)}) are expected to be safe from any thread.
 */
public interface ProxyPlatform {

    /**
     * Scheduler used by the shared client-core network layer. Defaults to this
     * platform's own {@link #runAsync} / {@link #runLater}, so shells implement
     * the two primitives once.
     *
     * @return the seconds-based scheduler bridge
     */
    default SchedulerBridge getScheduler() {
        return new SchedulerBridge() {
            @Override
            public void runAsync(Runnable task) {
                ProxyPlatform.this.runAsync(task);
            }

            @Override
            public void runLater(Runnable task, long delaySeconds) {
                ProxyPlatform.this.runLater(task, delaySeconds);
            }
        };
    }

    // ------------------------------------------------------------ player lookup

    /**
     * @return the players currently connected to this proxy
     */
    Collection<? extends ProxyPlayer> getOnlinePlayers();

    /**
     * @param playerId the player UUID
     * @return the player handle, or {@code null} when not on this proxy
     */
    ProxyPlayer getPlayer(UUID playerId);

    // ---------------------------------------------------------------- messaging

    /**
     * Sends one already-formatted chat line.
     *
     * @param player the recipient
     * @param legacyText text carrying legacy {@code &} color codes
     */
    void sendMessage(ProxyPlayer player, String legacyText);

    /**
     * Sends one chat line with a hover tooltip.
     *
     * @param player the recipient
     * @param legacyText legacy-coded line text
     * @param hoverText legacy-coded tooltip body
     */
    void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText);

    /**
     * Shows a title/subtitle.
     *
     * <p>Timings are in game ticks (20/s), matching {@code TitlePacket}'s wire
     * format; implementations convert to their API's unit.
     *
     * @param player the recipient
     * @param title legacy-coded title text
     * @param subtitle legacy-coded subtitle text
     * @param fadeIn fade-in ticks
     * @param stay stay ticks
     * @param fadeOut fade-out ticks
     */
    void showTitle(ProxyPlayer player, String title, String subtitle,
                   int fadeIn, int stay, int fadeOut);

    /**
     * Plays a notification sound. Best-effort; platforms without a proxy-level
     * sound API implement this as a no-op.
     *
     * @param player the recipient
     * @param soundId the sound identifier
     * @param volume playback volume
     * @param pitch playback pitch
     */
    void playSound(ProxyPlayer player, String soundId, float volume, float pitch);

    /**
     * Delivers a mention notification (UX-DESIGN §4.1).
     *
     * <p>Velocity shows {@code highlight} as the title and {@code subtitle} as
     * the subtitle, then plays the default mention sound. BungeeCord renders the
     * same title through {@code ProxyServer#createTitle()} and repeats
     * {@code highlight + " " + subtitle} on the action bar.
     *
     * @param player the mentioned player
     * @param highlight legacy-coded mentioner name (e.g. {@code "&eAlex"})
     * @param subtitle legacy-coded localized subtitle
     */
    void notifyMention(ProxyPlayer player, String highlight, String subtitle);

    /**
     * Sends a transient action-bar line.
     *
     * <p>Velocity's {@code Player} is an Adventure {@code Audience}
     * ({@code sendActionBar}); BungeeCord writes
     * {@code sendMessage(ChatMessageType.ACTION_BAR, ...)} straight to the
     * downstream client. Neither touches proxy-internal protocol classes.
     *
     * @param player the recipient
     * @param legacyText legacy-coded text
     */
    void sendActionBar(ProxyPlayer player, String legacyText);

    /**
     * Flashes the §7 channel status indicator after a successful JOIN/LEAVE.
     *
     * <p>Both proxies render {@code PlayerMessages.currentChannelBar(...)} as a
     * one-shot action bar. The core resolves the channel/mode; the platform only
     * sends.
     *
     * @param player the recipient
     * @param channelId the channel to name, or {@code null} for the leave variant
     * @param mode the player's current chat mode
     */
    void showChannelStatusBar(ProxyPlayer player, String channelId,
                              com.nova.chat.client.state.ChatMode mode);

    /**
     * Delivers the target-side KICK/MUTE notice (UX-DESIGN §5).
     *
     * <p>Both proxies render a proxy-native title plus the action-bar line
     * carrying the same text: Velocity via Adventure
     * {@code showTitle}/{@code sendActionBar}, BungeeCord via
     * {@code ProxyServer#createTitle()} plus
     * {@code sendMessage(ChatMessageType.ACTION_BAR, ...)}.
     *
     * @param player the affected player
     * @param action {@link ChannelAction#KICK} or {@link ChannelAction#MUTE}
     * @param channelId the channel the action was applied in (may be empty)
     * @param operator the operator name (pre-resolved with fallback)
     * @param durationText the formatted duration (mute only, may be empty)
     */
    void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                        String operator, String durationText);

    // -------------------------------------------------------------- permissions

    /**
     * Checks a permission node for the console sender (players use
     * {@link ProxyPlayer#hasPermission(String)}).
     *
     * @param permission the permission node
     * @return {@code true} when granted
     */
    boolean consoleHasPermission(String permission);

    // -------------------------------------------------------------- dispatch

    /**
     * Runs a task on the platform's dispatch thread: Velocity's plugin
     * scheduler, BungeeCord's async pool. Used by the shared
     * {@code ChannelResponseDispatcher} adapter, whose callbacks arrive on the
     * Netty event loop and must not mutate shared state concurrently with the
     * proxy's own event thread.
     *
     * @param task the task to run
     */
    void execute(Runnable task);

    /**
     * Runs a task asynchronously (see {@link SchedulerBridge#runAsync(Runnable)}).
     *
     * @param task the task to run
     */
    void runAsync(Runnable task);

    /**
     * Runs a task after a delay in seconds.
     *
     * @param task the task to run
     * @param delaySeconds delay before execution
     */
    void runLater(Runnable task, long delaySeconds);

    // ------------------------------------------------------------------ logging

    /**
     * @param message the message
     */
    void logInfo(String message);

    /**
     * @param message the message
     */
    void logWarn(String message);

    /**
     * Logs a debug message when the plugin's debug flag is enabled.
     *
     * @param message the message
     */
    void logDebug(String message);

    /**
     * @param message the message
     */
    void logError(String message);

    /**
     * @param message the message
     * @param cause the cause, or {@code null}
     */
    void logError(String message, Throwable cause);

    // ------------------------------------------------------------------ identity

    /**
     * @return the protocol platform identity stamped into channel-action packet
     *         extras by the shared {@code ChannelCommandService}
     */
    PlatformType getPlatformType();

    /**
     * Reloads the plugin configuration and reconnects the backend link.
     *
     * <p>Owned by the shell because it also swaps the shell's config snapshot;
     * the shared {@code /nc reload} subcommand calls it after the admin gate.
     */
    void reloadConfiguration();

    /**
     * Sends one plugin message to a downstream server.
     *
     * <p>Plugin messaging has no server-addressed transport: the only way to
     * reach a sub-server is through a player connection on it. Implementations
     * therefore pick one player currently on {@code serverName} and write the
     * payload through that connection, and they return {@code false} when no
     * such player exists — the message is then genuinely undeliverable and the
     * caller must report the drop instead of pretending success. They must not
     * fan the payload out to every player on the server: the downstream would
     * receive one copy per player.
     *
     * <p>The default returns {@code false} so lightweight platform doubles
     * (tests) do not have to implement it; both real shells do.
     *
     * @param serverName the downstream server name, or {@code null} for the
     *                   player's current server
     * @param channel the plugin-message channel (for NovaChat:
     *                {@code novachat:main})
     * @param payload the raw payload
     * @return {@code true} when the message was handed to a live connection
     */
    default boolean sendPluginMessage(String serverName, String channel, byte[] payload) {
        return false;
    }
}
