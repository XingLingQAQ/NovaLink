package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages client authentication using SHA-256 password hashing.
 * 
 * Requirements:
 * - 1.1: SHA-256 hash authentication
 * - 1.2: Successful authentication when credentials match
 * - 1.3: NC-401 error when credentials don't match
 */
public class AuthManager {

    private static final Logger logger = LoggerFactory.getLogger(AuthManager.class);
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    // Client credentials storage: username -> credentials
    private final Map<String, ClientCredentials> clientCredentials = new ConcurrentHashMap<>();

    // Web-panel credentials pool: username -> panel credentials.
    // Deliberately separate from clientCredentials so game-server accounts can
    // NEVER log into the web panel (credential pool separation).
    private final Map<String, PanelUserCredentials> panelCredentials = new ConcurrentHashMap<>();

    // IP ban manager for tracking failed attempts
    private final IpBanManager ipBanManager;

    public AuthManager() {
        this(new IpBanManager());
    }

    public AuthManager(IpBanManager ipBanManager) {
        this.ipBanManager = ipBanManager;
    }

    /**
     * Computes SHA-256 hash of the given password.
     *
     * @param password the password to hash
     * @return the hex-encoded SHA-256 hash
     */
    public static String hashPassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Converts a byte array to a hex string.
     *
     * @param bytes the byte array
     * @return the hex string representation
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Registers client credentials for authentication.
     *
     * @param credentials the client credentials to register
     */
    public void registerClient(ClientCredentials credentials) {
        if (credentials == null || credentials.getUsername() == null) {
            throw new IllegalArgumentException("Credentials and username cannot be null");
        }
        clientCredentials.put(credentials.getUsername(), credentials);
        logger.info("Registered client: {}", credentials.getUsername());
    }

    /**
     * Unregisters client credentials.
     *
     * @param username the username to unregister
     */
    public void unregisterClient(String username) {
        clientCredentials.remove(username);
        logger.info("Unregistered client: {}", username);
    }

    /**
     * Gets the credentials for a client.
     *
     * @param username the username
     * @return the credentials, or null if not found
     */
    public ClientCredentials getCredentials(String username) {
        return clientCredentials.get(username);
    }

    /**
     * Authenticates a client using username and password hash.
     * 
     * Requirements:
     * - 1.1: Uses SHA-256 hash for authentication
     * - 1.2: Returns success when credentials match
     * - 1.3: Returns NC-401 when credentials don't match
     * - 1.5: Checks IP ban status before authentication
     *
     * @param username     the client username
     * @param passwordHash the SHA-256 hash of the password
     * @param ipAddress    the IP address of the connecting client
     * @return the authentication result
     */
    public AuthResult authenticate(String username, String passwordHash, String ipAddress) {
        // Check if IP is banned
        if (ipBanManager.isBanned(ipAddress)) {
            long remainingSeconds = ipBanManager.getRemainingBanTime(ipAddress) / 1000;
            logger.warn("Authentication attempt from banned IP: {}", ipAddress);
            return AuthResult.ipBanned("IP temporarily banned. Try again in " + remainingSeconds + " seconds.");
        }

        // Validate input
        if (username == null || username.isEmpty()) {
            ipBanManager.recordFailure(ipAddress);
            return AuthResult.unauthorized("Username is required");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            ipBanManager.recordFailure(ipAddress);
            return AuthResult.unauthorized("Password hash is required");
        }

        // Look up credentials
        ClientCredentials credentials = clientCredentials.get(username);
        if (credentials == null) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Authentication failed for unknown user: {} from IP: {}", username, ipAddress);
            return AuthResult.unauthorized("Invalid credentials");
        }

