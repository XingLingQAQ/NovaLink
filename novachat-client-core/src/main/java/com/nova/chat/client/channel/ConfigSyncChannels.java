package com.nova.chat.client.channel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts the set of channel IDs the backend advertised in a
 * {@code ConfigSyncPacket} payload (UX-DESIGN §2.1).
 *
 * <p>Shared so every platform facade parses ConfigSync the same way instead of
 * re-implementing the bukkit-only parser. Returns the union of
 * {@code global_channels} keys and the {@code channels} keys of the client
 * whose {@code username} matches {@code thisClientUsername} (null / blank =
 * global channels only). Best-effort: returns an empty set on any parse error.
 *
 * <p>This is a pure, platform-agnostic helper; callers feed it the raw
 * {@code configJson} string and the configured client username.
 */
public final class ConfigSyncChannels {

    private ConfigSyncChannels() {
        // Utility class — not instantiated.
    }

    /**
     * Extracts known channel IDs from a ConfigSync JSON payload.
     *
     * @param configJson         the raw config JSON (null / blank = empty set)
     * @param thisClientUsername this client's username filter, or null to skip
     *                           per-client channels and return only globals
     * @return the set of channel IDs (never null)
     */
    public static Set<String> extract(String configJson, String thisClientUsername) {
        Set<String> result = new HashSet<>();
        if (configJson == null || configJson.isBlank()) {
            return result;
        }
        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();

            // Global channels
            JsonObject gc = root.getAsJsonObject("global_channels");
            if (gc != null) {
                result.addAll(gc.keySet());
            }

            // Per-client channels for this client only
            if (thisClientUsername == null || thisClientUsername.isBlank()) {
                return result;
            }
            if (!root.has("clients") || !root.get("clients").isJsonArray()) {
                return result;
            }

            JsonArray clients = root.getAsJsonArray("clients");
            for (JsonElement element : clients) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject client = element.getAsJsonObject();
                String username = client.has("username") ? safeString(client.get("username")) : null;
                if (username == null || !username.equals(thisClientUsername)) {
                    continue;
                }
                if (client.has("channels") && client.get("channels").isJsonObject()) {
                    result.addAll(client.getAsJsonObject("channels").keySet());
                }
                break;
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return result;
    }

    private static String safeString(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            String s = element.getAsString();
            return (s != null && !s.isBlank()) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
