package com.nova.link.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

final class JdbcStringCollections {
    private JdbcStringCollections() {}

    static String encode(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(String.valueOf(value));
        }
        return array.toString();
    }

    static List<String> decode(String value, int format) throws SQLException {
        if (format == 0) {
            return value == null || value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
        }
        if (format != 1) {
            throw new SQLException("Unsupported string collection format: " + format);
        }
        try {
            JsonArray array = JsonParser.parseString(value).getAsJsonArray();
            List<String> values = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Expected a string collection");
                }
                values.add(element.getAsString());
            }
            return values;
        } catch (RuntimeException error) {
            throw new SQLException("Invalid JSON string collection", error);
        }
    }
}
