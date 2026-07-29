package com.nova.chat.client.network;

/**
 * Platform-owned scheduler port for reconnect delays and optional async hops.
 *
 * <p>API is <strong>seconds-based</strong> so client-core never multiplies by game
 * ticks. Platform adapters own any tick conversion ({@code delay * 20} on Bukkit-like
 * schedulers).
 *
 * <p>Threading contract:
 * <ul>
 *   <li>{@link #runAsync(Runnable)} – run off the calling thread when the platform
 *       provides an async executor; may run inline if the platform has no separate pool.</li>
 *   <li>{@link #runLater(Runnable, long)} – schedule after {@code delaySeconds}
 *       (clamped to {@code >= 0} by the platform adapter if needed).</li>
 * </ul>
 *
 * <p>Core reconnect uses only {@link #runLater(Runnable, long)}. Core does not call
 * a sync/main-thread API — packet handlers that need the main thread hop themselves.
 */
public interface SchedulerBridge {

    /**
     * Runs a task asynchronously (or as close as the platform allows).
     *
     * @param task work to run; must not be null
     */
    void runAsync(Runnable task);

    /**
     * Schedules a task to run after a delay measured in whole seconds.
     *
     * @param task work to run; must not be null
     * @param delaySeconds delay before execution; callers pass policy delays ({@code >= 0})
     */
    void runLater(Runnable task, long delaySeconds);
}
