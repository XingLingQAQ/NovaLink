package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages webhooks for external notifications.
 * Handles webhook registration, triggering, and delivery.
 * 
 * Requirements: 25.5 - Webhook support
 */
public class WebhookManager {

    private static final Logger logger = LoggerFactory.getLogger(WebhookManager.class);
    
    /** Characters used for generating webhook IDs */
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    
    /** Webhook ID length */
    private static final int ID_LENGTH = 12;
    
    /** HTTP client timeout */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    
    /** Maximum retry attempts */
    private static final int MAX_RETRIES = 3;
    
    /** Retry delay in milliseconds */
    private static final long RETRY_DELAY_MS = 1000;

    private final Map<String, Webhook> webhooks;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Gson gson;
    private final SecureRandom random;

    public WebhookManager() {
        this.webhooks = new ConcurrentHashMap<>();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Webhook-Executor");
            t.setDaemon(true);
            return t;
        });
        this.gson = new Gson();
        this.random = new SecureRandom();
    }

    /**
     * Creates a new webhook.
     *
     * @param url the webhook URL
     * @param event the event type to listen for
     * @param secret the optional secret for signing
     * @return the created webhook
     */
    public Webhook createWebhook(String url, String event, String secret) {
        String id = generateId();
        Webhook webhook = new Webhook(id, url, event, secret);
        webhooks.put(id, webhook);
        logger.info("Created webhook {} for event '{}' -> {}", id, event, url);
        return webhook;
    }

    /**
     * Deletes a webhook by ID.
     *
     * @param id the webhook ID
     * @return true if the webhook was deleted
     */
    public boolean deleteWebhook(String id) {
        Webhook removed = webhooks.remove(id);
        if (removed != null) {
            logger.info("Deleted webhook {}", id);
            return true;
        }
        return false;
    }

    /**
     * Gets a webhook by ID.
     *
     * @param id the webhook ID
     * @return the webhook, or null if not found
     */
    public Webhook getWebhook(String id) {
        return webhooks.get(id);
    }

    /**
     * Gets all webhooks.
     *
     * @return collection of all webhooks
     */
    public Collection<Webhook> getAllWebhooks() {
        return Collections.unmodifiableCollection(webhooks.values());
    }

    /**
     * Triggers webhooks for a specific event.
     * This method is non-blocking and executes webhook calls asynchronously.
     *
     * @param eventType the event type
     * @param payload the event payload
     */
    public void triggerWebhook(String eventType, JsonObject payload) {
        for (Webhook webhook : webhooks.values()) {
            if (webhook.matchesEvent(eventType)) {
                executor.submit(() -> deliverWebhook(webhook, eventType, payload));
            }
        }
    }

    /**
     * Delivers a webhook payload to the target URL.
     *
     * @param webhook the webhook to deliver
     * @param eventType the event type
     * @param payload the payload
     */
    private void deliverWebhook(Webhook webhook, String eventType, JsonObject payload) {
        // Build the full payload
        JsonObject fullPayload = new JsonObject();
        fullPayload.addProperty("event", eventType);
        fullPayload.addProperty("webhookId", webhook.getId());
        fullPayload.addProperty("timestamp", System.currentTimeMillis());
        fullPayload.add("data", payload);
        
        String body = gson.toJson(fullPayload);
        
        // Build request
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(webhook.getUrl()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "NovaLink-Webhook/1.0")
                .header("X-NovaLink-Event", eventType)
                .header("X-NovaLink-Webhook-Id", webhook.getId())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        
        // Add signature if secret is configured
        if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
            String signature = computeSignature(body, webhook.getSecret());
            requestBuilder.header("X-NovaLink-Signature", signature);
        }
        
        HttpRequest request = requestBuilder.build();
        
        // Attempt delivery with retries
        int attempt = 0;
        boolean success = false;
        
        while (attempt < MAX_RETRIES && !success) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    success = true;
                    webhook.setLastTriggered(System.currentTimeMillis());
                    logger.debug("Webhook {} delivered successfully (attempt {})", 
                            webhook.getId(), attempt);
                } else {
                    logger.warn("Webhook {} delivery failed with status {} (attempt {})", 
                            webhook.getId(), response.statusCode(), attempt);
                }
            } catch (Exception e) {
                logger.warn("Webhook {} delivery error (attempt {}): {}", 
                        webhook.getId(), attempt, e.getMessage());
            }
            
            if (!success && attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (!success) {
            logger.error("Webhook {} delivery failed after {} attempts", 
                    webhook.getId(), MAX_RETRIES);
        }
    }

    /**
     * Computes HMAC-SHA256 signature for webhook payload.
     *
     * @param payload the payload to sign
     * @param secret the secret key
     * @return the hex-encoded signature
     */
    private String computeSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return "sha256=" + hexString.toString();
        } catch (Exception e) {
            logger.error("Error computing webhook signature", e);
            return "";
        }
    }

    /**
     * Generates a unique webhook ID.
     *
     * @return the generated ID
     */
    private String generateId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        String id = sb.toString();
        
        // Ensure uniqueness
        if (webhooks.containsKey(id)) {
            return generateId();
        }
        return id;
    }

    /**
     * Shuts down the webhook manager.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Webhook manager shut down");
    }
}
