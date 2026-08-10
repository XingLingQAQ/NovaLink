package com.nova.chat.folia.chat;

import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Async-safe message formatter for Folia.
 *
 * <p>String assembly is delegated to {@link MessageFormatService}; the Folia
 * {@link ColorRenderer} converts the legacy-coded string (hex expanded to
 * {@code §x§r…}) to a plain {@link String} via {@link ChatColor#translateAlternateColorCodes}.
 * Component rendering uses the Adventure {@link LegacyComponentSerializer} with
 * the {@code §} character. PlaceholderAPI substitution and all region-thread
 * scheduling remain platform-specific.
 *
 * Requirements: 2.4
 */
public class AsyncMessageFormatter {

    private final NovaChatFolia plugin;
    private final NovaChatConfig config;
    private final FoliaSchedulerAdapter scheduler;
    private final ColorRenderer<String> renderer;

    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private volatile boolean placeholderApiAvailable = false;

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();

    public AsyncMessageFormatter(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.scheduler = plugin.getScheduler();
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToSection(text);
            return ChatColor.translateAlternateColorCodes('&', converted);
        };
        checkPlaceholderApi();
    }

    private void checkPlaceholderApi() {
        try {
            if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                placeholderApiAvailable = true;
                plugin.getLogger().info("PlaceholderAPI found, variable support enabled");
            }
        } catch (Exception e) {
            placeholderApiAvailable = false;
        }
    }

    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message) {
        String format = config.getChannelFormat(channelId);
        Map<String, String> extras = platformExtras(player, senderName);
        String result = FormatTemplateEngine.apply(
                format, senderName, channelId,
                channelName != null ? channelName : channelId,
                message, extras);
        result = applyPlaceholderApi(player, result);
        return renderer.render(result);
    }

    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message,
                                    Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);
        Map<String, String> values = new LinkedHashMap<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                values.put(entry.getKey(), entry.getValue());
            }
        }
        values.put(FormatTemplateEngine.KEY_PLAYER, senderName);
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME,
                channelName != null ? channelName : channelId);
        values.put(FormatTemplateEngine.KEY_MESSAGE, message);
        values.putAll(platformExtras(player, senderName));

        String result = FormatTemplateEngine.apply(format, values);
        result = applyPlaceholderApi(player, result);
        return renderer.render(result);
    }

    public Component formatAsComponent(String message) {
        return LEGACY_SERIALIZER.deserialize(translateColorCodes(message));
    }

    public String formatSystemMessage(String type, String message) {
        String result = MessageFormatService.buildTypedSystem(
                config.getPrefix(), config.getErrorFormat(), config.getSuccessFormat(), type, message);
        return renderer.render(result);
    }

    private Map<String, String> platformExtras(Player player, String senderName) {
        Map<String, String> extras = new LinkedHashMap<>(4);
        extras.put("display_name", player != null ? player.getDisplayName() : senderName);
        extras.put("world", player != null ? player.getWorld().getName() : "");
        extras.put("server", plugin.getServer().getName());
        return extras;
    }

    public String translateColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return renderer.render(text);
    }

    public String stripColors(String text) {
        if (text == null) {
            return null;
        }
        String stripped = MessageFormatService.stripColors(text);
        return ChatColor.stripColor(stripped);
    }

    private String applyPlaceholderApi(Player player, String result) {
        if (placeholderApiAvailable && player != null) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
            } catch (Exception e) {
                plugin.debug("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            }
        }
        return result;
    }

    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    public void reload() {
        checkPlaceholderApi();
    }

    // ==================== Async Rendering Methods for Folia ====================

    /**
     * Formats a chat message and sends it to the player on the player's region
     * thread.
     *
     * <p>PlaceholderAPI reads player state and is only safe on the player's
     * own thread, so the format + send is wrapped in
     * {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)}; the online
     * check is repeated inside the callback because the player may disconnect
     * between scheduling and execution.
     *
     * @param player      the recipient, ignored if {@code null} or offline
     * @param channelId   the channel id the message is being sent to
     * @param channelName the display name of the channel (falls back to {@code channelId})
     * @param senderName  the sender name used for template substitution
     * @param message     the raw message body
     */
    public void formatAndSendOnPlayerThread(Player player, String channelId, String channelName,
                                            String senderName, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String formattedMessage = formatChatMessage(player, channelId, channelName, senderName, message);
            player.sendMessage(formattedMessage);
        });
    }

    /**
     * Formats a chat message with extra placeholders and sends it to the player
     * on the player's region thread.
     *
     * <p>Same threading contract as
     * {@link #formatAndSendOnPlayerThread(Player, String, String, String, String)};
     * the {@code placeholders} map is merged on top of the built-in keys.
     *
     * @param player       the recipient, ignored if {@code null} or offline
     * @param channelId    the channel id the message is being sent to
     * @param channelName  the display name of the channel (falls back to {@code channelId})
     * @param senderName   the sender name used for template substitution
     * @param message      the raw message body
     * @param placeholders additional template values; {@code null}/empty keys are skipped
     */
    public void formatAndSendOnPlayerThread(Player player, String channelId, String channelName,
                                            String senderName, String message,
                                            Map<String, String> placeholders) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String formattedMessage = formatChatMessage(player, channelId, channelName,
                                                        senderName, message, placeholders);
            player.sendMessage(formattedMessage);
        });
    }

    /**
     * Formats a chat message and sends it to the player as an Adventure
     * {@link Component} on the player's region thread.
     *
     * <p>Unlike {@link #formatAndSendOnPlayerThread(Player, String, String, String, String)}
     * the final string is re-parsed into a {@link Component} via
     * {@link #formatAsComponent(String)} so hover/click decorations survive. The
     * Component send must still happen on the player's thread.
     *
     * @param player      the recipient, ignored if {@code null} or offline
     * @param channelId   the channel id the message is being sent to
     * @param channelName the display name of the channel (falls back to {@code channelId})
     * @param senderName  the sender name used for template substitution
     * @param message     the raw message body
     */
    public void formatAndSendComponentOnPlayerThread(Player player, String channelId, String channelName,
                                                     String senderName, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String formattedMessage = formatChatMessage(player, channelId, channelName, senderName, message);
            Component component = formatAsComponent(formattedMessage);
            player.sendMessage(component);
        });
    }

    /**
     * Formats a chat message asynchronously, returning the rendered string via
     * a {@link CompletableFuture}.
     *
     * <p>When PlaceholderAPI is available and the player is online the work is
     * scheduled on the player's region thread (PAPI reads player state and is
     * only safe there); otherwise it runs on a generic async thread. The future
     * completes exceptionally if formatting throws.
     *
     * @param player      the player whose state feeds PAPI; may be {@code null}
     * @param channelId   the channel id the message is being sent to
     * @param channelName the display name of the channel (falls back to {@code channelId})
     * @param senderName  the sender name used for template substitution
     * @param message     the raw message body
     * @return a future that resolves to the formatted legacy-coded string
     */
    public CompletableFuture<String> formatChatMessageAsync(Player player, String channelId,
                                                            String channelName, String senderName,
                                                            String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (placeholderApiAvailable && player != null && player.isOnline()) {
            scheduler.runForPlayer(player, () -> {
                try {
                    future.complete(formatChatMessage(player, channelId, channelName, senderName, message));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            scheduler.runAsync(() -> {
                try {
                    future.complete(formatChatMessage(player, channelId, channelName, senderName, message));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }

    /**
     * Formats a chat message with extra placeholders asynchronously, returning
     * the rendered string via a {@link CompletableFuture}.
     *
     * <p>Same threading contract as
     * {@link #formatChatMessageAsync(Player, String, String, String, String)};
     * the {@code placeholders} map is merged on top of the built-in keys.
     *
     * @param player       the player whose state feeds PAPI; may be {@code null}
     * @param channelId    the channel id the message is being sent to
     * @param channelName  the display name of the channel (falls back to {@code channelId})
     * @param senderName   the sender name used for template substitution
     * @param message      the raw message body
     * @param placeholders additional template values; {@code null}/empty keys are skipped
     * @return a future that resolves to the formatted legacy-coded string
     */
    public CompletableFuture<String> formatChatMessageAsync(Player player, String channelId,
                                                            String channelName, String senderName,
                                                            String message, Map<String, String> placeholders) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (placeholderApiAvailable && player != null && player.isOnline()) {
            scheduler.runForPlayer(player, () -> {
                try {
                    future.complete(formatChatMessage(player, channelId, channelName,
                                                        senderName, message, placeholders));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            scheduler.runAsync(() -> {
                try {
                    future.complete(formatChatMessage(player, channelId, channelName,
                                                        senderName, message, placeholders));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        return future;
    }

    /**
     * Formats a chat message on the player's region thread and hands the result
     * to {@code callback}.
     *
     * <p>Used when the caller does not send the message itself but needs the
     * formatted string (e.g. to wrap it further); the callback runs on the
     * player's thread so any downstream player-API use is safe. No-op if the
     * player is {@code null} or has logged off before the callback fires.
     *
     * @param player      the player whose state feeds PAPI
     * @param channelId   the channel id the message is being sent to
     * @param channelName the display name of the channel (falls back to {@code channelId})
     * @param senderName  the sender name used for template substitution
     * @param message     the raw message body
     * @param callback    receives the formatted string on the player's thread
     */
    public void formatOnPlayerThread(Player player, String channelId, String channelName,
                                     String senderName, String message, Consumer<String> callback) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String formatted = formatChatMessage(player, channelId, channelName, senderName, message);
            callback.accept(formatted);
        });
    }

    /**
     * Formats a typed system message and sends it to the player on the player's
     * region thread.
     *
     * <p>{@code type} selects the shared {@code buildTypedSystem} styling
     * ({@code "error"} / {@code "success"} / etc.). The send is scheduled via
     * {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)} with an
     * online re-check inside the callback.
     *
     * @param player  the recipient, ignored if {@code null} or offline
     * @param type    the message category passed to the shared system builder
     * @param message the raw message body
     */
    public void sendSystemMessageOnPlayerThread(Player player, String type, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            String formatted = formatSystemMessage(type, message);
            player.sendMessage(formatted);
        });
    }

    /** Convenience for {@link #sendSystemMessageOnPlayerThread(Player, String, String)} with type {@code "error"}. */
    public void sendErrorOnPlayerThread(Player player, String message) {
        sendSystemMessageOnPlayerThread(player, "error", message);
    }

    /** Convenience for {@link #sendSystemMessageOnPlayerThread(Player, String, String)} with type {@code "success"}. */
    public void sendSuccessOnPlayerThread(Player player, String message) {
        sendSystemMessageOnPlayerThread(player, "success", message);
    }

    /**
     * Parses a legacy-coded message into an Adventure {@link Component} on a
     * generic async thread.
     *
     * <p>Unlike the player-thread variants this does not touch PlaceholderAPI,
     * so it is safe off the player's thread. The future completes exceptionally
     * if parsing throws.
     *
     * @param message the legacy-coded message (with {@code &}/hex codes)
     * @return a future that resolves to the parsed {@link Component}
     */
    public CompletableFuture<Component> formatAsComponentAsync(String message) {
        CompletableFuture<Component> future = new CompletableFuture<>();
        scheduler.runAsync(() -> {
            try {
                future.complete(formatAsComponent(message));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Sends a pre-built {@link Component} to the player on the player's region
     * thread.
     *
     * <p>The component is assumed already rendered; this only performs the
     * thread hop + online check required for any player send on Folia.
     *
     * @param player    the recipient, ignored if {@code null} or offline
     * @param component the component to send
     */
    public void sendComponentOnPlayerThread(Player player, Component component) {
        if (player == null || !player.isOnline()) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.sendMessage(component);
        });
    }

    public FoliaSchedulerAdapter getScheduler() {
        return scheduler;
    }
}
