package com.nova.chat.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NovaChat Mod - Common module entry point
 * This module contains platform-independent code shared across all mod loaders
 */
public class NovaChatMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(NovaChatMod.class);
    
    public static final String MOD_ID = "novachat";
    public static final String MOD_NAME = "NovaChat";
    public static final String MOD_VERSION = "1.0.0";
    
    public static void init() {
        LOGGER.info("Initializing NovaChat Mod v{}", MOD_VERSION);
    }
}
