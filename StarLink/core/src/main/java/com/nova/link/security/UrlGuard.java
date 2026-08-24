package com.nova.link.security;

import com.google.common.net.InetAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guards outbound "sink" URLs (webhook targets, callbacks) against
 * Server-Side Request Forgery by rejecting schemes other than http/https
 * and hosts that resolve to — or are literally written as — internal,
 * private, link-local, cloud-metadata, multicast, or otherwise
 * non-publicly-routable addresses.
 *
 * <p>This performs DNS resolution and prefix matching only; it never opens a
 * connection. A TOCTOU DNS-rebinding window remains between validation and
 * the caller's own connect, because {@link java.net.http.HttpClient} resolves
 * the host again when the request is sent. Full pinning of the validated
 * address would require a custom HttpClient address-resolver; that is tracked
 * as a residual hardening item, not a regression of this guard.
 *
 * <p>Logs use only the matched segment label and the host's IP literal — the
 * full URL is never logged, since webhook URLs frequently carry secrets in
 * the path or query.
 */
public final class UrlGuard {

    private static final Logger logger = LoggerFactory.getLogger(UrlGuard.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Test-only bypass: when set, loopback and link-local addresses are not
     * rejected. This exists solely so {@code WebhookManagerTest} and related
     * focused tests can drive an actual HTTP delivery against an in-process
     * 127.0.0.1 server without disabling the guard wholesale. It is never
     * enabled in production wiring.
     *
     * <p>The bypass also covers the RFC 6598 CGNAT range (100.64.0.0/10) and
     * the RFC 2544 benchmark range (198.18.0.0/15): some CI environments
     * resolve well-known hostnames such as {@code example.com} into these
     * non-public ranges, and tests that assert webhook creation should not be
     * blocked by an environment-specific DNS artifact. Production wiring never
     * sets this flag.
     */
    private static final AtomicBoolean LOOPBACK_ALLOWED_FOR_TEST =
            new AtomicBoolean(false);

    private UrlGuard() {
    }

    public static void setLoopbackAllowedForTest(boolean allowed) {
        LOOPBACK_ALLOWED_FOR_TEST.set(allowed);
    }

    public static boolean isLoopbackAllowedForTest() {
        return LOOPBACK_ALLOWED_FOR_TEST.get();
    }

    /**
     * Validates an outbound sink URL.
     *
     * @param url the URL to validate
     * @return the validated URI (unchanged, for chaining)
     * @throws SecurityException when the scheme is not http/https, the host is
     *         missing, the host is a literal or resolved internal/private/
     *         link-local/multicast/anyLocal/cloud-metadata/CGNAT/benchmark
     *         address, or DNS resolution fails.
     */
    public static URI validateSink(String url) {
        if (url == null) {
            throw new SecurityException("URL must not be null");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid URL: " + e.getMessage(), e);
        }
        return validateSink(uri);
    }

    /**
     * Validates an outbound sink URI.
     *
     * @see #validateSink(String)
     */
    public static URI validateSink(URI uri) {
        if (uri == null) {
            throw new SecurityException("URI must not be null");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new SecurityException(
                    "Scheme '" + scheme + "' is not allowed; only http/https are permitted");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new SecurityException("URL host must not be empty");
        }
        String normalizedHost = stripBrackets(host);

        // Literal IP fast path: no DNS. Otherwise resolve and check every
        // A/AAAA record so a host with one public and one private record is
        // still rejected (basic DNS-rebinding mitigation).
        if (isLiteralIp(normalizedHost)) {
            InetAddress literal = InetAddresses.forString(normalizedHost);
            checkBlocked(literal);
            return uri;
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(normalizedHost);
        } catch (UnknownHostException e) {
            // Unresolvable hosts must not be handed to the HTTP client: a
            // later resolution race or a rebinding DNS server could still
            // route the request internally.
            throw new SecurityException("Unable to resolve host: " + normalizedHost);
        }
        for (InetAddress addr : resolved) {
            checkBlocked(addr);
        }
        return uri;
    }

    private static boolean isLiteralIp(String host) {
        try {
            InetAddresses.forString(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '['
                && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static void checkBlocked(InetAddress addr) {
        BlockedReason reason = classify(addr);
        if (reason == null) {
            return;
        }
        if (LOOPBACK_ALLOWED_FOR_TEST.get()
                && (reason == BlockedReason.LOOPBACK
                || reason == BlockedReason.LINK_LOCAL
                || reason == BlockedReason.ANY_LOCAL
                || reason == BlockedReason.CGNAT
                || reason == BlockedReason.BENCHMARK)) {
            return;
        }
        logger.warn("UrlGuard rejected sink host: matched {} ({})",
                reason.label, addr.getHostAddress());
        throw new SecurityException(
                "Host is blocked by SSRF guard: " + reason.label);
    }

    private static BlockedReason classify(InetAddress addr) {
        if (addr.isAnyLocalAddress()) {
            return BlockedReason.ANY_LOCAL;
        }
        if (addr.isLoopbackAddress()) {
            return BlockedReason.LOOPBACK;
        }
        if (addr.isLinkLocalAddress()) {
            // 169.254.169.254 (AWS/GCE/Azure IMDS) falls in 169.254/16 and is
            // rejected here; the label stays generic as the segment is the
            // actionable signal, not the specific metadata endpoint.
            return BlockedReason.LINK_LOCAL;
        }
        if (addr.isSiteLocalAddress()) {
            return BlockedReason.SITE_LOCAL;
        }
        if (addr.isMulticastAddress()) {
            return BlockedReason.MULTICAST;
        }
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            if (matchesPrefix(b, CGNAT_PREFIX, CGNAT_PREFIX_BITS)) {
                return BlockedReason.CGNAT;
            }
            if (matchesPrefix(b, BENCHMARK_PREFIX, BENCHMARK_PREFIX_BITS)) {
                return BlockedReason.BENCHMARK;
            }
        }
        return null;
    }

    // 100.64.0.0/10 — RFC 6598 carrier-grade NAT. isSiteLocalAddress() does
    // not cover it, but it is non-public and must not be SSRF-reachable.
    private static final byte[] CGNAT_PREFIX = { 100, 64 };
    private static final int CGNAT_PREFIX_BITS = 10;

    // 198.18.0.0/15 — RFC 2544 benchmark range. Also not site-local per the
    // JDK and also non-public.
    private static final byte[] BENCHMARK_PREFIX = { (byte) 198, 18 };
    private static final int BENCHMARK_PREFIX_BITS = 15;

    private static boolean matchesPrefix(byte[] addr, byte[] prefix, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (i >= addr.length || i >= prefix.length) {
                return false;
            }
            if (addr[i] != prefix[i]) {
                return false;
            }
        }
        if (remBits != 0) {
            int idx = fullBytes;
            if (idx >= addr.length || idx >= prefix.length) {
                return false;
            }
            int mask = 0xFF << (8 - remBits);
            if ((addr[idx] & mask) != (prefix[idx] & mask)) {
                return false;
            }
        }
        return true;
    }

    private enum BlockedReason {
        ANY_LOCAL("anyLocal"),
        LOOPBACK("loopback"),
        LINK_LOCAL("linkLocal"),
        SITE_LOCAL("siteLocal"),
        MULTICAST("multicast"),
        CGNAT("cgnat"),
        BENCHMARK("benchmark");

        private final String label;

        BlockedReason(String label) {
            this.label = label;
        }
    }
}
