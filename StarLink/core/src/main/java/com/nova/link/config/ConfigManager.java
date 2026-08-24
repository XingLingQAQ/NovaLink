package com.nova.link.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configuration manager with hot reload support.
 * Watches for configuration file changes and broadcasts updates to clients.
 * 
 * Requirements:
 * - 18.1, 18.2: Hot reload configuration
 * - 4.5: Broadcast ConfigSyncPacket on reload
 */
public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    
    private final ConfigLoader configLoader;
    private final Gson gson;
    
    // File watcher
    private WatchService watchService;
    private ScheduledExecutorService watchExecutor;
    private volatile boolean watching;
    
    // Connected clients for broadcasting (legacy support)
    private final Set<ClientConnection> connectedClients;
    
    // Network handler for broadcasting (preferred)
    private volatile ServerNetworkHandler networkHandler;
    
    // Supplier for getting current connections (alternative to direct handler reference)
    private volatile Supplier<Set<ClientConnection>> connectionSupplier;
    
    // Reload listeners
    private final List<Consumer<NovaLinkConfig>> reloadListeners;
    
    // Debounce for file changes
    private volatile long lastReloadTime;
    private static final long DEBOUNCE_MS = 1000;

    // Reload statistics
    private volatile int reloadCount = 0;
    private volatile long lastSuccessfulReload = 0;

    // PANEL-010: settings revision for optimistic-concurrency control.
    // Incremented atomically every time a settings mutation lands (save() or
    // a panel-driven update that changes the FeatureConfig). GET /api/settings
    // exposes this as revision + ETag; PUT /api/settings requires the client
    // to echo it back via If-Match / baseRevision and rejects a stale write
    // with 409 Conflict + the current server values.
    private final java.util.concurrent.atomic.AtomicLong settingsRevision =
            new java.util.concurrent.atomic.AtomicLong(0);

    // SHA-256 of the file content we wrote ourselves. When the watcher
    // fires for a change whose hash matches this, it was our own save; skip
    // the reload to avoid the self-trigger loop (save -> reload -> broadcast).
    private volatile String lastWrittenFileHash;
    private volatile long lastWrittenAt;
    private final Object fileOperationLock = new Object();
    private static final long SELF_WRITE_EVENT_WINDOW_MS = 10_000;

    // §11.6 Project 20 / PANEL proposal 10 — config diff + atomic rollback.
    // Setter-injected (NOT constructor-injected) to avoid a wiring cycle and to
    // keep NovaLinkMain untouched (the service is lazily assembled from the db
    // + this manager by the REST layer). When null, save() simply skips the
    // snapshot recording — the live config is still written and the revision
    // bumped. The service masks secrets before persisting.
    private volatile com.nova.link.api.ConfigHistoryService configHistoryService;

    public ConfigManager(Path configPath) {
        this.configLoader = new ConfigLoader(configPath);
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.connectedClients = ConcurrentHashMap.newKeySet();
        this.reloadListeners = new CopyOnWriteArrayList<>();
        this.watching = false;
        this.lastReloadTime = 0;
    }
    
    /**
     * Sets the network handler for broadcasting config sync packets.
     * This is the preferred way to integrate with the network layer.
     *
     * @param networkHandler the server network handler
     */
    public void setNetworkHandler(ServerNetworkHandler networkHandler) {
        this.networkHandler = networkHandler;
        logger.debug("Network handler set for config sync broadcasting");
    }
    
    /**
     * Sets a supplier for getting current client connections.
     * Alternative to setting the network handler directly.
     *
     * @param connectionSupplier supplier that returns current connections
     */
    public void setConnectionSupplier(Supplier<Set<ClientConnection>> connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
    }

    /**
     * Sets the config-history service used to record a masked snapshot after
     * every successful save (§11.6 Project 20). Setter-injected rather than
     * constructor-injected to avoid a wiring cycle and to keep NovaLinkMain
     * untouched — the REST layer wires this once the service is assembled.
     * When null (or never set), {@link #save()} skips the snapshot and the
     * live config is still written normally.
     *
     * @param configHistoryService the history service, or null to disable
     */
    public void setConfigHistoryService(com.nova.link.api.ConfigHistoryService configHistoryService) {
        this.configHistoryService = configHistoryService;
        logger.debug("Config history service {} for config snapshot recording",
                configHistoryService == null ? "cleared" : "set");
    }

    /**
     * Loads the configuration from file.
     *
     * @return the loaded configuration
     * @throws ConfigException if loading fails
     */
    public NovaLinkConfig load() throws ConfigException {
        return configLoader.load();
    }

    /**
     * Saves the current configuration to file.
     *
     * <p>After writing, records the SHA-256 of the written content so the file
     * watcher in {@link #pollFileChanges()} can recognise this save as our own
     * write and skip the reload, avoiding a save -> reload -> broadcast loop.
     *
     * @throws ConfigException if saving fails
     */
    public void save() throws ConfigException {
        synchronized (fileOperationLock) {
            configLoader.save();
            try {
                lastWrittenFileHash = sha256(
                        Files.readString(configLoader.getConfigPath(), StandardCharsets.UTF_8));
                lastWrittenAt = System.currentTimeMillis();
            } catch (IOException e) {
                // Non-fatal: worst case the watcher treats our own write as external.
                logger.debug("Could not hash config file after save: {}", e.getMessage());
            }
            // PANEL-010: a successful save is a settings mutation; bump the
            // revision so concurrent panel edits can detect staleness.
            long revision = settingsRevision.incrementAndGet();

            // §11.6 Project 20: record a masked snapshot of the just-written
            // config so the panel can diff/rollback later. Best-effort — a
            // failure here must NOT undo the save that just succeeded. The
            // service masks secrets itself; the live config passed in is the
            // unmasked in-memory form.
            com.nova.link.api.ConfigHistoryService history = configHistoryService;
            if (history != null) {
                try {
                    NovaLinkConfig live = configLoader.getConfig();
                    if (live != null) {
                        history.recordSnapshot(revision, gson.toJson(live), null);
                    }
                } catch (Exception e) {
                    logger.debug("Config history snapshot recording failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Reloads the configuration and broadcasts to all clients.
     *
     * @return the reloaded configuration
     * @throws ConfigException if reloading fails
     */
    public NovaLinkConfig reload() throws ConfigException {
        return reload(true);
    }
    
    /**
     * Reloads the configuration with optional broadcast.
     *
     * @param broadcast whether to broadcast the config sync to clients
     * @return the reloaded configuration
     * @throws ConfigException if reloading fails
     */
    public NovaLinkConfig reload(boolean broadcast) throws ConfigException {
        logger.info("Reloading configuration (broadcast={})...", broadcast);
        
        long startTime = System.currentTimeMillis();
        NovaLinkConfig config = configLoader.reload();
        lastReloadTime = System.currentTimeMillis();

        // Update statistics
        reloadCount++;
        lastSuccessfulReload = System.currentTimeMillis();

        // PANEL-010: reload replaces the in-memory config, so any concurrent
        // panel edit based on the pre-reload state must be rejected. Bump the
        // revision so stale If-Match / baseRevision values conflict.
        settingsRevision.incrementAndGet();
        
        // Notify listeners
        int listenerErrors = 0;
        for (Consumer<NovaLinkConfig> listener : reloadListeners) {
            try {
                listener.accept(config);
            } catch (Exception e) {
                listenerErrors++;
                logger.error("Error in reload listener", e);
            }
        }
        
        // Broadcast to all connected clients if requested
        if (broadcast) {
            broadcastConfigSync(config);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("Configuration reloaded successfully in {}ms (reload #{}, listener errors: {})", 
                duration, reloadCount, listenerErrors);
        
        return config;
    }
    
    /**
     * Triggers a manual reload from command (e.g., /nl reload).
     * This method is specifically for command-triggered reloads.
     *
     * Requirements: 18.2 - WHEN 管理员执行 `/nl reload` THEN NovaLink SHALL 重新加载后端配置并广播 ConfigSyncPacket
     *
     * @return the reloaded configuration
     * @throws ConfigException if reloading fails
     */
    public NovaLinkConfig triggerReload() throws ConfigException {
        logger.info("Manual reload triggered (e.g., from /nl reload command)");
        return reload(true);
    }

    /**
     * Gets the current configuration.
     *
     * @return the current configuration
     */
    public NovaLinkConfig getConfig() {
        return configLoader.getConfig();
    }

    /**
     * Starts watching the configuration file for changes.
     */
    public void startWatching() {
        if (watching) {
            return;
        }
        
        try {
            Path configPath = configLoader.getConfigPath();
            Path parentDir = configPath.getParent();

            // When configPath is a simple relative file name like "novalink.yml",
            // getParent() is null. In that case, watch the current working directory.
            if (parentDir == null) {
                parentDir = Paths.get(".");
            }
            
            watchService = FileSystems.getDefault().newWatchService();
            parentDir.register(watchService, 
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            
            watchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ConfigWatcher");
                t.setDaemon(true);
                return t;
            });
            
            watching = true;
            
            watchExecutor.scheduleWithFixedDelay(this::pollFileChanges, 
                    1, 1, TimeUnit.SECONDS);
            
            logger.info("Started watching configuration file: {}", configPath);
            
        } catch (IOException e) {
            logger.error("Failed to start config file watcher", e);
        }
    }

    /**
     * Stops watching the configuration file.
     */
    public void stopWatching() {
        watching = false;
        
        if (watchExecutor != null) {
            watchExecutor.shutdown();
            try {
                if (!watchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    watchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                watchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            watchExecutor = null;
        }
        
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.warn("Error closing watch service", e);
            }
            watchService = null;
        }
        
        logger.info("Stopped watching configuration file");
    }

    /**
     * Registers a client connection for config sync broadcasts.
     *
     * @param client the client connection
     */
    public void registerClient(ClientConnection client) {
        connectedClients.add(client);
        logger.debug("Registered client for config sync: {}", client.getClientId());
    }

    /**
     * Unregisters a client connection.
     *
     * @param client the client connection
     */
    public void unregisterClient(ClientConnection client) {
        connectedClients.remove(client);
        logger.debug("Unregistered client from config sync: {}", client.getClientId());
    }

    /**
     * Adds a listener to be notified when configuration is reloaded.
     *
     * @param listener the reload listener
     */
    public void addReloadListener(Consumer<NovaLinkConfig> listener) {
        reloadListeners.add(listener);
    }

    /**
     * Removes a reload listener.
     *
     * @param listener the listener to remove
     */
    public void removeReloadListener(Consumer<NovaLinkConfig> listener) {
        reloadListeners.remove(listener);
    }

    /**
     * Broadcasts a ConfigSyncPacket to all connected clients.
     * Uses the network handler if available, otherwise falls back to direct client set.
     *
     * @param config the configuration to broadcast
     */
    public void broadcastConfigSync(NovaLinkConfig config) {
        // Convert config to JSON for transmission
        String configJson = serializeConfigForSync(config);
        ConfigSyncPacket packet = new ConfigSyncPacket(configJson, System.currentTimeMillis());
        
        // Get clients to broadcast to
        Set<ClientConnection> clients = getClientsForBroadcast();
        
        if (clients.isEmpty()) {
            logger.debug("No clients connected, skipping config sync broadcast");
            return;
        }
        
        int successCount = 0;
        int failCount = 0;
        
        for (ClientConnection client : clients) {
            if (!client.isActive()) {
                continue;
            }
            
            try {
                client.sendPacket(packet);
                successCount++;
                logger.debug("Sent config sync to client: {}", client.getClientId());
            } catch (Exception e) {
                failCount++;
                logger.warn("Failed to send config sync to client {}: {}", 
                        client.getClientId(), e.getMessage());
            }
        }
        
        logger.info("Broadcasted config sync to {}/{} clients (failed: {})", 
                successCount, clients.size(), failCount);
    }

    /**
     * Sends a ConfigSyncPacket to a single client connection.
     *
     * This is useful for initial sync right after a successful handshake.
     *
     * @param client the client connection
     */
    public void sendConfigSync(ClientConnection client) {
        if (client == null || !client.isActive()) {
            return;
        }

        NovaLinkConfig current = getConfig();
        if (current == null) {
            // Not expected, but don't fail the connection.
            client.sendPacket(new ConfigSyncPacket("{}", System.currentTimeMillis()));
            return;
        }

        String configJson = serializeConfigForSync(current);
        client.sendPacket(new ConfigSyncPacket(configJson, System.currentTimeMillis()));
    }
    
    /**
     * Gets the set of clients to broadcast to.
     * Prefers network handler, then connection supplier, then direct client set.
     *
     * @return set of client connections
     */
    private Set<ClientConnection> getClientsForBroadcast() {
        // Prefer network handler
        if (networkHandler != null) {
            return networkHandler.getConnections();
        }
        
        // Fall back to connection supplier
        if (connectionSupplier != null) {
            return connectionSupplier.get();
        }
        
        // Fall back to direct client set
        return Set.copyOf(connectedClients);
    }

    /**
     * Serializes the configuration for sync transmission.
     * Only includes data relevant to clients (channels, templates).
     *
     * @param config the configuration
     * @return JSON string
     */
    private String serializeConfigForSync(NovaLinkConfig config) {
        // Create a simplified config for clients
        Map<String, Object> syncData = new LinkedHashMap<>();
        
        // Include global channels
        syncData.put("global_channels", config.getGlobalChannels());
        
        // Include templates
        syncData.put("templates", config.getTemplates());
        
        // Include client-specific channel info (without passwords)
        List<Map<String, Object>> clientsData = new ArrayList<>();
        for (ClientConfig client : config.getClients()) {
            Map<String, Object> clientData = new LinkedHashMap<>();
            clientData.put("username", client.getUsername());
            clientData.put("display_name", client.getDisplayName());
            clientData.put("channels", client.getChannels());
            clientsData.add(clientData);
        }
        syncData.put("clients", clientsData);
        
        return gson.toJson(syncData);
    }

    /**
     * Polls for file changes from the watch service.
     */
    private void pollFileChanges() {
        if (!watching || watchService == null) {
            return;
        }
        
        try {
            WatchKey key = watchService.poll();
            if (key == null) {
                return;
            }
            
            Path configFileName = configLoader.getConfigPath().getFileName();
            
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path changedFile = pathEvent.context();
                
                if (changedFile.equals(configFileName)) {
                    // Debounce rapid changes
                    long now = System.currentTimeMillis();
                    if (now - lastReloadTime < DEBOUNCE_MS) {
                        continue;
                    }

                    synchronized (fileOperationLock) {
                        // If the file's current content matches what we last
                        // wrote, this change was our own save. The lock closes
                        // the window between the atomic move and hash capture.
                        try {
                            String currentHash = sha256(Files.readString(
                                    configLoader.getConfigPath(), StandardCharsets.UTF_8));
                            if (lastWrittenFileHash != null
                                    && currentHash.equals(lastWrittenFileHash)
                                    && now - lastWrittenAt <= SELF_WRITE_EVENT_WINDOW_MS) {
                                logger.debug("Config file change was our own write, skipping reload");
                                continue;
                            }
                            if (lastWrittenFileHash != null && !currentHash.equals(lastWrittenFileHash)) {
                                lastWrittenFileHash = null;
                            }
                        } catch (IOException hashErr) {
                            // Can't verify; proceed with reload.
                            logger.debug("Could not hash config file before reload: {}", hashErr.getMessage());
                        }

                        logger.info("Configuration file changed, triggering reload");
                        try {
                            reload();
                        } catch (ConfigException e) {
                            logger.error("Failed to reload configuration after file change", e);
                        }
                    }
                }
            }
            
            key.reset();
            
        } catch (Exception e) {
            logger.error("Error polling file changes", e);
        }
    }

    /**
     * Computes the SHA-256 hex digest of the given content.
     */
    private static String sha256(String content) {
        if (content == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandated by the JLS; this should never happen.
            return null;
        }
    }

    /**
     * Shuts down the config manager.
     */
    public void shutdown() {
        stopWatching();
        connectedClients.clear();
        reloadListeners.clear();
        networkHandler = null;
        connectionSupplier = null;
    }
    
    /**
     * Checks if the file watcher is currently active.
     *
     * @return true if watching for file changes
     */
    public boolean isWatching() {
        return watching;
    }
    
    /**
     * Gets the number of times the configuration has been reloaded.
     *
     * @return the reload count
     */
    public int getReloadCount() {
        return reloadCount;
    }
    
    /**
     * Gets the timestamp of the last successful reload.
     *
     * @return the timestamp in milliseconds, or 0 if never reloaded
     */
    public long getLastSuccessfulReload() {
        return lastSuccessfulReload;
    }

    /**
     * Returns the current settings revision (PANEL-010).
     *
     * <p>The revision is incremented atomically on every {@link #save()} and
     * {@link #reload(boolean)} call. Panel clients read it via
     * {@code GET /api/settings} (where it is also rendered as the ETag) and
     * must echo it back on {@code PUT /api/settings} via {@code If-Match} or
     * {@code baseRevision}; a stale value yields {@code 409 Conflict} with the
     * current server values.
     *
     * @return the monotonic settings revision
     */
    public long getSettingsRevision() {
        return settingsRevision.get();
    }

    /**
     * Returns a weak ETag for the current settings revision (PANEL-010).
     *
     * <p>A weak ETag is appropriate because semantically-equivalent settings
     * states can serialize to different byte strings (map ordering, whitespace);
     * the revision is the authoritative change indicator.
     *
     * @return an ETag string of the form {@code W/"<revision>"}
     */
    public String settingsETag() {
        return "W/\"" + settingsRevision.get() + "\"";
    }
    
    /**
     * Gets the configuration file path.
     *
     * @return the config file path
     */
    public Path getConfigPath() {
        return configLoader.getConfigPath();
    }

    /**
     * Validates a candidate YAML document without persisting it or mutating
     * the live config. Delegates to {@link ConfigLoader#validateYaml(String)};
     * this thin pass-through is the shortest path that exposes the loader's
     * validation to {@code RestApiHandler.handleValidateConfig} without adding
     * a {@code getConfigLoader()} accessor (which would widen the ConfigManager
     * surface and touch save/reload internals — forbidden by the guard list).
     *
     * <p>§11.6 Project 20 (proposal 10): backs {@code POST /api/settings/validate}.
     *
     * @param yaml the candidate YAML document
     * @return an immutable {@link ConfigValidationResult}; never {@code null}
     */
    public ConfigValidationResult validateYaml(String yaml) {
        return configLoader.validateYaml(yaml);
    }

    /**
     * Gets the number of registered reload listeners.
     *
     * @return the listener count
     */
    public int getReloadListenerCount() {
        return reloadListeners.size();
    }
    
    /**
     * Gets the number of connected clients (from direct registration).
     * Note: This may not reflect all clients if using network handler.
     *
     * @return the connected client count
     */
    public int getConnectedClientCount() {
        Set<ClientConnection> clients = getClientsForBroadcast();
        return clients.size();
    }
}
