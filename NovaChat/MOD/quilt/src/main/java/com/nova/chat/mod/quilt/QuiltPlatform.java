package com.nova.chat.mod.quilt;

import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.PlatformType;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Quilt platform implementation
 * Uses Quilted Fabric API for chat interception and message rendering
 * Compatible with Fabric API through Quilted Fabric API
 * 
 * Requirements: 4.2, 4.3
 */
public class QuiltPlatform implements Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuiltPlatform.class);
    
    private MinecraftServer server;
    private ChatHandler chatHandler;
    private CommandManager commandManager;
    private boolean replaceVanillaChat = false;

    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "NovaChat-quilt-async");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(
            2, r -> {
                Thread t = new Thread(r, "NovaChat-quilt-scheduled");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentLinkedQueue<ScheduledFuture<?>> pendingDelayedTasks = new ConcurrentLinkedQueue<>();

    public QuiltPlatform() {
    }
    
    /**
     * Set the Minecraft server instance
     * @param server the server instance
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }
    
    /**
     * Set whether to replace vanilla chat
     * @param replace true to replace vanilla chat
     */
    public void setReplaceVanillaChat(boolean replace) {
        this.replaceVanillaChat = replace;
    }

    @Override
    public void registerChatListener(ChatHandler handler) {
        this.chatHandler = handler;

        // Quilt (via Quilted Fabric API) fires ALLOW_CHAT_MESSAGE before
        // CHAT_MESSAGE. If we returned false from ALLOW_CHAT_MESSAGE in REPLACE
        // mode, CHAT_MESSAGE (which forwards to NovaLink) would never fire and the
        // message would be lost. To mirror the NeoForge ordering (forward first,
        // then cancel), we forward inside ALLOW_CHAT_MESSAGE when replacing, and
        // return false to suppress vanilla chat. In non-replace mode we forward
        // via CHAT_MESSAGE and allow vanilla.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (chatHandler != null && sender != null) {
                UUID playerId = sender.getUUID();
                String playerName = sender.getName().getString();
                String content = message.signedContent();

                LOGGER.debug("Chat intercepted from {}: {}", playerName, content);
                chatHandler.onPlayerChat(playerId, playerName, content);
            }
            // If replaceVanillaChat is true, cancel the vanilla chat message
            // (already forwarded above). The message will be handled by our chat
            // handler instead.
            return !replaceVanillaChat;
        });

        // In non-replace mode, CHAT_MESSAGE also fires and we forward there too.
        // To avoid double-forwarding, only forward via CHAT_MESSAGE when NOT in
        // replace mode (ALLOW_CHAT_MESSAGE handles the replace path above).
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (!replaceVanillaChat && chatHandler != null && sender != null) {
                UUID playerId = sender.getUUID();
                String playerName = sender.getName().getString();
                String content = message.signedContent();

                LOGGER.debug("Chat forwarded from {}: {}", playerName, content);
                chatHandler.onPlayerChat(playerId, playerName, content);
            }
        });

        LOGGER.info("Quilt chat listener registered (via Quilted Fabric API)");
    }
    
    @Override
    public void registerCommands(CommandManager manager) {
        this.commandManager = manager;
        // Commands are registered separately via CommandRegistrationCallback
        LOGGER.info("Command manager set for Quilt platform");
    }

    @Override
    public void sendMessage(UUID playerId, Object message) {
        if (server == null) {
            LOGGER.warn("Server not initialized, cannot send message");
            return;
        }
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            Component component;
            if (message instanceof Component) {
                component = (Component) message;
            } else if (message instanceof String) {
                component = Component.literal((String) message);
            } else if (message == null) {
                LOGGER.warn("Null message provided");
                return;
            } else {
                component = Component.literal(message.toString());
            }
            player.sendSystemMessage(component);
        }
    }

    @Override
    public void broadcastMessage(Object message) {
        if (server == null) {
            LOGGER.warn("Server not initialized, cannot broadcast message");
            return;
        }
        
        Component component;
        if (message instanceof Component) {
            component = (Component) message;
        } else if (message instanceof String) {
            component = Component.literal((String) message);
        } else if (message == null) {
            LOGGER.warn("Null message provided");
            return;
        } else {
            component = Component.literal(message.toString());
        }
        
        server.getPlayerList().broadcastSystemMessage(component, false);
    }
    
    @Override
    public String getCurrentWorld(UUID playerId) {
        if (server == null) {
            return null;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.level().dimension().identifier().toString();
        }
        return null;
    }

    /**
     * §11.6 / 提案 05: serializes the player's main-hand item into the shared
     * minimal display schema ({@code id} / {@code count} / optional
     * {@code name}) via {@link ItemDisplayTokens#buildItemJson}, mirroring the
     * bukkit/folia/nukkit/pnx send-side. Quilt inherits the Fabric API item
     * model, so the implementation is identical to {@code FabricPlatform}.
     * Empty hand or offline player returns null (the ChatInterceptor then
     * leaves the {@code [item]} token as plain text). Full NBT is never
     * serialized (Property 13, Requirements 19.1).
     *
     * <p>Exception-safe: any failure returns null and logs a warning so the
     * chat send path is never broken by item introspection.
     */
    @Override
    public String getHeldItemJson(UUID playerId) {
        if (server == null) {
            return null;
        }
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return null;
            }
            net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            int count = stack.getCount();
            String customName = resolveCustomItemName(stack);
            return com.nova.chat.client.itemdisplay.ItemDisplayTokens
                    .buildItemJson(id, count, customName);
        } catch (Throwable t) {
            LOGGER.warn("Failed to serialize held item for {}: {}", playerId, t.getMessage());
            return null;
        }
    }

    /**
     * Resolves the optional custom display name from an item stack. Returns null
     * when the item has no custom name (so {@code ItemDisplayTokens.buildItemJson}
     * omits the {@code name} field and the receiver prettifies the id).
     *
     * <p>MC 1.21.5+ moved custom names into the data-component model; the old
     * {@code hasCustomHoverName()} accessor was removed. This helper reads
     * {@code DataComponents.CUSTOM_NAME} reflectively so the same source
     * compiles across 1.20.x (legacy NBT items) and 1.21.x (data components)
     * without a version-split build. Any reflective failure degrades to null.
     */
    private static String resolveCustomItemName(net.minecraft.world.item.ItemStack stack) {
        try {
            // DataComponents.CUSTOM_NAME was added in 1.20.5+. Use reflection so the
            // common source compiles on 1.20.2 (no DataComponents class) too.
            Class<?> dataComponentsClass = Class.forName(
                    "net.minecraft.core.component.DataComponents");
            Object customNameComponent = java.util.Arrays.stream(dataComponentsClass.getFields())
                    .filter(f -> "CUSTOM_NAME".equals(f.getName()))
                    .findFirst()
                    .map(f -> {
                        try { return f.get(null); }
                        catch (Exception e) { return null; }
                    })
                    .orElse(null);
            if (customNameComponent == null) {
                return null;
            }
            Object name = stack.getClass().getMethod("get", Object.class)
                    .invoke(stack, customNameComponent);
            if (name instanceof net.minecraft.network.chat.Component component) {
                return component.getString();
            }
        } catch (Throwable ignored) {
            // accessor missing on this mapping/version; skip custom name
        }
        return null;
    }

    @Override
    public String getPlayerName(UUID playerId) {
        if (server == null) {
            return null;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.getName().getString();
        }
        return null;
    }
    
    @Override
    public boolean isPlayerOnline(UUID playerId) {
        if (server == null) {
            return false;
        }

        return server.getPlayerList().getPlayer(playerId) != null;
    }

    @Override
    public Collection<UUID> getOnlinePlayerIds() {
        if (server == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<UUID> ids = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ids.add(player.getUUID());
        }
        return ids;
    }

    @Override
    public void runAsync(Runnable task) {
        if (task == null) {
            return;
        }
        // Submit to a dedicated thread pool for true async execution, NOT the
        // server main thread (server.execute runs on the main server thread).
        asyncExecutor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("Error in async task", t);
            }
        });
    }

    /**
     * PLAT-001: hops a task to the Minecraft server thread before it touches
     * any server API (player list, {@code sendSystemMessage},
     * {@code broadcastSystemMessage}, dimension lookup). Inbound packet
     * handlers dispatch on the Netty event loop, which is not safe for these
     * calls.
     *
     * <p>If the caller is already on the server thread ({@link
     * MinecraftServer#isSameThread}), the task runs synchronously to avoid
     * unnecessary re-queueing and to preserve the behavior expected by unit
     * tests that stub {@link Platform#execute} to run the runnable inline.
     *
     * <p>If the server reference is null (pre-{@link #setServer}), the task
     * runs on the calling thread as a best-effort fallback so handler
     * invocations are never silently dropped during startup ordering.
     *
     * @param task the task to run on the server thread
     */
    @Override
    public void execute(Runnable task) {
        if (task == null) {
            return;
        }
        if (server == null) {
            LOGGER.debug("Server not initialized, running task on caller thread");
            runTaskSafely(task);
            return;
        }
        if (server.isSameThread()) {
            runTaskSafely(task);
            return;
        }
        server.execute(() -> runTaskSafely(task));
    }

    private static void runTaskSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("Error in server-thread task", t);
        }
    }

    @Override
    public void runLater(Runnable task, long delaySeconds) {
        if (task == null) {
            return;
        }
        long delaySec = Math.max(0L, delaySeconds);
        // Schedule on a managed ScheduledExecutorService so pending tasks can be
        // cancelled on shutdown instead of leaking raw Thread.sleep threads.
        ScheduledFuture<?> future = scheduledExecutor.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("Error in delayed task", t);
            }
        }, delaySec, TimeUnit.SECONDS);
        pendingDelayedTasks.add(future);
        // Best-effort cleanup of completed futures to avoid unbounded growth.
        pendingDelayedTasks.removeIf(f -> f.isDone());
    }

    /**
     * Shuts down the async and scheduled executors. Should be called on server
     * stop / mod disable to avoid leaking threads and to cancel pending delayed
     * tasks.
     */
    public void shutdown() {
        LOGGER.info("Shutting down NovaChat Quilt schedulers...");
        pendingDelayedTasks.forEach(f -> f.cancel(false));
        pendingDelayedTasks.clear();
        scheduledExecutor.shutdownNow();
        asyncExecutor.shutdownNow();
        try {
            if (!scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("Scheduled executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!asyncExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("Async executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("NovaChat Quilt schedulers shut down");
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logWarn(String message) {
        LOGGER.warn(message);
    }

    @Override
    public void logDebug(String message) {
        LOGGER.debug(message);
    }

    @Override
    public void logError(String message) {
        LOGGER.error(message);
    }

    @Override
    public void logError(String message, Throwable cause) {
        if (cause == null) {
            LOGGER.error(message);
        } else {
            LOGGER.error(message, cause);
        }
    }

    @Override
    public String getServerVersion() {
        if (server == null) {
            return "";
        }
        try {
            return server.getServerVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.QUILT;
    }
    
    /**
     * Get the command manager
     * @return the command manager
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }
    
    /**
     * Send a formatted message to a player using color codes
     * @param playerId the player UUID
     * @param formattedMessage the message with color codes (e.g., &c for red)
     */
    public void sendFormattedMessage(UUID playerId, String formattedMessage) {
        Component component = parseColorCodes(formattedMessage);
        sendMessage(playerId, component);
    }
    
    /**
     * Broadcast a formatted message to all players using color codes
     * @param formattedMessage the message with color codes
     */
    public void broadcastFormattedMessage(String formattedMessage) {
        Component component = parseColorCodes(formattedMessage);
        broadcastMessage(component);
    }
    
    private static final String COLOR_CODE_CHARS = "0123456789abcdefklmnorABCDEFKLMNOR";

    /**
     * Parse color codes in a message and convert to Component
     * Supports & color codes (e.g., &c for red, &l for bold)
     * @param message the message with color codes
     * @return the parsed Component
     */
    private Component parseColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        return Component.literal(parseColorCodesToSection(message));
    }

    /**
     * Replaces {@code &X} with {@code §X} only when {@code X} is a valid
     * Minecraft color/format code. A bare {@code &} not followed by a valid
     * code character is preserved as-is so plain text like {@code "Tom & Jerry"}
     * is not corrupted into garbage section signs.
     */
    private static String parseColorCodesToSection(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length());
        int len = message.length();
        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);
            if (c == '&' && i + 1 < len
                    && COLOR_CODE_CHARS.indexOf(message.charAt(i + 1)) >= 0) {
                sb.append('§').append(message.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
