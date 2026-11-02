package com.nova.joint.fixture;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.IOException;

public final class CompletionBungee extends Plugin implements Listener {
    private CompletionPlayers players;

    @Override
    public void onEnable() {
        try {
            players = new CompletionPlayers(getDataFolder().toPath().resolve("players.properties"));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Invalid completion fixture configuration", error);
        }
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("jointcompletion") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (sender != getProxy().getConsole()) {
                    return;
                }
                try {
                    CompletionPlayers.Change change = players.change(args);
                    ProxiedPlayer player = getProxy().getPlayer(change.name());
                    boolean online = player != null && player.isConnected();
                    if (online) {
                        player.setPermission(CompletionPlayers.NODE, change.allowed());
                    }
                    getLogger().info(CompletionPlayers.receipt(change, online,
                            online && player.hasPermission(CompletionPlayers.NODE)));
                } catch (IllegalArgumentException error) {
                    getLogger().warning("JOINT_COMPLETION_ERROR " + error.getMessage());
                }
            }
        });
        getLogger().info("JOINT_COMPLETION_READY bungee");
    }

    @EventHandler
    public void onLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        Boolean allowed = players.permission(player.getName(), CompletionPlayers.NODE);
        if (allowed != null) {
            player.setPermission(CompletionPlayers.NODE, allowed);
        }
    }
}
