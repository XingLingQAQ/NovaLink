package com.nova.link.mute;

import com.nova.link.auth.AuthManager;
import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.MuteInfo;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for MuteManager mute duration enforcement.
 * 
 * **Feature: starchat-starlink, Property 15: Mute Duration Enforcement**
 * 
 * Tests that muted players cannot send messages until mute expires,
 * and can send immediately after expiration.
 * 
 * **Validates: Requirements 13.2, 13.6**
 */
public class MuteDurationEnforcementPropertyTest {

    private static final String TEST_CLIENT_ID = "test-client";
    private static final String TEST_CHANNEL_ID = "test-channel";

    private void createTestChannel(ChannelManager channelManager) {
        ChannelConfig config = ChannelConfig.builder()
                .id(TEST_CHANNEL_ID)
                .displayName("Test Channel")
                .scope(ChannelScope.SERVER)
                .clientId(TEST_CLIENT_ID)
                .build();
        channelManager.createChannel(config);
    }

    private MemoryProvider createDbProvider() {
        MemoryProvider dbProvider = new MemoryProvider();
        try {
            dbProvider.initialize();
        } catch (DatabaseException e) {
            throw new RuntimeException("Failed to initialize database provider", e);
        }
        return dbProvider;
    }

    /**
     * **Feature: starchat-starlink, Property 15: Mute Duration Enforcement**
     * 
     * For any muted player, they should be unable to send messages until the mute expires.
     * 
     * **Validates: Requirements 13.2**
     */
    @Property(tries = 100)
    void mutedPlayerCannotSendMessagesBeforeExpiration(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 1000, max = 3600000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        String passwordHash = AuthManager.hashPassword("test-password");
        SuperAdminCredentials credentials = new SuperAdminCredentials(operatorId, passwordHash);
        permManager.registerSuperAdmin(credentials);
        permManager.authenticateSuperAdmin(operatorId, passwordHash);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isTrue();
        assertThat(muteManager.isMuted(targetPlayerId, TEST_CHANNEL_ID)).isTrue();
        
        MuteInfo muteInfo = muteManager.getMuteInfo(targetPlayerId, TEST_CHANNEL_ID);
        assertThat(muteInfo).isNotNull();
        assertThat(muteInfo.isExpired()).isFalse();
    }


    /**
     * **Feature: starchat-starlink, Property 15: Mute Duration Enforcement**
     * 
     * For any muted player, they should be able to send messages immediately after expiration.
     * 
     * **Validates: Requirements 13.6**
     */
    @Property(tries = 100)
    void mutedPlayerCanSendMessagesAfterExpiration(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        String passwordHash = AuthManager.hashPassword("test-password");
        SuperAdminCredentials credentials = new SuperAdminCredentials(operatorId, passwordHash);
        permManager.registerSuperAdmin(credentials);
        permManager.authenticateSuperAdmin(operatorId, passwordHash);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                1, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isTrue();
        
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        assertThat(muteManager.isMuted(targetPlayerId, TEST_CHANNEL_ID)).isFalse();
    }

    /**
     * Property: Channel admin mute duration cannot exceed 1 hour.
     * **Validates: Requirements 13.3**
     */
    @Property(tries = 100)
    void channelAdminMuteDurationCannotExceedOneHour(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 3600001, max = 86400000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        permManager.grantChannelAdmin(TEST_CHANNEL_ID, operatorId);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("1 hour");
    }

    /**
     * Property: Client admin mute duration cannot exceed 24 hours.
     * **Validates: Requirements 13.4**
     */
    @Property(tries = 100)
    void clientAdminMuteDurationCannotExceedTwentyFourHours(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 86400001, max = 172800000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        permManager.registerClientAdmin(operatorId, TEST_CLIENT_ID);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("24 hours");
    }


    /**
     * Property: Super admin can mute with any duration (including permanent).
     * **Validates: Requirements 13.5**
     */
    @Property(tries = 100)
    void superAdminCanMuteWithAnyDuration(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 0, max = 604800000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        String passwordHash = AuthManager.hashPassword("test-password");
        SuperAdminCredentials credentials = new SuperAdminCredentials(operatorId, passwordHash);
        permManager.registerSuperAdmin(credentials);
        permManager.authenticateSuperAdmin(operatorId, passwordHash);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isTrue();
    }

    /**
     * Property: Channel admin can mute within 1 hour limit.
     * **Validates: Requirements 13.3**
     */
    @Property(tries = 100)
    void channelAdminCanMuteWithinOneHourLimit(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 1, max = 3600000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        permManager.grantChannelAdmin(TEST_CHANNEL_ID, operatorId);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isTrue();
    }

    /**
     * Property: Client admin can mute within 24 hour limit.
     * **Validates: Requirements 13.4**
     */
    @Property(tries = 100)
    void clientAdminCanMuteWithinTwentyFourHourLimit(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 1, max = 86400000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        permManager.registerClientAdmin(operatorId, TEST_CLIENT_ID);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isTrue();
    }


    /**
     * Property: Regular players cannot mute anyone.
     * **Validates: Requirements 13.3, 13.4, 13.5**
     */
    @Property(tries = 100)
    void regularPlayersCannotMute(
            @ForAll("playerUuids") UUID targetPlayerId,
            @ForAll("playerUuids") UUID operatorId,
            @ForAll @LongRange(min = 1, max = 3600000) long durationMs
    ) {
        MemoryProvider dbProvider = createDbProvider();
        PermissionManager permManager = new PermissionManager();
        ChannelManager channelManager = new ChannelManager();
        createTestChannel(channelManager);
        
        MuteManager muteManager = new MuteManager(dbProvider, permManager, channelManager);
        
        MuteResult result = muteManager.mutePlayer(
                operatorId, targetPlayerId, TEST_CHANNEL_ID, 
                durationMs, "Test mute", TEST_CLIENT_ID);
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }

    /**
     * Property: isValidDuration correctly validates durations for each permission level.
     */
    @Property(tries = 100)
    void isValidDurationCorrectlyValidates(
            @ForAll("permissionLevels") PermissionLevel level,
            @ForAll @LongRange(min = 0, max = 172800000) long durationMs
    ) {
        boolean isValid = MuteManager.isValidDuration(level, durationMs);
        long maxDuration = MuteManager.getMaxDuration(level);
        
        if (maxDuration == -1) {
            assertThat(isValid).isTrue();
        } else if (maxDuration == 0) {
            assertThat(isValid).isFalse();
        } else {
            assertThat(isValid).isEqualTo(durationMs == 0 || durationMs <= maxDuration);
        }
    }

    @Provide
    Arbitrary<UUID> playerUuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<PermissionLevel> permissionLevels() {
        return Arbitraries.of(PermissionLevel.values());
    }
}
