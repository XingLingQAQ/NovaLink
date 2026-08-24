package com.nova.link.config;

import java.util.Objects;

/**
 * Database configuration section.
 * 
 * Requirements: 22.1-22.5
 */
public class DatabaseConfig {

    private String type;
    private MySQLConfig mysql;
    private PostgreSQLConfig postgresql;
    private SQLiteConfig sqlite;
    private RedisConfig redis;

    public DatabaseConfig() {
        this.mysql = new MySQLConfig();
        this.postgresql = new PostgreSQLConfig();
        this.sqlite = new SQLiteConfig();
        this.redis = new RedisConfig();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public MySQLConfig getMysql() {
        return mysql;
    }

    public void setMysql(MySQLConfig mysql) {
        this.mysql = mysql;
    }

    public PostgreSQLConfig getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(PostgreSQLConfig postgresql) {
        this.postgresql = postgresql;
    }

    public SQLiteConfig getSqlite() {
        return sqlite;
    }

    public void setSqlite(SQLiteConfig sqlite) {
        this.sqlite = sqlite;
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseConfig that = (DatabaseConfig) o;
        return Objects.equals(type, that.type) &&
               Objects.equals(mysql, that.mysql) &&
               Objects.equals(postgresql, that.postgresql) &&
               Objects.equals(sqlite, that.sqlite) &&
               Objects.equals(redis, that.redis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, mysql, postgresql, sqlite, redis);
    }

    /**
     * MySQL configuration.
     */
    public static class MySQLConfig {
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private int poolSize;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MySQLConfig that = (MySQLConfig) o;
            return port == that.port &&
                   poolSize == that.poolSize &&
                   Objects.equals(host, that.host) &&
                   Objects.equals(database, that.database) &&
                   Objects.equals(username, that.username) &&
                   Objects.equals(password, that.password);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, database, username, password, poolSize);
        }
    }

    /**
     * PostgreSQL configuration. Mirrors the MySQL connection fields.
     */
    public static class PostgreSQLConfig {
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private int poolSize;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PostgreSQLConfig that = (PostgreSQLConfig) o;
            return port == that.port &&
                   poolSize == that.poolSize &&
                   Objects.equals(host, that.host) &&
                   Objects.equals(database, that.database) &&
                   Objects.equals(username, that.username) &&
                   Objects.equals(password, that.password);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port, database, username, password, poolSize);
        }
    }

    /**
     * SQLite configuration. SQLite is an embedded file database, so only a
     * file path and (optional) pool size are needed.
     */
    public static class SQLiteConfig {
        private String filePath;
        private int poolSize;

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SQLiteConfig that = (SQLiteConfig) o;
            return poolSize == that.poolSize &&
                   Objects.equals(filePath, that.filePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(filePath, poolSize);
        }
    }

    /**
     * Redis configuration.
     */
    public static class RedisConfig {
        private boolean enabled;
        private String host;
        private int port;
        private String password;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RedisConfig that = (RedisConfig) o;
            return enabled == that.enabled &&
                   port == that.port &&
                   Objects.equals(host, that.host) &&
                   Objects.equals(password, that.password);
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, host, port, password);
        }
    }
}
