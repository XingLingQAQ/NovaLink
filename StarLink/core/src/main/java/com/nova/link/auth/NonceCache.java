package com.nova.link.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of pending challenge-response handshakes (AUTH-002).
 *
 * <p>When the server receives a {@code HandshakeInit} (0x15) it generates a
 * fresh server nonce and stores a {@link PendingChallenge} keyed by
 * {@code clientId + clientNonce}. When the matching
 * {@code HandshakeAuthenticate} (0x17) arrives, the entry is
 * {@link #consume(String, String) consumed atomically} — a second attempt to
 * use the same nonce pair (a replay) finds nothing and is rejected.
 *
 * <p>Bounds:
 * <ul>
 *   <li>{@code maxSize = 10000} — once exceeded, oldest entries are evicted
 *       alongside expired ones during {@link #cleanup()}.</li>
 *   <li>{@code ttlMillis = 30_000} — a pending challenge that is not completed
 *       within 30 seconds expires and cannot be used.</li>
 * </ul>
 *
 * <p>This is deliberately dependency-free (no Caffeine) so the auth module
 * stays self-contained. Concurrency: entries are inserted and removed via
 * {@link ConcurrentHashMap} atomic {@code compute}/{@code remove} so two
 * racing authenticate packets for the same nonce pair cannot both win.
 */
public final class NonceCache {

    private static final Logger logger = LoggerFactory.getLogger(NonceCache.class);

    public static final int DEFAULT_MAX_SIZE = 10_000;
    public static final long DEFAULT_TTL_MILLIS = 30_000L;

    private final int maxSize;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, PendingChallenge> entries = new ConcurrentHashMap<>();

    public NonceCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS);
    }

    public NonceCache(int maxSize, long ttlMillis) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Records a pending challenge. If an entry already exists for this
     * (clientId, clientNonce) pair it is overwritten — the old challenge
     * becomes unusable, which is the safe outcome for a duplicate init.
     *
     * @param clientId     the client id from the init packet
     * @param clientNonce  the client nonce from the init packet
     * @param serverNonce  the freshly generated server nonce to remember
     */
    public void put(String clientId, String clientNonce, String serverNonce) {
        if (clientId == null || clientNonce == null || serverNonce == null) {
            throw new IllegalArgumentException("clientId/clientNonce/serverNonce must not be null");
        }
        String key = key(clientId, clientNonce);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        entries.put(key, new PendingChallenge(serverNonce, expiresAt));
        if (entries.size() > maxSize) {
            // Best-effort trim: drop expired entries first, then the oldest
            // survivors. size() on CHM is a weak estimate; the trimmer is
            // advisory, not a hard cap, but it keeps a flood of init packets
            // from growing the map without bound.
            cleanup();
            if (entries.size() > maxSize) {
                trimOldest(entries.size() - maxSize);
            }
        }
    }

    /**
     * Atomically consumes the pending challenge for this
     * (clientId, clientNonce) pair. Returns the stored server nonce if a
     * non-expired entry existed, or {@code null} if none existed, the entry
     * had expired, or a concurrent consume already claimed it.
     *
     * <p>The remove is atomic: two concurrent calls for the same key cannot
     * both receive the nonce.
     *
     * @param clientId    the client id from the authenticate packet
     * @param clientNonce the client nonce from the authenticate packet
     * @return the server nonce to verify the HMAC against, or {@code null}
     */
    public String consume(String clientId, String clientNonce) {
        if (clientId == null || clientNonce == null) {
            return null;
        }
        String key = key(clientId, clientNonce);
        PendingChallenge entry = entries.remove(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() >= entry.expiresAt) {
            // Expired — treat as not present so the auth fails with NC-401.
            return null;
        }
        return entry.serverNonce;
    }

    /** @return the current number of pending (possibly expired) entries */
    public int size() {
        return entries.size();
    }

    /** @return the configured maximum size */
    public int getMaxSize() {
        return maxSize;
    }

    /** @return the configured TTL in milliseconds */
    public long getTtlMillis() {
        return ttlMillis;
    }

    /**
     * Drops all expired entries. Safe to call from anywhere; also invoked
     * internally when the map exceeds {@link #maxSize}.
     *
     * @return the number of expired entries removed
     */
    public int cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, PendingChallenge>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingChallenge> e = it.next();
            PendingChallenge pc = e.getValue();
            if (pc == null || now >= pc.expiresAt) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger.debug("NonceCache cleaned up {} expired pending challenges", removed);
        }
        return removed;
    }

    /** Removes all entries. Used by tests. */
    public void clear() {
        entries.clear();
    }

    private void trimOldest(int count) {
        if (count <= 0) {
            return;
        }
        // PendingChallenge is not Comparable to avoid a Comparable dependency
        // in the hot path; for the rare overflow path we sort by expiry.
        entries.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue() != null ? e.getValue().expiresAt : Long.MAX_VALUE))
                .limit(count)
                .forEach(e -> entries.remove(e.getKey()));
    }

    private static String key(String clientId, String clientNonce) {
        return clientId + "|" + clientNonce;
    }

    /** Immutable record of a pending challenge's server nonce and its expiry. */
    private static final class PendingChallenge {
        final String serverNonce;
        final long expiresAt;

        PendingChallenge(String serverNonce, long expiresAt) {
            this.serverNonce = serverNonce;
            this.expiresAt = expiresAt;
        }
    }
}
