package com.nova.chat.common.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.Objects;

/**
 * Serializer for converting ItemData to/from JSON format.
 * 
 * This class provides platform-agnostic serialization of item data
 * for transmission across servers via NovaProtocol.
 * 
 * **Feature: novachat-platform-extensions, Property 5: Item Serialization Round-Trip**
 * **Validates: Requirements 12.2**
 */
public class ItemSerializer {

    /** Maximum allowed JSON string length to prevent abuse */
    public static final int MAX_JSON_LENGTH = 65536; // 64KB

    /** Gson instance for JSON serialization */
    private final Gson gson;

    /** Gson instance for pretty-printed JSON (for debugging) */
    private final Gson prettyGson;

    /**
     * Creates a new ItemSerializer with default configuration.
     */
    public ItemSerializer() {
        this.gson = new GsonBuilder()
                .disableHtmlEscaping()
                .create();
        this.prettyGson = new GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Serializes an ItemData object to JSON string.
     * 
     * @param item the item data to serialize
     * @return JSON string representation
     * @throws IllegalArgumentException if item is null
     */
    public String serialize(ItemData item) {
        Objects.requireNonNull(item, "item cannot be null");
        return gson.toJson(item);
    }

    /**
     * Serializes an ItemData object to pretty-printed JSON string.
     * Useful for debugging and logging.
     * 
     * @param item the item data to serialize
     * @return pretty-printed JSON string representation
     * @throws IllegalArgumentException if item is null
     */
    public String serializePretty(ItemData item) {
        Objects.requireNonNull(item, "item cannot be null");
        return prettyGson.toJson(item);
    }

    /**
     * Deserializes a JSON string to ItemData object.
     * 
     * @param json the JSON string to deserialize
     * @return the deserialized ItemData object
     * @throws ItemSerializationException if deserialization fails
     * @throws IllegalArgumentException if json is null or empty
     */
    public ItemData deserialize(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("json cannot be null or empty");
        }
        
        if (json.length() > MAX_JSON_LENGTH) {
            throw new ItemSerializationException(
                "JSON string exceeds maximum length: " + json.length() + " > " + MAX_JSON_LENGTH);
        }

        try {
            ItemData item = gson.fromJson(json, ItemData.class);
            if (item == null) {
                throw new ItemSerializationException("Deserialization returned null");
            }
            return item;
        } catch (JsonSyntaxException e) {
            throw new ItemSerializationException("Invalid JSON format: " + e.getMessage(), e);
        }
    }

    /**
     * Safely deserializes a JSON string to ItemData object.
     * Returns null instead of throwing an exception on failure.
     * 
     * @param json the JSON string to deserialize
     * @return the deserialized ItemData object, or null if deserialization fails
     */
    public ItemData deserializeSafe(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        if (json.length() > MAX_JSON_LENGTH) {
            return null;
        }

        try {
            return gson.fromJson(json, ItemData.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * Validates that a JSON string can be deserialized to ItemData.
     * 
     * @param json the JSON string to validate
     * @return true if the JSON is valid ItemData format
     */
    public boolean isValidJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        
        if (json.length() > MAX_JSON_LENGTH) {
            return false;
        }

        try {
            ItemData item = gson.fromJson(json, ItemData.class);
            return item != null;
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    /**
     * Creates a copy of an ItemData by serializing and deserializing.
     * Useful for ensuring deep copy of all fields.
     * 
     * @param item the item to copy
     * @return a deep copy of the item
     */
    public ItemData deepCopy(ItemData item) {
        if (item == null) {
            return null;
        }
        return deserialize(serialize(item));
    }

    /**
     * Checks if two ItemData objects are equivalent by comparing their JSON representations.
     * This handles cases where object equality might differ due to collection implementations.
     * 
     * @param item1 first item
     * @param item2 second item
     * @return true if the items serialize to equivalent JSON
     */
    public boolean areEquivalent(ItemData item1, ItemData item2) {
        if (item1 == item2) {
            return true;
        }
        if (item1 == null || item2 == null) {
            return false;
        }
        return serialize(item1).equals(serialize(item2));
    }

    /**
     * Creates an empty/air item data.
     * 
     * @return an ItemData representing air/empty slot
     */
    public static ItemData createEmpty() {
        ItemData item = new ItemData();
        item.setType("minecraft:air");
        item.setAmount(0);
        return item;
    }

    /**
     * Creates a simple item data with just type and amount.
     * 
     * @param type the item type (e.g., "minecraft:diamond")
     * @param amount the item amount
     * @return a new ItemData instance
     */
    public static ItemData createSimple(String type, int amount) {
        return new ItemData(type, amount);
    }
}
