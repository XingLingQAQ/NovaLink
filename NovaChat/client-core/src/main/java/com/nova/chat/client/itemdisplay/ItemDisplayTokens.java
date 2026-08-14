package com.nova.chat.client.itemdisplay;

import com.google.gson.JsonObject;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared send-side logic for the chat {@code [item]}/{@code [i]} display play:
 * token detection, per-player rate limiting, and construction of the minimal
 * {@code itemJson} payload / {@link ItemDisplayPacket}.
 *
 * <p><b>Token semantics</b> are aligned with the Bedrock reference clients:
 * the pattern is {@code \[(item|i)\]} case-insensitive (pmmp
 * {@code ItemDisplayRenderer::ITEM_PATTERN}, endstone
 * {@code chat/item_display.py — ITEM_PATTERN}). A message with one or more
 * tokens triggers exactly one item display; the permission gate is the shared
 * {@code novachat.feature.item} node (Requirements 12.5: without permission
 * the tokens stay plain text).
 *
 * <p><b>Rate limiting</b>: the Bedrock reference implementations carry no
 * explicit item-display rate limit, so this class adopts the in-repo anti-spam
 * precedent — {@code MentionNotifier.DEDUP_INTERVAL_MS} (3s) — as a per-player
 * cooldown. Within the window only the first {@code [item]} send goes out;
 * subsequent tokens are left as plain text (same degradation as the
 * no-permission path). State-eviction mirrors {@code MentionNotifier}.
 *
 * <p>The {@code itemJson} payload carries only the display fields defined by
 * the protocol golden samples ({@code id} / {@code count} / optional
 * {@code name}) — never full item NBT.
 *
 * <p>Thread-safe: cooldown state is synchronized on its map, matching the
 * {@code MentionNotifier} implementation. Platforms create one instance per
 * chat interceptor (same lifecycle as their {@code MentionNotifier}).
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
public final class ItemDisplayTokens {

    /**
     * Token pattern — identical semantics to the Bedrock reference
     * ({@code /\[(item|i)\]/i}).
     */
    public static final Pattern TOKEN_PATTERN =
            Pattern.compile("\\[(item|i)\\]", Pattern.CASE_INSENSITIVE);

    /** Permission node gating the play — same node as pmmp/endstone. */
    public static final String PERMISSION_ITEM = "novachat.feature.item";

    /**
     * Minimum interval (milliseconds) between two item displays from the same
     * player. Mirrors {@code MentionNotifier.DEDUP_INTERVAL_MS}; the Bedrock
     * reference has no explicit limit, so the shared in-repo anti-spam window
     * is used.
     */
    public static final long COOLDOWN_MS = 3_000L;

    /** Identifier used for the empty-hand payload (Bedrock renders "Empty"). */
    public static final String EMPTY_ITEM_ID = "minecraft:air";

    /**
     * Per-player cooldown state: player id → timestamp of the last emitted
     * item display. Access is synchronized on the map (MentionNotifier style).
     */
    private final Map<UUID, Long> lastSentAt = new HashMap<>();

    /**
     * Checks whether a message contains at least one {@code [item]}/{@code [i]}
     * token (case-insensitive).
     *
     * @param message the raw chat message; null/empty → false
     * @return true when the message carries an item token
     */
    public static boolean hasItemToken(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return TOKEN_PATTERN.matcher(message).find();
    }

    /**
     * Convenience overload of {@link #tryAcquire(UUID, long)} using the wall
     * clock.
     *
     * @param playerId the sending player
     * @return true when the send is allowed (and the cooldown was recorded)
     */
    public boolean tryAcquire(UUID playerId) {
        return tryAcquire(playerId, System.currentTimeMillis());
    }

    /**
     * Side-effecting rate-limit check: returns true (and records the send) when
     * the player is outside the {@link #COOLDOWN_MS} window, false when the
     * send must be suppressed. Expired entries are evicted on each call so the
     * map does not grow with the player count.
     *
     * @param playerId the sending player
     * @param now      the current time in Unix milliseconds
     * @return true when the send is allowed
     */
    public boolean tryAcquire(UUID playerId, long now) {
        Objects.requireNonNull(playerId, "playerId cannot be null");
        synchronized (lastSentAt) {
            lastSentAt.entrySet().removeIf(entry -> (now - entry.getValue()) >= COOLDOWN_MS);
            Long last = lastSentAt.get(playerId);
            if (last != null && (now - last) < COOLDOWN_MS) {
                return false;
            }
            lastSentAt.put(playerId, now);
            return true;
        }
    }

    /** Clears all cooldown state. Useful for tests and plugin reloads. */
    public void clearCooldowns() {
        synchronized (lastSentAt) {
            lastSentAt.clear();
        }
    }

    /**
     * Builds the minimal display payload: {@code id} + {@code count} + optional
     * {@code name}. Platforms feed the fields from their native item API; no
     * NBT is serialized.
     *
     * @param id         the (namespaced) item identifier; null/blank → air
     * @param count      the stack size; negative values clamp to 0
     * @param customName the custom / display name, or null when absent
     * @return the JSON payload string
     */
    public static String buildItemJson(String id, int count, String customName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id != null && !id.isBlank() ? id : EMPTY_ITEM_ID);
        obj.addProperty("count", Math.max(0, count));
        if (customName != null && !customName.isBlank()) {
            obj.addProperty("name", customName);
        }
        return obj.toString();
    }

    /**
     * Payload for an empty hand. Aligned with the Bedrock renderers, which show
     * the "Empty" placeholder instead of suppressing the display.
     *
     * @return the empty-hand JSON payload
     */
    public static String emptyHandJson() {
        return buildItemJson(EMPTY_ITEM_ID, 0, null);
    }

    /**
     * Builds the outbound packet with the current wall-clock timestamp.
     *
     * @param senderId   the sending player's UUID
     * @param senderName the sending player's display name
     * @param channelId  the player's active channel
     * @param itemJson   the payload built via {@link #buildItemJson}
     * @return the packet ready for {@code NetworkClient.sendPacket}
     */
    public static ItemDisplayPacket buildPacket(UUID senderId, String senderName,
                                                String channelId, String itemJson) {
        return new ItemDisplayPacket(senderId, senderName, channelId, itemJson,
                System.currentTimeMillis());
    }
}
