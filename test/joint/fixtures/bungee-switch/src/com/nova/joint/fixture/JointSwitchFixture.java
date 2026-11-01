package com.nova.joint.fixture;

import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.command.ConsoleCommandSender;

public final class JointSwitchFixture extends Plugin {
    @Override
    public void onEnable() {
        getProxy().getPluginManager().registerCommand(this, new Command("jointswitch") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(new TextComponent("JointSwitchFixture error: jointswitch is console-only"));
                    return;
                }
                if (args.length != 2) {
                    getLogger().severe("JointSwitchFixture error: usage jointswitch <player> <server>");
                    return;
                }

                final String route = args[0] + " -> " + args[1];
                ProxiedPlayer player = getProxy().getPlayer(args[0]);
                if (player == null) {
                    getLogger().severe("JointSwitchFixture error " + route + ": player is offline");
                    return;
                }
                ServerInfo target = getProxy().getServerInfo(args[1]);
                if (target == null) {
                    getLogger().severe("JointSwitchFixture error " + route + ": server is not configured");
                    return;
                }

                try {
                    player.connect(target, new Callback<Boolean>() {
                        @Override
                        public void done(Boolean connected, Throwable error) {
                            if (Boolean.TRUE.equals(connected) && error == null) {
                                getLogger().info("JointSwitchFixture switched " + route);
                            } else {
                                getLogger().severe("JointSwitchFixture error " + route + ": "
                                        + (error == null ? "connect callback returned " + connected : error.toString()));
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    getLogger().severe("JointSwitchFixture error " + route + ": " + error);
                }
            }
        });
        getLogger().info("JointSwitchFixture ready");
    }
}
