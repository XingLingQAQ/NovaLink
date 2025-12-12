package com.nova.link.config;

/**
 * Exception thrown when configuration loading or parsing fails.
 */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
