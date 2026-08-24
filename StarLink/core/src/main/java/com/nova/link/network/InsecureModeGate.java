package com.nova.link.network;

import com.nova.link.config.ServerConfig;

/**
 * AUTH-002 fail-closed gate.
 *
 * <p>The TCP listener must not start in plaintext unless the operator has
 * explicitly opted in via {@code server.insecure-allow-plaintext: true}.
 * The 3-packet challenge-response handshake protects the stored password
 * hash from replay regardless of transport, but only TLS hides the
 * nonce/HMAC exchange from a passive network observer. Failing closed here
 * forces a production deployment to either configure {@code server.tls:}
 * or consciously acknowledge the plaintext risk.
 *
 * <p>This is a pure check — it has no side effects and throws
 * {@link IllegalStateException} when the gate is violated so the caller's
 * startup sequence aborts before the listener binds.
 */
public final class InsecureModeGate {

    private InsecureModeGate() {}

    /**
     * Requires either a configured TLS block or an explicit insecure-plaintext
     * opt-in.
     *
     * @param server the server configuration section
     * @param label  a human-readable label for the listener (used in the
     *               error message, e.g. "TCP listener (port 8888)")
     * @throws IllegalStateException when TLS is not configured and the
     *         operator has not set {@code insecure-allow-plaintext: true}
     */
    public static void requireTlsOrExplicitInsecure(ServerConfig server, String label) {
        if (server == null) {
            throw new IllegalStateException(
                    "AUTH-002: cannot evaluate security gate — server config is null");
        }
        boolean tlsConfigured = server.getTls() != null && server.getTls().isConfigured();
        if (tlsConfigured) {
            return;
        }
        if (server.isInsecureAllowPlaintext()) {
            return;
        }
        throw new IllegalStateException(
                "AUTH-002: " + label + " would start in plaintext. The challenge-response "
                        + "handshake protects the stored password hash from replay, but "
                        + "plaintext transport exposes the nonce/HMAC exchange to a passive "
                        + "observer. Either configure server.tls (cert-chain-file + "
                        + "private-key-file) or explicitly set "
                        + "server.insecure-allow-plaintext: true to acknowledge the risk.");
    }
}
