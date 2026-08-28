package com.nova.chat.mod.platform;

import com.nova.chat.client.format.LegacyColorCodes;

import java.util.UUID;

/**
 * Context for command execution. Carries the player identity, platform bridge
 * and admin flag; {@link #sendMessage(String)} routes through the platform so
 * the string carries {@code &}-style color codes that the loader converts to
 * its native {@code Component}.
 */
public class CommandContext {
    private final UUID playerId;
    private final String playerName;
    private final Platform platform;
    private final boolean isAdmin;
    private ModServices services;

    public CommandContext(UUID playerId, String playerName, Platform platform, boolean isAdmin) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.platform = platform;
        this.isAdmin = isAdmin;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Platform getPlatform() {
        return platform;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    /**
     * Attaches the shared mod services (network / commands / registry / state).
     * Called by the bootstrap before dispatching a command.
     *
     * @param services the shared services holder
     * @return this
     */
    public CommandContext withServices(ModServices services) {
        this.services = services;
        return this;
    }

    /**
     * @return the shared mod services, or null if not attached (e.g. a
     *         platform-only context without the common bootstrap)
     */
    public ModServices getServices() {
        return services;
    }

    /**
     * Sends a message to this context's player. The message may carry
     * {@code &}-style color codes; they are converted to {@code §} here so each
     * loader's {@link Platform#sendMessage} receives section-sign text it can
     * wrap in a plain literal Component.
     *
     * @param message the message with optional {@code &} color codes
     */
    public void sendMessage(String message) {
        if (platform != null && playerId != null && message != null) {
            platform.sendMessage(playerId, LegacyColorCodes.ampersandToSection(message));
        }
    }

    /**
     * Sends an error line to this context's native source, converting {@code &}
     * color codes to {@code §} via the unsafe {@code replace("&","§")} path
     * (preserving the loader-style conversion — distinct from the safe
     * {@link LegacyColorCodes#ampersandToSection} used by {@link #sendMessage}).
     * Delegates to {@link #dispatchFailure} so loaders carrying a brigadier
     * {@code CommandSourceStack} route to the source directly; the default
     * routes through the attached {@link Platform}.
     *
     * @param message the error message with optional {@code &} color codes
     */
    public void sendError(String message) {
        if (message != null) {
            dispatchFailure(message.replace("&", "§"));
        }
    }

    /**
     * Bridge for sending a {@code §}-converted success line to this context's
     * native command source. Loaders override to wrap the string in a native
     * {@code Component} and dispatch via the brigadier source; the default
     * routes through the attached {@link Platform}. The argument already carries
     * {@code §}-style codes — no MC types cross this boundary so the common layer
     * stays Minecraft-free (method A).
     *
     * @param sectionMessage the message with {@code §} color codes
     */
    protected void dispatchSuccess(String sectionMessage) {
        if (platform != null && playerId != null) {
            platform.sendMessage(playerId, sectionMessage);
        }
    }

    /**
     * Bridge for sending a {@code §}-converted failure line to this context's
     * native command source. Loaders override to wrap the string in a native
     * {@code Component} and dispatch via the brigadier source; the default
     * routes through the attached {@link Platform}.
     *
     * @param sectionMessage the message with {@code §} color codes
     */
    protected void dispatchFailure(String sectionMessage) {
        if (platform != null && playerId != null) {
            platform.sendMessage(playerId, sectionMessage);
        }
    }
}
