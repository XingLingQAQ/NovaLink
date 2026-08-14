package com.nova.chat.client.itemdisplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Platform-agnostic view of the {@code itemJson} payload carried by an
 * {@code ItemDisplayPacket} (0x10).
 *
 * <p>The wire schema is the minimal display schema used by the protocol golden
 * samples ({@code test/protocol-golden/item_display_typical.json}):
 * <pre>{"id":"minecraft:netherite_sword","count":1,"name":"&amp;bExcalibur"}</pre>
 * where {@code id} is the (namespaced) item identifier, {@code count} the stack
 * size and {@code name} an optional custom / display name. Only these display
 * fields transit the wire — never full item NBT.
 *
 * <p>Emptiness follows the Bedrock reference semantics
 * ({@code endstone/novachat_endstone/chat/item_display.py — ItemData.is_empty}):
 * a blank or air id, or a non-positive count, renders as the localized "empty
 * hand" placeholder.
 */
public final class ItemDisplayInfo {

    private final String id;
    private final String name;
    private final int count;

    private ItemDisplayInfo(String id, String name, int count) {
        this.id = id != null ? id : "";
        this.name = name;
        this.count = count;
    }

    /**
     * Parses an {@code itemJson} payload leniently.
     *
     * <p>Robustness contract (receive side must never throw on remote input):
     * <ul>
     *   <li>null / blank → empty item (air)</li>
     *   <li>valid JSON object → {@code id} / {@code name} / {@code count} fields
     *       (missing count defaults to 1)</li>
     *   <li>anything else (malformed JSON, non-object JSON) → the raw trimmed
     *       string is used as the display name, mirroring how the Bedrock
     *       clients fall back to rendering the raw payload inline</li>
     * </ul>
     *
     * @param itemJson the raw payload; may be null
     * @return a non-null parsed view
     */
    public static ItemDisplayInfo fromJson(String itemJson) {
        if (itemJson == null || itemJson.isBlank()) {
            return new ItemDisplayInfo("", null, 0);
        }
        try {
            JsonElement root = JsonParser.parseString(itemJson);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                String id = stringField(obj, "id");
                String name = stringField(obj, "name");
                int count = 1;
                if (obj.has("count") && obj.get("count").isJsonPrimitive()) {
                    try {
                        count = obj.get("count").getAsInt();
                    } catch (NumberFormatException ignored) {
                        count = 1;
                    }
                }
                return new ItemDisplayInfo(id, name, count);
            }
        } catch (Exception ignored) {
            // fall through to the raw-string fallback below
        }
        return new ItemDisplayInfo("", itemJson.trim(), 1);
    }

    private static String stringField(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** @return the (namespaced) item identifier; never null, may be empty */
    public String getId() {
        return id;
    }

    /** @return the custom / display name, or null when the item has none */
    public String getName() {
        return name;
    }

    /** @return the stack size */
    public int getCount() {
        return count;
    }

    /**
     * Empty-hand check aligned with the Bedrock reference
     * ({@code ItemData.is_empty}): blank id, air id, or non-positive count.
     *
     * @return true when this item should render as the empty placeholder
     */
    public boolean isEmpty() {
        boolean noName = name == null || name.isBlank();
        if (count <= 0) {
            return true;
        }
        if (id.isBlank()) {
            return noName;
        }
        String bare = id.toLowerCase(Locale.ROOT);
        int colon = bare.indexOf(':');
        if (colon >= 0) {
            bare = bare.substring(colon + 1);
        }
        return "air".equals(bare) && noName;
    }

    /**
     * Resolves the human-readable name: the custom name when present, otherwise
     * the prettified id (namespace stripped, underscores split, words
     * capitalized — mirrors the Bedrock {@code _format_type_name} helper).
     *
     * @return the display name; never null
     */
    public String resolveDisplayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return prettifyId(id);
    }

    /**
     * Converts an item id into a readable name:
     * {@code "minecraft:netherite_sword"} → {@code "Netherite Sword"}.
     *
     * @param itemId the raw id; may be null/blank
     * @return the prettified name, or {@code "Unknown"} for blank input
     */
    public static String prettifyId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Unknown";
        }
        String bare = itemId;
        int colon = bare.indexOf(':');
        if (colon >= 0) {
            bare = bare.substring(colon + 1);
        }
        String[] parts = bare.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.length() > 0 ? sb.toString() : "Unknown";
    }

    @Override
    public String toString() {
        return "ItemDisplayInfo{id='" + id + "', name='" + name + "', count=" + count + '}';
    }
}
