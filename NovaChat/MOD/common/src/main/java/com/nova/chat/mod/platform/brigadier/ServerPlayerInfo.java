package com.nova.chat.mod.platform.brigadier;

/**
 * Minimal player identity extracted from a brigadier source by the
 * loader-specific {@code ServerPlayerLookup}. Carries just the UUID + name the
 * shared command path needs; the full Minecraft {@code ServerPlayer} reference
 * stays in the platform shim so the common layer never depends on a Minecraft
 * type.
 */
public record ServerPlayerInfo(java.util.UUID uuid, String name) {}
