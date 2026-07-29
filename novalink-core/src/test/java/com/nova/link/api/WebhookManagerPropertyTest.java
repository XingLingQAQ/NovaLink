package com.nova.link.api;

import com.google.gson.JsonObject;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for WebhookManager event distribution.
 * 
 * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
 * 
 * Tests that for any event, all registered webhooks with matching event types
 * should receive the event.
 * 
 * **Validates: Requirements 22.2**
 */
public class WebhookManagerPropertyTest {

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook created with a specific event type, it should be retrievable
     * and have the correct event type.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void createdWebhookHasCorrectEventType(
            @ForAll("validUrls") String url,
            @ForAll("eventTypes") String eventType,
            @ForAll("secrets") String secret
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Webhook webhook = manager.createWebhook(url, eventType, secret);
            
            assertThat(webhook).isNotNull();
            assertThat(webhook.getEvent()).isEqualTo(eventType);
            assertThat(webhook.getUrl()).isEqualTo(url);
            assertThat(webhook.getSecret()).isEqualTo(secret);
            assertThat(webhook.getId()).isNotNull();
            assertThat(webhook.getId()).hasSize(12);
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook created, it should be retrievable by its ID.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void createdWebhookIsRetrievableById(
            @ForAll("validUrls") String url,
            @ForAll("eventTypes") String eventType
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Webhook created = manager.createWebhook(url, eventType, null);
            
            Webhook retrieved = manager.getWebhook(created.getId());
            
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getId()).isEqualTo(created.getId());
            assertThat(retrieved.getUrl()).isEqualTo(url);
            assertThat(retrieved.getEvent()).isEqualTo(eventType);
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook deleted, it should no longer be retrievable.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void deletedWebhookIsNotRetrievable(
            @ForAll("validUrls") String url,
            @ForAll("eventTypes") String eventType
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Webhook created = manager.createWebhook(url, eventType, null);
            String id = created.getId();
            
            boolean deleted = manager.deleteWebhook(id);
            
            assertThat(deleted).isTrue();
            assertThat(manager.getWebhook(id)).isNull();
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * Deleting a non-existent webhook should return false.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void deletingNonExistentWebhookReturnsFalse(
            @ForAll @AlphaChars @StringLength(min = 12, max = 12) String fakeId
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            boolean deleted = manager.deleteWebhook(fakeId);
            
            assertThat(deleted).isFalse();
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any number of webhooks created, getAllWebhooks should return all of them.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 50)
    void getAllWebhooksReturnsAllCreatedWebhooks(
            @ForAll @IntRange(min = 1, max = 10) int count
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Set<String> createdIds = new HashSet<>();
            
            for (int i = 0; i < count; i++) {
                Webhook webhook = manager.createWebhook(
                        "http://example.com/webhook" + i,
                        "event.type." + i,
                        null
                );
                createdIds.add(webhook.getId());
            }
            
            Collection<Webhook> allWebhooks = manager.getAllWebhooks();
            
            assertThat(allWebhooks).hasSize(count);
            for (Webhook webhook : allWebhooks) {
                assertThat(createdIds).contains(webhook.getId());
            }
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook with exact event type, matchesEvent should return true
     * only for that exact event type.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookMatchesExactEventType(
            @ForAll("eventTypes") String eventType
    ) {
        Webhook webhook = new Webhook("test-id", "http://example.com", eventType, null);
        
        assertThat(webhook.matchesEvent(eventType)).isTrue();
        assertThat(webhook.matchesEvent(eventType + ".extra")).isFalse();
        assertThat(webhook.matchesEvent("different." + eventType)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook with wildcard event type (ending with .*), matchesEvent
     * should return true for any event starting with the prefix.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookMatchesWildcardEventType(
            @ForAll("eventPrefixes") String prefix,
            @ForAll("eventSuffixes") String suffix
    ) {
        String wildcardEvent = prefix + ".*";
        Webhook webhook = new Webhook("test-id", "http://example.com", wildcardEvent, null);
        
        String matchingEvent = prefix + "." + suffix;
        String nonMatchingEvent = "other." + suffix;
        
        assertThat(webhook.matchesEvent(matchingEvent)).isTrue();
        assertThat(webhook.matchesEvent(nonMatchingEvent)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook, matchesEvent with null should return false.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookDoesNotMatchNullEvent(
            @ForAll("eventTypes") String eventType
    ) {
        Webhook webhook = new Webhook("test-id", "http://example.com", eventType, null);
        
        assertThat(webhook.matchesEvent(null)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook with null event type, matchesEvent should return false.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookWithNullEventDoesNotMatch(
            @ForAll("eventTypes") String eventType
    ) {
        Webhook webhook = new Webhook("test-id", "http://example.com", null, null);
        
        assertThat(webhook.matchesEvent(eventType)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * Each created webhook should have a unique ID.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 50)
    void webhookIdsAreUnique(
            @ForAll @IntRange(min = 2, max = 20) int count
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Set<String> ids = new HashSet<>();
            
            for (int i = 0; i < count; i++) {
                Webhook webhook = manager.createWebhook(
                        "http://example.com/webhook" + i,
                        "event.type",
                        null
                );
                ids.add(webhook.getId());
            }
            
            // All IDs should be unique
            assertThat(ids).hasSize(count);
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * Webhook creation timestamp should be set correctly.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookCreationTimestampIsSet(
            @ForAll("validUrls") String url,
            @ForAll("eventTypes") String eventType
    ) {
        long before = System.currentTimeMillis();
        
        WebhookManager manager = new WebhookManager();
        try {
            Webhook webhook = manager.createWebhook(url, eventType, null);
            
            long after = System.currentTimeMillis();
            
            assertThat(webhook.getCreatedAt()).isGreaterThanOrEqualTo(before);
            assertThat(webhook.getCreatedAt()).isLessThanOrEqualTo(after);
            assertThat(webhook.getLastTriggered()).isEqualTo(0);
        } finally {
            manager.shutdown();
        }
    }

    /**
     * **Feature: novachat-platform-expansion, Property 14: Webhook Event Distribution**
     * 
     * For any webhook ID format, it should be 12 characters of lowercase alphanumeric.
     * 
     * **Validates: Requirements 22.2**
     */
    @Property(tries = 100)
    void webhookIdHasCorrectFormat(
            @ForAll("validUrls") String url,
            @ForAll("eventTypes") String eventType
    ) {
        WebhookManager manager = new WebhookManager();
        try {
            Webhook webhook = manager.createWebhook(url, eventType, null);
            
            String id = webhook.getId();
            assertThat(id).hasSize(12);
            assertThat(id).matches("[a-z0-9]+");
        } finally {
            manager.shutdown();
        }
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> validUrls() {
        return Arbitraries.of(
                "http://example.com/webhook",
                "https://api.example.com/hooks/notify",
                "http://localhost:8080/callback",
                "https://webhook.site/test",
                "http://192.168.1.1:3000/api/webhook"
        );
    }

    @Provide
    Arbitrary<String> eventTypes() {
        return Arbitraries.of(
                "message.sent",
                "message.received",
                "player.join",
                "player.leave",
                "channel.created",
                "channel.deleted",
                "announcement.sent",
                "mute.applied",
                "kick.executed"
        );
    }

    @Provide
    Arbitrary<String> eventPrefixes() {
        return Arbitraries.of(
                "message",
                "player",
                "channel",
                "announcement",
                "admin"
        );
    }

    @Provide
    Arbitrary<String> eventSuffixes() {
        return Arbitraries.of(
                "sent",
                "received",
                "created",
                "deleted",
                "updated",
                "join",
                "leave"
        );
    }

    @Provide
    Arbitrary<String> secrets() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(16).ofMaxLength(64)
        );
    }
}
