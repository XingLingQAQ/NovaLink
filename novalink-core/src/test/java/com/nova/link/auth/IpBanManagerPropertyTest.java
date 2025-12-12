package com.nova.link.auth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for IpBanManager.
 * 
 * Tests correctness properties defined in the design document.
 */
public class IpBanManagerPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 3: IP Ban After Consecutive Failures**
     * 
     * For any IP address, after exactly 3 consecutive authentication failures,
     * the IP should be temporarily banned.
     * 
     * **Validates: Requirements 1.5**
     */
    @Property(tries = 100)
    void ipIsBannedAfterExactlyThreeConsecutiveFailures(
            @ForAll @StringLength(min = 7, max = 15) @CharRange(from = '0', to = '9') String ipSuffix
    ) {
        // Create a valid-looking IP address
        String ipAddress = "192.168.1." + (Math.abs(ipSuffix.hashCode()) % 255);
        
        // Use default max failures (3)
        IpBanManager banManager = new IpBanManager();
        
        // Initially not banned
        assertThat(banManager.isBanned(ipAddress)).isFalse();
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(0);
        
        // After 1 failure - not banned
        banManager.recordFailure(ipAddress);
        assertThat(banManager.isBanned(ipAddress)).isFalse();
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(1);
        
        // After 2 failures - not banned
        banManager.recordFailure(ipAddress);
        assertThat(banManager.isBanned(ipAddress)).isFalse();
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(2);
        
        // After 3 failures - BANNED
        banManager.recordFailure(ipAddress);
        assertThat(banManager.isBanned(ipAddress)).isTrue();
        // Failure count is reset after ban
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(0);
    }

    /**
     * Property 3 (continued): IP ban works with configurable max failures.
     * 
     * **Validates: Requirements 1.5**
     */
    @Property(tries = 50)
    void ipIsBannedAfterConfigurableMaxFailures(
            @ForAll @IntRange(min = 1, max = 10) int maxFailures,
            @ForAll @StringLength(min = 1, max = 10) String ipSuffix
    ) {
        String ipAddress = "10.0.0." + (Math.abs(ipSuffix.hashCode()) % 255);
        
        IpBanManager banManager = new IpBanManager(maxFailures, 60000);
        
        // Record failures up to maxFailures - 1
        for (int i = 0; i < maxFailures - 1; i++) {
            banManager.recordFailure(ipAddress);
            assertThat(banManager.isBanned(ipAddress))
                .as("Should not be banned after %d failures (max: %d)", i + 1, maxFailures)
                .isFalse();
        }
        
        // The maxFailures-th failure should trigger ban
        banManager.recordFailure(ipAddress);
        assertThat(banManager.isBanned(ipAddress))
            .as("Should be banned after %d failures", maxFailures)
            .isTrue();
    }

    /**
     * Property: Successful authentication clears failure count.
     */
    @Property(tries = 100)
    void successfulAuthClearsFailureCount(
            @ForAll @IntRange(min = 1, max = 2) int failureCount,
            @ForAll @StringLength(min = 1, max = 10) String ipSuffix
    ) {
        String ipAddress = "172.16.0." + (Math.abs(ipSuffix.hashCode()) % 255);
        
        IpBanManager banManager = new IpBanManager();
        
        // Record some failures (but not enough to ban)
        for (int i = 0; i < failureCount; i++) {
            banManager.recordFailure(ipAddress);
        }
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(failureCount);
        
        // Clear failures (simulating successful auth)
        banManager.clearFailures(ipAddress);
        
        // Failure count should be reset
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(0);
        assertThat(banManager.isBanned(ipAddress)).isFalse();
    }

    /**
     * Property: Different IPs have independent failure counts.
     */
    @Property(tries = 100)
    void differentIpsHaveIndependentFailureCounts(
            @ForAll @StringLength(min = 1, max = 5) String suffix1,
            @ForAll @StringLength(min = 1, max = 5) String suffix2
    ) {
        String ip1 = "192.168.1." + (Math.abs(suffix1.hashCode()) % 255);
        String ip2 = "192.168.2." + (Math.abs(suffix2.hashCode()) % 255);
        
        Assume.that(!ip1.equals(ip2));
        
        IpBanManager banManager = new IpBanManager();
        
        // Record failures for ip1 only
        banManager.recordFailure(ip1);
        banManager.recordFailure(ip1);
        
        // ip1 should have 2 failures, ip2 should have 0
        assertThat(banManager.getFailureCount(ip1)).isEqualTo(2);
        assertThat(banManager.getFailureCount(ip2)).isEqualTo(0);
        
        // Ban ip1
        banManager.recordFailure(ip1);
        
        // ip1 should be banned, ip2 should not
        assertThat(banManager.isBanned(ip1)).isTrue();
        assertThat(banManager.isBanned(ip2)).isFalse();
    }

    /**
     * Property: Manual unban removes ban status.
     */
    @Property(tries = 100)
    void manualUnbanRemovesBanStatus(
            @ForAll @StringLength(min = 1, max = 10) String ipSuffix
    ) {
        String ipAddress = "10.10.10." + (Math.abs(ipSuffix.hashCode()) % 255);
        
        IpBanManager banManager = new IpBanManager();
        
        // Ban the IP
        banManager.recordFailure(ipAddress);
        banManager.recordFailure(ipAddress);
        banManager.recordFailure(ipAddress);
        assertThat(banManager.isBanned(ipAddress)).isTrue();
        
        // Unban
        banManager.unban(ipAddress);
        
        // Should no longer be banned
        assertThat(banManager.isBanned(ipAddress)).isFalse();
        assertThat(banManager.getFailureCount(ipAddress)).isEqualTo(0);
    }

    /**
     * Property: Ban has positive remaining time immediately after ban.
     */
    @Property(tries = 50)
    void banHasPositiveRemainingTimeAfterBan(
            @ForAll @LongRange(min = 1000, max = 600000) long banDuration,
            @ForAll @StringLength(min = 1, max = 10) String ipSuffix
    ) {
        String ipAddress = "8.8.8." + (Math.abs(ipSuffix.hashCode()) % 255);
        
        IpBanManager banManager = new IpBanManager(3, banDuration);
        
        // Ban the IP
        banManager.recordFailure(ipAddress);
        banManager.recordFailure(ipAddress);
        banManager.recordFailure(ipAddress);
        
        // Remaining time should be positive and <= ban duration
        long remaining = banManager.getRemainingBanTime(ipAddress);
        assertThat(remaining).isGreaterThan(0);
        assertThat(remaining).isLessThanOrEqualTo(banDuration);
    }
}
