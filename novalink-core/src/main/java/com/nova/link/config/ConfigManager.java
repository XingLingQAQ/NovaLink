package com.nova.link.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
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
     * @throws ConfigException if saving fails
     */
    public void save() throws ConfigException {
        configLoader.save();
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
                    
                    logger.info("Configuration file changed, triggering reload");
                    try {
                        reload();
                    } catch (ConfigException e) {
                        logger.error("Failed to reload configuration after file change", e);
                    }
                }
            }
            
            key.reset();
            
        } catch (Exception e) {
            logger.error("Error polling file changes", e);
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
     * Gets the configuration file path.
     *
     * @return the config file path
     */
    public Path getConfigPath() {
        return configLoader.getConfigPath();
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
