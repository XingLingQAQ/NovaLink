package com.nova.link.api;

import com.google.gson.JsonObject;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebhookManager} persistence + active-flag behavior: write-through
 * CRUD, restart reload, inactive webhooks skipped during distribution, and
 * the synchronous test delivery (success/failure branches).
 */
@DisplayName("WebhookManager persistence + active flag + test delivery")
class WebhookPersistenceTest {

    private MemoryProvider provider;
    private WebhookManager manager;

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int responseStatus = 200;
    private CountDownLatch deliveryLatch;

    @BeforeEach
    void setUp() throws Exception {
        provider = new MemoryProvider();
        provider.initialize();
        manager = new WebhookManager();
        manager.setDatabaseProvider(provider);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            byte[] response = "ok".getBytes();
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            if (deliveryLatch != null) {
                deliveryLatch.countDown();
            }
        });
        server.start();
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        server.stop(0);
        provider.shutdown();
    }

    @Test
    @DisplayName("create/update/delete write through to the database")
    void crudWritesThrough() throws DatabaseException {
        Webhook created = manager.createWebhook("https://example.com/a", "message.sent", "sec");
        assertThat(created.isActive()).isTrue();

        List<Webhook> persisted = provider.getAllPersistedWebhooks();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getUrl()).isEqualTo("https://example.com/a");

        Webhook updated = manager.updateWebhook(created.getId(),
                "https://example.com/b", "player.join", null, false);
        assertThat(updated).isNotNull();
        Webhook reloaded = provider.getAllPersistedWebhooks().get(0);
        assertThat(reloaded.getUrl()).isEqualTo("https://example.com/b");
        assertThat(reloaded.getEvent()).isEqualTo("player.join");
        assertThat(reloaded.isActive()).isFalse();

        assertThat(manager.updateWebhook("missing", null, null, null, true)).isNull();

        assertThat(manager.deleteWebhook(created.getId())).isTrue();
        assertThat(provider.getAllPersistedWebhooks()).isEmpty();
    }

    @Test
    @DisplayName("restart reload restores webhooks from the database")
    void restartRestoresWebhooks() {
        Webhook created = manager.createWebhook("https://example.com/a", "message.sent", null);
        manager.updateWebhook(created.getId(), null, null, null, false);
        manager.shutdown();

        WebhookManager fresh = new WebhookManager();
        fresh.setDatabaseProvider(provider);
        assertThat(fresh.loadPersistedWebhooks()).isEqualTo(1);

        Webhook restored = fresh.getWebhook(created.getId());
        assertThat(restored).isNotNull();
        assertThat(restored.getUrl()).isEqualTo("https://example.com/a");
        assertThat(restored.isActive()).isFalse();
        fresh.shutdown();
    }

    @Test
    @DisplayName("triggerWebhook skips inactive webhooks and delivers to active ones")
    void triggerSkipsInactive() throws Exception {
        Webhook active = manager.createWebhook(hookUrl(), "message.sent", null);
        Webhook inactive = manager.createWebhook(hookUrl(), "message.sent", null);
        manager.updateWebhook(inactive.getId(), null, null, null, false);

        deliveryLatch = new CountDownLatch(1);
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "hi");
        manager.triggerWebhook("message.sent", payload);

        assertThat(deliveryLatch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give a moment for any (incorrect) second delivery to surface.
        Thread.sleep(200);
        assertThat(requestCount.get()).isEqualTo(1);

        // Successful delivery stamps lastTriggered and persists it.
        assertThat(manager.getWebhook(active.getId()).getLastTriggered()).isPositive();
        assertThat(manager.getWebhook(inactive.getId()).getLastTriggered()).isZero();
    }

    @Test
    @DisplayName("sendTest success branch: 2xx response, lastTriggered persisted")
    void sendTestSuccess() throws DatabaseException {
        Webhook webhook = manager.createWebhook(hookUrl(), "message.sent", "sec");

        WebhookManager.TestResult result = manager.sendTest(webhook);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
        assertThat(result.getError()).isNull();
        assertThat(webhook.getLastTriggered()).isPositive();
        assertThat(provider.getAllPersistedWebhooks().get(0).getLastTriggered()).isPositive();
    }

    @Test
    @DisplayName("sendTest failure branch: non-2xx status is reported, lastTriggered untouched")
    void sendTestFailureStatus() {
        responseStatus = 500;
        Webhook webhook = manager.createWebhook(hookUrl(), "message.sent", null);

        WebhookManager.TestResult result = manager.sendTest(webhook);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isEqualTo(500);
        assertThat(result.getError()).isNotBlank();
        assertThat(webhook.getLastTriggered()).isZero();
    }

    @Test
    @DisplayName("sendTest failure branch: invalid URL fails without touching the network")
    void sendTestFailureInvalidUrl() {
        Webhook webhook = manager.createWebhook("ht!tp://not a url", "message.sent", null);

        WebhookManager.TestResult result = manager.sendTest(webhook);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatusCode()).isNull();
        assertThat(result.getError()).isNotBlank();
        assertThat(requestCount.get()).isZero();
    }
}
