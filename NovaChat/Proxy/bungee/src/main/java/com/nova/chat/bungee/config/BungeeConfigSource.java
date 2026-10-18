package com.nova.chat.bungee.config;

import com.nova.chat.proxy.config.ConfigSource;
import net.md_5.bungee.config.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ConfigSource} over an md_5 {@link Configuration} tree.
 *
 * <p>Navigation walks the {@link Configuration} object graph rather than using
 * dotted {@code get} calls: {@code getSection} returns an empty section for an
 * absent key, so existence has to be decided with {@link #contains} at each
 * level before descending.
 *
 * <p>Values are normalised to the shapes {@code ProxyConfig} expects:
 * integers become {@code Long} (the toml4j shape), sections become
 * {@code Map<String, Object>}.
 */
final class BungeeConfigSource implements ConfigSource {

    private final Configuration root;

    /**
     * @param root the loaded configuration
     */
    BungeeConfigSource(Configuration root) {
        this.root = root;
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
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < Integer.MIN_VALUE
                || number.doubleValue() > Integer.MAX_VALUE) {
            throw new ConfigException("Configuration value " + key + " must be an integer");
        }
        return number.intValue();
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
    public Map<String, Object> requiredTable(String key) throws ConfigException {
        Object value = lookup(key);
        if (value == null) {
            throw new ConfigException("Missing required configuration table: " + key);
        }
        if (!(value instanceof Configuration section)) {
            throw new ConfigException("Configuration value " + key + " must be a mapping");
        }
        return toMap(section);
    }

    @Override
    public boolean contains(String key) {
        return lookup(key) != null;
    }

    /**
     * @param key the dot path
     * @return the raw leaf value, or null when absent
     */
    private Object lookup(String key) {
        Configuration current = root;
        String[] segments = key.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            boolean last = i == segments.length - 1;
            if (last) {
                return current.contains(segments[i]) ? current.get(segments[i]) : null;
            }
            if (!current.contains(segments[i])) {
                return null;
            }
            Object next = current.get(segments[i]);
            if (!(next instanceof Configuration section)) {
                // A scalar in the middle of a path cannot contain the rest; treat
                // it as absent so callers report the leaf key rather than a cast.
                return null;
            }
            current = section;
        }
        return null;
    }

    /**
     * Converts a section to a plain map, recursively, so the shared model can
     * iterate it exactly like the toml4j map (which is likewise nested maps all
     * the way down). Integers become {@code Long} to match toml4j's shape.
     *
     * @param section the section
     * @return key → raw value (nested sections as {@code Map})
     */
    private static Map<String, Object> toMap(Configuration section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys()) {
            Object value = section.get(key);
            if (value instanceof Configuration nested) {
                values.put(key, toMap(nested));
            } else if (value instanceof Integer integer) {
                values.put(key, integer.longValue());
            } else {
                values.put(key, value);
            }
        }
        return values;
    }
}
