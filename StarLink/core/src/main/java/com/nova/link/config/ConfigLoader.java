package com.nova.link.config;

import com.nova.link.auth.AuthManager;
import com.nova.link.auth.SuperAdminCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * YAML configuration loader with comments preservation and auto-completion.
 * 
 * Requirements:
 * - 18.3, 18.4: Auto-completion for missing fields, preserve comments
 * - 20.1-20.6: Configuration file structure
 */
public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    
    private final Path configPath;
    private NovaLinkConfig config;
    private String originalContent;
    private long lastModified;

    public ConfigLoader(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "Config path cannot be null");
    }

    /**
     * Loads the configuration from file.
     * If the file doesn't exist, creates a default configuration.
     * Auto-completes missing fields with defaults.
     *
     * @return the loaded configuration
     * @throws ConfigException if loading fails
     */
    public NovaLinkConfig load() throws ConfigException {
        try {
            if (!Files.exists(configPath)) {
                logger.info("Configuration file not found, creating default: {}", configPath);
                config = NovaLinkConfig.createDefault();
                save();
                return config;
            }

            originalContent = Files.readString(configPath, StandardCharsets.UTF_8);
            lastModified = Files.getLastModifiedTime(configPath).toMillis();
            
            config = parseYaml(originalContent);
            
            // Auto-complete missing fields
            autoComplete(config);
            
            logger.info("Configuration loaded from: {}", configPath);
            return config;
            
        } catch (IOException e) {
            throw new ConfigException("Failed to load configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Saves the current configuration to file.
     * Attempts to preserve comments from the original file.
     *
     * @throws ConfigException if saving fails
     */
    public void save() throws ConfigException {
        try {
            String yamlContent = serializeToYaml(config);
            
            // If we have original content, try to preserve comments
            if (originalContent != null && !originalContent.isEmpty()) {
                yamlContent = preserveComments(originalContent, yamlContent);
            } else {
                // Add default comments for new files
                yamlContent = addDefaultComments(yamlContent);
            }
            
            // configPath may be a relative path like "./novalink.yml" where getParent() is null.
            // In that case, we should write directly to the working directory.
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(configPath, yamlContent, StandardCharsets.UTF_8);
            lastModified = Files.getLastModifiedTime(configPath).toMillis();
            originalContent = yamlContent;
            
            logger.info("Configuration saved to: {}", configPath);
            
        } catch (IOException e) {
            throw new ConfigException("Failed to save configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Reloads the configuration from file.
     *
     * @return the reloaded configuration
     * @throws ConfigException if reloading fails
     */
    public NovaLinkConfig reload() throws ConfigException {
        return load();
    }

    /**
     * Checks if the configuration file has been modified since last load.
     *
     * @return true if modified
     */
    public boolean hasFileChanged() {
        try {
            if (!Files.exists(configPath)) {
                return false;
            }
            long currentModified = Files.getLastModifiedTime(configPath).toMillis();
            return currentModified > lastModified;
        } catch (IOException e) {
            logger.warn("Failed to check file modification time", e);
            return false;
        }
    }

    /**
     * Gets the current configuration.
     *
     * @return the current configuration, or null if not loaded
     */
    public NovaLinkConfig getConfig() {
        return config;
    }

    /**
     * Gets the configuration file path.
     *
     * @return the config path
     */
    public Path getConfigPath() {
        return configPath;
    }

    // Internal parsing methods

    @SuppressWarnings("unchecked")
    private NovaLinkConfig parseYaml(String content) throws ConfigException {
        try {
            Yaml yaml = createYaml();
            Map<String, Object> data = yaml.load(content);
            
            if (data == null) {
                return NovaLinkConfig.createDefault();
            }
            
            NovaLinkConfig config = new NovaLinkConfig();
            
            // Parse server section
            if (data.containsKey("server")) {
                config.setServer(parseServerConfig((Map<String, Object>) data.get("server")));
            }
            
            // Parse database section
            if (data.containsKey("database")) {
                config.setDatabase(parseDatabaseConfig((Map<String, Object>) data.get("database")));
            }
            
            // Parse security section
            if (data.containsKey("security")) {
                config.setSecurity(parseSecurityConfig((Map<String, Object>) data.get("security")));
            }
            
            // Parse super-admins
            if (data.containsKey("super-admins")) {
                config.setSuperAdmins(parseSuperAdmins((List<Map<String, Object>>) data.get("super-admins")));
            }
            
            // Parse debug
            if (data.containsKey("debug")) {
                config.setDebug(Boolean.TRUE.equals(data.get("debug")));
            }
            
            // Parse global_channels
            if (data.containsKey("global_channels")) {
                config.setGlobalChannels(parseGlobalChannels((Map<String, Object>) data.get("global_channels")));
            }
            
            // Parse templates
            if (data.containsKey("templates")) {
                config.setTemplates(parseTemplates((Map<String, Object>) data.get("templates")));
            }
            
            // Parse clients
            if (data.containsKey("clients")) {
                config.setClients(parseClients((List<Map<String, Object>>) data.get("clients")));
            }

            // Parse features (Settings page)
            if (data.containsKey("features")) {
                config.setFeatures(parseFeatureConfig((Map<String, Object>) data.get("features")));
            }

            return config;
            
        } catch (Exception e) {
            throw new ConfigException("Failed to parse YAML: " + e.getMessage(), e);
        }
    }

    private ServerConfig parseServerConfig(Map<String, Object> data) {
        ServerConfig config = new ServerConfig();
        if (data == null) return config;
        
        if (data.containsKey("bind-address")) {
            config.setBindAddress((String) data.get("bind-address"));
        }
        if (data.containsKey("port")) {
            config.setPort(((Number) data.get("port")).intValue());
        }
        if (data.containsKey("websocket-port")) {
            config.setWebsocketPort(((Number) data.get("websocket-port")).intValue());
        }
        if (data.containsKey("secret-key")) {
            config.setSecretKey((String) data.get("secret-key"));
        }
        if (data.containsKey("worker-threads")) {
            config.setWorkerThreads(((Number) data.get("worker-threads")).intValue());
        }
        if (data.containsKey("locale")) {
            config.setLocale((String) data.get("locale"));
        }

        return config;
    }

    @SuppressWarnings("unchecked")
    private DatabaseConfig parseDatabaseConfig(Map<String, Object> data) {
        DatabaseConfig config = new DatabaseConfig();
        if (data == null) return config;
        
        if (data.containsKey("type")) {
            config.setType((String) data.get("type"));
        }
        
        if (data.containsKey("mysql")) {
            Map<String, Object> mysqlData = (Map<String, Object>) data.get("mysql");
            DatabaseConfig.MySQLConfig mysql = config.getMysql();
            if (mysqlData.containsKey("host")) mysql.setHost((String) mysqlData.get("host"));
            if (mysqlData.containsKey("port")) mysql.setPort(((Number) mysqlData.get("port")).intValue());
            if (mysqlData.containsKey("database")) mysql.setDatabase((String) mysqlData.get("database"));
            if (mysqlData.containsKey("username")) mysql.setUsername((String) mysqlData.get("username"));
            if (mysqlData.containsKey("password")) mysql.setPassword((String) mysqlData.get("password"));
            if (mysqlData.containsKey("pool-size")) mysql.setPoolSize(((Number) mysqlData.get("pool-size")).intValue());
        }

        if (data.containsKey("postgresql")) {
            Map<String, Object> pgData = (Map<String, Object>) data.get("postgresql");
            DatabaseConfig.PostgreSQLConfig pg = config.getPostgresql();
            if (pgData.containsKey("host")) pg.setHost((String) pgData.get("host"));
            if (pgData.containsKey("port")) pg.setPort(((Number) pgData.get("port")).intValue());
            if (pgData.containsKey("database")) pg.setDatabase((String) pgData.get("database"));
            if (pgData.containsKey("username")) pg.setUsername((String) pgData.get("username"));
            if (pgData.containsKey("password")) pg.setPassword((String) pgData.get("password"));
            if (pgData.containsKey("pool-size")) pg.setPoolSize(((Number) pgData.get("pool-size")).intValue());
        }

        if (data.containsKey("sqlite")) {
            Map<String, Object> sqliteData = (Map<String, Object>) data.get("sqlite");
            DatabaseConfig.SQLiteConfig sqlite = config.getSqlite();
            if (sqliteData.containsKey("file-path")) sqlite.setFilePath((String) sqliteData.get("file-path"));
            if (sqliteData.containsKey("pool-size")) sqlite.setPoolSize(((Number) sqliteData.get("pool-size")).intValue());
        }

        if (data.containsKey("redis")) {
            Map<String, Object> redisData = (Map<String, Object>) data.get("redis");
            DatabaseConfig.RedisConfig redis = config.getRedis();
            if (redisData.containsKey("enabled")) redis.setEnabled(Boolean.TRUE.equals(redisData.get("enabled")));
            if (redisData.containsKey("host")) redis.setHost((String) redisData.get("host"));
            if (redisData.containsKey("port")) redis.setPort(((Number) redisData.get("port")).intValue());
            if (redisData.containsKey("password")) redis.setPassword((String) redisData.get("password"));
        }

        return config;
    }

    @SuppressWarnings("unchecked")
    private SecurityConfig parseSecurityConfig(Map<String, Object> data) {
        SecurityConfig config = new SecurityConfig();
        if (data == null) return config;
        
        if (data.containsKey("allowed-ips")) {
            config.setAllowedIps((List<String>) data.get("allowed-ips"));
        }
        if (data.containsKey("ip-ban-duration")) {
            config.setIpBanDuration(((Number) data.get("ip-ban-duration")).intValue());
        }
        
        return config;
    }

    private List<SuperAdminCredentials> parseSuperAdmins(List<Map<String, Object>> data) {
        List<SuperAdminCredentials> admins = new ArrayList<>();
        if (data == null) return admins;

        for (Map<String, Object> adminData : data) {
            String uuidStr = (String) adminData.get("uuid");
            String passwordHash = (String) adminData.get("password-hash");
            String plainPassword = (String) adminData.get("password");
            String username = (String) adminData.get("username");
            if (uuidStr == null) {
                logger.warn("Skipping super-admin entry without uuid: {}", adminData);
                continue;
            }
            // Resolve the effective password hash:
            //  - password-hash present -> use as-is (precomputed SHA-256 hex).
            //  - password-hash absent but password (plain) present -> SHA-256 it at load time.
            //  - neither present -> skip (no credentials).
            String effectiveHash = passwordHash;
            if (effectiveHash == null) {
                if (plainPassword == null) {
                    logger.warn("Skipping super-admin entry without password-hash or password: {}", uuidStr);
                    continue;
                }
                effectiveHash = AuthManager.hashPassword(plainPassword);
            }
            try {
                UUID uuid = UUID.fromString(uuidStr);
                admins.add(new SuperAdminCredentials(uuid, effectiveHash, username));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID in super-admins: {}", uuidStr);
            }
        }

        return admins;
    }

    @SuppressWarnings("unchecked")
    private Map<String, GlobalChannelConfig> parseGlobalChannels(Map<String, Object> data) {
        Map<String, GlobalChannelConfig> channels = new LinkedHashMap<>();
        if (data == null) return channels;
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Map<String, Object> channelData = (Map<String, Object>) entry.getValue();
            GlobalChannelConfig channel = new GlobalChannelConfig();
            
            if (channelData.containsKey("display_name")) {
                channel.setDisplayName((String) channelData.get("display_name"));
            }
            if (channelData.containsKey("permission")) {
                channel.setPermission((String) channelData.get("permission"));
            }
            if (channelData.containsKey("max_capacity")) {
                channel.setMaxCapacity(((Number) channelData.get("max_capacity")).intValue());
            }
            
            channels.put(entry.getKey(), channel);
        }
        
        return channels;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ChannelTemplateConfig> parseTemplates(Map<String, Object> data) {
        Map<String, ChannelTemplateConfig> templates = new LinkedHashMap<>();
        if (data == null) return templates;
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Map<String, Object> templateData = (Map<String, Object>) entry.getValue();
            ChannelTemplateConfig template = new ChannelTemplateConfig();
            
            if (templateData.containsKey("display_name")) {
                template.setDisplayName((String) templateData.get("display_name"));
            }
            if (templateData.containsKey("scope")) {
                template.setScope((String) templateData.get("scope"));
            }
            if (templateData.containsKey("permission")) {
                template.setPermission((String) templateData.get("permission"));
            }
            if (templateData.containsKey("max_capacity")) {
                template.setMaxCapacity(((Number) templateData.get("max_capacity")).intValue());
            }
            if (templateData.containsKey("allowed_worlds")) {
                template.setAllowedWorlds((List<String>) templateData.get("allowed_worlds"));
            }
            
            templates.put(entry.getKey(), template);
        }
        
        return templates;
    }

    @SuppressWarnings("unchecked")
    private List<ClientConfig> parseClients(List<Map<String, Object>> data) {
        List<ClientConfig> clients = new ArrayList<>();
        if (data == null) return clients;
        
        for (Map<String, Object> clientData : data) {
            ClientConfig client = new ClientConfig();
            
            if (clientData.containsKey("username")) {
                client.setUsername((String) clientData.get("username"));
            }
            if (clientData.containsKey("password")) {
                client.setPassword((String) clientData.get("password"));
            }
            if (clientData.containsKey("display_name")) {
                client.setDisplayName((String) clientData.get("display_name"));
            }
            if (clientData.containsKey("permissions")) {
                Object perms = clientData.get("permissions");
                if (perms instanceof List) {
                    List<String> permissionList = new ArrayList<>();
                    for (Object p : (List<?>) perms) {
                        if (p != null) {
                            String s = String.valueOf(p).trim();
                            if (!s.isEmpty()) {
                                permissionList.add(s);
                            }
                        }
                    }
                    client.setPermissions(permissionList);
                }
            }

            if (clientData.containsKey("channels")) {
                Map<String, Object> channelsData = (Map<String, Object>) clientData.get("channels");
                Map<String, ServerChannelConfig> channels = new LinkedHashMap<>();
                
                for (Map.Entry<String, Object> channelEntry : channelsData.entrySet()) {
                    Map<String, Object> channelData = (Map<String, Object>) channelEntry.getValue();
                    ServerChannelConfig channel = new ServerChannelConfig();
                    
                    if (channelData.containsKey("use_template")) {
                        channel.setUseTemplate((String) channelData.get("use_template"));
                    }
                    if (channelData.containsKey("display_name")) {
                        channel.setDisplayName((String) channelData.get("display_name"));
                    }
                    if (channelData.containsKey("scope")) {
                        channel.setScope((String) channelData.get("scope"));
                    }
                    if (channelData.containsKey("permission")) {
                        channel.setPermission((String) channelData.get("permission"));
                    }
                    if (channelData.containsKey("max_capacity")) {
                        channel.setMaxCapacity(((Number) channelData.get("max_capacity")).intValue());
                    }
                    if (channelData.containsKey("allowed_worlds")) {
                        channel.setAllowedWorlds((List<String>) channelData.get("allowed_worlds"));
                    }
                    
                    channels.put(channelEntry.getKey(), channel);
                }
                
                client.setChannels(channels);
            }
            
            clients.add(client);
        }

        return clients;
    }

    private FeatureConfig parseFeatureConfig(Map<String, Object> data) {
        FeatureConfig features = new FeatureConfig();
        if (data == null) return features;

        if (data.containsKey("filter-enabled")) {
            features.setFilterEnabled(Boolean.TRUE.equals(data.get("filter-enabled")));
        }
        if (data.containsKey("message-log-enabled")) {
            features.setMessageLogEnabled(Boolean.TRUE.equals(data.get("message-log-enabled")));
        }
        if (data.containsKey("cross-server-chat-enabled")) {
            features.setCrossServerChatEnabled(Boolean.TRUE.equals(data.get("cross-server-chat-enabled")));
        }

        return features;
    }

    // Serialization methods

    private String serializeToYaml(NovaLinkConfig config) {
        Map<String, Object> data = new LinkedHashMap<>();
        
        // Server section
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("bind-address", config.getServer().getBindAddress());
        server.put("port", config.getServer().getPort());
        server.put("websocket-port", config.getServer().getWebsocketPort());
        server.put("secret-key", config.getServer().getSecretKey());
        server.put("worker-threads", config.getServer().getWorkerThreads());
        server.put("locale", config.getServer().getLocale());
        data.put("server", server);
        
        // Database section
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("type", config.getDatabase().getType());
        
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("host", config.getDatabase().getMysql().getHost());
        mysql.put("port", config.getDatabase().getMysql().getPort());
        mysql.put("database", config.getDatabase().getMysql().getDatabase());
        mysql.put("username", config.getDatabase().getMysql().getUsername());
        mysql.put("password", config.getDatabase().getMysql().getPassword());
        mysql.put("pool-size", config.getDatabase().getMysql().getPoolSize());
        database.put("mysql", mysql);

        Map<String, Object> postgresql = new LinkedHashMap<>();
        postgresql.put("host", config.getDatabase().getPostgresql().getHost());
        postgresql.put("port", config.getDatabase().getPostgresql().getPort());
        postgresql.put("database", config.getDatabase().getPostgresql().getDatabase());
        postgresql.put("username", config.getDatabase().getPostgresql().getUsername());
        postgresql.put("password", config.getDatabase().getPostgresql().getPassword());
        postgresql.put("pool-size", config.getDatabase().getPostgresql().getPoolSize());
        database.put("postgresql", postgresql);

        Map<String, Object> sqlite = new LinkedHashMap<>();
        sqlite.put("file-path", config.getDatabase().getSqlite().getFilePath());
        sqlite.put("pool-size", config.getDatabase().getSqlite().getPoolSize());
        database.put("sqlite", sqlite);

        Map<String, Object> redis = new LinkedHashMap<>();
        redis.put("enabled", config.getDatabase().getRedis().isEnabled());
        redis.put("host", config.getDatabase().getRedis().getHost());
        redis.put("port", config.getDatabase().getRedis().getPort());
        redis.put("password", config.getDatabase().getRedis().getPassword());
        database.put("redis", redis);
        data.put("database", database);
        
        // Security section
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("allowed-ips", config.getSecurity().getAllowedIps());
        security.put("ip-ban-duration", config.getSecurity().getIpBanDuration());
        data.put("security", security);
        
        // Super admins (always persist the resolved password-hash; never the plain password)
        List<Map<String, Object>> superAdmins = new ArrayList<>();
        for (SuperAdminCredentials admin : config.getSuperAdmins()) {
            Map<String, Object> adminData = new LinkedHashMap<>();
            adminData.put("uuid", admin.getUuid().toString());
            adminData.put("password-hash", admin.getPasswordHash());
            if (admin.getUsername() != null && !admin.getUsername().isBlank()) {
                adminData.put("username", admin.getUsername());
            }
            superAdmins.add(adminData);
        }
        data.put("super-admins", superAdmins);
        
        // Debug
        data.put("debug", config.isDebug());
        
        // Global channels
        Map<String, Object> globalChannels = new LinkedHashMap<>();
        for (Map.Entry<String, GlobalChannelConfig> entry : config.getGlobalChannels().entrySet()) {
            Map<String, Object> channelData = new LinkedHashMap<>();
            channelData.put("display_name", entry.getValue().getDisplayName());
            channelData.put("permission", entry.getValue().getPermission());
            channelData.put("max_capacity", entry.getValue().getMaxCapacity());
            globalChannels.put(entry.getKey(), channelData);
        }
        data.put("global_channels", globalChannels);
        
        // Templates
        Map<String, Object> templates = new LinkedHashMap<>();
        for (Map.Entry<String, ChannelTemplateConfig> entry : config.getTemplates().entrySet()) {
            Map<String, Object> templateData = new LinkedHashMap<>();
            templateData.put("display_name", entry.getValue().getDisplayName());
            templateData.put("scope", entry.getValue().getScope());
            if (entry.getValue().getPermission() != null) {
                templateData.put("permission", entry.getValue().getPermission());
            }
            if (entry.getValue().getMaxCapacity() != null) {
                templateData.put("max_capacity", entry.getValue().getMaxCapacity());
            }
            if (entry.getValue().getAllowedWorlds() != null) {
                templateData.put("allowed_worlds", entry.getValue().getAllowedWorlds());
            }
            templates.put(entry.getKey(), templateData);
        }
        data.put("templates", templates);
        
        // Clients
        List<Map<String, Object>> clients = new ArrayList<>();
        for (ClientConfig client : config.getClients()) {
            Map<String, Object> clientData = new LinkedHashMap<>();
            clientData.put("username", client.getUsername());
            clientData.put("password", client.getPassword());
            clientData.put("display_name", client.getDisplayName());
            if (client.getPermissions() != null && !client.getPermissions().isEmpty()) {
                clientData.put("permissions", client.getPermissions());
            }

            Map<String, Object> channels = new LinkedHashMap<>();
            for (Map.Entry<String, ServerChannelConfig> channelEntry : client.getChannels().entrySet()) {
                Map<String, Object> channelData = new LinkedHashMap<>();
                ServerChannelConfig channel = channelEntry.getValue();
                
                if (channel.getUseTemplate() != null) {
                    channelData.put("use_template", channel.getUseTemplate());
                }
                if (channel.getDisplayName() != null) {
                    channelData.put("display_name", channel.getDisplayName());
                }
                if (channel.getScope() != null && channel.getUseTemplate() == null) {
                    channelData.put("scope", channel.getScope());
                }
                if (channel.getPermission() != null) {
                    channelData.put("permission", channel.getPermission());
                }
                if (channel.getMaxCapacity() != null) {
                    channelData.put("max_capacity", channel.getMaxCapacity());
                }
                if (channel.getAllowedWorlds() != null) {
                    channelData.put("allowed_worlds", channel.getAllowedWorlds());
                }
                
                channels.put(channelEntry.getKey(), channelData);
            }
            clientData.put("channels", channels);
            
            clients.add(clientData);
        }
        data.put("clients", clients);

        // Features (Settings page)
        if (config.getFeatures() != null) {
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("filter-enabled", config.getFeatures().isFilterEnabled());
            features.put("message-log-enabled", config.getFeatures().isMessageLogEnabled());
            features.put("cross-server-chat-enabled", config.getFeatures().isCrossServerChatEnabled());
            data.put("features", features);
        }

        Yaml yaml = createYaml();
        return yaml.dump(data);
    }

    private Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        
        return new Yaml(options);
    }

    // Auto-completion

    private void autoComplete(NovaLinkConfig config) {
        NovaLinkConfig defaults = NovaLinkConfig.createDefault();
        
        // Auto-complete server config
        if (config.getServer() == null) {
            config.setServer(defaults.getServer());
        }
        
        // Auto-complete database config
        if (config.getDatabase() == null) {
            config.setDatabase(defaults.getDatabase());
        }
        
        // Auto-complete security config
        if (config.getSecurity() == null) {
            config.setSecurity(defaults.getSecurity());
        }

        // Auto-complete features config
        if (config.getFeatures() == null) {
            config.setFeatures(defaults.getFeatures());
        }
    }

    // Comments preservation

    private String preserveComments(String original, String newContent) {
        // Extract comments from original content
        List<String> comments = new ArrayList<>();
        String[] originalLines = original.split("\n");
        
        StringBuilder result = new StringBuilder();
        String[] newLines = newContent.split("\n");
        
        int commentIndex = 0;
        for (String line : originalLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                comments.add(line);
            }
        }
        
        // Add header comments at the beginning
        for (String comment : comments) {
            if (comment.trim().startsWith("# =")) {
                result.append(comment).append("\n");
            }
        }
        
        // Add the new content
        result.append(newContent);
        
        return result.toString();
    }

    private String addDefaultComments(String content) {
        StringBuilder result = new StringBuilder();
        result.append("# ==========================================\n");
        result.append("# NovaLink 后端配置文件\n");
        result.append("# ==========================================\n\n");
        result.append(content);
        return result.toString();
    }
}
