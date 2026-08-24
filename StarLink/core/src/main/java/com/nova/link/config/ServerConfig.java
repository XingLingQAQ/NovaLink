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

    private String bindAddress;
    private int port;
    private int websocketPort;
    private String secretKey;
    private int workerThreads;
    private String locale;
    private List<String> corsAllowedOrigins = new ArrayList<>();
    private int idleTimeoutSeconds;
    private int rateLimitMessagesPerSecond;
    private int rateLimitBurst;
    private int restWorkerThreads;
    /**
     * AUTH-002: when {@code true}, the operator has explicitly acknowledged
     * that the TCP listener runs without TLS and passwords traverse the wire
     * (challenge-response HMAC still protects the stored hash, but the
     * challenge itself is observable). The backend refuses to start in
     * plaintext unless this flag is set. Defaults to {@code false}.
     */
    private boolean insecureAllowPlaintext;
    /** AUTH-002: optional TLS configuration for the TCP listener. {@code null} = no TLS. */
    private TlsConfig tls;

    public ServerConfig() {}

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getWebsocketPort() {
        return websocketPort;
    }

    public void setWebsocketPort(int websocketPort) {
        this.websocketPort = websocketPort;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    /**
     * @return the backend console locale string (e.g. {@code "zh_CN"},
     *         {@code "en_US"})
     */
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    /**
     * @return the configured CORS origin whitelist
     */
    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = new ArrayList<>(corsAllowedOrigins);
    }

    /**
     * @return the TCP read-idle timeout in seconds; {@code 0} disables idle detection
     */
    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    /**
     * @return the per-connection message rate limit; {@code 0} disables it
     */
    public int getRateLimitMessagesPerSecond() {
        return rateLimitMessagesPerSecond;
    }

    public void setRateLimitMessagesPerSecond(int rateLimitMessagesPerSecond) {
        this.rateLimitMessagesPerSecond = rateLimitMessagesPerSecond;
    }

    /**
     * @return the token-bucket burst capacity (max messages accepted at once).
     */
    public int getRateLimitBurst() {
        return rateLimitBurst;
    }

    public void setRateLimitBurst(int rateLimitBurst) {
        this.rateLimitBurst = rateLimitBurst;
    }

    /**
     * @return the fixed size of the dedicated REST worker pool
     */
    public int getRestWorkerThreads() {
        return restWorkerThreads;
    }

    public void setRestWorkerThreads(int restWorkerThreads) {
        this.restWorkerThreads = restWorkerThreads;
    }

    /**
     * @return {@code true} only if the operator has explicitly opted into
     *         running the TCP listener without TLS (AUTH-002 insecure mode).
     */
    public boolean isInsecureAllowPlaintext() {
        return insecureAllowPlaintext;
    }

    public void setInsecureAllowPlaintext(boolean insecureAllowPlaintext) {
        this.insecureAllowPlaintext = insecureAllowPlaintext;
    }

    /**
     * @return the TLS configuration for the TCP listener, or {@code null} when
     *         TLS is disabled (AUTH-002).
     */
    public TlsConfig getTls() {
        return tls;
    }

    public void setTls(TlsConfig tls) {
        this.tls = tls;
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
               insecureAllowPlaintext == that.insecureAllowPlaintext &&
               Objects.equals(bindAddress, that.bindAddress) &&
               Objects.equals(secretKey, that.secretKey) &&
               Objects.equals(locale, that.locale) &&
               Objects.equals(corsAllowedOrigins, that.corsAllowedOrigins) &&
               Objects.equals(tls, that.tls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindAddress, port, websocketPort, secretKey, workerThreads, locale,
                corsAllowedOrigins, idleTimeoutSeconds, rateLimitMessagesPerSecond, rateLimitBurst,
                restWorkerThreads, insecureAllowPlaintext, tls);
    }
}
