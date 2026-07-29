package com.nova.chat.client.network;

import java.util.Objects;

/**
 * Immutable connection settings for a NovaChat client talking to a NovaLink backend.
 *
 * <p>Platform modules typically load these values from their own config YAML/JSON
 * and pass the resulting instance into shared network helpers.
 */
public final class ClientConnectionConfig {

    public static final int DEFAULT_PORT = 8888;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;
    public static final int DEFAULT_INITIAL_RECONNECT_DELAY_SECONDS = 1;
    public static final int DEFAULT_MAX_RECONNECT_DELAY_SECONDS = 30;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000L;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final int maxReconnectAttempts;
    private final int initialReconnectDelaySeconds;
    private final int maxReconnectDelaySeconds;
    private final long requestTimeoutMs;
    private final boolean autoReconnect;

    private ClientConnectionConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.username = builder.username;
        this.password = builder.password;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.initialReconnectDelaySeconds = builder.initialReconnectDelaySeconds;
        this.maxReconnectDelaySeconds = builder.maxReconnectDelaySeconds;
        this.requestTimeoutMs = builder.requestTimeoutMs;
        this.autoReconnect = builder.autoReconnect;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public int getInitialReconnectDelaySeconds() {
        return initialReconnectDelaySeconds;
    }

    public int getMaxReconnectDelaySeconds() {
        return maxReconnectDelaySeconds;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    /**
     * Creates a {@link ReconnectPolicy} configured from this connection config.
     */
    public ReconnectPolicy toReconnectPolicy() {
        return new ExponentialBackoffReconnectPolicy(
                maxReconnectAttempts,
                initialReconnectDelaySeconds,
                maxReconnectDelaySeconds
        );
    }

    public Builder toBuilder() {
        return new Builder()
                .host(host)
                .port(port)
                .username(username)
                .password(password)
                .connectTimeoutMs(connectTimeoutMs)
                .maxReconnectAttempts(maxReconnectAttempts)
                .initialReconnectDelaySeconds(initialReconnectDelaySeconds)
                .maxReconnectDelaySeconds(maxReconnectDelaySeconds)
                .requestTimeoutMs(requestTimeoutMs)
                .autoReconnect(autoReconnect);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = DEFAULT_PORT;
        private String username = "";
        private String password = "";
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        private int initialReconnectDelaySeconds = DEFAULT_INITIAL_RECONNECT_DELAY_SECONDS;
        private int maxReconnectDelaySeconds = DEFAULT_MAX_RECONNECT_DELAY_SECONDS;
        private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
        private boolean autoReconnect = true;

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder port(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be in range 1-65535, got " + port);
            }
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username == null ? "" : username;
            return this;
        }

        public Builder password(String password) {
            this.password = password == null ? "" : password;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            if (connectTimeoutMs < 0) {
                throw new IllegalArgumentException("connectTimeoutMs must be >= 0");
            }
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder maxReconnectAttempts(int maxReconnectAttempts) {
            if (maxReconnectAttempts < 0) {
                throw new IllegalArgumentException("maxReconnectAttempts must be >= 0");
            }
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder initialReconnectDelaySeconds(int initialReconnectDelaySeconds) {
            if (initialReconnectDelaySeconds < 0) {
                throw new IllegalArgumentException("initialReconnectDelaySeconds must be >= 0");
            }
            this.initialReconnectDelaySeconds = initialReconnectDelaySeconds;
            return this;
        }

        public Builder maxReconnectDelaySeconds(int maxReconnectDelaySeconds) {
            if (maxReconnectDelaySeconds < 0) {
                throw new IllegalArgumentException("maxReconnectDelaySeconds must be >= 0");
            }
            this.maxReconnectDelaySeconds = maxReconnectDelaySeconds;
            return this;
        }

        public Builder requestTimeoutMs(long requestTimeoutMs) {
            if (requestTimeoutMs < 0L) {
                throw new IllegalArgumentException("requestTimeoutMs must be >= 0");
            }
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        public ClientConnectionConfig build() {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (maxReconnectDelaySeconds < initialReconnectDelaySeconds) {
                throw new IllegalArgumentException(
                        "maxReconnectDelaySeconds (" + maxReconnectDelaySeconds
                                + ") must be >= initialReconnectDelaySeconds ("
                                + initialReconnectDelaySeconds + ")");
            }
            return new ClientConnectionConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientConnectionConfig that)) {
            return false;
        }
        return port == that.port
                && connectTimeoutMs == that.connectTimeoutMs
                && maxReconnectAttempts == that.maxReconnectAttempts
                && initialReconnectDelaySeconds == that.initialReconnectDelaySeconds
                && maxReconnectDelaySeconds == that.maxReconnectDelaySeconds
                && requestTimeoutMs == that.requestTimeoutMs
                && autoReconnect == that.autoReconnect
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                host, port, username, password, connectTimeoutMs,
                maxReconnectAttempts, initialReconnectDelaySeconds,
                maxReconnectDelaySeconds, requestTimeoutMs, autoReconnect
        );
    }

    @Override
    public String toString() {
        return "ClientConnectionConfig{"
                + "host='" + host + '\''
                + ", port=" + port
                + ", username='" + username + '\''
                + ", password=***"
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", maxReconnectAttempts=" + maxReconnectAttempts
                + ", initialReconnectDelaySeconds=" + initialReconnectDelaySeconds
                + ", maxReconnectDelaySeconds=" + maxReconnectDelaySeconds
                + ", requestTimeoutMs=" + requestTimeoutMs
                + ", autoReconnect=" + autoReconnect
                + '}';
    }
}
