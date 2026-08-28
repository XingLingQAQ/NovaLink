package com.nova.chat.mod.platform.brigadier;

/**
 * Debug info the platform shim extracts from its mod entry point for the
 * shared {@code /novachat debug} body. Carries only primitive values so the
 * common layer never touches Minecraft types or the platform-specific
 * {@code NetworkClient} / {@code ModConfig} directly.
 */
public record ModDebugInfo(
        String backendHost,
        int backendPort,
        boolean connected,
        boolean replaceVanillaChat
) {}
