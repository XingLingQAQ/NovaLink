package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.Invitation;
import com.nova.link.database.MemoryProvider;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for invitation code validity.
 * 
 * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
 * 
 * For any invitation code, it should be valid for exactly 24 hours after generation,
 * and invalid after being used once.
 * 
 * **Validates: Requirements 8.2, 8.4**
 */
public class InvitationCodeValidityPropertyTest {

    /**
     * Property 11a: Generated invitation codes are 6 characters alphanumeric.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.2**
     */
    @Property(tries = 100)
    void generatedCodesAre6CharactersAlphanumeric() throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Generate multiple codes
        for (int i = 0; i < 10; i++) {
            String code = invitationManager.generateCode();
            
            // Code should be exactly 6 characters
            assertThat(code).hasSize(6);
            
            // Code should only contain alphanumeric characters (excluding ambiguous ones)
            assertThat(code).matches("[A-HJ-NP-Z2-9]{6}");
        }
        
        db.shutdown();
    }

    /**
     * Property 11b: Generated codes are unique.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.2**
     */
    @Property(tries = 100)
    void generatedCodesAreUnique() throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel
        ChannelConfig config = ChannelConfig.builder()
            .id("test-channel")
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        
        // Generate multiple invitations and verify uniqueness
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            Invitation invitation = invitationManager.createInvitation("test-channel", inviterId);
            assertThat(codes.add(invitation.getCode()))
                .as("Code %s should be unique", invitation.getCode())
                .isTrue();
        }
        
        db.shutdown();
    }


    /**
     * Property 11c: Invitation is valid immediately after creation.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.2**
     */
    @Property(tries = 100)
    void invitationIsValidImmediatelyAfterCreation(
            @ForAll @StringLength(min = 1, max = 20) @AlphaChars String channelId
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel
        ChannelConfig config = ChannelConfig.builder()
            .id(channelId)
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        
        // Create invitation
        Invitation invitation = invitationManager.createInvitation(channelId, inviterId);
        
        // Invitation should be valid immediately
        assertThat(invitation.isValid()).isTrue();
        assertThat(invitation.isUsed()).isFalse();
        assertThat(invitation.isExpired()).isFalse();
        
        // Validate through manager
        InvitationResult result = invitationManager.validateInvitation(invitation.getCode());
        assertThat(result.isSuccess()).isTrue();
        
        db.shutdown();
    }

    /**
     * Property 11d: Invitation becomes invalid after being used once.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 100)
    void invitationBecomesInvalidAfterUse(
            @ForAll @StringLength(min = 1, max = 20) @AlphaChars String channelId
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel (GLOBAL so no client isolation check)
        ChannelConfig config = ChannelConfig.builder()
            .id(channelId)
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        UUID accepterId = UUID.randomUUID();
        
        // Create invitation
        Invitation invitation = invitationManager.createInvitation(channelId, inviterId);
        String code = invitation.getCode();
        
        // Accept the invitation
        InvitationResult acceptResult = invitationManager.acceptInvitation(code, accepterId, null);
        assertThat(acceptResult.isSuccess()).isTrue();
        
        // Try to use the same invitation again
        UUID secondAccepter = UUID.randomUUID();
        InvitationResult secondResult = invitationManager.acceptInvitation(code, secondAccepter, null);
        
        // Should fail with NC-411 (already used)
        assertThat(secondResult.isSuccess()).isFalse();
        assertThat(secondResult.getErrorCode()).isEqualTo("NC-411");
        
        db.shutdown();
    }


    /**
     * Property 11e: Invitation expires after TTL.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.2**
     */
    @Property(tries = 100)
    void invitationExpiresAfterTTL(
            @ForAll @StringLength(min = 1, max = 20) @AlphaChars String channelId
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel
        ChannelConfig config = ChannelConfig.builder()
            .id(channelId)
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        
        // Create invitation with very short TTL (already expired)
        long expireTime = System.currentTimeMillis() - 1; // Already expired
        Invitation expiredInvitation = new Invitation(
            invitationManager.generateCode(),
            channelId,
            inviterId,
            expireTime
        );
        db.saveInvitation(expiredInvitation);
        
        // Validate should fail with NC-410 (expired)
        InvitationResult result = invitationManager.validateInvitation(expiredInvitation.getCode());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-410");
        
        db.shutdown();
    }

    /**
     * Property 11f: Default TTL is 24 hours.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.2**
     */
    @Property(tries = 100)
    void defaultTTLIs24Hours(
            @ForAll @StringLength(min = 1, max = 20) @AlphaChars String channelId
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel
        ChannelConfig config = ChannelConfig.builder()
            .id(channelId)
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        
        // Create invitation with default TTL
        long beforeCreate = System.currentTimeMillis();
        Invitation invitation = invitationManager.createInvitation(channelId, inviterId);
        long afterCreate = System.currentTimeMillis();
        
        // Verify TTL is approximately 24 hours
        long expectedMinExpire = beforeCreate + TimeUnit.HOURS.toMillis(24);
        long expectedMaxExpire = afterCreate + TimeUnit.HOURS.toMillis(24);
        
        assertThat(invitation.getExpireTime())
            .isGreaterThanOrEqualTo(expectedMinExpire)
            .isLessThanOrEqualTo(expectedMaxExpire);
        
        db.shutdown();
    }


    /**
     * Property 11g: Validation returns correct error for non-existent code.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 100)
    void validationReturnsNotFoundForNonExistentCode(
            @ForAll @StringLength(min = 6, max = 6) @CharRange(from = 'A', to = 'Z') String randomCode
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Validate non-existent code
        InvitationResult result = invitationManager.validateInvitation(randomCode);
        
        // Should fail with NC-404 (not found)
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-404");
        
        db.shutdown();
    }

    /**
     * Property 11h: Revoked invitation becomes invalid.
     * 
     * **Feature: starchat-starlink, Property 11: Invitation Code Validity**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 100)
    void revokedInvitationBecomesInvalid(
            @ForAll @StringLength(min = 1, max = 20) @AlphaChars String channelId
    ) throws DatabaseException {
        // Setup
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channelManager = new ChannelManager();
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        
        // Create a test channel
        ChannelConfig config = ChannelConfig.builder()
            .id(channelId)
            .displayName("Test Channel")
            .scope(ChannelScope.GLOBAL)
            .build();
        channelManager.createChannel(config);
        
        UUID inviterId = UUID.randomUUID();
        
        // Create invitation
        Invitation invitation = invitationManager.createInvitation(channelId, inviterId);
        String code = invitation.getCode();
        
        // Verify it's valid
        assertThat(invitationManager.validateInvitation(code).isSuccess()).isTrue();
        
        // Revoke the invitation
        boolean revoked = invitationManager.revokeInvitation(code, inviterId);
        assertThat(revoked).isTrue();
        
        // Validate should now fail with NC-404 (not found, since it's deleted)
        InvitationResult result = invitationManager.validateInvitation(code);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-404");
        
        db.shutdown();
    }
}
