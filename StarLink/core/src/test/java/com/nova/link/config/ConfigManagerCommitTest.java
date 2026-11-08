package com.nova.link.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ConfigManagerCommitTest {
    @TempDir Path directory;

    @Test
    void snapshotPreservesExplicitNullRatherThanReplacingItWithConstructorDefaults() throws Exception {
        ConfigManager manager = new ConfigManager(directory.resolve("config.yml"));
        manager.load();
        manager.getConfig().setFeatures(null);
        assertThat(manager.snapshot().config().getFeatures()).isNull();
    }

    @Test
    void failedClientNotificationDoesNotTurnACommittedWriteIntoASaveFailure() throws Exception {
        ConfigManager manager = new ConfigManager(directory.resolve("config.yml"));
        manager.load();
        manager.setConnectionSupplier(() -> { throw new IllegalStateException("Client registry unavailable"); });
        var commit = manager.updateSettings(0L, config -> config.getFeatures().setFilterEnabled(false), "admin");
        assertThat(commit.revision()).isEqualTo(1);
        assertThat(manager.snapshot().config().getFeatures().isFilterEnabled()).isFalse();
        ConfigManager disk = new ConfigManager(directory.resolve("config.yml"));
        assertThat(disk.load().getFeatures().isFilterEnabled()).isFalse();
    }

    @Test
    void settingsCommitDoesNotReapplyFullConfigButStillPublishesRuntimeSnapshot() throws Exception {
        ConfigManager manager = new ConfigManager(directory.resolve("config.yml"));
        manager.load();
        AtomicInteger reloads = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        manager.addReloadListener(config -> reloads.incrementAndGet());
        manager.addCommitListener(commit -> commits.incrementAndGet());
        manager.updateSettings(0L, config -> config.getFeatures().setFilterEnabled(false), "admin");
        assertThat(reloads.get()).isZero();
        assertThat(commits.get()).isEqualTo(1);
        manager.update(1L, config -> config.getFeatures().setFilterEnabled(true), "admin");
        assertThat(reloads.get()).isEqualTo(1);
        assertThat(commits.get()).isEqualTo(2);
    }

    @Test
    void sameRevisionHasExactlyOneWinner() throws Exception {
        ConfigManager manager = new ConfigManager(directory.resolve("config.yml"));
        manager.load();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger applied = new AtomicInteger();
        manager.addCommitListener(commit -> applied.incrementAndGet());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    manager.update(0L, candidate -> candidate.getFeatures().setFilterEnabled(index == 0), "admin");
                    success.incrementAndGet();
                } catch (ConfigManager.RevisionConflict expected) {
                    conflict.incrementAndGet();
                    assertThat(expected.current().revision()).isEqualTo(1);
                }
                return null;
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(applied.get()).isEqualTo(1);
        ConfigManager disk = new ConfigManager(directory.resolve("config.yml"));
        assertThat(disk.load().getFeatures().isFilterEnabled())
                .isEqualTo(manager.snapshot().config().getFeatures().isFilterEnabled());
    }

    @Test
    void failedWriteNeverPublishesCandidateOrNotifiesRuntime() throws Exception {
        ConfigManager manager = new ConfigManager(directory.resolve("config.yml")) {
            @Override protected void persist(NovaLinkConfig candidate) throws ConfigException {
                assertThat(getConfig().getFeatures().isFilterEnabled()).isTrue();
                throw new ConfigException("Injected write failure");
            }
        };
        manager.load();
        String before = Files.readString(directory.resolve("config.yml"));
        AtomicInteger applied = new AtomicInteger();
        manager.addReloadListener(config -> applied.incrementAndGet());
        manager.addCommitListener(config -> applied.incrementAndGet());
        assertThatThrownBy(() -> manager.update(null, c -> c.getFeatures().setFilterEnabled(false), "admin"))
                .isInstanceOf(ConfigException.class);
        assertThat(manager.snapshot().config().getFeatures().isFilterEnabled()).isTrue();
        assertThat(manager.snapshot().revision()).isZero();
        assertThat(Files.readString(directory.resolve("config.yml"))).isEqualTo(before);
        assertThat(applied.get()).isZero();
    }
}
