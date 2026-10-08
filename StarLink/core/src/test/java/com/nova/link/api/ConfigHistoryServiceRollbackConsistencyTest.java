package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nova.link.audit.AuditStore;
import com.nova.link.config.ConfigException;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H4 regression: rollback / publish / restore must merge onto a private clone
 * of the live config and only commit it to the live slot via
 * {@link ConfigManager#swapConfigForSave} after a successful save. If
 * {@link ConfigManager#save()} throws, the caller-visible live config must be
 * unchanged — no in-memory/disk divergence.
 *
 * <p>Pre-fix, {@code ConfigHistoryService.rollback()} called
 * {@code applySnapshot(live, target)} which mutated the live singleton in
 * place. A subsequent save failure then left the live singleton carrying the
 * merge while the disk file retained the pre-rollback state — the divergence
 * the audit flagged.
 */
@DisplayName("ConfigHistoryService H4 — rollback merge on a copy, swap on save success")
class ConfigHistoryServiceRollbackConsistencyTest {

    @TempDir
    Path tempDir;

    private MemoryProvider db;
    private FailingConfigManager configManager;
    private ConfigHistoryService service;
    private Gson gson;

    /**
     * A ConfigManager whose save() can be flipped to throw on demand, so the
     * rollback failure path is exercised deterministically without relying on
     * OS-level file permissions (which the atomic-move save path sidesteps on
     * Windows).
     */
    private static final class FailingConfigManager extends ConfigManager {
        private final AtomicBoolean failNextSave = new AtomicBoolean(false);

        FailingConfigManager(Path configPath) {
            super(configPath);
        }

        void failSaves(boolean fail) {
            failNextSave.set(fail);
        }

        @Override
        public void save() throws ConfigException {
            if (failNextSave.get()) {
                throw new ConfigException("simulated save failure (H4 test)");
            }
            super.save();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        configManager = new FailingConfigManager(tempDir.resolve("h4-consistency.yml"));
        configManager.load();
        service = new ConfigHistoryService(db, configManager, new AuditStore(db));
        // Wire the service so save() auto-records snapshots (same wiring the
        // REST layer performs). Without this, the rollback target revisions
        // never land in the DB.
        configManager.setConfigHistoryService(service);
        gson = new GsonBuilder().serializeNulls().create();
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    @DisplayName("rollback: save failure leaves the caller-visible live config unchanged (no divergence)")
    void rollbackSaveFailureLeavesLiveConfigUntouched() throws Exception {
        // r1: baseline snapshot.
        configManager.save();
        long r1 = configManager.getSettingsRevision();

        // r2: mutate a non-secret field so the rollback would actually try to
        // write a change back to disk.
        boolean originalFilterEnabled = configManager.getConfig().getFeatures().isFilterEnabled();
        configManager.getConfig().getFeatures().setFilterEnabled(!originalFilterEnabled);
        configManager.save();
        long r2 = configManager.getSettingsRevision();
        assertThat(r2).isGreaterThan(r1);

        // Snapshot the exact pre-rollback live state for comparison.
        String liveBeforeJson = gson.toJson(configManager.getConfig());
        boolean fieldBeforeRollback =
                configManager.getConfig().getFeatures().isFilterEnabled();
        assertThat(fieldBeforeRollback).isEqualTo(!originalFilterEnabled);

        // Force the save inside rollback to fail.
        configManager.failSaves(true);
        try {
            assertThatThrownBy(() -> service.rollback(r1, "operator"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Rollback save failed");
        } finally {
            configManager.failSaves(false);
        }

        // The caller-visible live config must be unchanged from the
        // pre-rollback snapshot — both the specific mutated field and the
        // overall serialized form. Pre-fix, applySnapshot(live, ...) had
        // already flipped the field back on the singleton before save()
        // threw, so getConfig() would have returned the r1 value here.
        assertThat(configManager.getConfig().getFeatures().isFilterEnabled())
                .isEqualTo(fieldBeforeRollback);
        assertThat(gson.toJson(configManager.getConfig())).isEqualTo(liveBeforeJson);
    }

    @Test
    @DisplayName("rollback: save success commits the merge into the live config")
    void rollbackSaveSuccessCommitsMerge() throws Exception {
        // r1: baseline snapshot with a known value.
        configManager.getConfig().getFeatures().setMessageLogRetentionDays(30);
        configManager.save();
        long r1 = configManager.getSettingsRevision();

        // r2: mutate the field and save.
        configManager.getConfig().getFeatures().setMessageLogRetentionDays(7);
        configManager.save();
        long r2 = configManager.getSettingsRevision();
        assertThat(r2).isGreaterThan(r1);

        // Roll back to r1. Save must succeed and the merge must be visible
        // through configManager.getConfig() afterwards.
        long newRev = service.rollback(r1, "operator");
        assertThat(newRev).isGreaterThan(r2);
        assertThat(configManager.getConfig().getFeatures().getMessageLogRetentionDays())
                .isEqualTo(30);
    }
}
