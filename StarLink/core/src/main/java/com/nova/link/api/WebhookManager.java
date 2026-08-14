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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Default delivery worker count (preserves the existing concurrency). */
    public static final int DEFAULT_WORKER_THREADS = 4;

    /** Maximum initial/retry attempts waiting for a delivery worker. */
    public static final int DEFAULT_DELIVERY_QUEUE_CAPACITY = 1024;

    /** Maximum delayed retries waiting in the scheduler. */
    public static final int DEFAULT_RETRY_QUEUE_CAPACITY = 1024;

    /** Timeout for the synchronous test delivery (POST /api/webhooks/{id}/test). */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final long OVERFLOW_WARN_INTERVAL_MILLIS = 30_000;

    private final Map<String, Webhook> webhooks;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor deliveryExecutor;
    private final ScheduledThreadPoolExecutor retryScheduler;
    private final Gson gson;
    private final SecureRandom random;
    private final int deliveryQueueCapacity;
    private final int retryQueueCapacity;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final AtomicLong acceptedDeliveryCount = new AtomicLong();
    private final AtomicLong rejectedDeliveryCount = new AtomicLong();
    private final AtomicLong completedDeliveryCount = new AtomicLong();
    private final AtomicLong successfulDeliveryCount = new AtomicLong();
    private final AtomicLong failedDeliveryCount = new AtomicLong();
    private final AtomicLong retryRejectedCount = new AtomicLong();
    private final AtomicLong rejectedAttemptCount = new AtomicLong();
    private final AtomicInteger pendingRetryCount = new AtomicInteger();
    private final AtomicLong lastOverflowWarnAt = new AtomicLong();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final Set<RetryTask> pendingRetries = ConcurrentHashMap.newKeySet();
    private final Object retryLifecycleLock = new Object();

    /**
     * Optional persistence backend (schema v5 webhooks table). When set,
     * webhooks are written through on create/update/delete and restored via
     * {@link #loadPersistedWebhooks()} at startup.
     */
    private volatile com.nova.link.database.DatabaseProvider databaseProvider;

    public WebhookManager() {
        this(DEFAULT_WORKER_THREADS, DEFAULT_DELIVERY_QUEUE_CAPACITY,
                DEFAULT_RETRY_QUEUE_CAPACITY, MAX_RETRIES, RETRY_DELAY_MS);
    }

    /**
     * Capacity/configuration injection entry point used by focused tests and
     * future configuration wiring. {@code maxRetries} retains the historical
     * meaning: total attempts, including the initial request.
     */
    public WebhookManager(int workerThreads, int deliveryQueueCapacity,
                          int retryQueueCapacity, int maxRetries, long retryDelayMillis) {
        requirePositive(workerThreads, "workerThreads");
        this.deliveryQueueCapacity =
                requirePositive(deliveryQueueCapacity, "deliveryQueueCapacity");
        this.retryQueueCapacity = requirePositive(retryQueueCapacity, "retryQueueCapacity");
        this.maxRetries = requirePositive(maxRetries, "maxRetries");
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        this.retryDelayMillis = retryDelayMillis;
        this.webhooks = new ConcurrentHashMap<>();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.deliveryExecutor = new ThreadPoolExecutor(
                workerThreads,
                workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(deliveryQueueCapacity),
                daemonThreadFactory("Webhook-Delivery"),
                new ThreadPoolExecutor.AbortPolicy());
        this.retryScheduler = new ScheduledThreadPoolExecutor(
                1,
                daemonThreadFactory("Webhook-Retry-Scheduler"),
                new ThreadPoolExecutor.AbortPolicy());
        this.retryScheduler.setRemoveOnCancelPolicy(true);
        this.retryScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.retryScheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.gson = new Gson();
        this.random = new SecureRandom();
    }

    /**
     * Sets the persistence backend. Must be called before
     * {@link #loadPersistedWebhooks()}.
     */
    public void setDatabaseProvider(com.nova.link.database.DatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
    }

    /**
     * Loads persisted webhooks from the database into the runtime registry.
     * Call once at startup.
     *
     * @return number of webhooks restored
     */
    public int loadPersistedWebhooks() {
        if (databaseProvider == null) {
            return 0;
        }
        int restored = 0;
        try {
            for (Webhook webhook : databaseProvider.getAllPersistedWebhooks()) {
                webhooks.put(webhook.getId(), webhook);
                restored++;
            }
        } catch (com.nova.link.database.DatabaseException e) {
            logger.error("Failed to load persisted webhooks: {}", e.getMessage());
        }
        if (restored > 0) {
            logger.info("Restored {} persisted webhooks", restored);
        }
        return restored;
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
        persistWebhook(webhook);
        logger.info("Created webhook {} for event '{}'", id, event);
        return webhook;
    }

    /**
     * Updates an existing webhook (PUT /api/webhooks/{id}). Null parameters
     * leave the corresponding field unchanged; the change is written through
     * to the database.
     *
     * @param id the webhook ID
     * @param url new URL, or null to keep
     * @param event new event type, or null to keep
     * @param secret new secret, or null to keep
     * @param active new active flag, or null to keep
     * @return the updated webhook, or null when not found
     */
    public Webhook updateWebhook(String id, String url, String event, String secret, Boolean active) {
        Webhook webhook = webhooks.get(id);
        if (webhook == null) {
            return null;
        }
        if (url != null) {
            webhook.setUrl(url);
        }
        if (event != null) {
            webhook.setEvent(event);
        }
        if (secret != null) {
            webhook.setSecret(secret);
        }
        if (active != null) {
            webhook.setActive(active);
        }
        persistWebhook(webhook);
        logger.info("Updated webhook {}", id);
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
            removePersistedWebhook(id);
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
            if (!webhook.isActive()) {
                continue;
            }
            if (webhook.matchesEvent(eventType)) {
                try {
                    submitInitial(createDelivery(webhook, eventType, payload));
                } catch (IllegalArgumentException e) {
                    rejectedDeliveryCount.incrementAndGet();
                    failedDeliveryCount.incrementAndGet();
                    warnOverflow("invalid webhook delivery request");
                }
            }
        }
    }

    /** @return delivery attempts currently waiting for a worker */
    public int getDeliveryQueueDepth() {
        return deliveryExecutor.getQueue().size();
    }

    /** @return maximum delivery attempts that may wait for workers */
    public int getDeliveryQueueCapacity() {
        return deliveryQueueCapacity;
    }

    /** @return delayed retries currently retained by the scheduler */
    public int getPendingRetryCount() {
        return pendingRetryCount.get();
    }

    /** @return maximum delayed retries retained by the scheduler */
    public int getRetryQueueCapacity() {
        return retryQueueCapacity;
    }

    /** @return logical deliveries accepted for asynchronous processing */
    public long getAcceptedDeliveryCount() {
        return acceptedDeliveryCount.get();
    }

    /** @return initial logical deliveries rejected before asynchronous processing */
    public long getRejectedDeliveryCount() {
        return rejectedDeliveryCount.get();
    }

    /** @return accepted logical deliveries that reached a terminal outcome */
    public long getCompletedDeliveryCount() {
        return completedDeliveryCount.get();
    }

    /** @return logical deliveries that succeeded */
    public long getSuccessfulDeliveryCount() {
        return successfulDeliveryCount.get();
    }

    /** @return logical deliveries that failed, including overflow rejection */
    public long getFailedDeliveryCount() {
        return failedDeliveryCount.get();
    }

    /** @return retries rejected because the delayed-retry backlog was full */
    public long getRetryRejectedCount() {
        return retryRejectedCount.get();
    }

    /** @return attempts rejected because the delivery-worker queue was full */
    public long getRejectedAttemptCount() {
        return rejectedAttemptCount.get();
    }

    /**
     * Synchronously sends a test payload ({@code event=test}) to a webhook.
     * No retries; 5-second timeout. Used by POST /api/webhooks/{id}/test.
     *
     * @param webhook the webhook to test
     * @return the delivery outcome
     */
    public TestResult sendTest(Webhook webhook) {
        JsonObject data = new JsonObject();
        data.addProperty("message", "NovaLink webhook test");

        JsonObject fullPayload = new JsonObject();
        fullPayload.addProperty("event", "test");
        fullPayload.addProperty("webhookId", webhook.getId());
        fullPayload.addProperty("timestamp", System.currentTimeMillis());
        fullPayload.add("data", data);

        String body = gson.toJson(fullPayload);

        HttpRequest.Builder requestBuilder;
        try {
            requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(webhook.getUrl()))
                    .timeout(TEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NovaLink-Webhook/1.0")
                    .header("X-NovaLink-Event", "test")
                    .header("X-NovaLink-Webhook-Id", webhook.getId())
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } catch (IllegalArgumentException e) {
            return TestResult.failure("Invalid webhook URL: " + e.getMessage());
        }
        if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
            requestBuilder.header("X-NovaLink-Signature", computeSignature(body, webhook.getSecret()));
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                webhook.setLastTriggered(System.currentTimeMillis());
                persistWebhook(webhook);
                return TestResult.success(status);
            }
            return TestResult.failure(status, "Webhook responded with status " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestResult.failure("Interrupted while sending test payload");
        } catch (Exception e) {
            return TestResult.failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Outcome of a synchronous webhook test delivery.
     */
    public static class TestResult {
        private final boolean success;
        private final Integer statusCode;
        private final String error;

        private TestResult(boolean success, Integer statusCode, String error) {
            this.success = success;
            this.statusCode = statusCode;
            this.error = error;
        }

        public static TestResult success(int statusCode) {
            return new TestResult(true, statusCode, null);
        }

        public static TestResult failure(String error) {
            return new TestResult(false, null, error);
        }

        public static TestResult failure(int statusCode, String error) {
            return new TestResult(false, statusCode, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getError() {
            return error;
        }
    }

    private Delivery createDelivery(Webhook webhook, String eventType, JsonObject payload) {
        JsonObject fullPayload = new JsonObject();
        fullPayload.addProperty("event", eventType);
        fullPayload.addProperty("webhookId", webhook.getId());
        fullPayload.addProperty("timestamp", System.currentTimeMillis());
        fullPayload.add("data", payload);

        String body = gson.toJson(fullPayload);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(webhook.getUrl()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "NovaLink-Webhook/1.0")
                .header("X-NovaLink-Event", eventType)
                .header("X-NovaLink-Webhook-Id", webhook.getId())
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
            requestBuilder.header("X-NovaLink-Signature",
                    computeSignature(body, webhook.getSecret()));
        }

        return new Delivery(webhook, requestBuilder.build());
    }

    private void submitInitial(Delivery delivery) {
        acceptedDeliveryCount.incrementAndGet();
        try {
            deliveryExecutor.execute(new DeliveryAttempt(delivery, 1));
        } catch (RejectedExecutionException e) {
            acceptedDeliveryCount.decrementAndGet();
            rejectedDeliveryCount.incrementAndGet();
            rejectedAttemptCount.incrementAndGet();
            delivery.rejectBeforeAcceptance();
            warnOverflow("delivery worker queue full or closed");
        }
    }

    private void submitRetry(Delivery delivery, int attempt) {
        if (shuttingDown.get()) {
            delivery.finishFailure();
            return;
        }
        try {
            deliveryExecutor.execute(new DeliveryAttempt(delivery, attempt));
        } catch (RejectedExecutionException e) {
            rejectedAttemptCount.incrementAndGet();
            delivery.finishFailure();
            warnOverflow("delivery worker queue full or closed");
        }
    }

    private void deliverAttempt(Delivery delivery, int attempt) {
        if (delivery.isFinished()) {
            return;
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    delivery.request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                delivery.webhook.setLastTriggered(System.currentTimeMillis());
                persistWebhook(delivery.webhook);
                delivery.finishSuccess();
                logger.debug("Webhook {} delivered successfully (attempt {})",
                        delivery.webhook.getId(), attempt);
                return;
            }
            logger.warn("Webhook {} delivery failed with status {} (attempt {})",
                    delivery.webhook.getId(), status, attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delivery.finishFailure();
            return;
        } catch (Exception e) {
            // Do not log exception messages: HTTP errors often embed the full
            // target URL, which may itself contain credentials.
            logger.warn("Webhook {} delivery error (attempt {}, type={})",
                    delivery.webhook.getId(), attempt, e.getClass().getSimpleName());
        }

        if (attempt >= maxRetries) {
            delivery.finishFailure();
            logger.error("Webhook {} delivery failed after {} attempts",
                    delivery.webhook.getId(), maxRetries);
            return;
        }

        scheduleRetry(delivery, attempt + 1, saturatingMultiply(retryDelayMillis, attempt));
    }

    private void scheduleRetry(Delivery delivery, int nextAttempt, long delayMillis) {
        synchronized (retryLifecycleLock) {
            if (shuttingDown.get()) {
                delivery.finishFailure();
                return;
            }
            if (!reserveRetrySlot()) {
                retryRejectedCount.incrementAndGet();
                delivery.finishFailure();
                warnOverflow("retry scheduler queue full");
                return;
            }

            RetryTask retryTask = new RetryTask(delivery, nextAttempt);
            pendingRetries.add(retryTask);
            try {
                ScheduledFuture<?> future = retryScheduler.schedule(
                        retryTask, delayMillis, TimeUnit.MILLISECONDS);
                retryTask.setFuture(future);
            } catch (RejectedExecutionException e) {
                retryTask.cancelForShutdown();
                retryRejectedCount.incrementAndGet();
                warnOverflow("retry scheduler closed");
            }
        }
    }

    private boolean reserveRetrySlot() {
        while (true) {
            int current = pendingRetryCount.get();
            if (current >= retryQueueCapacity) {
                return false;
            }
            if (pendingRetryCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void warnOverflow(String reason) {
        long now = System.currentTimeMillis();
        long previous = lastOverflowWarnAt.get();
        if (now - previous < OVERFLOW_WARN_INTERVAL_MILLIS
                || !lastOverflowWarnAt.compareAndSet(previous, now)) {
            return;
        }
        logger.warn("Webhook delivery overload: {} (deliveryCapacity={}, retryCapacity={}, "
                        + "rejectedInitial={}, rejectedAttempts={}, rejectedRetries={})",
                reason,
                deliveryQueueCapacity,
                retryQueueCapacity,
                rejectedDeliveryCount.get(),
                rejectedAttemptCount.get(),
                retryRejectedCount.get());
    }

    private final class Delivery {
        private final Webhook webhook;
        private final HttpRequest request;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Delivery(Webhook webhook, HttpRequest request) {
            this.webhook = webhook;
            this.request = request;
        }

        private boolean isFinished() {
            return finished.get();
        }

        private void rejectBeforeAcceptance() {
            if (finished.compareAndSet(false, true)) {
                failedDeliveryCount.incrementAndGet();
            }
        }

        private void finishSuccess() {
            if (finished.compareAndSet(false, true)) {
                successfulDeliveryCount.incrementAndGet();
                completedDeliveryCount.incrementAndGet();
            }
        }

        private void finishFailure() {
            if (finished.compareAndSet(false, true)) {
                failedDeliveryCount.incrementAndGet();
                completedDeliveryCount.incrementAndGet();
            }
        }
    }

    private final class DeliveryAttempt implements Runnable {
        private final Delivery delivery;
        private final int attempt;

        private DeliveryAttempt(Delivery delivery, int attempt) {
            this.delivery = delivery;
            this.attempt = attempt;
        }

        @Override
        public void run() {
            deliverAttempt(delivery, attempt);
        }

        private void cancel() {
            delivery.finishFailure();
        }
    }

    private final class RetryTask implements Runnable {
        private final Delivery delivery;
        private final int attempt;
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> future;

        private RetryTask(Delivery delivery, int attempt) {
            this.delivery = delivery;
            this.attempt = attempt;
        }

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
            if (!pending.get()) {
                future.cancel(false);
            }
        }

        @Override
        public void run() {
            if (!releaseReservation()) {
                return;
            }
            submitRetry(delivery, attempt);
        }

        private void cancelForShutdown() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (releaseReservation()) {
                delivery.finishFailure();
            }
        }

        private boolean releaseReservation() {
            if (!pending.compareAndSet(true, false)) {
                return false;
            }
            pendingRetries.remove(this);
            pendingRetryCount.decrementAndGet();
            return true;
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
     * Writes a webhook through to the database (best-effort).
     */
    private void persistWebhook(Webhook webhook) {
        if (databaseProvider == null) {
            return;
        }
        try {
            databaseProvider.saveWebhook(webhook);
        } catch (com.nova.link.database.DatabaseException e) {
            logger.warn("Failed to persist webhook {}: {}", webhook.getId(), e.getMessage());
        }
    }

    /**
     * Removes a persisted webhook row (best-effort).
     */
    private void removePersistedWebhook(String id) {
        if (databaseProvider == null) {
            return;
        }
        try {
            databaseProvider.deleteWebhook(id);
        } catch (com.nova.link.database.DatabaseException e) {
            logger.warn("Failed to delete persisted webhook {}: {}", id, e.getMessage());
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
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        synchronized (retryLifecycleLock) {
            retryScheduler.shutdownNow();
            for (RetryTask retry : new ArrayList<>(pendingRetries)) {
                retry.cancelForShutdown();
            }
        }

        deliveryExecutor.shutdown();
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        boolean interrupted = false;
        boolean deliveryTerminated = false;
        try {
            retryScheduler.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            deliveryTerminated = deliveryExecutor.awaitTermination(
                    remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }

        if (!deliveryTerminated) {
            List<Runnable> cancelled = deliveryExecutor.shutdownNow();
            for (Runnable task : cancelled) {
                if (task instanceof DeliveryAttempt) {
                    ((DeliveryAttempt) task).cancel();
                }
            }
            logger.warn("Webhook delivery shutdown deadline reached (cancelledQueued={})",
                    cancelled.size());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        logger.info("Webhook manager shut down");
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long saturatingMultiply(long value, int multiplier) {
        if (value == 0 || multiplier == 0) {
            return 0;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }
}
