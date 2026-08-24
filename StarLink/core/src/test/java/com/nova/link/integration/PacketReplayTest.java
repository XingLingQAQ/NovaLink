package com.nova.link.integration;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakeAuthenticatePacket;
import com.nova.chat.common.protocol.packets.HandshakeChallengePacket;
import com.nova.chat.common.protocol.packets.HandshakeInitPacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-002: end-to-end replay resistance of the challenge-response handshake.
 *
 * <p>A {@link HandshakeAuthenticatePacket} that was valid at capture time (it
 * authenticated Client A) must NOT authenticate Client B when the exact same
 * packet is replayed on a fresh connection. The {@link com.nova.link.auth.NonceCache}
 * consumes the {@code (clientId, clientNonce)} pair on the first use, so the
 * second attempt finds no pending challenge and the server returns {@code NC-401}.
 *
 * <p>The second case is stronger still: even when the attacker (Client B) sends
 * a fresh {@link HandshakeInitPacket} to obtain a new server nonce, the replayed
 * HMAC — computed over the OLD server nonce — does not match the fresh challenge,
 * so authentication still fails with {@code NC-401}.
 *
 * <p>The captured packet is driven by hand with a fixed client nonce so the
 * replayed bytes are deterministic and byte-for-byte identical to the original.
 */
@DisplayName("AUTH-002 packet replay resistance")
class PacketReplayTest {

    private static final int TEST_PORT = 18912;
    private static final String CLIENT_ID = "ReplayVictim";
    private static final String PASSWORD = "replay-secret";
    /**
     * Fixed client nonce so the captured authenticate packet is deterministic
     * and can be replayed byte-for-byte on the second connection.
     */
    private static final String CLIENT_NONCE = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    private IntegrationTestHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
        helper.registerClient(CLIENT_ID, PASSWORD);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @DisplayName("replayed HandshakeAuthenticatePacket on a fresh connection is rejected (NC-401)")
    void replayedAuthenticatePacketRejected() throws Exception {
        // === Client A: perform a real handshake, capturing the authenticate packet. ===
        IntegrationTestHelper.TestClient clientA = helper.createClient(PlatformType.BUKKIT);
        clientA.connect().get(5, TimeUnit.SECONDS);

        // Capture the server nonce when the challenge arrives. We do NOT send
        // the authenticate packet from this handler — we build it by hand below
        // so the exact packet object can be replayed on Client B.
        AtomicReference<String> serverNonceRef = new AtomicReference<>();
        CountDownLatch challengeLatch = new CountDownLatch(1);
        clientA.registerHandler(HandshakeChallengePacket.class, (HandshakeChallengePacket challenge) -> {
            serverNonceRef.set(challenge.getServerNonce());
            challengeLatch.countDown();
        });

        // Send HandshakeInit with the fixed clientNonce so the captured packet
        // is deterministic.
        HandshakeInitPacket init = new HandshakeInitPacket(
                NovaProtocol.PROTOCOL_VERSION,
                CLIENT_ID,
                PlatformType.BUKKIT,
                "",
                CLIENT_NONCE
        );
        clientA.sendPacket(init);

        assertThat(challengeLatch.await(3, TimeUnit.SECONDS))
                .as("Client A must receive a HandshakeChallenge")
                .isTrue();
        String serverNonce = serverNonceRef.get();
        assertThat(serverNonce).isNotBlank();

        // Build the authenticate packet by hand — this is the "captured" packet
        // an attacker would record off the wire.
        String passwordHash = IntegrationTestHelper.hashPassword(PASSWORD);
        String hmac = computeChallengeHmac(passwordHash, serverNonce, CLIENT_NONCE);
        HandshakeAuthenticatePacket capturedPacket = new HandshakeAuthenticatePacket(
                CLIENT_ID, CLIENT_NONCE, hmac);

        // Send it on Client A to prove it was valid at capture time.
        clientA.sendPacket(capturedPacket);
        HandshakeResponsePacket responseA = clientA.waitForPacket(
                HandshakeResponsePacket.class, 5, TimeUnit.SECONDS);
        assertThat(responseA.isSuccess())
                .as("the captured authenticate packet must authenticate Client A on first use")
                .isTrue();
        assertThat(clientA.isAuthenticated()).isTrue();

        // === Client B: replay the EXACT captured packet on a fresh connection. ===
        IntegrationTestHelper.TestClient clientB = helper.createClient(PlatformType.BUKKIT);
        clientB.connect().get(5, TimeUnit.SECONDS);

        // Replay the captured packet verbatim — same clientId, clientNonce, hmac.
        // No HandshakeInit is sent on this connection, and in any case the
        // (CLIENT_ID, CLIENT_NONCE) cache entry was consumed during Client A's
        // authentication, so the lookup misses -> NC-401.
        clientB.sendPacket(capturedPacket);

        HandshakeResponsePacket responseB = clientB.waitForPacket(
                HandshakeResponsePacket.class, 5, TimeUnit.SECONDS);
        assertThat(responseB.isSuccess())
                .as("a replayed authenticate packet must not authenticate a second time")
                .isFalse();
        assertThat(responseB.getErrorCode()).isEqualTo("NC-401");
        assertThat(clientB.isAuthenticated()).isFalse();

        clientA.disconnect();
        clientB.disconnect();
    }

