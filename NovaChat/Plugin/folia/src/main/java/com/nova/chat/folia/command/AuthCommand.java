package com.nova.chat.folia.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.PasswordHasher;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Auth command - establishes a super-admin session with the backend.
 *
 * <p>Without it Folia's {@code /nc announce} and {@code /nc title} can never
 * succeed: {@code AdminActionHandler#handleStatus} gates STATUS actions on
 * {@code permissionManager.hasSuperAdminSession(playerId)} and answers NC-403
 * otherwise. The outcome arrives later as an
 * {@code AdminActionResponsePacket}, rendered by
 * {@link com.nova.chat.folia.network.AdminActionResponseHandler}.
 *
 * <p>Mirrors the Bukkit shell exactly: same {@code novachat.auth} node, hidden
 * from help, player-only, same {@code AUTH} packet with a SHA-256 hex password
 * hash and the {@code playerName} extra, same progress/failure copy.
 */
public class AuthCommand extends AbstractSubCommand {

    public AuthCommand(NovaChatFolia plugin) {
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
        String passwordHash = PasswordHasher.sha256Hex(args[0]);

        AdminActionPacket packet = AdminActionPacket.createAuthPacket(
                player.getUniqueId(),
                passwordHash
        );
        packet.addExtra("playerName", player.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.auth.progress"));
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
