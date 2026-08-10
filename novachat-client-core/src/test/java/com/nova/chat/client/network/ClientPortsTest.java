package com.nova.chat.client.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code SchedulerBridge} and {@code ClientLogger} port
 * contracts shared across platform network clients.
 */
@DisplayName("SchedulerBridge / ClientLogger contracts")
class ClientPortsTest {

    @Test
    @DisplayName("SchedulerBridge runLater records delay seconds")
    void schedulerBridgeRecordsDelay() {
        List<Long> delays = new ArrayList<>();
        AtomicInteger asyncRuns = new AtomicInteger();

        SchedulerBridge bridge = new SchedulerBridge() {
            @Override
            public void runAsync(Runnable task) {
                asyncRuns.incrementAndGet();
                task.run();
            }

            @Override
            public void runLater(Runnable task, long delaySeconds) {
                delays.add(delaySeconds);
            }
        };

        bridge.runAsync(() -> { });
        bridge.runLater(() -> { }, 4);

        assertThat(asyncRuns.get()).isEqualTo(1);
        assertThat(delays).containsExactly(4L);
    }

    @Test
    @DisplayName("ClientLogger default error(message, cause) appends cause message")
    void loggerErrorWithCauseDefault() {
        List<String> errors = new ArrayList<>();
        ClientLogger logger = new ClientLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void debug(String message) {
            }

            @Override
            public void error(String message) {
                errors.add(message);
            }
        };

        logger.error("boom", new IllegalStateException("detail"));
        logger.error("plain", null);

        assertThat(errors).containsExactly("boom: detail", "plain");
    }
}
