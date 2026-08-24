package com.nova.chat.client.network;

import java.util.Objects;

/**
 * Client-side TLS configuration (AUTH-002).
 *
 * <p>When attached to a {@link ClientConnectionConfig} via
 * {@link ClientConnectionConfig.Builder#tls(ClientTlsConfig)}, the Netty client
 * pipeline prepends an {@code SslHandler} so the challenge-response handshake
 * runs inside an encrypted channel.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code caCertFile} — PEM trust CA used to verify the server certificate.
 *       When {@code null}/blank the client uses the JVM default trust store
 *       (sufficient when the server presents a cert chain rooted in a public
 *       CA, e.g. Let's Encrypt).</li>
 *   <li>{@code clientCertFile} / {@code clientKeyFile} — optional client
 *       identity for mutual TLS. When both are set the client presents a
 *       certificate; leave blank when the server does not require client
 *       certificates.</li>
 * </ul>
 */
public final class ClientTlsConfig {

    private final String caCertFile;
    private final String clientCertFile;
    private final String clientKeyFile;

    public ClientTlsConfig(String caCertFile, String clientCertFile, String clientKeyFile) {
        this.caCertFile = caCertFile;
        this.clientCertFile = clientCertFile;
        this.clientKeyFile = clientKeyFile;
    }

    public String getCaCertFile() {
        return caCertFile;
    }

    public String getClientCertFile() {
        return clientCertFile;
    }

    public String getClientKeyFile() {
        return clientKeyFile;
    }

    /** @return {@code true} when a client identity (mTLS) is configured. */
    public boolean hasClientIdentity() {
        return clientCertFile != null && !clientCertFile.isBlank()
                && clientKeyFile != null && !clientKeyFile.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientTlsConfig that = (ClientTlsConfig) o;
        return Objects.equals(caCertFile, that.caCertFile)
                && Objects.equals(clientCertFile, that.clientCertFile)
                && Objects.equals(clientKeyFile, that.clientKeyFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caCertFile, clientCertFile, clientKeyFile);
    }

    @Override
    public String toString() {
        return "ClientTlsConfig{caCertFile='" + caCertFile + '\''
                + ", clientCertFile='" + clientCertFile + '\''
                + ", clientKeyFile='" + (clientKeyFile != null ? "***" : "null") + '\''
                + '}';
    }
}
