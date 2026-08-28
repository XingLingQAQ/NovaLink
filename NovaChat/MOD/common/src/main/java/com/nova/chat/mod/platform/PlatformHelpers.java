package com.nova.chat.mod.platform;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Shared helpers used by the three mod-loader platform bridges
 * (FabricPlatform / NeoForgePlatform / QuiltPlatform) to deduplicate
 * loader-agnostic logic that was previously copy-pasted across the three
 * Platform.java files.
 *
 * <p>Everything here is Minecraft-API-free so it can live in
 * :NovaChat:MOD:common, which does not depend on the Minecraft jar (see
 * {@code NovaChat/MOD/common/build.gradle}). Callers on each platform pass
 * native Minecraft types ({@code net.minecraft.world.item.ItemStack}) as
 * {@code Object}; the reflective calls work identically on the strongly-typed
 * instance.
 */
public final class PlatformHelpers {

    private PlatformHelpers() {}

    /**
     * Valid Minecraft color/format code characters for {@code &X} translation.
     * Shared by the three platform bridges' {@code parseColorCodes(String)}
     * instance methods.
     */
    static final String COLOR_CODE_CHARS = "0123456789abcdefklmnorABCDEFKLMNOR";

    /**
     * Resolves the optional custom display name from a native item stack.
     * Returns {@code null} when the item has no custom name or the current
     * mapping/version does not expose {@code DataComponents.CUSTOM_NAME}.
     *
     * <p>MC 1.21.5+ moved custom names into the data-component model; the old
     * {@code hasCustomHoverName()} accessor was removed. This helper reads
     * {@code DataComponents.CUSTOM_NAME} reflectively so the same source
     * compiles across 1.20.x (legacy NBT items) and 1.21.x (data components)
     * without a version-split build. Any reflective failure degrades to
     * {@code null}.
     *
     * <p>The {@code nativeStack} parameter is typed {@code Object} so this
     * helper can live in :NovaChat:MOD:common (which has no Minecraft jar on
     * its compile classpath). Callers on each platform pass a
     * {@code net.minecraft.world.item.ItemStack} directly; the reflective
     * {@code get(Object)} / {@code getString()} calls work identically on the
     * strongly-typed instance. The {@code instanceof Component} check from the
     * original per-platform implementations is preserved via
     * {@code Class.forName("net.minecraft.network.chat.Component").isInstance(...)}
     * so a non-Component value returned by {@code stack.get(...)} still
     * degrades to {@code null} rather than blindly invoking {@code getString()}
     * on an unrelated type.
     *
     * @param nativeStack the native {@code net.minecraft.world.item.ItemStack}
     *                    (passed as {@code Object} so common need not depend
     *                    on the Minecraft jar)
     * @return the custom display name, or {@code null} if absent/unavailable
     */
    public static String resolveCustomItemName(Object nativeStack) {
        try {
            // DataComponents.CUSTOM_NAME was added in 1.20.5+. Use reflection so the
            // common source compiles on 1.20.2 (no DataComponents class) too.
            Class<?> dataComponentsClass = Class.forName(
                    "net.minecraft.core.component.DataComponents");
            Object customNameComponent = java.util.Arrays.stream(dataComponentsClass.getFields())
                    .filter(f -> "CUSTOM_NAME".equals(f.getName()))
                    .findFirst()
                    .map(f -> {
                        try { return f.get(null); }
                        catch (Exception e) { return null; }
                    })
                    .orElse(null);
            if (customNameComponent == null) {
                return null;
            }
            Object name = nativeStack.getClass().getMethod("get", Object.class)
                    .invoke(nativeStack, customNameComponent);
            // Mirror `if (name instanceof net.minecraft.network.chat.Component component)`
            // without a hard Minecraft dependency: resolve the Component interface
            // reflectively so non-Component values (and null) degrade to null,
            // exactly like the original per-platform implementations.
            Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
            if (!componentClass.isInstance(name)) {
                return null;
            }
            return (String) name.getClass().getMethod("getString").invoke(name);
        } catch (Throwable ignored) {
            // accessor missing on this mapping/version; skip custom name
        }
        return null;
    }

    /**
     * Schedules a task on a {@link ScheduledExecutorService} with exception
     * guarding, returning the {@link ScheduledFuture} and registering it in
     * {@code pendingTasks} so it can be cancelled on shutdown. Deduplicates the
     * {@code runLater} body that was byte-identical across the three platform
     * bridges.
     *
     * <p>The executor and pending-tasks collection stay as per-platform fields
     * because their lifecycle (thread naming, shutdown ordering) differs per
     * loader; only the try/catch wrapping and bookkeeping is shared.
     *
     * @param executor     the scheduled executor service (per-platform field)
     * @param pendingTasks the live collection of pending delayed tasks
     *                     (per-platform field)
     * @param task         the task to run; no-op if {@code null}
     * @param delaySeconds  the delay in seconds (clamped to {@code >= 0})
     * @param logger       the platform logger for failure logging
     * @return the scheduled future, or {@code null} if {@code task} was
     *         {@code null}
     */
    public static ScheduledFuture<?> safeSchedule(ScheduledExecutorService executor,
                                                  Collection<ScheduledFuture<?>> pendingTasks,
                                                  Runnable task, long delaySeconds,
                                                  Logger logger) {
        if (task == null) {
            return null;
        }
        long delaySec = Math.max(0L, delaySeconds);
        // Schedule on a managed ScheduledExecutorService so pending tasks can be
        // cancelled on shutdown instead of leaking raw Thread.sleep threads.
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Error in delayed task", t);
            }
        }, delaySec, TimeUnit.SECONDS);
        pendingTasks.add(future);
        // Best-effort cleanup of completed futures to avoid unbounded growth.
        pendingTasks.removeIf(f -> f.isDone());
        return future;
    }

    /**
     * Replaces {@code &X} with {@code §X} only when {@code X} is a valid
     * Minecraft color/format code. A bare {@code &} not followed by a valid
     * code character is preserved as-is so plain text like
     * {@code "Tom & Jerry"} is not corrupted into garbage section signs.
     *
     * <p>Shared by the three platform bridges'
     * {@code parseColorCodes(String)} instance methods. The Component-wrapping
     * stays on each platform because {@code net.minecraft.network.chat.Component}
     * is not visible to :NovaChat:MOD:common.
     *
     * @param message the message with {@code &} color codes
     * @return the converted message with {@code §} codes, or the input if blank
     */
    public static String parseColorCodesToSection(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length());
        int len = message.length();
        for (int i = 0; i < len; i++) {
            char c = message.charAt(i);
            if (c == '&' && i + 1 < len
                    && COLOR_CODE_CHARS.indexOf(message.charAt(i + 1)) >= 0) {
                sb.append('§').append(message.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
