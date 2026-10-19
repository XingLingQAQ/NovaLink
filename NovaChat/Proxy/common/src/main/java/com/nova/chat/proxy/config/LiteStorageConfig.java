package com.nova.chat.proxy.config;

import java.util.Locale;

/**
 * Storage backend declared under {@code lite.storage}.
 *
 * <p>Mirrors the backend's {@code database} section of {@code novalink.yml}:
 * the selected {@code type} decides which nested section is validated
 * ({@code mysql} / {@code postgresql} / {@code sqlite}), and the unused
 * sections are ignored — a lite deployment can keep the template's default
 * sections around without satisfying them.
 *
 * <p>Only the settings needed to open the connection are parsed here; the lite
 * storage implementation itself is out of scope for the configuration layer.
 */
public final class LiteStorageConfig {

    /** Supported storage types, spelled as the backend's {@code database.type}. */
    public enum Type {
        MEMORY, SQLITE, MYSQL, POSTGRESQL;

        /**
         * @param raw the configured value
         * @return the parsed type
         * @throws ConfigSource.ConfigException when the value is missing or unsupported
         */
        static Type parse(String raw) throws ConfigSource.ConfigException {
            if (raw == null || raw.isBlank()) {
                throw new ConfigSource.ConfigException(
                        "Missing required configuration value: lite.storage.type");
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "memory" -> MEMORY;
                case "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "postgresql" -> POSTGRESQL;
                default -> throw new ConfigSource.ConfigException(
                        "Configuration value lite.storage.type is unsupported: " + raw
                                + " (allowed: memory, sqlite, mysql, postgresql)");
            };
        }
    }

    private final Type type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final String filePath;
    private final String sslMode;
    private final boolean allowPlaintext;

    private LiteStorageConfig(Type type, String host, int port, String database,
                              String username, String password, int poolSize, String filePath,
                              String sslMode, boolean allowPlaintext) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.filePath = filePath;
        this.sslMode = sslMode;
        this.allowPlaintext = allowPlaintext;
    }

    /**
     * Parses {@code lite.storage}.
     *
     * @param source the dialect accessor
     * @return the parsed storage settings
     * @throws ConfigSource.ConfigException when a value required by the selected
     *         type is missing or invalid
     */
    static LiteStorageConfig parse(ConfigSource source) throws ConfigSource.ConfigException {
        Type type = Type.parse(source.requiredString("lite.storage.type"));
        String host = null;
        int port = 0;
        String database = null;
        String username = null;
        String password = null;
        int poolSize = 0;
        String filePath = null;
        String sslMode = null;
        boolean allowPlaintext = false;

        switch (type) {
            case MYSQL, POSTGRESQL -> {
                String prefix = type == Type.MYSQL ? "lite.storage.mysql" : "lite.storage.postgresql";
                host = source.requiredString(prefix + ".host");
                if (host.isBlank()) {
                    throw new ConfigSource.ConfigException(
                            "Configuration value " + prefix + ".host must not be blank");
                }
                port = source.requiredInt(prefix + ".port");
                if (port < 1 || port > 65535) {
                    throw new ConfigSource.ConfigException(
                            "Configuration value " + prefix + ".port must be between 1 and 65535");
                }
                database = source.requiredString(prefix + ".database");
                if (database.isBlank()) {
                    throw new ConfigSource.ConfigException(
                            "Configuration value " + prefix + ".database must not be blank");
                }
                poolSize = source.requiredInt(prefix + ".pool-size");
                if (poolSize <= 0) {
                    throw new ConfigSource.ConfigException(
                            "Configuration value " + prefix + ".pool-size must be greater than 0");
                }
                // Credentials are optional so an existing document (which the
                // template updater cannot rewrite in place) keeps loading: an
                // empty username/password means "let the server decide", exactly
                // as the backend's database section behaves.
                username = source.optionalString(prefix + ".username");
                password = source.optionalString(prefix + ".password");
                sslMode = source.optionalString(prefix + ".ssl-mode");
                String plaintext = source.optionalString(prefix + ".allow-plaintext");
                allowPlaintext = plaintext != null && Boolean.parseBoolean(plaintext.trim());
            }
            case SQLITE -> {
                filePath = source.requiredString("lite.storage.sqlite.file-path");
                if (filePath.isBlank()) {
                    throw new ConfigSource.ConfigException(
                            "Configuration value lite.storage.sqlite.file-path must not be blank");
                }
            }
            case MEMORY -> {
                // No settings: everything lives in the proxy process.
            }
        }

        return new LiteStorageConfig(type, host, port, database, username, password,
                poolSize, filePath, sslMode, allowPlaintext);
    }

    /**
     * Builds a configuration programmatically, without a configuration document.
     *
     * <p>Production always goes through {@link #parse(ConfigSource)}; this exists
     * so tests can drive a backend directly. For {@code SQLITE} the
     * {@code hostOrFilePath} argument is the file path.
     *
     * @param type the backend type
     * @param hostOrFilePath the host (MySQL/PostgreSQL) or file path (SQLite);
     *                       ignored for memory
     * @param port the port, or 0
     * @param database the database name, or null
     * @return the settings
     */
    public static LiteStorageConfig forTests(Type type, String hostOrFilePath,
                                             int port, String database) {
        return switch (type) {
            case SQLITE -> new LiteStorageConfig(type, null, 0, null,
                    null, null, 0, hostOrFilePath, null, false);
            case MEMORY -> new LiteStorageConfig(type, null, 0, null,
                    null, null, 0, null, null, false);
            case MYSQL, POSTGRESQL -> new LiteStorageConfig(type, hostOrFilePath, port, database,
                    null, null, Math.max(1, port > 0 ? 2 : 2), null, null, false);
        };
    }

    /**
     * @return the selected storage type
     */
    public Type getType() {
        return type;
    }

    /**
     * @return the mysql/postgresql host, or null for other types
     */
    public String getHost() {
        return host;
    }

    /**
     * @return the mysql/postgresql port, or 0 for other types
     */
    public int getPort() {
        return port;
    }

    /**
     * @return the mysql/postgresql database name, or null for other types
     */
    public String getDatabase() {
        return database;
    }

    /**
     * @return the mysql/postgresql connection pool size, or 0 for other types
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * @return the sqlite file path, or null for other types
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @return the mysql/postgresql username, or null when not configured
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return the mysql/postgresql password, or null when not configured
     */
    public String getPassword() {
        return password;
    }

    /**
     * @return the mysql/postgresql TLS mode, or null to use the provider's
     *         fail-closed default
     */
    public String getSslMode() {
        return sslMode;
    }

    /**
     * @return whether an unencrypted JDBC connection was explicitly accepted.
     *         Required by the storage providers before they will honour an
     *         {@code ssl-mode} of {@code DISABLED}/{@code disable}
     */
    public boolean isAllowPlaintext() {
        return allowPlaintext;
    }
}