        // Verify password hash using constant-time comparison to reduce timing leaks
        if (!constantTimeEqualsIgnoreCase(credentials.getPasswordHash(), passwordHash)) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Authentication failed for user: {} from IP: {} (password mismatch)", username, ipAddress);
            return AuthResult.unauthorized("Invalid credentials");
        }

        // Authentication successful - clear any failure records
        ipBanManager.clearFailures(ipAddress);
        logger.info("Authentication successful for user: {} from IP: {}", username, ipAddress);
        return AuthResult.success(credentials);
    }

    /**
     * Authenticates a client via the AUTH-002 challenge-response handshake.
     *
     * <p>The caller (server handshake handler) is responsible for:
     * <ol>
     *   <li>generating a fresh server nonce on receipt of {@code HandshakeInit},</li>
     *   <li>storing it in {@link NonceCache} keyed by
     *       {@code (clientId, clientNonce)}, and</li>
     *   <li>passing the {@code clientNonce} that the {@code HandshakeAuthenticate}
     *       packet carries so this method can look up (and atomically consume)
     *       the pending challenge.</li>
     * </ol>
     *
     * <p>HMAC verification:
     * <ul>
     *   <li>key = UTF-8 bytes of {@code sha256hex(password)}
     *       ({@link ClientCredentials#getPasswordHash()})</li>
     *   <li>message = UTF-8 bytes of {@code serverNonce + clientNonce}
     *       (string concatenation) — the serverNonce is the one this server
     *       generated and stored at init time</li>
     *   <li>expected = lowercase-hex HMAC-SHA-256</li>
     * </ul>
     * compared in constant time via {@link #constantTimeEqualsIgnoreCase}.
     *
     * <p>The pending challenge is consumed from the {@link NonceCache} exactly
     * once, so a replayed authenticate packet (same nonce pair) finds no entry
     * and fails with {@code NC-401}. The clientNonce echoed by the client must
     * match the one from the init packet or the lookup misses.
     *
     * @param username     the client id (from the authenticate packet)
     * @param clientNonce  the client nonce (from the authenticate packet; must
     *                     match the init packet's nonce)
     * @param hmac         the HMAC the client sent in the authenticate packet
     * @param nonceCache   the pending-challenge cache (consumed atomically)
     * @param ipAddress    the IP address of the connecting client
     * @return the authentication result
     */
    public AuthResult authenticateChallenge(String username,
                                            String clientNonce,
                                            String hmac,
                                            NonceCache nonceCache,
                                            String ipAddress) {
        // Check if IP is banned
        if (ipBanManager.isBanned(ipAddress)) {
            long remainingSeconds = ipBanManager.getRemainingBanTime(ipAddress) / 1000;
            logger.warn("Challenge authentication attempt from banned IP: {}", ipAddress);
            return AuthResult.ipBanned("IP temporarily banned. Try again in " + remainingSeconds + " seconds.");
        }

        // Validate input
        if (username == null || username.isEmpty()) {
            ipBanManager.recordFailure(ipAddress);
            return AuthResult.unauthorized("Username is required");
        }
        if (clientNonce == null || clientNonce.isEmpty() || hmac == null || hmac.isEmpty()) {
            ipBanManager.recordFailure(ipAddress);
            return AuthResult.unauthorized("Invalid challenge response");
        }

        // Atomically consume the pending challenge. A replay (same nonce pair
        // reused) or an expired/missing entry finds nothing here and is rejected.
        String serverNonce = nonceCache.consume(username, clientNonce);
        if (serverNonce == null) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Challenge authentication failed for user: {} from IP: {} (no/expired/replayed nonce)",
                    username, ipAddress);
            return AuthResult.unauthorized("Invalid credentials");
        }

        // Look up credentials
        ClientCredentials credentials = clientCredentials.get(username);
        if (credentials == null) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Challenge authentication failed for unknown user: {} from IP: {}", username, ipAddress);
            return AuthResult.unauthorized("Invalid credentials");
        }

        // Recompute the expected HMAC over (serverNonce + clientNonce) keyed by
        // the stored password hash, and compare in constant time.
        String expectedHmac = computeChallengeHmac(credentials.getPasswordHash(), serverNonce, clientNonce);
        if (expectedHmac == null || !constantTimeEqualsIgnoreCase(expectedHmac, hmac)) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Challenge authentication failed for user: {} from IP: {} (HMAC mismatch)",
                    username, ipAddress);
            return AuthResult.unauthorized("Invalid credentials");
        }

        // Authentication successful - clear any failure records
        ipBanManager.clearFailures(ipAddress);
        logger.info("Challenge authentication successful for user: {} from IP: {}", username, ipAddress);
        return AuthResult.success(credentials);
    }

    /**
     * Computes the AUTH-002 challenge-response HMAC.
     *
     * <p>{@code key = utf8(sha256hex(password))},
     * {@code message = utf8(serverNonce + clientNonce)},
     * output is lowercase-hex HMAC-SHA-256.
     *
     * @param passwordHash the stored credential hash (sha256hex of the password)
     * @param serverNonce  the server nonce (hex)
     * @param clientNonce  the client nonce (hex)
     * @return the lowercase-hex HMAC, or {@code null} if the algorithm is missing
     */
    public static String computeChallengeHmac(String passwordHash,
                                              String serverNonce,
                                              String clientNonce) {
        if (passwordHash == null || serverNonce == null || clientNonce == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(passwordHash.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hmacBytes = mac.doFinal((serverNonce + clientNonce).getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            // HmacSHA256 is guaranteed by the JCA; a bad key only happens if
            // the stored hash is somehow the wrong length, which is a config bug.
            logger.error("Failed to compute challenge HMAC", e);
            return null;
        }
    }

    /**
     * Constant-time case-insensitive equality for hex password hashes.
     * Avoids short-circuiting on the first mismatched character.
     */
    static boolean constantTimeEqualsIgnoreCase(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.length() != actual.length()) {
            // Still walk the longer string to keep runtime closer across lengths
            String longer = expected.length() > actual.length() ? expected : actual;
            int result = expected.length() ^ actual.length();
            for (int i = 0; i < longer.length(); i++) {
                char a = i < expected.length() ? Character.toLowerCase(expected.charAt(i)) : 0;
                char b = i < actual.length() ? Character.toLowerCase(actual.charAt(i)) : 0;
                result |= a ^ b;
            }
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length(); i++) {
            result |= Character.toLowerCase(expected.charAt(i)) ^ Character.toLowerCase(actual.charAt(i));
        }
        return result == 0;
    }

    /**
     * Authenticates a client using username and plain password.
     * The password will be hashed before comparison.
     *
     * @param username  the client username
     * @param password  the plain text password
     * @param ipAddress the IP address of the connecting client
     * @return the authentication result
     */
    public AuthResult authenticateWithPlainPassword(String username, String password, String ipAddress) {
        String passwordHash = hashPassword(password);
        return authenticate(username, passwordHash, ipAddress);
    }

    /**
     * Gets the IP ban manager.
     *
     * @return the IP ban manager
     */
    public IpBanManager getIpBanManager() {
        return ipBanManager;
    }

    /**
     * Clears all registered clients.
     */
    public void clearClients() {
        clientCredentials.clear();
    }

    /**
     * Gets the number of registered clients.
     *
     * @return the client count
     */
    public int getClientCount() {
        return clientCredentials.size();
    }

    /**
     * Authenticates a client using username and password (plain text).
     * This overload is for web panel authentication where IP tracking is not needed.
     *
     * @param username the client username
     * @param password the plain text password
     * @return the authentication result
     */
    public AuthResult authenticate(String username, String password) {
        return authenticateWithPlainPassword(username, password, "web-panel");
    }

    /**
     * Checks if a username belongs to a super admin.
     *
     * @param username the username to check
     * @return true if the user is a super admin
     */
    public boolean isSuperAdmin(String username) {
        ClientCredentials credentials = clientCredentials.get(username);
        return credentials != null && credentials.isSuperAdmin();
    }

    /**
     * Registers a super admin. The account is added to the web-panel pool with
     * role SUPER_ADMIN (and kept in the legacy client table for backward
     * compatibility with existing callers of {@link #isSuperAdmin}/{@link #authenticate}).
     *
     * @param username     the super admin username
     * @param passwordHash the password hash
     */
    public void registerSuperAdmin(String username, String passwordHash) {
        ClientCredentials credentials = new ClientCredentials(username, passwordHash);
        credentials.setSuperAdmin(true);
        clientCredentials.put(username, credentials);
        panelCredentials.put(username, new PanelUserCredentials(username, passwordHash, PanelRole.SUPER_ADMIN));
        logger.info("Registered super admin: {}", username);
    }

    /**
     * Registers a web-panel login account (role ADMIN or VIEWER, from the
     * {@code panel-users} config section).
     *
     * @param credentials the panel user credentials
     */
    public void registerPanelUser(PanelUserCredentials credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("Panel credentials cannot be null");
        }
        panelCredentials.put(credentials.getUsername(), credentials);
        logger.info("Registered panel user: {} ({})", credentials.getUsername(), credentials.getRole());
    }

    /**
     * Gets the panel credentials for a username, or null when the account is
     * not a panel account.
     */
    public PanelUserCredentials getPanelUser(String username) {
        return username != null ? panelCredentials.get(username) : null;
    }

    /**
     * @return the number of registered web-panel accounts
     */
    public int getPanelUserCount() {
        return panelCredentials.size();
    }

    /**
     * Authenticates a web-panel login. ONLY consults the panel credentials
     * pool ({@code super-admins} + {@code panel-users}); game-server client
     * credentials are rejected. Failed attempts count toward the IP ban.
     *
     * @param username  the panel username
     * @param password  the plain-text password
     * @param ipAddress the remote IP (for failure tracking / banning)
     * @return the authentication result carrying the panel role on success
     */
    public PanelAuthResult authenticatePanelUser(String username, String password, String ipAddress) {
        if (ipBanManager.isBanned(ipAddress)) {
            logger.warn("Panel login attempt from banned IP: {}", ipAddress);
            return PanelAuthResult.ipBanned();
        }
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            ipBanManager.recordFailure(ipAddress);
            return PanelAuthResult.unauthorized();
        }

        PanelUserCredentials credentials = panelCredentials.get(username);
        if (credentials == null) {
            // Not a panel account (unknown user OR a game-server client account).
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Panel login rejected for non-panel account: {} from IP: {}", username, ipAddress);
            return PanelAuthResult.unauthorized();
        }

        String passwordHash = hashPassword(password);
        if (!constantTimeEqualsIgnoreCase(credentials.getPasswordHash(), passwordHash)) {
            ipBanManager.recordFailure(ipAddress);
            logger.warn("Panel login failed for user: {} from IP: {} (password mismatch)", username, ipAddress);
            return PanelAuthResult.unauthorized();
        }

        ipBanManager.clearFailures(ipAddress);
        logger.info("Panel login successful for user: {} ({}) from IP: {}",
                username, credentials.getRole(), ipAddress);
        return PanelAuthResult.success(credentials);
    }
}
