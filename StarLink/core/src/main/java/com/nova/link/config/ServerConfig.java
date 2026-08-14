package com.nova.link.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server configuration section.
 * 
 * Requirements: 20.1-20.6
 */
public class ServerConfig {

    /** Default TCP read-idle timeout (seconds). Must stay >= 3x the client
     * heartbeat period: Bedrock clients send a KeepAlive every 15s; Java-side
     * clients never send on their own (they only echo), so the backend pings
     * them on write-idle at timeout/3 (30s by default) and expects the echo. */
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 90;
    /** Default per-connection chat/item-display rate limit (messages per second). */
    public static final int DEFAULT_RATE_LIMIT_MESSAGES_PER_SECOND = 10;
    /** Default token-bucket burst capacity. */
    public static final int DEFAULT_RATE_LIMIT_BURST = 20;
    /** Default REST worker pool size (business logic off the Netty IO threads). */
    public static final int DEFAULT_REST_WORKER_THREADS = 4;

    private String bindAddress = "0.0.0.0";
    private int port = 8888;
    private int websocketPort = 8889;
    private String secretKey = "change-me-in-production";
    private int workerThreads = 4;
    private String locale = "zh_CN";
    // CORS origin whitelist for the REST/WS HTTP endpoints. The default ["*"]
    // keeps backward compatibility (allow all); configure explicit origins to
    // lock the panel API down.
    private List<String> corsAllowedOrigins = new ArrayList<>(List.of("*"));
    // TCP read-idle timeout in seconds (0 = disabled).
    private int idleTimeoutSeconds = DEFAULT_IDLE_TIMEOUT_SECONDS;
    // Per-connection message rate limit (token bucket); 0 = disabled.
    private int rateLimitMessagesPerSecond = DEFAULT_RATE_LIMIT_MESSAGES_PER_SECOND;
    private int rateLimitBurst = DEFAULT_RATE_LIMIT_BURST;
    // Dedicated REST worker pool size (fixed); requests are rejected with 503
    // when the pool + queue are saturated.
    private int restWorkerThreads = DEFAULT_REST_WORKER_THREADS;

    public ServerConfig() {}

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress != null ? bindAddress : "0.0.0.0";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port > 0 ? port : 8888;
    }

    public int getWebsocketPort() {
        return websocketPort;
    }

    public void setWebsocketPort(int websocketPort) {
        this.websocketPort = websocketPort > 0 ? websocketPort : 8889;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey != null ? secretKey : "change-me-in-production";
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads > 0 ? workerThreads : 4;
    }

    /**
     * @return the backend console locale string (e.g. {@code "zh_CN"},
     *         {@code "en_US"}); never null — defaults to {@code "zh_CN"}.
     */
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale != null && !locale.isBlank() ? locale : "zh_CN";
    }

    /**
     * @return the CORS origin whitelist; never null/empty — defaults to
     *         {@code ["*"]} (allow all, backward compatible)
     */
    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = (corsAllowedOrigins != null && !corsAllowedOrigins.isEmpty())
                ? new ArrayList<>(corsAllowedOrigins)
                : new ArrayList<>(List.of("*"));
    }

    /**
     * @return the TCP read-idle timeout in seconds; {@code 0} disables idle
     *         detection. Never negative.
     */
    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds >= 0
                ? idleTimeoutSeconds
                : DEFAULT_IDLE_TIMEOUT_SECONDS;
    }

    /**
     * @return the per-connection message rate limit (tokens per second);
     *         {@code 0} disables rate limiting. Never negative.
     */
    public int getRateLimitMessagesPerSecond() {
        return rateLimitMessagesPerSecond;
    }

    public void setRateLimitMessagesPerSecond(int rateLimitMessagesPerSecond) {
        this.rateLimitMessagesPerSecond = rateLimitMessagesPerSecond >= 0
                ? rateLimitMessagesPerSecond
                : DEFAULT_RATE_LIMIT_MESSAGES_PER_SECOND;
    }

    /**
     * @return the token-bucket burst capacity (max messages accepted at once).
     */
    public int getRateLimitBurst() {
        return rateLimitBurst;
    }

    public void setRateLimitBurst(int rateLimitBurst) {
        this.rateLimitBurst = rateLimitBurst > 0 ? rateLimitBurst : DEFAULT_RATE_LIMIT_BURST;
    }

    /**
     * @return the fixed size of the dedicated REST worker pool (>= 1).
     */
    public int getRestWorkerThreads() {
        return restWorkerThreads;
    }

    public void setRestWorkerThreads(int restWorkerThreads) {
        this.restWorkerThreads = restWorkerThreads > 0
                ? restWorkerThreads
                : DEFAULT_REST_WORKER_THREADS;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerConfig that = (ServerConfig) o;
        return port == that.port &&
               websocketPort == that.websocketPort &&
               workerThreads == that.workerThreads &&
               idleTimeoutSeconds == that.idleTimeoutSeconds &&
               rateLimitMessagesPerSecond == that.rateLimitMessagesPerSecond &&
               rateLimitBurst == that.rateLimitBurst &&
               restWorkerThreads == that.restWorkerThreads &&
               Objects.equals(bindAddress, that.bindAddress) &&
               Objects.equals(secretKey, that.secretKey) &&
               Objects.equals(locale, that.locale) &&
               Objects.equals(corsAllowedOrigins, that.corsAllowedOrigins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindAddress, port, websocketPort, secretKey, workerThreads, locale,
                corsAllowedOrigins, idleTimeoutSeconds, rateLimitMessagesPerSecond, rateLimitBurst,
                restWorkerThreads);
    }
}
