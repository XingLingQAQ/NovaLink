package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;

/**
 * Auth command - allows super admin authentication.
 * This is a hidden command not shown in help.
 * 
 * Requirements: 2.2
 */
public class AuthCommand extends AbstractSubCommand {

    public AuthCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "auth";
    }

    @Override
    public String getDescription() {
        return "超级管理员认证";
    }

    @Override
    public String getUsage() {
        return "/nc auth <密码>";
    }

    @Override
    public String getPermission() {
        return "novachat.auth";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean isHidden() {
        return true; // Hidden from help
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messageHelper.sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        String password = args[0];
        String passwordHash = hashPassword(password);

        // Create and send auth packet
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(
                player.getUniqueId(),
                passwordHash
        );
        packet.addExtra("playerName", player.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在验证超级管理员身份...");
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    /**
     * Hashes a password using SHA-256.
     *
     * @param password the password to hash
     * @return the hex-encoded hash
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        // Don't provide tab completion for password
        return Collections.emptyList();
    }
}
