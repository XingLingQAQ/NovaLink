package com.nova.link.config;

import java.util.Objects;

/**
 * TLS configuration for the TCP listener (AUTH-002).
 *
 * <p>When present on {@link ServerConfig#getTls()}, the Netty server prepends an
 * {@code SslHandler} at the pipeline HEAD so the challenge-response handshake
 * runs inside an encrypted channel. When absent, the listener runs in plaintext
 * — which {@code InsecureModeGate} blocks unless
 * {@link ServerConfig#isInsecureAllowPlaintext()} is explicitly set.
 *
 * <p>Paths are filesystem locations (relative to the backend working
 * directory) of PEM/PKCS#8 material:
 * <ul>
 *   <li>{@code certChainFile} — server certificate chain (PEM)</li>
 *   <li>{@code privateKeyFile} — server private key (PEM, PKCS#8)</li>
 *   <li>{@code caCertFile} — optional trust CA for mutual TLS verification
 *       of client certs. When set, {@code mutualTls} is effectively enabled.</li>
 * </ul>
 */
public class TlsConfig {

    private String certChainFile;
    private String privateKeyFile;
    private String caCertFile;
    /** Require + verify a client certificate (mutual TLS). */
    private boolean mutualTls;

    public TlsConfig() {}

    public TlsConfig(String certChainFile, String privateKeyFile, String caCertFile, boolean mutualTls) {
        this.certChainFile = certChainFile;
        this.privateKeyFile = privateKeyFile;
        this.caCertFile = caCertFile;
        this.mutualTls = mutualTls;
    }

    public String getCertChainFile() {
        return certChainFile;
    }

    public void setCertChainFile(String certChainFile) {
        this.certChainFile = certChainFile;
    }

    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    public void setPrivateKeyFile(String privateKeyFile) {
        this.privateKeyFile = privateKeyFile;
    }

    public String getCaCertFile() {
        return caCertFile;
    }

    public void setCaCertFile(String caCertFile) {
        this.caCertFile = caCertFile;
    }

    public boolean isMutualTls() {
        return mutualTls;
    }

    public void setMutualTls(boolean mutualTls) {
        this.mutualTls = mutualTls;
    }

    /** @return {@code true} when the minimum server-identity material is present. */
    public boolean isConfigured() {
        return certChainFile != null && !certChainFile.isBlank()
                && privateKeyFile != null && !privateKeyFile.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TlsConfig tlsConfig = (TlsConfig) o;
        return mutualTls == tlsConfig.mutualTls &&
               Objects.equals(certChainFile, tlsConfig.certChainFile) &&
               Objects.equals(privateKeyFile, tlsConfig.privateKeyFile) &&
               Objects.equals(caCertFile, tlsConfig.caCertFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(certChainFile, privateKeyFile, caCertFile, mutualTls);
    }
}
