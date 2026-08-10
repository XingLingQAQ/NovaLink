package com.nova.link.config;

import java.util.Objects;

/**
 * Database configuration section.
 * 
 * Requirements: 22.1-22.5
 */
public class DatabaseConfig {

    private String type = "memory";
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
        this.type = type != null ? type : "memory";
    }

    public MySQLConfig getMysql() {
        return mysql;
    }

    public void setMysql(MySQLConfig mysql) {
        this.mysql = mysql != null ? mysql : new MySQLConfig();
    }

    public PostgreSQLConfig getPostgresql() {
        return postgresql;
    }

    public void setPostgresql(PostgreSQLConfig postgresql) {
        this.postgresql = postgresql != null ? postgresql : new PostgreSQLConfig();
    }

    public SQLiteConfig getSqlite() {
        return sqlite;
    }

    public void setSqlite(SQLiteConfig sqlite) {
        this.sqlite = sqlite != null ? sqlite : new SQLiteConfig();
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis != null ? redis : new RedisConfig();
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
        private String host = "127.0.0.1";
        private int port = 3306;
        private String database = "novalink";
        private String username = "root";
        private String password = "";
        private int poolSize = 10;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host != null ? host : "127.0.0.1";
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port > 0 ? port : 3306;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database != null ? database : "novalink";
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username != null ? username : "root";
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password != null ? password : "";
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize > 0 ? poolSize : 10;
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
     * PostgreSQL configuration. Mirrors the MySQL connection fields with
     * PostgreSQL-appropriate defaults (port 5432).
     */
    public static class PostgreSQLConfig {
        private String host = "127.0.0.1";
        private int port = 5432;
        private String database = "novalink";
        private String username = "postgres";
        private String password = "";
        private int poolSize = 10;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host != null ? host : "127.0.0.1";
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port > 0 ? port : 5432;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database != null ? database : "novalink";
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username != null ? username : "postgres";
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password != null ? password : "";
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize > 0 ? poolSize : 10;
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
        private String filePath = "data/novalink.db";
        private int poolSize = 5;

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath != null && !filePath.isBlank() ? filePath : "data/novalink.db";
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize > 0 ? poolSize : 5;
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
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";

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
            this.host = host != null ? host : "127.0.0.1";
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port > 0 ? port : 6379;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password != null ? password : "";
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
