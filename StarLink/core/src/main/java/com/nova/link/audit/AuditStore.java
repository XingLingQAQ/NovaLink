package com.nova.link.audit;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Append-only audit log facade over {@link DatabaseProvider}.
 *
 * <p>PANEL-006: every P1 admin mutation routes through {@link #record} which
 * persists an {@link AuditEvent} to the {@code audit_events} table. The store
 * deliberately swallows {@link DatabaseException}: a persistence failure must
 * never block the business operation that triggered the audit (the action has
 * already happened by the time we record it). The failure is logged at WARN
 * so operators can detect a degraded audit trail without losing service.
 *
 * <p>Reads ({@link #list} and {@link #count}) also degrade to empty/zero on
 * failure so the panel's audit tab stays usable when the backing store is
 * briefly unavailable.
 *
 * <p>Requirements: PANEL-006 audit log
 */
public class AuditStore {

    private static final Logger logger = LoggerFactory.getLogger(AuditStore.class);

    private final DatabaseProvider databaseProvider;

    public AuditStore(DatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
    }

    /**
     * Persists an audit event. The event is treated as append-only: there is
     * no update or delete path. A persistence failure is logged but never
     * propagated, so a failing audit store cannot block the mutation that
     * triggered the record.
     *
     * @param event the event to persist (not null)
     */
    public void record(AuditEvent event) {
        if (databaseProvider == null || event == null) {
            return;
        }
        try {
            databaseProvider.saveAuditEvent(event);
        } catch (DatabaseException e) {
            logger.warn("Failed to persist audit event action={} actor={}: {}",
                    event.getAction(), event.getActor(), e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Unexpected error persisting audit event action={} actor={}: {}",
                    event.getAction(), event.getActor(), e.getMessage());
        }
    }

    /**
     * Lists audit events with pagination and optional actor/action filters.
     * Returns the newest events first. An empty list is returned on failure
     * so the panel can still render the audit tab.
     *
     * @param offset 0-based offset
     * @param limit  maximum number of events to return
     * @param actor  optional actor filter (null/empty = no filter)
     * @param action optional action filter (null/empty = no filter)
     * @return list of audit events, newest first, never null
     */
    public List<AuditEvent> list(int offset, int limit, String actor, String action) {
        if (databaseProvider == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.getAuditEvents(offset, limit, actor, action);
        } catch (DatabaseException e) {
            logger.warn("Failed to list audit events: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.warn("Unexpected error listing audit events: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Counts audit events matching the optional actor/action filters. Used to
     * report the real total for paginated audit listings. Returns 0 on
     * failure.
     *
     * @param actor  optional actor filter (null/empty = no filter)
     * @param action optional action filter (null/empty = no filter)
     * @return the total number of matching events, or 0 on failure
     */
    public int count(String actor, String action) {
        if (databaseProvider == null) {
            return 0;
        }
        try {
            return databaseProvider.countAuditEvents(actor, action);
        } catch (DatabaseException e) {
            logger.warn("Failed to count audit events: {}", e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            logger.warn("Unexpected error counting audit events: {}", e.getMessage());
            return 0;
        }
    }
}
