package com.nova.bedrock.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.powernukkitx.Player;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.ConsoleCommandSender;
import org.powernukkitx.plugin.PluginBase;

public final class BedrockTransferFixture extends PluginBase {
    private String node;
    private InetSocketAddress destination;

    @Override
    public void onEnable() {
        Properties settings = new Properties();
        Path file = getDataFolder().toPath().resolve("transfer.properties");
        try (InputStream input = Files.newInputStream(file)) {
            settings.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read transfer fixture settings", error);
        }
        node = settings.getProperty("node");
        if (!"a".equals(node) && !"b".equals(node)) {
            throw new IllegalArgumentException("Fixture node must be a or b");
        }
        destination = new InetSocketAddress(settings.getProperty("peer-host"),
                Integer.parseInt(settings.getProperty("peer-port")));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length != 2) {
            return false;
        }
        Player player = getServer().getPlayerExact(args[0]);
        String prefix = node + ":" + args[1] + ":" + args[0] + ":";
        if ("btstate".equals(command.getName())) {
            sender.sendMessage("BT_STATE:" + prefix
                    + (player == null ? "absent" : player.getUniqueId()));
            return true;
        }
        if (player == null) {
            sender.sendMessage("BT_MISSING:" + prefix);
            return true;
        }
        if ("btprobe".equals(command.getName())) {
            player.sendMessage("BT_PROBE:" + prefix + player.getUniqueId());
            sender.sendMessage("BT_PROBE_ISSUED:" + prefix);
            return true;
        }
        if ("bttransfer".equals(command.getName())) {
            player.transfer(destination);
            sender.sendMessage("BT_TRANSFER_CALLED:" + prefix + destination);
            return true;
        }
        return false;
    }
}
