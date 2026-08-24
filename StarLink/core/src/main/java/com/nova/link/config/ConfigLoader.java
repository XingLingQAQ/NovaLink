package com.nova.link.config;

import com.nova.link.auth.AuthManager;
import com.nova.link.auth.SuperAdminCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

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
    private static final String DEFAULT_CONFIG_RESOURCE = "/novalink.yml";
    private static final Set<String> DYNAMIC_TEMPLATE_MAPPINGS = Set.of(
            "global_channels", "templates");
    private static final Set<String> KEYED_SEQUENCES = Set.of(
            "super-admins", "panel-users", "clients");
    
    private final Path configPath;
    private NovaLinkConfig config;
    private String originalContent;
    private long lastModified;

    public ConfigLoader(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "Config path cannot be null");
    }

    static NovaLinkConfig loadBundledDefaults() throws ConfigException {
        ConfigLoader loader = new ConfigLoader(Path.of("novalink.yml"));
        return loader.parseYaml(loader.readDefaultConfigTemplate());
    }

    /**
     * Loads the configuration from file.
     * If the file doesn't exist, copies the bundled configuration template.
     * Missing fields introduced by newer templates are added automatically.
     *
     * @return the loaded configuration
     * @throws ConfigException if loading fails
     */
    public NovaLinkConfig load() throws ConfigException {
        try {
            String templateContent = readDefaultConfigTemplate();
            if (!Files.exists(configPath)) {
                logger.info("Configuration file not found, creating default: {}", configPath);
                NovaLinkConfig loaded = parseYaml(templateContent);
                writeConfigAtomically(templateContent, false);
                originalContent = templateContent;
                lastModified = Files.getLastModifiedTime(configPath).toMillis();
                config = loaded;
                logger.info("Configuration loaded from: {}", configPath);
                return loaded;
            }

            String diskContent = Files.readString(configPath, StandardCharsets.UTF_8);
            String upgradedContent = mergeMissingDefaults(diskContent, templateContent);
            NovaLinkConfig loaded = parseYaml(upgradedContent);
            if (!diskContent.equals(upgradedContent)) {
                writeConfigAtomically(upgradedContent, true);
                logger.info("Configuration upgraded with newly introduced template fields: {}", configPath);
            }
            originalContent = upgradedContent;
            lastModified = Files.getLastModifiedTime(configPath).toMillis();
            config = loaded;

            logger.info("Configuration loaded from: {}", configPath);
            return config;
            
        } catch (IOException e) {
            throw new ConfigException("Failed to load configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Saves the current configuration to file.
     *
     * <p>Comment-preserving round-trip via SnakeYAML 2.x Node API: if we have
     * the original file content, we re-compose it into a Node tree (which
     * carries block/inline/end comments), merge values from the live
     * {@link NovaLinkConfig}, and re-serialize the tree. Section-level,
     * field-level and inline comments all survive a panel save.
     *
     * <p>If this loader has not loaded a file yet, the bundled template is used
     * as the base node tree. A failed comment-preserving merge aborts the save;
     * the live file is never replaced by a lossy full re-serialization.
     *
     * @throws ConfigException if saving fails
     */
    public void save() throws ConfigException {
        try {
            if (config == null) {
                throw new ConfigException("Cannot save before a configuration object is available");
            }

            String baseContent = originalContent;
            if (baseContent == null || baseContent.isEmpty()) {
                String diskContent = Files.exists(configPath)
                        ? Files.readString(configPath, StandardCharsets.UTF_8)
                        : "";
                baseContent = diskContent.isBlank()
                        ? readDefaultConfigTemplate()
                        : diskContent;
            }
            String yamlContent = saveWithComments(baseContent, config);
            if (yamlContent == null) {
                throw new ConfigException(
                        "Comment-preserving configuration save failed; live file was not changed");
            }

            // Validate the exact document that will be committed. Runtime
            // mutations must not be able to write an unloadable config.
            parseYaml(yamlContent);

            writeConfigAtomically(yamlContent, true);

            lastModified = Files.getLastModifiedTime(configPath).toMillis();
            originalContent = yamlContent;

            logger.info("Configuration saved to: {}", configPath);

        } catch (IOException e) {
            throw new ConfigException("Failed to save configuration: " + e.getMessage(), e);
        }
    }

    private String readDefaultConfigTemplate() throws ConfigException {
        try (InputStream input = ConfigLoader.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (input == null) {
                throw new ConfigException(
                        "Bundled default configuration is missing: " + DEFAULT_CONFIG_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Failed to read bundled default configuration", e);
        }
    }

    private void writeConfigAtomically(String yamlContent, boolean createBackup) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path absolutePath = configPath.toAbsolutePath();
        Path absoluteParent = absolutePath.getParent();
        if (absoluteParent == null) {
            throw new IOException("Configuration path has no parent directory: " + configPath);
        }
        Path tmp = Files.createTempFile(absoluteParent,
                "." + absolutePath.getFileName() + ".", ".tmp");
        try {
            Files.writeString(tmp, yamlContent, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            if (createBackup && Files.exists(absolutePath)) {
                Path bak = absolutePath.resolveSibling(absolutePath.getFileName() + ".bak");
                Files.copy(configPath, bak, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(tmp, absolutePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, absolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
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

    /**
     * Validates a candidate YAML document against the same structural rules
     * used by {@link #parseYaml(String)} without persisting anything or
     * mutating the live in-memory config.
     *
     * <p>§11.6 Project 20 (proposal 10): backs {@code POST /api/settings/validate}.
     * This is a pure wrapper around {@code parseYaml} — it does NOT duplicate
     * the structural validation logic (validateScope, requiredNonBlankString,
     * max_capacity&gt;0, port, etc.). On success the result is
     * {@link ConfigValidationResult#ok()}; on failure the caught
     * {@link ConfigException}'s message (which already embeds the path线索
     * the loader adds) becomes a single {@link ConfigValidationResult.ValidationError}
     * with {@code path=null} (the loader does not emit a structured path, and
     * the contract requires {@code path} to be {@code null} rather than
     * synthesised).
     *
     * @param yaml the candidate YAML document; must not be {@code null}
     * @return an immutable validation result; never {@code null}
     */
    public ConfigValidationResult validateYaml(String yaml) {
        if (yaml == null) {
            return ConfigValidationResult.failure(null,
                    "yaml must not be null");
        }
        try {
            parseYaml(yaml);
            return ConfigValidationResult.ok();
        } catch (ConfigException e) {
            String message = e.getMessage();
            if (message == null) {
                message = "Failed to parse YAML";
            }
            return ConfigValidationResult.failure(null, message);
        }
    }

    private NovaLinkConfig parseYaml(String content) throws ConfigException {
        try {
            Yaml yaml = createYaml();
            Object loaded = yaml.load(content);
            Map<String, Object> data = asMap(loaded, "root");
            
            if (data == null) {
                throw new IllegalArgumentException("configuration root must be a YAML mapping");
            }
            
            NovaLinkConfig config = new NovaLinkConfig();
            
            // Fixed sections are guaranteed by the bundled template updater.
            // Reading them as required values keeps the file authoritative: a
            // future field must be added to the template, not silently restored
            // from a second default hidden in the Java model.
            config.setServer(parseServerConfig(requiredMap(data, "server")));
            config.setDatabase(parseDatabaseConfig(requiredMap(data, "database")));
            config.setSecurity(parseSecurityConfig(requiredMap(data, "security")));
            config.setSuperAdmins(parseSuperAdmins(
                    requiredMapList(data.get("super-admins"), "super-admins")));
            config.setPanelUsers(parsePanelUsers(
                    requiredMapList(data.get("panel-users"), "panel-users")));
            config.setDebug(requiredBoolean(data, "debug"));
            config.setGlobalChannels(parseGlobalChannels(
                    requiredMap(data, "global_channels")));
            config.setTemplates(parseTemplates(requiredMap(data, "templates")));
            config.setClients(parseClients(
                    requiredMapList(data.get("clients"), "clients")));
            config.setFeatures(parseFeatureConfig(requiredMap(data, "features")));
            config.setFilter(parseFilterConfig(requiredMap(data, "filter")));

            return config;
            
        } catch (Exception e) {
            throw new ConfigException("Failed to parse YAML: " + e.getMessage(), e);
        }
    }

    private ServerConfig parseServerConfig(Map<String, Object> data) {
        ServerConfig config = new ServerConfig();
        config.setBindAddress(requiredNonBlankString(data, "bind-address", "server.bind-address"));
        config.setPort(requiredPort(data, "port", "server.port"));
        config.setWebsocketPort(requiredPort(data, "websocket-port", "server.websocket-port"));
        config.setSecretKey(requiredNonBlankString(data, "secret-key", "server.secret-key"));
        config.setWorkerThreads(requiredPositiveInt(data, "worker-threads", "server.worker-threads"));
        config.setLocale(requiredNonBlankString(data, "locale", "server.locale"));
        config.setCorsAllowedOrigins(requiredStringList(data.get("cors-allowed-origins"),
                "server.cors-allowed-origins"));
        config.setIdleTimeoutSeconds(requiredNonNegativeInt(
                data, "idle-timeout-seconds", "server.idle-timeout-seconds"));
        config.setRestWorkerThreads(requiredPositiveInt(
                data, "rest-worker-threads", "server.rest-worker-threads"));
        Map<String, Object> rateLimitData = requiredMap(data, "rate-limit");
        config.setRateLimitMessagesPerSecond(requiredNonNegativeInt(
                rateLimitData, "messages-per-second", "server.rate-limit.messages-per-second"));
        config.setRateLimitBurst(requiredPositiveInt(
                rateLimitData, "burst", "server.rate-limit.burst"));

        // AUTH-002: plaintext TCP is opt-in. Defaults to false (fail-closed).
        config.setInsecureAllowPlaintext(optionalBoolean(
                data, "insecure-allow-plaintext", false));

        // AUTH-002: optional TLS block. Absent ⇒ plaintext (which then requires
        // insecure-allow-plaintext, enforced by InsecureModeGate).
        TlsConfig tls = null;
        if (data.containsKey("tls") && data.get("tls") != null) {
            Map<String, Object> tlsData = asMap(data.get("tls"), "server.tls");
            tls = new TlsConfig();
            tls.setCertChainFile(optionalString(tlsData, "cert-chain-file", "server.tls.cert-chain-file"));
            tls.setPrivateKeyFile(optionalString(tlsData, "private-key-file", "server.tls.private-key-file"));
            tls.setCaCertFile(optionalString(tlsData, "ca-cert-file", "server.tls.ca-cert-file"));
            tls.setMutualTls(optionalBoolean(tlsData, "mutual-tls", false));
            if (tls.isMutualTls() && (tls.getCaCertFile() == null || tls.getCaCertFile().isBlank())) {
                throw new IllegalArgumentException(
                        "Configuration value server.tls.ca-cert-file is required when server.tls.mutual-tls is true");
            }
        }
        config.setTls(tls);

        return config;
    }

    private DatabaseConfig parseDatabaseConfig(Map<String, Object> data) {
        DatabaseConfig config = new DatabaseConfig();
        String type = requiredNonBlankString(data, "type", "database.type").toLowerCase(Locale.ROOT);
        if (!Set.of("memory", "mysql", "postgresql", "postgres", "pg", "sqlite", "redis")
                .contains(type)) {
            throw new IllegalArgumentException("Configuration value database.type is unsupported: " + type);
        }
        config.setType(type);

        Map<String, Object> mysqlData = requiredMap(data, "mysql");
        DatabaseConfig.MySQLConfig mysql = config.getMysql();
        mysql.setHost(requiredNonBlankString(mysqlData, "host", "database.mysql.host"));
        mysql.setPort(requiredPort(mysqlData, "port", "database.mysql.port"));
        mysql.setDatabase(requiredNonBlankString(mysqlData, "database", "database.mysql.database"));
        mysql.setUsername(requiredNonBlankString(mysqlData, "username", "database.mysql.username"));
        mysql.setPassword(requiredString(mysqlData, "password"));
        mysql.setPoolSize(requiredPositiveInt(mysqlData, "pool-size", "database.mysql.pool-size"));

        Map<String, Object> pgData = requiredMap(data, "postgresql");
        DatabaseConfig.PostgreSQLConfig pg = config.getPostgresql();
        pg.setHost(requiredNonBlankString(pgData, "host", "database.postgresql.host"));
        pg.setPort(requiredPort(pgData, "port", "database.postgresql.port"));
        pg.setDatabase(requiredNonBlankString(pgData, "database", "database.postgresql.database"));
        pg.setUsername(requiredNonBlankString(pgData, "username", "database.postgresql.username"));
        pg.setPassword(requiredString(pgData, "password"));
        pg.setPoolSize(requiredPositiveInt(pgData, "pool-size", "database.postgresql.pool-size"));

        Map<String, Object> sqliteData = requiredMap(data, "sqlite");
        DatabaseConfig.SQLiteConfig sqlite = config.getSqlite();
        sqlite.setFilePath(requiredNonBlankString(sqliteData, "file-path", "database.sqlite.file-path"));
        sqlite.setPoolSize(requiredPositiveInt(sqliteData, "pool-size", "database.sqlite.pool-size"));

        Map<String, Object> redisData = requiredMap(data, "redis");
        DatabaseConfig.RedisConfig redis = config.getRedis();
        redis.setEnabled(requiredBoolean(redisData, "enabled"));
        redis.setHost(requiredNonBlankString(redisData, "host", "database.redis.host"));
        redis.setPort(requiredPort(redisData, "port", "database.redis.port"));
        redis.setPassword(requiredString(redisData, "password"));

        return config;
    }

    private SecurityConfig parseSecurityConfig(Map<String, Object> data) {
        SecurityConfig config = new SecurityConfig();
        config.setAllowedIps(requiredStringList(data.get("allowed-ips"), "security.allowed-ips"));
        config.setIpBanDuration(requiredPositiveInt(
                data, "ip-ban-duration", "security.ip-ban-duration"));

        return config;
    }

    private List<SuperAdminCredentials> parseSuperAdmins(List<Map<String, Object>> data) {
        List<SuperAdminCredentials> admins = new ArrayList<>();
        if (data == null) return admins;

        for (Map<String, Object> adminData : data) {
            String uuidStr = asString(adminData.get("uuid"), "super-admins.uuid");
            String passwordHash = asString(adminData.get("password-hash"), "super-admins.password-hash");
            String plainPassword = asString(adminData.get("password"), "super-admins.password");
            String username = asString(adminData.get("username"), "super-admins.username");
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

    /**
     * Parses the {@code panel-users} section: web-panel login accounts with
     * role ADMIN or VIEWER. Credentials support the same two spellings as
     * super-admins: {@code password-hash} (precomputed SHA-256 hex, wins when
     * both are present) or {@code password} (plain, hashed at load time).
     * Entries with a missing/invalid role (including SUPER_ADMIN, which is
     * reserved for the {@code super-admins} section) are skipped with a warning.
     */
    private List<PanelUserConfig> parsePanelUsers(List<Map<String, Object>> data) {
        List<PanelUserConfig> users = new ArrayList<>();
        if (data == null) return users;

        for (Map<String, Object> userData : data) {
            String username = asString(userData.get("username"), "panel-users.username");
            String passwordHash = asString(userData.get("password-hash"), "panel-users.password-hash");
            String plainPassword = asString(userData.get("password"), "panel-users.password");
            String role = userData.get("role") != null ? String.valueOf(userData.get("role")).trim() : null;

            if (username == null || username.isBlank()) {
                logger.warn("Skipping panel-user entry without username: {}", userData);
                continue;
            }
            String effectiveHash = passwordHash;
            if (effectiveHash == null) {
                if (plainPassword == null) {
                    logger.warn("Skipping panel-user entry without password-hash or password: {}", username);
                    continue;
                }
                effectiveHash = AuthManager.hashPassword(plainPassword);
            }
            String normalizedRole = role != null ? role.toUpperCase(Locale.ROOT) : null;
            if (!"ADMIN".equals(normalizedRole) && !"VIEWER".equals(normalizedRole)) {
                logger.warn("Skipping panel-user entry '{}' with invalid role '{}' (allowed: ADMIN, VIEWER)",
                        username, role);
                continue;
            }
            users.add(new PanelUserConfig(username, effectiveHash, normalizedRole));
        }

        return users;
    }

    private Map<String, GlobalChannelConfig> parseGlobalChannels(Map<String, Object> data) {
        Map<String, GlobalChannelConfig> channels = new LinkedHashMap<>();
        if (data == null) return channels;
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Global channel ID must not be blank");
            }
            Map<String, Object> channelData = requiredMap(entry.getValue(),
                    "global_channels." + entry.getKey());
            GlobalChannelConfig channel = new GlobalChannelConfig();

            channel.setDisplayName(optionalString(channelData, "display_name",
                    "global_channels." + entry.getKey() + ".display_name"));
            channel.setPermission(optionalString(channelData, "permission",
                    "global_channels." + entry.getKey() + ".permission"));
            channel.setMaxCapacity(requiredPositiveInt(channelData, "max_capacity",
                    "global_channels." + entry.getKey() + ".max_capacity"));
            Integer slowMode = optionalInt(channelData, "slow_mode",
                    "global_channels." + entry.getKey() + ".slow_mode");
            if (slowMode != null) {
                if (slowMode < 0) {
                    throw new IllegalArgumentException("Configuration value global_channels."
                            + entry.getKey() + ".slow_mode must not be negative");
                }
                channel.setSlowModeSeconds(slowMode);
            }

            channels.put(entry.getKey(), channel);
        }

        return channels;
    }

    private Map<String, ChannelTemplateConfig> parseTemplates(Map<String, Object> data) {
        Map<String, ChannelTemplateConfig> templates = new LinkedHashMap<>();
        if (data == null) return templates;
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Channel template ID must not be blank");
            }
            Map<String, Object> templateData = requiredMap(entry.getValue(),
                    "templates." + entry.getKey());
            ChannelTemplateConfig template = new ChannelTemplateConfig();

            template.setDisplayName(optionalString(templateData, "display_name",
                    "templates." + entry.getKey() + ".display_name"));
            template.setScope(requiredNonBlankString(templateData, "scope",
                    "templates." + entry.getKey() + ".scope"));
            validateScope(template.getScope(), "templates." + entry.getKey() + ".scope");
            template.setPermission(optionalString(templateData, "permission",
                    "templates." + entry.getKey() + ".permission"));
            template.setMaxCapacity(optionalInt(templateData, "max_capacity",
                    "templates." + entry.getKey() + ".max_capacity"));
            if (template.getMaxCapacity() != null && template.getMaxCapacity() <= 0) {
                throw new IllegalArgumentException("Configuration value templates."
                        + entry.getKey() + ".max_capacity must be greater than 0");
            }
            if (templateData.containsKey("allowed_worlds")) {
                template.setAllowedWorlds(asStringList(templateData.get("allowed_worlds"), "allowed_worlds"));
            }

            templates.put(entry.getKey(), template);
        }

        return templates;
    }

    private List<ClientConfig> parseClients(List<Map<String, Object>> data) {
        List<ClientConfig> clients = new ArrayList<>();
        if (data == null) return clients;
        
        for (Map<String, Object> clientData : data) {
            ClientConfig client = new ClientConfig();

            client.setUsername(requiredNonBlankString(clientData, "username", "clients.username"));
            client.setPassword(requiredString(clientData, "password"));
            client.setDisplayName(optionalString(clientData, "display_name", "clients.display_name"));
            if (clientData.containsKey("permissions")) {
                Object perms = clientData.get("permissions");
                if (perms instanceof List<?>) {
                    List<String> permissionList = new ArrayList<>();
                    for (Object p : (List<?>) perms) {
                        if (!(p instanceof String permission)) {
                            throw new IllegalArgumentException(
                                    "Configuration value clients.permissions must contain only strings");
                        }
                        String normalized = permission.trim();
                        if (!normalized.isEmpty()) {
                            permissionList.add(normalized);
                        }
                    }
                    client.setPermissions(permissionList);
                } else {
                    throw new IllegalArgumentException(
                            "Configuration value clients.permissions must be a list");
                }
            }

            if (clientData.containsKey("channels")) {
                Map<String, Object> channelsData = requiredMap(clientData.get("channels"),
                        "clients.channels");
                Map<String, ServerChannelConfig> channels = new LinkedHashMap<>();

                for (Map.Entry<String, Object> channelEntry : channelsData.entrySet()) {
                        if (channelEntry.getKey().isBlank()) {
                            throw new IllegalArgumentException("Client channel ID must not be blank");
                        }
                        Map<String, Object> channelData = requiredMap(channelEntry.getValue(),
                                "clients.channels." + channelEntry.getKey());
                        ServerChannelConfig channel = new ServerChannelConfig();

                        String channelPath = "clients.channels." + channelEntry.getKey();
                        channel.setUseTemplate(optionalString(channelData, "use_template",
                                channelPath + ".use_template"));
                        channel.setDisplayName(optionalString(channelData, "display_name",
                                channelPath + ".display_name"));
                        channel.setScope(optionalString(channelData, "scope", channelPath + ".scope"));
                        if (channel.getScope() != null) {
                            validateScope(channel.getScope(), "clients.channels."
                                    + channelEntry.getKey() + ".scope");
                        }
                        channel.setPermission(optionalString(channelData, "permission",
                                channelPath + ".permission"));
                        channel.setMaxCapacity(optionalInt(channelData, "max_capacity",
                                channelPath + ".max_capacity"));
                        if (channel.getMaxCapacity() != null && channel.getMaxCapacity() <= 0) {
                            throw new IllegalArgumentException("Configuration value clients.channels."
                                    + channelEntry.getKey() + ".max_capacity must be greater than 0");
                        }
                        if (channelData.containsKey("allowed_worlds")) {
                            channel.setAllowedWorlds(
                                    asStringList(channelData.get("allowed_worlds"), "allowed_worlds"));
                        }
                        Integer slowMode = optionalInt(channelData, "slow_mode",
                                channelPath + ".slow_mode");
                        if (slowMode != null) {
                            if (slowMode < 0) {
                                throw new IllegalArgumentException("Configuration value clients.channels."
                                        + channelEntry.getKey() + ".slow_mode must not be negative");
                            }
                            channel.setSlowModeSeconds(slowMode);
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
        features.setFilterEnabled(requiredBoolean(data, "filter-enabled"));
        features.setMessageLogEnabled(requiredBoolean(data, "message-log-enabled"));
        features.setCrossServerChatEnabled(requiredBoolean(data, "cross-server-chat-enabled"));
        features.setPrivateMessagesEnabled(requiredBoolean(data, "private-messages-enabled"));
        features.setMessageLogRetentionDays(requiredNonNegativeInt(
                data, "message-log-retention-days", "features.message-log-retention-days"));

        return features;
    }

    private FilterConfig parseFilterConfig(Map<String, Object> data) {
        FilterConfig filter = new FilterConfig();
        filter.setWords(requiredStringList(data.get("words"), "filter.words"));
        filter.setPatterns(requiredStringList(data.get("patterns"), "filter.patterns"));

        return filter;
    }

    private static void validateScope(String value, String path) {
        try {
            com.nova.link.channel.ChannelScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " has unsupported scope: " + value);
        }
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
        server.put("cors-allowed-origins", config.getServer().getCorsAllowedOrigins());
        server.put("idle-timeout-seconds", config.getServer().getIdleTimeoutSeconds());
        server.put("rest-worker-threads", config.getServer().getRestWorkerThreads());
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("messages-per-second", config.getServer().getRateLimitMessagesPerSecond());
        rateLimit.put("burst", config.getServer().getRateLimitBurst());
        server.put("rate-limit", rateLimit);
        // AUTH-002: persist the explicit plaintext opt-in + optional TLS block.
        server.put("insecure-allow-plaintext", config.getServer().isInsecureAllowPlaintext());
        TlsConfig tlsCfg = config.getServer().getTls();
        if (tlsCfg != null) {
            Map<String, Object> tls = new LinkedHashMap<>();
            tls.put("cert-chain-file", tlsCfg.getCertChainFile() != null ? tlsCfg.getCertChainFile() : "");
            tls.put("private-key-file", tlsCfg.getPrivateKeyFile() != null ? tlsCfg.getPrivateKeyFile() : "");
            tls.put("ca-cert-file", tlsCfg.getCaCertFile() != null ? tlsCfg.getCaCertFile() : "");
            tls.put("mutual-tls", tlsCfg.isMutualTls());
            server.put("tls", tls);
        }
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

        // Panel users (always persist the resolved password-hash; never the plain password)
        List<Map<String, Object>> panelUsers = new ArrayList<>();
        if (config.getPanelUsers() != null) {
            for (PanelUserConfig user : config.getPanelUsers()) {
                Map<String, Object> userData = new LinkedHashMap<>();
                userData.put("username", user.getUsername());
                userData.put("password-hash", user.getPasswordHash());
                userData.put("role", user.getRole());
                panelUsers.add(userData);
            }
        }
        data.put("panel-users", panelUsers);

        // Debug
        data.put("debug", config.isDebug());
        
        // Global channels
        Map<String, Object> globalChannels = new LinkedHashMap<>();
        for (Map.Entry<String, GlobalChannelConfig> entry : config.getGlobalChannels().entrySet()) {
            Map<String, Object> channelData = new LinkedHashMap<>();
            if (entry.getValue().getDisplayName() != null) {
                channelData.put("display_name", entry.getValue().getDisplayName());
            }
            if (entry.getValue().getPermission() != null) {
                channelData.put("permission", entry.getValue().getPermission());
            }
            channelData.put("max_capacity", entry.getValue().getMaxCapacity());
            channelData.put("slow_mode", entry.getValue().getSlowModeSeconds());
            globalChannels.put(entry.getKey(), channelData);
        }
        data.put("global_channels", globalChannels);
        
        // Templates
        Map<String, Object> templates = new LinkedHashMap<>();
        for (Map.Entry<String, ChannelTemplateConfig> entry : config.getTemplates().entrySet()) {
            Map<String, Object> templateData = new LinkedHashMap<>();
            if (entry.getValue().getDisplayName() != null) {
                templateData.put("display_name", entry.getValue().getDisplayName());
            }
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
            if (client.getDisplayName() != null) {
                clientData.put("display_name", client.getDisplayName());
            }
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
                channelData.put("slow_mode", channel.getSlowModeSeconds());
                
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
            features.put("private-messages-enabled", config.getFeatures().isPrivateMessagesEnabled());
            features.put("message-log-retention-days", config.getFeatures().getMessageLogRetentionDays());
            data.put("features", features);
        }

        // Custom sensitive-word filter lists
        if (config.getFilter() != null) {
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("words", new ArrayList<>(config.getFilter().getWords()));
            filter.put("patterns", new ArrayList<>(config.getFilter().getPatterns()));
            data.put("filter", filter);
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

    // Optional fields may be absent, but an existing value with the wrong type
    // is rejected instead of being coerced or reset.

    private static Integer optionalInt(Map<String, Object> data, String key, String path) {
        if (!data.containsKey(key)) {
            return null;
        }
        return integerValue(data.get(key), path);
    }

    private static String optionalString(Map<String, Object> data, String key, String path) {
        if (!data.containsKey(key)) {
            return null;
        }
        Object value = data.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a string");
        }
        return stringValue;
    }

    private static Map<String, Object> asMap(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "Configuration value " + fieldName + " must be a mapping");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String) {
                result.put((String) entry.getKey(), entry.getValue());
            } else if (entry.getKey() != null) {
                throw new IllegalArgumentException(
                        "Configuration mapping " + fieldName + " contains a non-string key");
            }
        }
        return result;
    }

    private static List<Map<String, Object>> asMapList(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Configuration value " + fieldName + " must be a list");
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Object item : (List<?>) value) {
            Map<String, Object> mapping = asMap(item, fieldName + "[" + index + "]");
            if (mapping == null) {
                throw new IllegalArgumentException(
                        "Configuration value " + fieldName + "[" + index + "] must be a mapping");
            }
            result.add(mapping);
            index++;
        }
        return result;
    }

    private static String asString(Object value, String fieldName) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        throw new IllegalArgumentException(
                "Configuration value " + fieldName + " must be a string");
    }

    private static List<String> asStringList(Object value, String key) {
        if (value == null) return null;
        if (value instanceof List<?>) {
            List<String> result = new ArrayList<>();
            for (Object o : (List<?>) value) {
                if (!(o instanceof String stringValue)) {
                    throw new IllegalArgumentException(
                            "Configuration value " + key + " must contain only strings");
                }
                result.add(stringValue);
            }
            return result;
        }
        throw new IllegalArgumentException("Configuration value " + key + " must be a list");
    }

    private static Map<String, Object> requiredMap(Map<String, Object> parent, String key) {
        return requiredMap(parent.get(key), key);
    }

    private static Map<String, Object> requiredMap(Object value, String path) {
        Map<String, Object> result = asMap(value, path);
        if (result == null) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a mapping");
        }
        return result;
    }

    private static List<Map<String, Object>> requiredMapList(Object value, String path) {
        List<Map<String, Object>> result = asMapList(value, path);
        if (result == null) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a list");
        }
        return result;
    }

    private static List<String> requiredStringList(Object value, String path) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be a list");
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String stringValue)) {
                throw new IllegalArgumentException(
                        "Configuration value " + path + " must contain only strings");
            }
            result.add(stringValue);
        }
        return result;
    }

    private static String requiredString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Configuration value " + key + " must be a string");
        }
        return stringValue;
    }

    private static String requiredNonBlankString(Map<String, Object> data, String key, String path) {
        String value = requiredString(data, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static int requiredInt(Map<String, Object> data, String key, String path) {
        if (!data.containsKey(key)) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return integerValue(data.get(key), path);
    }

    private static int integerValue(Object value, String path) {
        if (!(value instanceof Number numberValue)
                || !Double.isFinite(numberValue.doubleValue())
                || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
                || numberValue.doubleValue() < Integer.MIN_VALUE
                || numberValue.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configuration value " + path + " must be an integer");
        }
        return numberValue.intValue();
    }

    private static int requiredPositiveInt(Map<String, Object> data, String key, String path) {
        int value = requiredInt(data, key, path);
        if (value <= 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requiredNonNegativeInt(Map<String, Object> data, String key, String path) {
        int value = requiredInt(data, key, path);
        if (value < 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be negative");
        }
        return value;
    }

    private static int requiredPort(Map<String, Object> data, String key, String path) {
        int value = requiredInt(data, key, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static boolean requiredBoolean(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Configuration value " + key + " must be a boolean");
        }
        return booleanValue;
    }

    /**
     * AUTH-002: optional boolean that defaults to {@code false} when the key is
     * absent. Used for {@code server.insecure-allow-plaintext}.
     */
    private static boolean optionalBoolean(Map<String, Object> data, String key, boolean defaultValue) {
        if (!data.containsKey(key)) {
            return defaultValue;
        }
        Object value = data.get(key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Configuration value " + key + " must be a boolean");
        }
        return booleanValue;
    }

    // Comments preservation (Node API round-trip)

    private String mergeMissingDefaults(String userContent, String defaultContent) throws ConfigException {
        try {
            MappingNode userRoot = composeMapping(userContent);
            MappingNode defaultRoot = composeMapping(defaultContent);
            if (defaultRoot == null) {
                throw new ConfigException("Bundled default configuration is not a YAML mapping");
            }
            if (userRoot == null) {
                throw new ConfigException("Existing configuration root is not a YAML mapping");
            }

            boolean changed = mergeMappings(userRoot, defaultRoot, "", MergeMode.DEFAULTS, Set.of());
            return changed ? serializeNode(userRoot) : userContent;
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("Failed to merge configuration defaults: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the original YAML content in place using SnakeYAML 2.x's
     * comment-preserving Node API. Composes the original content into a Node
     * tree (which retains block/inline/end comments), walks the tree and
     * merges values from the live {@code NovaLinkConfig}, then re-serializes
     * the tree while leaving unknown keys untouched.
     *
     * @param originalContent the previous file content (with comments)
     * @param config          the live config to write into the tree
     * @return the re-serialized YAML with comments preserved, or {@code null}
     *         if compose/serialize failed and the caller must abort the save
     */
    private String saveWithComments(String originalContent, NovaLinkConfig config) {
        try {
            MappingNode root = composeMapping(originalContent);
            MappingNode desiredRoot = composeMapping(serializeToYaml(config));
            if (root == null || desiredRoot == null) {
                return null;
            }

            NovaLinkConfig diskConfig = parseYaml(originalContent);
            Set<String> replacePaths = new HashSet<>();
            if (!Objects.equals(diskConfig.getGlobalChannels(), config.getGlobalChannels())) {
                replacePaths.add("global_channels");
            }
            if (!Objects.equals(diskConfig.getTemplates(), config.getTemplates())) {
                replacePaths.add("templates");
            }
            if (!Objects.equals(diskConfig.getClients(), config.getClients())) {
                replacePaths.add("clients");
            }
            if (!Objects.equals(diskConfig.getSuperAdmins(), config.getSuperAdmins())) {
                replacePaths.add("super-admins");
            }
            if (!Objects.equals(diskConfig.getPanelUsers(), config.getPanelUsers())) {
                replacePaths.add("panel-users");
            }

            mergeMappings(root, desiredRoot, "", MergeMode.RUNTIME_VALUES, replacePaths);
            return serializeNode(root);
        } catch (Exception e) {
            logger.warn("Comment-preserving save failed ({}); save aborted", e.getMessage());
            return null;
        }
    }

    private MappingNode composeMapping(String content) {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setProcessComments(true);
        Node node = new Yaml(loaderOptions).compose(new StringReader(content));
        return node instanceof MappingNode ? (MappingNode) node : null;
    }

    private String serializeNode(Node node) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setProcessComments(true);

        StringWriter writer = new StringWriter();
        new Yaml(options).serialize(node, writer);
        return writer.toString();
    }

    private boolean mergeMappings(MappingNode target, MappingNode source, String parentPath,
                                  MergeMode mode, Set<String> replacePaths) {
        boolean changed = false;
        for (NodeTuple sourceTuple : source.getValue()) {
            if (!(sourceTuple.getKeyNode() instanceof ScalarNode)) {
                continue;
            }

            String key = ((ScalarNode) sourceTuple.getKeyNode()).getValue();
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            int targetIndex = findTupleIndex(target, key);
            if (targetIndex < 0) {
                target.getValue().add(sourceTuple);
                changed = true;
                continue;
            }

            NodeTuple targetTuple = target.getValue().get(targetIndex);
            Node targetValue = targetTuple.getValueNode();
            Node sourceValue = sourceTuple.getValueNode();

            if (mode == MergeMode.RUNTIME_VALUES && replacePaths.contains(path)) {
                replaceTupleValue(target, targetIndex, targetTuple, sourceValue);
                changed = true;
                continue;
            }

            if (sourceValue instanceof MappingNode) {
                if (targetValue instanceof MappingNode) {
                    if (mode != MergeMode.DEFAULTS || !DYNAMIC_TEMPLATE_MAPPINGS.contains(path)) {
                        changed |= mergeMappings((MappingNode) targetValue, (MappingNode) sourceValue,
                                path, mode, replacePaths);
                    }
                } else if (mode == MergeMode.RUNTIME_VALUES) {
                    replaceTupleValue(target, targetIndex, targetTuple, sourceValue);
                    changed = true;
                }
                continue;
            }

            if (sourceValue instanceof SequenceNode) {
                if (!(targetValue instanceof SequenceNode) && mode == MergeMode.RUNTIME_VALUES) {
                    replaceTupleValue(target, targetIndex, targetTuple, sourceValue);
                    changed = true;
                } else if (mode == MergeMode.RUNTIME_VALUES) {
                    SequenceNode targetSequence = (SequenceNode) targetValue;
                    SequenceNode sourceSequence = (SequenceNode) sourceValue;
                    if (KEYED_SEQUENCES.contains(path)
                            && canMergeKeyedSequence(targetSequence, sourceSequence, path)) {
                        changed |= mergeKeyedSequence(targetSequence, sourceSequence,
                                path, replacePaths);
                    } else if (!nodesSemanticallyEqual(targetValue, sourceValue)) {
                        replaceTupleValue(target, targetIndex, targetTuple, sourceValue);
                        changed = true;
                    }
                }
                continue;
            }

            if (mode == MergeMode.DEFAULTS) {
                continue;
            }

            if (mode == MergeMode.RUNTIME_VALUES && !nodesSemanticallyEqual(targetValue, sourceValue)) {
                if (targetValue instanceof ScalarNode && sourceValue instanceof ScalarNode) {
                    ScalarNode oldScalar = (ScalarNode) targetValue;
                    ScalarNode newScalar = (ScalarNode) sourceValue;
                    ScalarNode replacement = new ScalarNode(newScalar.getTag(), newScalar.getValue(),
                            oldScalar.getStartMark(), oldScalar.getEndMark(), oldScalar.getScalarStyle());
                    copyComments(oldScalar, replacement);
                    target.getValue().set(targetIndex,
                            new NodeTuple(targetTuple.getKeyNode(), replacement));
                } else {
                    replaceTupleValue(target, targetIndex, targetTuple, sourceValue);
                }
                changed = true;
            }
        }
        return changed;
    }

    private boolean mergeKeyedSequence(SequenceNode target, SequenceNode source, String path,
                                       Set<String> replacePaths) {
        boolean changed = false;
        for (int i = 0; i < target.getValue().size(); i++) {
            MappingNode targetEntry = (MappingNode) target.getValue().get(i);
            MappingNode sourceEntry = (MappingNode) source.getValue().get(i);
            changed |= mergeMappings(targetEntry, sourceEntry, path + "[]",
                    MergeMode.RUNTIME_VALUES, replacePaths);
            if ("super-admins".equals(path) || "panel-users".equals(path)) {
                changed |= removeKey(targetEntry, "password");
            }
        }
        return changed;
    }

    private boolean canMergeKeyedSequence(SequenceNode target, SequenceNode source, String path) {
        if (target.getValue().size() != source.getValue().size()) {
            return false;
        }

        String identityKey = switch (path) {
            case "super-admins" -> "uuid";
            case "panel-users", "clients" -> "username";
            default -> null;
        };
        if (identityKey == null) {
            return false;
        }

        for (int i = 0; i < target.getValue().size(); i++) {
            Node targetEntry = target.getValue().get(i);
            Node sourceEntry = source.getValue().get(i);
            if (!(targetEntry instanceof MappingNode) || !(sourceEntry instanceof MappingNode)) {
                return false;
            }
            String targetIdentity = scalarValue((MappingNode) targetEntry, identityKey);
            String sourceIdentity = scalarValue((MappingNode) sourceEntry, identityKey);
            if (targetIdentity == null || !targetIdentity.equals(sourceIdentity)) {
                return false;
            }
        }
        return true;
    }

    private String scalarValue(MappingNode mapping, String key) {
        Node value = findValue(mapping, key);
        return value instanceof ScalarNode ? ((ScalarNode) value).getValue() : null;
    }

    private int findTupleIndex(MappingNode mapping, String key) {
        List<NodeTuple> tuples = mapping.getValue();
        for (int i = 0; i < tuples.size(); i++) {
            Node keyNode = tuples.get(i).getKeyNode();
            if (keyNode instanceof ScalarNode
                    && key.equals(((ScalarNode) keyNode).getValue())) {
                return i;
            }
        }
        return -1;
    }

    private void replaceTupleValue(MappingNode mapping, int index, NodeTuple oldTuple, Node newValue) {
        copyComments(oldTuple.getValueNode(), newValue);
        mapping.getValue().set(index, new NodeTuple(oldTuple.getKeyNode(), newValue));
    }

    private void copyComments(Node source, Node target) {
        target.setBlockComments(source.getBlockComments());
        target.setInLineComments(source.getInLineComments());
        target.setEndComments(source.getEndComments());
    }

    private boolean removeKey(MappingNode mapping, String key) {
        int index = findTupleIndex(mapping, key);
        if (index < 0) {
            return false;
        }
        mapping.getValue().remove(index);
        return true;
    }

    private boolean nodesSemanticallyEqual(Node left, Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || !left.getNodeId().equals(right.getNodeId())) {
            return false;
        }
        if (left instanceof ScalarNode && right instanceof ScalarNode) {
            ScalarNode leftScalar = (ScalarNode) left;
            ScalarNode rightScalar = (ScalarNode) right;
            return Objects.equals(leftScalar.getTag(), rightScalar.getTag())
                    && Objects.equals(leftScalar.getValue(), rightScalar.getValue());
        }
        if (left instanceof SequenceNode && right instanceof SequenceNode) {
            List<Node> leftValues = ((SequenceNode) left).getValue();
            List<Node> rightValues = ((SequenceNode) right).getValue();
            if (leftValues.size() != rightValues.size()) {
                return false;
            }
            for (int i = 0; i < leftValues.size(); i++) {
                if (!nodesSemanticallyEqual(leftValues.get(i), rightValues.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof MappingNode && right instanceof MappingNode) {
            MappingNode leftMapping = (MappingNode) left;
            MappingNode rightMapping = (MappingNode) right;
            if (leftMapping.getValue().size() != rightMapping.getValue().size()) {
                return false;
            }
            for (NodeTuple rightTuple : rightMapping.getValue()) {
                if (!(rightTuple.getKeyNode() instanceof ScalarNode)) {
                    return false;
                }
                String key = ((ScalarNode) rightTuple.getKeyNode()).getValue();
                Node leftValue = findValue(leftMapping, key);
                if (!nodesSemanticallyEqual(leftValue, rightTuple.getValueNode())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private enum MergeMode {
        DEFAULTS,
        RUNTIME_VALUES
    }

    /**
     * Finds the value Node under {@code key} in a mapping (by string key).
     */
    private Node findValue(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            Node keyNode = tuple.getKeyNode();
            if (keyNode instanceof ScalarNode) {
                if (key.equals(((ScalarNode) keyNode).getValue())) {
                    return tuple.getValueNode();
                }
            }
        }
        return null;
    }

}
