package com.nova.joint.fixture;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

public final class JointCustomEffectsBridge extends JavaPlugin {
    private final Map<UUID, PermissionAttachment> grants = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) return true;
        try {
            if (args.length < 2 || !args[1].equals("E2EJointJava")) throw new IllegalArgumentException();
            Player player = getServer().getPlayerExact(args[1]);
            if (player == null) throw new IllegalStateException();
            NovaChatBukkit nova = (NovaChatBukkit) getServer().getPluginManager().getPlugin("NovaChat");
            NetworkClient client = nova.getNetworkClient();
            if (client == null || !client.isAuthenticated()) throw new IllegalStateException();
            switch (args[0]) {
                case "grant": {
                    if (args.length != 2) throw new IllegalArgumentException();
                    PermissionAttachment grant = grants.computeIfAbsent(player.getUniqueId(), id -> player.addAttachment(this));
                    for (String node : new String[]{"novachat.auth", "novachat.title", "novachat.channel.global"}) grant.setPermission(node, true);
                    break;
                }
                case "auth": {
                    if (args.length != 2 || !player.hasPermission("novachat.auth")) throw new IllegalArgumentException();
                    String password = System.getenv("NOVALINK_JOINT_CUSTOM_EFFECTS_ADMIN_PASSWORD");
                    if (password == null || password.isEmpty()) throw new IllegalStateException();
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
                    AdminActionPacket packet = AdminActionPacket.createAuthPacket(player.getUniqueId(), hash);
                    packet.addExtra("playerName", player.getName());
                    client.sendPacket(packet);
                    break;
                }
                case "title": {
                    if (args.length != 9 || !player.hasPermission("novachat.title")) throw new IllegalArgumentException();
                    AdminActionPacket packet = new AdminActionPacket(UUID.fromString(args[2]));
                    packet.setAction(AdminAction.STATUS);
                    packet.setPlayerId(player.getUniqueId());
                    packet.setTarget(args[3]);
                    packet.addExtra("type", "TITLE");
                    packet.addExtra("operatorName", player.getName());
                    packet.addExtra("title", args[4]);
                    packet.addExtra("subtitle", args[5]);
                    String[] names = {"fadeIn", "stay", "fadeOut"};
                    for (int i = 0; i < names.length; i++) {
                        if (Integer.parseInt(args[i + 6]) < 0) throw new IllegalArgumentException();
                        packet.addExtra(names[i], args[i + 6]);
                    }
                    client.sendPacket(packet);
                    break;
                }
                case "release": {
                    if (args.length != 2) throw new IllegalArgumentException();
                    client.sendPacket(AdminActionPacket.createLogoutPacket(player.getUniqueId()));
                    PermissionAttachment grant = grants.remove(player.getUniqueId());
                    if (grant != null) player.removeAttachment(grant);
                    break;
                }
                default: throw new IllegalArgumentException();
            }
            sender.sendMessage("JointCustomEffectsBridge queued " + args[0]);
        } catch (Exception error) {
            sender.sendMessage("JointCustomEffectsBridge rejected: check player, permissions, backend link and server secret environment");
        }
        return true;
    }
}
