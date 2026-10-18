package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.proxy.config.ConfigSource;

import java.util.Map;

/**
 * {@link ConfigSource} over a parsed toml4j document.
 *
 * <p>Navigation is done on {@link Toml#toMap()} rather than toml4j's dotted
 * getters so quoted keys ({@code "!" = "global"}, {@code "global" = { ... }})
 * resolve the same way the BungeeCord dialect resolves plain YAML keys. toml4j
 * keeps the quotes in the map keys, so each path segment is looked up in its
 * bare form first and then in its quoted form.
 *
 * <p>Values keep toml4j's Java types: {@code Long} for integers, {@code String},
 * {@code Boolean}, and {@code Map<String, Object>} (a {@code HashMap}) for
 * tables.
 */
final class TomlConfigSource implements ConfigSource {

    private final Map<String, Object> root;

    /**
     * @param toml the parsed document
     */
    TomlConfigSource(Toml toml) {
        this.root = toml.toMap();
    }

    @Override
    public String requiredString(String key) throws ConfigException {
        Object value = lookup(key);
        if (value == null) {
            throw new ConfigException("Missing required configuration value: " + key);
        }
        if (!(value instanceof String text)) {
            throw new ConfigException("Configuration value " + key + " must be a string");
        }
        return text;
    }

    @Override
    public int requiredInt(String key) throws ConfigException {
        Object value = lookup(key);
        if (value == null) {
            throw new ConfigException("Missing required configuration value: " + key);
        }
        return toInt(value, key);
    }

    @Override
    public boolean requiredBoolean(String key) throws ConfigException {
        Object value = lookup(key);
        if (value == null) {
            throw new ConfigException("Missing or invalid boolean configuration value: " + key);
        }
        if (!(value instanceof Boolean flag)) {
            throw new ConfigException("Configuration value " + key + " must be a boolean");
        }
        return flag;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> requiredTable(String key) throws ConfigException {
        Object value = lookup(key);
        if (value == null) {
            throw new ConfigException("Missing required configuration table: " + key);
        }
        if (!(value instanceof Map)) {
            throw new ConfigException("Configuration value " + key + " must be a mapping");
        }
        return (Map<String, Object>) value;
    }

    @Override
    public boolean contains(String key) {
        return lookup(key) != null;
    }

    /**
     * Resolves a dot path against the map tree.
     *
     * <p>Segments are matched bare first, then wrapped in double quotes, which
     * is how toml4j reports a quoted key in {@link Toml#toMap()}. A key that
     * itself contains a dot cannot be addressed this way, matching the dotted
     * getters the loaders used before.
     *
     * @param key the dot path
     * @return the raw value, or null when absent
     */
    private Object lookup(String key) {
        Object current = root;
        for (String segment : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            if (map.containsKey(segment)) {
                current = map.get(segment);
            } else if (map.containsKey("\"" + segment + "\"")) {
                current = map.get("\"" + segment + "\"");
            } else if (map.containsKey("'" + segment + "'")) {
                current = map.get("'" + segment + "'");
            } else {
                return null;
            }
        }
        return current;
    }

    private static int toInt(Object value, String key) throws ConfigException {
        if (!(value instanceof Number number)) {
            throw new ConfigException("Configuration value " + key + " must be an integer");
        }
        long longValue = number.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new ConfigException("Configuration value " + key + " must be an integer");
        }
        return (int) longValue;
    }
}
