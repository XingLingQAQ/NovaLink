package com.nova.chat.pnx.command;

import org.powernukkitx.Player;
import org.powernukkitx.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.PasswordHasher;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Collections;
import java.util.List;

/**
 * Auth command - establishes a super-admin session with the backend.
 *
 * <p>Without it {@code /nc announce} and {@code /nc title} can never succeed:
 * {@code AdminActionHandler#handleStatus} gates STATUS actions on
 * {@code permissionManager.hasSuperAdminSession(playerId)} and answers NC-403
 * otherwise. The outcome arrives later as an {@code AdminActionResponsePacket},
 * rendered by {@link com.nova.chat.pnx.network.NetworkClient#handleAdminActionResponse}.
 *
 * <p>Mirrors the Bukkit/Folia shells: same {@code novachat.auth} node, hidden
 * from help, player-only (the backend session is keyed on a player UUID), same
 * {@code AUTH} packet with a SHA-256 hex password hash and the
 * {@code playerName} extra.
 */

public class AuthCommand extends AbstractSubCommand {

    public AuthCommand(NovaChatPNX plugin) {
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
            sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        String passwordHash = PasswordHasher.sha256Hex(args[0]);

        AdminActionPacket packet = AdminActionPacket.createAuthPacket(
                player.getUniqueId(),
                passwordHash
        );
        packet.addExtra("playerName", player.getName());

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.auth.progress"));
        } else {
            sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        // Don't provide tab completion for password
        return Collections.emptyList();
    }
}
