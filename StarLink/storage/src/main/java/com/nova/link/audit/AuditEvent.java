package com.nova.link.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable record of a single admin action taken through the REST API.
 *
 * <p>PANEL-006: every P1 mutation (channel create/update/delete, mute, ban,
 * webhook create/update/delete, reload, settings update) appends one of these
 * to the append-only {@code audit_events} table via {@link AuditStore}. The
 * record carries enough context to answer "who did what to which resource,
 * when, and from where" without ever persisting raw secrets: {@code beforeHash}
 * and {@code afterHash} are SHA-256 hex digests of the JSON representation of
 * the affected resource <em>after</em> secrets (channel password, webhook
 * secret, tokens) have been stripped by the caller.
 *
 * <p>The {@code requestId} field threads the same correlation id that is
 * stamped on the REST response ({@code X-Request-Id} header) and on webhook
 * deliveries, so a single admin action can be traced across logs, the audit
 * log, and downstream webhook consumers.
 *
 * <p>Requirements: PANEL-006 audit log
 */
public final class AuditEvent {

    private final long id;
    private final String eventId;
    private final String requestId;
    private final String actor;
    private final String role;
    private final String origin;
    private final String action;
    private final String resource;
    private final String beforeHash;
    private final String afterHash;
    private final String reason;
    private final String result;
    private final long createdAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (id is the assigned database id).
     *
     * @param id          the database-assigned id (0 when not yet persisted)
     * @param eventId     a client-visible UUID string identifying this event
     * @param requestId   the correlated request id (may be null)
     * @param actor       the panel username that performed the action
     * @param role        the resolved PanelRole name at action time
     * @param origin      the originating IP/host (may be null)
     * @param action      a stable action code (e.g. {@code channel.create})
     * @param resource    a human-readable resource identifier (may be null)
     * @param beforeHash  SHA-256 hex of the pre-action resource state (may be null)
     * @param afterHash   SHA-256 hex of the post-action resource state (may be null)
     * @param reason      a free-form reason supplied by the caller (may be null)
     * @param result      {@code "success"} or {@code "failure"}
     * @param createdAt   epoch millis at which the action was recorded
     */
    public AuditEvent(long id, String eventId, String requestId, String actor,
                      String role, String origin, String action, String resource,
                      String beforeHash, String afterHash, String reason,
                      String result, long createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.requestId = requestId;
        this.actor = actor;
        this.role = role;
        this.origin = origin;
        this.action = action;
        this.resource = resource;
        this.beforeHash = beforeHash;
        this.afterHash = afterHash;
        this.reason = reason;
        this.result = result;
        this.createdAt = createdAt;
    }

    /**
     * Convenience constructor for new (not-yet-persisted) events. The id is
     * set to 0 and will be assigned by the store layer on save.
     */
    public AuditEvent(String eventId, String requestId, String actor, String role,
                      String origin, String action, String resource,
                      String beforeHash, String afterHash, String reason,
                      String result, long createdAt) {
        this(0L, eventId, requestId, actor, role, origin, action, resource,
                beforeHash, afterHash, reason, result, createdAt);
    }

    public long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getActor() {
        return actor;
    }

    public String getRole() {
        return role;
    }

    public String getOrigin() {
        return origin;
    }

    public String getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    public String getBeforeHash() {
        return beforeHash;
    }

    public String getAfterHash() {
        return afterHash;
    }

    public String getReason() {
        return reason;
    }

    public String getResult() {
        return result;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditEvent)) {
            return false;
        }
        AuditEvent that = (AuditEvent) o;
        return id == that.id
                && createdAt == that.createdAt
                && Objects.equals(eventId, that.eventId)
                && Objects.equals(requestId, that.requestId)
                && Objects.equals(actor, that.actor)
                && Objects.equals(role, that.role)
                && Objects.equals(origin, that.origin)
                && Objects.equals(action, that.action)
                && Objects.equals(resource, that.resource)
                && Objects.equals(beforeHash, that.beforeHash)
                && Objects.equals(afterHash, that.afterHash)
                && Objects.equals(reason, that.reason)
                && Objects.equals(result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, eventId, requestId, actor, role, origin, action,
                resource, beforeHash, afterHash, reason, result, createdAt);
    }

    @Override
    public String toString() {
        return "AuditEvent{eventId=" + eventId
                + ", action=" + action
                + ", resource=" + resource
                + ", actor=" + actor
                + ", result=" + result
                + ", createdAt=" + createdAt + '}';
    }

    /**
     * Computes the SHA-256 hex digest of the given JSON string. Used by
     * callers to produce {@code beforeHash}/{@code afterHash} values from the
     * JSON form of a resource <em>after</em> any secret fields have been
     * removed. Returns {@code null} for null input so callers can pass through
     * a null pre/post state (e.g. for create/delete) without a special case.
     *
     * @param json the JSON payload to hash, or null
     * @return lowercase SHA-256 hex, or null if {@code json} is null
     */
    public static String hashJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA; this is effectively unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