    @Test
    @DisplayName("replayed HMAC does not validate against a fresh server nonce (NC-401)")
    void replayedHmacFailsAgainstFreshChallenge() throws Exception {
        // === Client A: capture a valid authenticate packet, then disconnect. ===
        IntegrationTestHelper.TestClient clientA = helper.createClient(PlatformType.BUKKIT);
        clientA.connect().get(5, TimeUnit.SECONDS);

        AtomicReference<String> serverNonceARef = new AtomicReference<>();
        CountDownLatch challengeALatch = new CountDownLatch(1);
        clientA.registerHandler(HandshakeChallengePacket.class, (HandshakeChallengePacket challenge) -> {
            serverNonceARef.set(challenge.getServerNonce());
            challengeALatch.countDown();
        });
        clientA.sendPacket(new HandshakeInitPacket(
                NovaProtocol.PROTOCOL_VERSION, CLIENT_ID, PlatformType.BUKKIT, "", CLIENT_NONCE));
        assertThat(challengeALatch.await(3, TimeUnit.SECONDS)).isTrue();

        String hmacA = computeChallengeHmac(
                IntegrationTestHelper.hashPassword(PASSWORD), serverNonceARef.get(), CLIENT_NONCE);
        HandshakeAuthenticatePacket capturedPacket = new HandshakeAuthenticatePacket(
                CLIENT_ID, CLIENT_NONCE, hmacA);
        clientA.sendPacket(capturedPacket);
        HandshakeResponsePacket responseA = clientA.waitForPacket(
                HandshakeResponsePacket.class, 5, TimeUnit.SECONDS);
        assertThat(responseA.isSuccess())
                .as("captured packet must authenticate Client A first")
                .isTrue();
        clientA.disconnect();

        // === Client B: send a FRESH HandshakeInit (new server nonce), then
        //     replay the OLD captured authenticate packet (HMAC over serverNonceA). ===
        IntegrationTestHelper.TestClient clientB = helper.createClient(PlatformType.BUKKIT);
        clientB.connect().get(5, TimeUnit.SECONDS);

        AtomicReference<String> serverNonceBRef = new AtomicReference<>();
        CountDownLatch challengeBLatch = new CountDownLatch(1);
        clientB.registerHandler(HandshakeChallengePacket.class, (HandshakeChallengePacket challenge) -> {
            serverNonceBRef.set(challenge.getServerNonce());
            challengeBLatch.countDown();
        });
        clientB.sendPacket(new HandshakeInitPacket(
                NovaProtocol.PROTOCOL_VERSION, CLIENT_ID, PlatformType.BUKKIT, "", CLIENT_NONCE));
        assertThat(challengeBLatch.await(3, TimeUnit.SECONDS)).isTrue();
        // The server must have issued a DIFFERENT server nonce — otherwise the
        // replay would trivially validate and the test would be meaningless.
        assertThat(serverNonceBRef.get())
                .as("server must issue a fresh server nonce for the new challenge")
                .isNotEqualTo(serverNonceARef.get());

        // Replay the captured packet (HMAC keyed by the old server nonce). The
        // server recomputes the expected HMAC over the fresh server nonce and
        // the comparison fails -> NC-401.
        clientB.sendPacket(capturedPacket);

        HandshakeResponsePacket responseB = clientB.waitForPacket(
                HandshakeResponsePacket.class, 5, TimeUnit.SECONDS);
        assertThat(responseB.isSuccess())
                .as("a replayed HMAC for an old server nonce must not validate against a fresh challenge")
                .isFalse();
        assertThat(responseB.getErrorCode()).isEqualTo("NC-401");
        assertThat(clientB.isAuthenticated()).isFalse();

        clientB.disconnect();
    }

    /**
     * Mirrors {@code AuthManager.computeChallengeHmac} /
     * {@code IntegrationTestHelper.computeChallengeHmac}: key = UTF-8 bytes of
     * the password hash, message = UTF-8 bytes of {@code serverNonce + clientNonce},
     * output = lowercase-hex HMAC-SHA-256.
     */
    private static String computeChallengeHmac(String passwordHash, String serverNonce, String clientNonce) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    passwordHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(
                    (serverNonce + clientNonce).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException("Failed to compute challenge HMAC", e);
        }
    }
}
