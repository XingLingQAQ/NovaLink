package com.nova.link.config;

import java.util.Objects;

/**
 * Server configuration section.
 * 
 * Requirements: 20.1-20.6
 */
public class ServerConfig {

    private String bindAddress = "0.0.0.0";
    private int port = 8888;
    private int websocketPort = 8889;
    private String secretKey = "change-me-in-production";
    private int workerThreads = 4;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerConfig that = (ServerConfig) o;
        return port == that.port &&
               websocketPort == that.websocketPort &&
               workerThreads == that.workerThreads &&
               Objects.equals(bindAddress, that.bindAddress) &&
               Objects.equals(secretKey, that.secretKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindAddress, port, websocketPort, secretKey, workerThreads);
    }
}
