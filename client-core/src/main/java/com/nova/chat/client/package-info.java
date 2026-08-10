/**
 * Platform-agnostic shared client logic for NovaChat plugins and mods.
 *
 * <h2>Architecture B (module split)</h2>
 *
 * <ul>
 *   <li>{@code novachat-common} – <strong>protocol only</strong> (packets, codecs,
 *       shared protocol utilities). No client runtime state machines.</li>
 *   <li>{@code novachat-client-core} – <strong>plugin/mod client runtime</strong>
 *       shared across platform modules (chat mode, player channel state, reconnect
 *       policy, connection config, pending-request tracking, optional Netty engine).
 *       This module depends on {@code novachat-common} only.</li>
 *   <li>{@code novalink-core} – backend server. Must <strong>not</strong> depend on
 *       {@code novachat-client-core}; it only shares {@code novachat-common}.</li>
 *   <li>Platform modules ({@code novachat-bukkit}, {@code novachat-velocity}, …)
 *       depend on both {@code novachat-common} and {@code novachat-client-core},
 *       and adapt shared types to their scheduler / player APIs.</li>
 * </ul>
 *
 * <p>This module intentionally has <strong>no</strong> dependency on Bukkit,
 * Velocity, Nukkit, Sponge, or any mod-loader API.
 *
 * <p>Contents:
 * <ul>
 *   <li>{@link com.nova.chat.client.network} – connection config, reconnect
 *       policy, request/response future tracking, {@code SchedulerBridge}/
 *       {@code ClientLogger} ports, and {@code CoreNetworkClient} (Netty
 *       transport + handshake/reconnect lifecycle)</li>
 *   <li>{@link com.nova.chat.client.state} – per-player channel membership and
 *       chat mode state</li>
 *   <li>{@link com.nova.chat.client.command} – command intent layer
 *       ({@code CommandIntent}, {@code ChannelCommandService}); not wired to
 *       platform commands yet</li>
 *   <li>{@link com.nova.chat.client.format} – pure format template engine and
 *       legacy color-code string transforms (no platform APIs)</li>
 * </ul>
 *
 * <p>{@code CoreNetworkClient} is adopted first by Velocity (thin facade). Other
 * platform modules still own local Netty bootstraps until migrated.
 */
package com.nova.chat.client;
