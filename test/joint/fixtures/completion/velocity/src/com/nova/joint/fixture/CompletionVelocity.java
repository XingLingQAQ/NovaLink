package com.nova.joint.fixture;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionProvider;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public final class CompletionVelocity {
    private final ProxyServer server;
    private final Logger logger;
    private final CompletionPlayers players;

    @Inject
    public CompletionVelocity(ProxyServer server, Logger logger, @DataDirectory Path directory) throws IOException {
        this.server = server;
        this.logger = logger;
        players = new CompletionPlayers(directory.resolve("players.properties"));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPermissions(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player) || !players.contains(player.getUsername())) {
            return;
        }
        PermissionProvider previous = event.getProvider();
        event.setProvider(subject -> {
            PermissionFunction inherited = previous.createFunction(subject);
            return permission -> {
                Boolean override = subject instanceof Player target
                        ? players.permission(target.getUsername(), permission) : null;
                return override == null ? inherited.getPermissionValue(permission) : Tristate.fromBoolean(override);
            };
        });
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        server.getCommandManager().register(server.getCommandManager().metaBuilder("jointcompletion").plugin(this).build(),
                (SimpleCommand) invocation -> {
                    if (invocation.source() != server.getConsoleCommandSource()) {
                        return;
                    }
                    try {
                        CompletionPlayers.Change change = players.change(invocation.arguments());
                        Player player = server.getPlayer(change.name()).orElse(null);
                        boolean online = player != null && player.isActive();
                        logger.info(CompletionPlayers.receipt(change, online,
                                online && player.hasPermission(CompletionPlayers.NODE)));
                    } catch (IllegalArgumentException error) {
                        logger.warn("JOINT_COMPLETION_ERROR " + error.getMessage());
                    }
                });
        logger.info("JOINT_COMPLETION_READY velocity");
    }
}
