package com.nova.link.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("configuration file watcher")
class ConfigManagerWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("internal saves are ignored but an external edit reloads once")
    void distinguishesInternalSaveFromExternalEdit() throws Exception {
        Path file = tempDir.resolve("novalink.yml");
        ConfigManager manager = new ConfigManager(file);
        try {
            manager.load();
            manager.startWatching();

            manager.getConfig().getFeatures().setFilterEnabled(false);
            manager.save();
            Thread.sleep(2200);
            assertThat(manager.getReloadCount()).isZero();

            String externallyEdited = Files.readString(file).replace("debug: false", "debug: true");
            Files.writeString(file, externallyEdited);

            awaitReloadCount(manager, 1, Duration.ofSeconds(6));
            assertThat(manager.getReloadCount()).isEqualTo(1);
            assertThat(manager.getConfig().isDebug()).isTrue();
        } finally {
            manager.shutdown();
        }
    }

    private void awaitReloadCount(ConfigManager manager, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (manager.getReloadCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
    }
}
