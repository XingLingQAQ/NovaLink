package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Client credentials storage: username -> credentials
    private final Map<String, ClientCredentials> clientCredentials = new ConcurrentHashMap<>();

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

        // Verify password hash
        if (!credentials.getPasswordHash().equalsIgnoreCase(passwordHash)) {
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
     * Registers a super admin.
     *
     * @param username     the super admin username
     * @param passwordHash the password hash
     */
    public void registerSuperAdmin(String username, String passwordHash) {
        ClientCredentials credentials = new ClientCredentials(username, passwordHash);
        credentials.setSuperAdmin(true);
        clientCredentials.put(username, credentials);
        logger.info("Registered super admin: {}", username);
    }
}
