package com.nova.chat.mod.fabric;

import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Fabric-specific command context that bridges {@link #dispatchSuccess} and
 * {@link #dispatchFailure} to the brigadier {@link CommandSourceStack}. The
 * {@code &}→{@code §} conversion happens once in the base {@link CommandContext};
 * this shim only wraps the converted string in a {@code Component} and dispatches
 * via the source — keeping all MC types out of the common layer (method A).
 */
public class FabricCommandContext extends CommandContext {
    private final CommandSourceStack source;

    public FabricCommandContext(UUID playerId, String playerName, Platform platform, boolean isAdmin, CommandSourceStack source) {
        super(playerId, playerName, platform, isAdmin);
        this.source = source;
    }

    /**
     * Routes to the brigadier source via {@link #dispatchSuccess} using the
     * unsafe {@code replace("&","§")} conversion (preserving the loader-style
     * split from the base {@link CommandContext#sendMessage} safe path).
     */
    @Override
    public void sendMessage(String message) {
        if (message != null) {
            dispatchSuccess(message.replace("&", "§"));
        }
    }

    @Override
    protected void dispatchSuccess(String sectionMessage) {
        if (source != null) {
            source.sendSuccess(() -> Component.literal(sectionMessage), false);
        }
    }

    @Override
    protected void dispatchFailure(String sectionMessage) {
        if (source != null) {
            source.sendFailure(Component.literal(sectionMessage));
        }
    }

    /**
     * Get the command source stack
     * @return the command source stack
     */
    public CommandSourceStack getSource() {
        return source;
    }
}
