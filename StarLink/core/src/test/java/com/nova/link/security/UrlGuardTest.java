package com.nova.link.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link UrlGuard}. Uses literal IP addresses throughout so
 * no DNS lookup is required and the tests are deterministic offline.
 */
@DisplayName("UrlGuard SSRF sink validation")
class UrlGuardTest {

    @BeforeEach
    void resetBypass() {
        // Tests in this class run in the same JVM; a loopback bypass set by
        // a WebhookPersistenceTest sibling could leak. Each UrlGuard test
        // starts from the production posture (loopback blocked).
        UrlGuard.setLoopbackAllowedForTest(false);
    }

    @AfterAll
    static void restoreBypass() {
        // Leave the static flag clean for any test class scheduled after this
        // one in the same JVM.
        UrlGuard.setLoopbackAllowedForTest(false);
    }

    private static void assertRejected(String url, String reasonFragment) {
        assertThatThrownBy(() -> UrlGuard.validateSink(url))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(reasonFragment);
    }

    private static void assertAccepted(String url) {
        URI uri = UrlGuard.validateSink(url);
        assertThat(uri).isNotNull();
    }

    // ---------- scheme allowlist ----------

    @Test
    @DisplayName("accepts http scheme")
    void acceptsHttp() {
        assertAccepted("http://8.8.8.8/hook");
    }

    @Test
    @DisplayName("accepts https scheme")
    void acceptsHttps() {
        assertAccepted("https://8.8.8.8/hook");
    }

    @Test
    @DisplayName("rejects file scheme")
    void rejectsFileScheme() {
        assertRejected("file:///etc/passwd", "not allowed");
    }

    @Test
    @DisplayName("rejects ftp scheme")
    void rejectsFtpScheme() {
        assertRejected("ftp://8.8.8.8/hook", "not allowed");
    }

    @Test
    @DisplayName("rejects gopher scheme")
    void rejectsGopherScheme() {
        assertRejected("gopher://8.8.8.8/hook", "not allowed");
    }

    @Test
    @DisplayName("scheme is case-insensitive")
    void schemeCaseInsensitive() {
        assertAccepted("HTTP://8.8.8.8/hook");
        assertAccepted("HTTPS://8.8.8.8/hook");
    }

    // ---------- host checks ----------

    @Test
    @DisplayName("rejects null host")
    void rejectsNullHost() {
        // Empty authority: URI parses, but getHost() is null — exercises the
        // host-presence check rather than the URI-syntax fast path.
        assertThatThrownBy(() -> UrlGuard.validateSink("http:///"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("host");
    }

    @Test
    void rejectsLoopback() {
        assertRejected("http://127.0.0.1/", "blocked");
    }

    @Test
    void rejectsLoopbackAny127() {
        assertRejected("http://127.255.255.254/", "blocked");
    }

    @Test
    void rejectsPrivate10() {
        assertRejected("http://10.0.0.1/", "blocked");
    }

    @Test
    void rejectsPrivate17216() {
        assertRejected("http://172.16.0.1/", "blocked");
    }

    @Test
    void rejectsPrivate192168() {
        assertRejected("http://192.168.1.1/", "blocked");
    }

    @Test
    void rejectsLinkLocal() {
        assertRejected("http://169.254.1.1/", "blocked");
    }

    @Test
    @DisplayName("rejects AWS/GCE/Azure cloud metadata endpoint (169.254.169.254)")
    void rejectsCloudMetadata() {
        assertRejected("http://169.254.169.254/latest/meta-data/", "blocked");
    }

    @Test
    void rejectsMulticast() {
        // 224.0.0.1 — all-hosts multicast
        assertRejected("http://224.0.0.1/", "blocked");
    }

    @Test
    void rejectsAnyLocal() {
        assertRejected("http://0.0.0.0/", "blocked");
    }

    @Test
    @DisplayName("rejects 100.64.0.0/10 (RFC 6598 CGNAT)")
    void rejectsCgnat10064() {
        assertRejected("http://100.64.0.1/", "blocked");
    }

    @Test
    @DisplayName("rejects 198.18.0.0/15 (RFC 2544 benchmark)")
    void rejectsBenchmark19818() {
        assertRejected("http://198.18.0.1/", "blocked");
    }

    @Test
    void rejectsIpv6Loopback() {
        assertRejected("http://[::1]/", "blocked");
    }

    @Test
    void rejectsIpv6LinkLocal() {
        assertRejected("http://[fe80::1]/", "blocked");
    }

    // ---------- accepted public literals ----------

    @Test
    void acceptsPublicIpv4() {
        assertAccepted("http://8.8.8.8/dns");
        assertAccepted("https://1.1.1.1/dns");
    }

    @Test
    void acceptsPublicIpv6() {
        // Cloudflare 2606:4700:4700::1111
        assertAccepted("https://[2606:4700:4700::1111]/");
    }

    @Test
    @DisplayName("accepts public IPv4 in all common port forms")
    void acceptsPublicWithPort() {
        assertAccepted("http://8.8.8.8:8080/hook");
        assertAccepted("https://1.1.1.1:443/hook");
    }

    // ---------- DNS resolution failure ----------

    @Test
    @DisplayName("rejects host that cannot be resolved")
    void rejectsUnresolvableHost() {
        // 254.0.0.1 is in 240.0.0.0/4 (reserved for future addressing,
        // RFC 1112) — guaranteed non-routable, but not in any of the
        // isSiteLocal/isLoopback/CGNAT/benchmark bands, so the only reason
        // validateSink can reject it is the literal-IP branch succeeding and
        // the address being unclassifiable… which would pass. To reliably
        // exercise UnknownHostException instead, use a syntactically-valid
        // but non-existent domain under a reserved TLD.
        //
        // Some test JVMs share a DNS resolver that maps arbitrary names into
        // a provider's "search assist" address (often 198.18.x.x benchmark
        // range), which would make getAllByName succeed with a blocked
        // literal and skip the UnknownHostException path. Therefore this test
        // accepts either outcome: UnknownHostException → "resolve", or a
        // blocked-resolved-address → "blocked". Both are SSRF-rejections.
        try {
            UrlGuard.validateSink("http://nova-link-ssrf.invalid/");
            // Should never happen: a reserved TLD resolves nowhere, and any
            // synthesized address it mapped to would itself be blocked.
            org.assertj.core.api.Assertions
                    .fail("Expected UrlGuard to reject an unresolvable/reserved host");
        } catch (SecurityException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            assertThat(msg).containsAnyOf("resolve", "blocked");
        }
    }
}