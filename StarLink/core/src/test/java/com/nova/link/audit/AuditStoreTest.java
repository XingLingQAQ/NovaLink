package com.nova.link.audit;

import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditStore} backed by a real {@link MemoryProvider}.
 *
 * <p>Verifies the three core operations — record, list, count — including the
 * PANEL-006 filtering (actor/action) and newest-first ordering, plus the
 * graceful-degradation contract: a null or failing provider never propagates
 * an exception to the caller (the audit trail must not block the mutation
 * that triggered it).
 *
 * <p>Requirements: PANEL-006 audit log
 */
@DisplayName("AuditStore record/list/count")
class AuditStoreTest {

    private MemoryProvider db;
    private AuditStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        store = new AuditStore(db);
    }

    private static AuditEvent newEvent(String actor, String action, String resource, long createdAt) {
        return new AuditEvent(
                java.util.UUID.randomUUID().toString(),    // eventId
                "req-" + createdAt,                          // requestId
                actor,
                "ADMIN",
                "127.0.0.1",
                action,
                resource,
                null,                                         // beforeHash
                "deadbeef",                                  // afterHash
                null,                                         // reason
                "success",
                createdAt);
    }

    @Test
    @DisplayName("record persists an event and list returns it newest-first")
    void recordPersistsAndListReturnsNewestFirst() {
        long t1 = 1_700_000_000_000L;
        long t2 = t1 + 1_000L;
        long t3 = t1 + 2_000L;
        store.record(newEvent("alice", "channel.create", "channel:NC-1", t1));
        store.record(newEvent("bob", "channel.delete", "channel:staff", t2));
        store.record(newEvent("alice", "settings.update", "config:features", t3));

        List<AuditEvent> all = store.list(0, 10, null, null);
        assertThat(all).hasSize(3);
        // Newest first (descending by createdAt).
        assertThat(all.get(0).getCreatedAt()).isEqualTo(t3);
        assertThat(all.get(1).getCreatedAt()).isEqualTo(t2);
        assertThat(all.get(2).getCreatedAt()).isEqualTo(t1);

        // The id is stamped by the store (MemoryProvider assigns it).
        assertThat(all.get(0).getId()).isGreaterThan(0L);
        assertThat(all.get(1).getId()).isGreaterThan(0L);
        assertThat(all.get(2).getId()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("count returns the total with no filters")
    void countReturnsTotal() {
        store.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));
        store.record(newEvent("bob", "channel.delete", "channel:staff", 2L));
        store.record(newEvent("alice", "settings.update", "config:features", 3L));

        assertThat(store.count(null, null)).isEqualTo(3);
        // Empty-string filters are treated as "no filter" (same as null).
        assertThat(store.count("", "")).isEqualTo(3);
    }

    @Test
    @DisplayName("list filters by actor")
    void listFiltersByActor() {
        store.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));
        store.record(newEvent("bob", "channel.delete", "channel:staff", 2L));
        store.record(newEvent("alice", "settings.update", "config:features", 3L));

        List<AuditEvent> aliceEvents = store.list(0, 10, "alice", null);
        assertThat(aliceEvents).hasSize(2);
        assertThat(aliceEvents).allSatisfy(e -> assertThat(e.getActor()).isEqualTo("alice"));
        // Still newest-first within the filtered set.
        assertThat(aliceEvents.get(0).getCreatedAt()).isEqualTo(3L);
        assertThat(aliceEvents.get(1).getCreatedAt()).isEqualTo(1L);

        // Filter that matches nothing returns an empty list, not null.
        assertThat(store.list(0, 10, "nobody", null)).isEmpty();
    }

    @Test
    @DisplayName("list filters by action")
    void listFiltersByAction() {
        store.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));
        store.record(newEvent("bob", "channel.delete", "channel:staff", 2L));
        store.record(newEvent("alice", "settings.update", "config:features", 3L));

        List<AuditEvent> deletes = store.list(0, 10, null, "channel.delete");
        assertThat(deletes).hasSize(1);
        assertThat(deletes.get(0).getAction()).isEqualTo("channel.delete");
        assertThat(deletes.get(0).getResource()).isEqualTo("channel:staff");
    }

    @Test
    @DisplayName("list filters by both actor and action")
    void listFiltersByActorAndAction() {
        store.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));
        store.record(newEvent("alice", "channel.delete", "channel:staff", 2L));
        store.record(newEvent("bob", "channel.delete", "channel:survival", 3L));

        List<AuditEvent> aliceDeletes = store.list(0, 10, "alice", "channel.delete");
        assertThat(aliceDeletes).hasSize(1);
        assertThat(aliceDeletes.get(0).getActor()).isEqualTo("alice");
        assertThat(aliceDeletes.get(0).getAction()).isEqualTo("channel.delete");

        // count honors the same combined filter.
        assertThat(store.count("alice", "channel.delete")).isEqualTo(1);
        assertThat(store.count("bob", "channel.delete")).isEqualTo(1);
        assertThat(store.count("alice", "channel.create")).isEqualTo(1);
    }

    @Test
    @DisplayName("list applies offset/limit after filtering")
    void listAppliesOffsetAndLimit() {
        // 5 alice events with increasing timestamps.
        for (int i = 0; i < 5; i++) {
            store.record(newEvent("alice", "channel.create", "channel:NC-" + i, 1000L + i));
        }
        // 2 bob events that should be filtered out.
        store.record(newEvent("bob", "channel.create", "channel:NC-bob", 2000L));
        store.record(newEvent("bob", "channel.create", "channel:NC-bob2", 3000L));

        // Page 1 (offset 0, limit 2) → newest two alice events.
        List<AuditEvent> page1 = store.list(0, 2, "alice", null);
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).getCreatedAt()).isEqualTo(1004L);
        assertThat(page1.get(1).getCreatedAt()).isEqualTo(1003L);

        // Page 2 (offset 2, limit 2) → next two.
        List<AuditEvent> page2 = store.list(2, 2, "alice", null);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getCreatedAt()).isEqualTo(1002L);
        assertThat(page2.get(1).getCreatedAt()).isEqualTo(1001L);

        // Page 3 (offset 4, limit 2) → last one.
        List<AuditEvent> page3 = store.list(4, 2, "alice", null);
        assertThat(page3).hasSize(1);
        assertThat(page3.get(0).getCreatedAt()).isEqualTo(1000L);

        // Total count is 5 (alice only).
        assertThat(store.count("alice", null)).isEqualTo(5);
    }

    @Test
    @DisplayName("record of a null event is a no-op (never throws)")
    void recordNullEventIsNoOp() {
        store.record(null);
        assertThat(store.count(null, null)).isZero();
        assertThat(store.list(0, 10, null, null)).isEmpty();
    }

    @Test
    @DisplayName("store with a null databaseProvider degrades to empty/zero")
    void nullProviderDegradesGracefully() {
        AuditStore nullStore = new AuditStore(null);
        // record must not NPE.
        nullStore.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));
        assertThat(nullStore.list(0, 10, null, null)).isEmpty();
        assertThat(nullStore.count(null, null)).isZero();
    }

    @Test
    @DisplayName("record does not throw when the underlying provider fails")
    void recordSwallowsDatabaseFailure() throws Exception {
        // Wrap the real provider so saveAuditEvent throws. We extend
        // MemoryProvider inline to keep the test self-contained.
        MemoryProvider throwing = new MemoryProvider() {
            @Override
            public synchronized void saveAuditEvent(com.nova.link.audit.AuditEvent event)
                    throws com.nova.link.database.DatabaseException {
                throw new com.nova.link.database.DatabaseException("simulated failure");
            }
        };
        throwing.initialize();
        AuditStore failingStore = new AuditStore(throwing);
        // record must swallow the DatabaseException — the mutation that
        // triggered the audit must never be blocked by an audit failure.
        failingStore.record(newEvent("alice", "channel.create", "channel:NC-1", 1L));

        // list/count on the throwing provider also degrade to empty/zero.
        assertThat(failingStore.list(0, 10, null, null)).isEmpty();
        assertThat(failingStore.count(null, null)).isZero();
    }

    @Test
    @DisplayName("AuditEvent.hashJson is a stable 64-char SHA-256 hex; null yields null")
    void hashJsonIsStableSha256() {
        String h1 = AuditEvent.hashJson("{\"a\":1}");
        String h2 = AuditEvent.hashJson("{\"a\":1}");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
        assertThat(h1).matches("[0-9a-f]{64}");

        // Different input → different hash.
        String h3 = AuditEvent.hashJson("{\"a\":2}");
        assertThat(h3).hasSize(64).isNotEqualTo(h1);

        // null input → null output (callers rely on this for create/delete).
        assertThat(AuditEvent.hashJson(null)).isNull();
    }

    @Test
    @DisplayName("AuditEvent preserves all fields through the store round-trip")
    void auditEventFieldsRoundTrip() {
        AuditEvent original = new AuditEvent(
                "evt-uuid-123",
                "req-id-456",
                "alice",
                "SUPER_ADMIN",
                "10.0.0.1",
                "webhook.create",
                "webhook:WH-1",
                "before-hash",
                "after-hash",
                "created for integration test",
                "success",
                1_700_000_000_000L);
        store.record(original);

        List<AuditEvent> events = store.list(0, 10, "alice", "webhook.create");
        assertThat(events).hasSize(1);
        AuditEvent loaded = events.get(0);
        assertThat(loaded.getEventId()).isEqualTo("evt-uuid-123");
        assertThat(loaded.getRequestId()).isEqualTo("req-id-456");
        assertThat(loaded.getActor()).isEqualTo("alice");
        assertThat(loaded.getRole()).isEqualTo("SUPER_ADMIN");
        assertThat(loaded.getOrigin()).isEqualTo("10.0.0.1");
        assertThat(loaded.getAction()).isEqualTo("webhook.create");
        assertThat(loaded.getResource()).isEqualTo("webhook:WH-1");
        assertThat(loaded.getBeforeHash()).isEqualTo("before-hash");
        assertThat(loaded.getAfterHash()).isEqualTo("after-hash");
        assertThat(loaded.getReason()).isEqualTo("created for integration test");
        assertThat(loaded.getResult()).isEqualTo("success");
        assertThat(loaded.getCreatedAt()).isEqualTo(1_700_000_000_000L);
    }
}
