package com.nova.chat.proxy.config;

import java.util.Map;

/**
 * Dialect-neutral view over a proxy configuration document.
 *
 * <p>Velocity reads {@code config.toml} through toml4j; BungeeCord reads
 * {@code config.yml} through md_5's {@code Configuration}. Both documents carry
 * the same schema ({@code backend.*}, {@code chat.*}, {@code format.*},
 * {@code debug}), so the model ({@link ProxyConfig}) and its validation live here
 * and each shell supplies only this thin accessor port.
 *
 * <p>Keys are the literal dot paths used by the templates
 * ({@code "backend.host"}, {@code "chat.replace_vanilla"}, ...). Table-valued
 * settings are exposed as raw maps so {@link ProxyConfig} owns the per-entry type
 * checks and both dialects reject the same documents for the same reasons.
 */
public interface ConfigSource {

    /**
     * @param key dot path of the setting
     * @return the string value
     * @throws ConfigException when the key is missing or not a string
     */
    String requiredString(String key) throws ConfigException;

    /**
     * @param key dot path of the setting
     * @return the integer value
     * @throws ConfigException when the key is missing or not an integer
     */
    int requiredInt(String key) throws ConfigException;

    /**
     * @param key dot path of the setting
     * @return the boolean value
     * @throws ConfigException when the key is missing or not a boolean
     */
    boolean requiredBoolean(String key) throws ConfigException;

    /**
     * Returns the raw entries of a table/section.
     *
     * <p>Both bundled templates always ship the {@code chat.channel-prefixes}
     * and {@code format.channels} tables (possibly empty), so an absent table is
     * a configuration error rather than an empty map — this matches the
     * historical per-platform loaders, which required the section to exist.
     *
     * @param key dot path of the table
     * @return entry key → raw value, never null
     * @throws ConfigException when the table is missing or is not a table
     */
    Map<String, Object> requiredTable(String key) throws ConfigException;

    /**
     * Reports whether a key is present in the document.
     *
     * <p>Needed because the two dialects disagree about missing keys: toml4j
     * returns {@code null} for an absent table, while BungeeCord's
     * {@code Configuration#getSection} returns an empty section for one. The
     * shared model must not read a key that is absent and treat the dialect's
     * placeholder as a configured value.
     *
     * @param key dot path of the setting
     * @return {@code true} when the document defines the key
     * @throws ConfigException when the document cannot be inspected
     */
    boolean contains(String key) throws ConfigException;

    /**
     * @param key dot path of the setting
     * @return the string value, or {@code null} when the key is absent
     * @throws ConfigException when the key is present but not a string
     */
    default String optionalString(String key) throws ConfigException {
        return contains(key) ? requiredString(key) : null;
    }

    /**
     * @param key dot path of the table
     * @return the table's entries, or {@code null} when the key is absent
     * @throws ConfigException when the key is present but not a table
     */
    default Map<String, Object> optionalTable(String key) throws ConfigException {
        return contains(key) ? requiredTable(key) : null;
    }

    /**
     * Raised when the document cannot satisfy the requested key.
     */
    class ConfigException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message the failure description (includes the key path)
         */
        public ConfigException(String message) {
            super(message);
        }

        /**
         * @param message the failure description
         * @param cause the underlying dialect exception
         */
        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
