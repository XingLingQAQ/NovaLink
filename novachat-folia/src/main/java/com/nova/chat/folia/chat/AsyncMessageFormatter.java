package com.nova.chat.folia.chat;

import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.LegacyColorCodes;
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
 * Handles message formatting including color codes and PlaceholderAPI variables.
 * All operations are thread-safe and can be called from any thread.
 *
 * <p>This formatter is designed to work correctly with Folia's regionized multithreading model.
 * Key thread safety considerations:</p>
 *
 * <ul>
 *   <li>All formatting methods are thread-safe and can be called from any thread</li>
 *   <li>PlaceholderAPI integration is handled carefully as it may not be fully thread-safe</li>
 *   <li>Methods that need player context can schedule rendering on the correct region thread</li>
 *   <li>Component rendering uses immutable Adventure API components</li>
 * </ul>
 *
 * <p>In Folia, different regions of the world run on different threads. When formatting
 * messages that require player context (like PlaceholderAPI), we must ensure we're on
 * the correct thread for that player's region. This class provides both synchronous
 * methods (for when you're already on the correct thread) and async methods (for when
 * you need to schedule on the correct thread).</p>
 *
 * Supports:
 * - Legacy color codes (&a, &b, etc.)
 * - Hex color codes (&#RRGGBB)
 * - PlaceholderAPI variables (%placeholder%)
 * - Custom placeholders ({player}, {channel}, etc.)
 *
 * Placeholder substitution is delegated to {@link FormatTemplateEngine};
 * color translation and PlaceholderAPI remain platform-specific. Folia region-thread
 * delivery APIs are unchanged.
 *
 * Requirements: 2.4
 */
public class AsyncMessageFormatter {

    private final NovaChatFolia plugin;
    private final NovaChatConfig config;
    private final FoliaSchedulerAdapter scheduler;

    /** Pattern for legacy color codes: &X where X is 0-9, a-f, k-o, r */
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Whether PlaceholderAPI is available */
    private volatile boolean placeholderApiAvailable = false;

    /** Legacy component serializer for color code support */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();

    /**
     * Creates a new AsyncMessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public AsyncMessageFormatter(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.scheduler = plugin.getScheduler();

        // Check if PlaceholderAPI is available
        checkPlaceholderApi();
    }

    /**
     * Checks if PlaceholderAPI is available on the server.
     */
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

    /**
     * Formats a chat message with all placeholders and color codes.
     * This method is thread-safe and can be called from any thread.
     *
     * @param player the player sending/receiving the message
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @return the formatted message
     */
    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message) {
        String format = config.getChannelFormat(channelId);

        Map<String, String> extras = platformExtras(player, senderName);
        String result = FormatTemplateEngine.apply(
                format,
                senderName,
                channelId,
                channelName != null ? channelName : channelId,
                message,
                extras);

        // Apply PlaceholderAPI if available (must be done carefully in async context)
        if (placeholderApiAvailable && player != null) {
            result = setPlaceholders(player, result);
        }

        // Apply color codes
        result = translateColorCodes(result);

        return result;
    }

    /**
     * Formats a chat message with custom placeholders map.
     * This method is thread-safe and can be called from any thread.
     *
     * @param player the player (can be null for console)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @param placeholders additional placeholders to apply
     * @return the formatted message
     */
    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message,
                                    Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);

        // Preserve prior semantics: custom map first, then standard/platform keys overwrite.
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

        // Apply PlaceholderAPI if available
        if (placeholderApiAvailable && player != null) {
            result = setPlaceholders(player, result);
        }

        // Apply color codes
        result = translateColorCodes(result);

        return result;
    }

    /**
     * Formats a message as a Component for Paper's Adventure API.
     *
     * @param message the message with color codes
     * @return the Component
     */
    public Component formatAsComponent(String message) {
        String translated = translateColorCodes(message);
        return LEGACY_SERIALIZER.deserialize(translated);
    }

    /**
     * Formats a system message (success, error, etc.).
     *
     * @param type the message type ("error", "success", or custom)
     * @param message the message content
     * @return the formatted message
     */
    public String formatSystemMessage(String type, String message) {
        String format;
        switch (type.toLowerCase()) {
            case "error":
                format = config.getErrorFormat();
                break;
            case "success":
                format = config.getSuccessFormat();
                break;
            default:
                format = "{message}";
        }

        String result = config.getPrefix()
                + FormatTemplateEngine.apply(format, Map.of(
                        FormatTemplateEngine.KEY_MESSAGE, message != null ? message : ""));
        return translateColorCodes(result);
    }

    /**
     * Builds platform-only extras ({@code display_name}, {@code world}, {@code server}).
     */
    private Map<String, String> platformExtras(Player player, String senderName) {
        Map<String, String> extras = new LinkedHashMap<>(4);
        extras.put("display_name", player != null ? player.getDisplayName() : senderName);
        extras.put("world", player != null ? player.getWorld().getName() : "");
        extras.put("server", plugin.getServer().getName());
        return extras;
    }

    /**
     * Translates all color codes in a string.
     * Supports both legacy (&X) and hex (&#RRGGBB) formats.
     * This method is thread-safe.
     *
     * @param text the text to translate
     * @return the translated text with color codes applied
     */
    public String translateColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // First, translate hex colors (&#RRGGBB) via shared pure transform
        text = translateHexColors(text);

        // Then, translate legacy colors (&X)
        text = translateLegacyColors(text);

        return text;
    }

    /**
     * Translates hex color codes (&#RRGGBB) to Minecraft section-sign form.
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateHexColors(String text) {
        return LegacyColorCodes.toSectionX(text);
    }

    /**
     * Translates legacy color codes (&X) to Minecraft format (§X).
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateLegacyColors(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Strips all color codes from a string.
     *
     * @param text the text to strip
     * @return the text without color codes
     */
    public String stripColors(String text) {
        if (text == null) {
            return null;
        }

        // Remove hex colors first
        text = LegacyColorCodes.HASH_HEX_PATTERN.matcher(text).replaceAll("");

        // Remove legacy colors
        text = ChatColor.stripColor(translateLegacyColors(text));

        // Remove any remaining & codes
        text = LEGACY_PATTERN.matcher(text).replaceAll("");

        return text;
    }
    
    /**
     * Sets PlaceholderAPI placeholders in a string.
     * Note: PlaceholderAPI may not be fully thread-safe, so use with caution.
     *
     * @param player the player context
     * @param text the text with placeholders
     * @return the text with placeholders replaced
     */
    private String setPlaceholders(Player player, String text) {
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Exception e) {
            plugin.debug("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            return text;
        }
    }
    
    /**
     * Checks if PlaceholderAPI is available.
     *
     * @return true if PlaceholderAPI is available
     */
    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }
    
    /**
     * Reloads the formatter (re-checks PlaceholderAPI availability).
     */
    public void reload() {
        checkPlaceholderApi();
    }
    
    // ==================== Async Rendering Methods for Folia ====================
    
    /**
     * Formats and sends a chat message to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread. It schedules
     * the message formatting and sending on the player's correct region thread,
     * ensuring all Bukkit API calls are made from the appropriate thread.</p>
     * 
     * <p>This is the recommended method for sending formatted messages in Folia,
     * as it handles all thread safety concerns automatically.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     */
    public void formatAndSendOnPlayerThread(Player player, String channelId, String channelName,
                                            String senderName, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        // Schedule formatting and sending on the player's region thread
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            
            String formattedMessage = formatChatMessage(player, channelId, channelName, senderName, message);
            player.sendMessage(formattedMessage);
        });
    }
    
    /**
     * Formats and sends a chat message to a player on their correct region thread,
     * with additional placeholders.
     * 
     * <p>Thread Safety: This method is safe to call from any thread. It schedules
     * the message formatting and sending on the player's correct region thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @param placeholders additional placeholders to apply
     */
    public void formatAndSendOnPlayerThread(Player player, String channelId, String channelName,
                                            String senderName, String message,
                                            Map<String, String> placeholders) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        // Schedule formatting and sending on the player's region thread
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
     * Formats a message and sends it as a Component to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread. It schedules
     * the component rendering and sending on the player's correct region thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     */
    public void formatAndSendComponentOnPlayerThread(Player player, String channelId, String channelName,
                                                     String senderName, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        // Schedule formatting and sending on the player's region thread
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
     * Formats a message asynchronously and returns the result via a CompletableFuture.
     * The formatting is done on the player's correct region thread if PlaceholderAPI
     * is being used, otherwise it's done on an async thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread. The returned
     * CompletableFuture will complete with the formatted message.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player context (can be null if no PlaceholderAPI needed)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @return a CompletableFuture that completes with the formatted message
     */
    public CompletableFuture<String> formatChatMessageAsync(Player player, String channelId, 
                                                            String channelName, String senderName, 
                                                            String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // If PlaceholderAPI is available and we have a player, format on player's thread
        if (placeholderApiAvailable && player != null && player.isOnline()) {
            scheduler.runForPlayer(player, () -> {
                try {
                    String formatted = formatChatMessage(player, channelId, channelName, senderName, message);
                    future.complete(formatted);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            // No PlaceholderAPI or no player, can format on any thread
            scheduler.runAsync(() -> {
                try {
                    String formatted = formatChatMessage(player, channelId, channelName, senderName, message);
                    future.complete(formatted);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        
        return future;
    }
    
    /**
     * Formats a message asynchronously with additional placeholders.
     *
     * Requirements: 2.4
     *
     * @param player the player context (can be null if no PlaceholderAPI needed)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @param placeholders additional placeholders to apply
     * @return a CompletableFuture that completes with the formatted message
     */
    public CompletableFuture<String> formatChatMessageAsync(Player player, String channelId,
                                                            String channelName, String senderName,
                                                            String message, Map<String, String> placeholders) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // If PlaceholderAPI is available and we have a player, format on player's thread
        if (placeholderApiAvailable && player != null && player.isOnline()) {
            scheduler.runForPlayer(player, () -> {
                try {
                    String formatted = formatChatMessage(player, channelId, channelName, 
                                                        senderName, message, placeholders);
                    future.complete(formatted);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } else {
            // No PlaceholderAPI or no player, can format on any thread
            scheduler.runAsync(() -> {
                try {
                    String formatted = formatChatMessage(player, channelId, channelName, 
                                                        senderName, message, placeholders);
                    future.complete(formatted);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        
        return future;
    }
    
    /**
     * Formats a message and delivers the result via a callback on the player's region thread.
     * This is useful when you need to perform additional operations with the formatted
     * message on the correct thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread. The callback
     * will be executed on the player's correct region thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player context
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @param callback the callback to receive the formatted message
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
     * Sends a system message to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param type the message type ("error", "success", or custom)
     * @param message the message content
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
    
    /**
     * Sends an error message to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param message the error message
     */
    public void sendErrorOnPlayerThread(Player player, String message) {
        sendSystemMessageOnPlayerThread(player, "error", message);
    }
    
    /**
     * Sends a success message to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
     * @param message the success message
     */
    public void sendSuccessOnPlayerThread(Player player, String message) {
        sendSystemMessageOnPlayerThread(player, "success", message);
    }
    
    /**
     * Formats a message as a Component asynchronously.
     *
     * Requirements: 2.4
     *
     * @param message the message with color codes
     * @return a CompletableFuture that completes with the Component
     */
    public CompletableFuture<Component> formatAsComponentAsync(String message) {
        CompletableFuture<Component> future = new CompletableFuture<>();
        
        scheduler.runAsync(() -> {
            try {
                Component component = formatAsComponent(message);
                future.complete(component);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Sends a Component message to a player on their correct region thread.
     * 
     * <p>Thread Safety: This method is safe to call from any thread.</p>
     *
     * Requirements: 2.4
     *
     * @param player the player to send the message to
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
    
    /**
     * Gets the Folia scheduler adapter.
     *
     * @return the scheduler adapter
     */
    public FoliaSchedulerAdapter getScheduler() {
        return scheduler;
    }
}
