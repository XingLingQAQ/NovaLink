package com.nova.chat.proxy.command;

/**
 * Platform-neutral command sender.
 *
 * <p>Velocity's {@code Invocation.source()} is a {@code CommandSource} (players
 * are a subtype); BungeeCord's is a {@code CommandSender} (players are a
 * subtype). The shared command core works against this handle, so the two shells
 * differ only in whether they can pattern-match a player, not in what the
 * subcommands do.
 */
public interface ProxyCommandSender {

    /**
     * @return the sender's UUID when it is a player, or {@code null} for the
     *         console (used as the i18n locale key)
     */
    java.util.UUID getPlayerId();

    /**
     * @return the sender's name (account name for players, {@code "CONSOLE"} for
     *         console on BungeeCord)
     */
    String getName();

    /**
     * @param permission the permission node
     * @return {@code true} when the node is granted
     */
    boolean hasPermission(String permission);

    /**
     * Sends one already-formatted (legacy-coded) line to the sender.
     *
     * @param legacyText the line text
     */
    void sendMessage(String legacyText);
}
