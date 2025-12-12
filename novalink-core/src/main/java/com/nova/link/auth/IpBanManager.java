package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages IP-based temporary bans after consecutive authentication failures.
 * 
 * Requirements:
 * - 1.5: Temporary IP ban after 3 consecutive failures
 */
public class IpBanManager {

    private static final Logger logger = LoggerFactory.getLogger(IpBanManager.class);

    // Default configuration
    public static final int DEFAULT_MAX_FAILURES = 3;
    public static final long DEFAULT_BAN_DURATION_MS = 300_000; // 5 minutes

    private final int maxFailures;
    private final long banDurationMs;

    // Track consecutive failures per IP
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    
    // Track ban expiration times per IP
    private final Map<String, Long> banExpirations = new ConcurrentHashMap<>();

    public IpBanManager() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_BAN_DURATION_MS);
    }

    public IpBanManager(int maxFailures, long banDurationMs) {
        this.maxFailures = maxFailures;
        this.banDurationMs = banDurationMs;
    }

    /**
     * Records an authentication failure for an IP address.
     * If the failure count reaches the threshold, the IP is banned.
     *
     * @param ipAddress the IP address
     */
    public void recordFailure(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return;
        }

        int failures = failureCounts.compute(ipAddress, (ip, count) -> 
            count == null ? 1 : count + 1
        );

        logger.debug("Recorded failure for IP: {} (count: {})", ipAddress, failures);

        if (failures >= maxFailures) {
            banIp(ipAddress);
        }
    }

    /**
     * Bans an IP address for the configured duration.
     *
     * @param ipAddress the IP address to ban
     */
    private void banIp(String ipAddress) {
        long expirationTime = System.currentTimeMillis() + banDurationMs;
        banExpirations.put(ipAddress, expirationTime);
        failureCounts.remove(ipAddress); // Reset failure count
        logger.warn("IP banned: {} until {}", ipAddress, expirationTime);
    }

    /**
     * Checks if an IP address is currently banned.
     *
     * @param ipAddress the IP address to check
     * @return true if the IP is banned
     */
    public boolean isBanned(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return false;
        }

        Long expiration = banExpirations.get(ipAddress);
        if (expiration == null) {
            return false;
        }

        if (System.currentTimeMillis() >= expiration) {
            // Ban has expired
            banExpirations.remove(ipAddress);
            return false;
        }

        return true;
    }

    /**
     * Gets the remaining ban time for an IP address.
     *
     * @param ipAddress the IP address
     * @return the remaining ban time in milliseconds, or 0 if not banned
     */
    public long getRemainingBanTime(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return 0;
        }

        Long expiration = banExpirations.get(ipAddress);
        if (expiration == null) {
            return 0;
        }

        long remaining = expiration - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Clears failure records for an IP address (called on successful auth).
     *
     * @param ipAddress the IP address
     */
    public void clearFailures(String ipAddress) {
        if (ipAddress != null) {
            failureCounts.remove(ipAddress);
        }
    }

    /**
     * Manually unbans an IP address.
     *
     * @param ipAddress the IP address to unban
     */
    public void unban(String ipAddress) {
        if (ipAddress != null) {
            banExpirations.remove(ipAddress);
            failureCounts.remove(ipAddress);
            logger.info("IP unbanned: {}", ipAddress);
        }
    }

    /**
     * Gets the current failure count for an IP address.
     *
     * @param ipAddress the IP address
     * @return the failure count
     */
    public int getFailureCount(String ipAddress) {
        return failureCounts.getOrDefault(ipAddress, 0);
    }

    /**
     * Gets the maximum allowed failures before ban.
     *
     * @return the max failures
     */
    public int getMaxFailures() {
        return maxFailures;
    }

    /**
     * Gets the ban duration in milliseconds.
     *
     * @return the ban duration
     */
    public long getBanDurationMs() {
        return banDurationMs;
    }

    /**
     * Clears all bans and failure records.
     */
    public void clearAll() {
        banExpirations.clear();
        failureCounts.clear();
    }
}
