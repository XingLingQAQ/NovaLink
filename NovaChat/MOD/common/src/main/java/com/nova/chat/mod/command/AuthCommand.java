package com.nova.chat.mod.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.PasswordHasher;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.mod.platform.CommandContext;

import java.util.List;

/**
 * Auth command — establishes a backend super-admin session
 * ({@code /nc auth <password>}).
 *
 * <p>Without it {@code /nc announce} and {@code /nc title} can never succeed:
 * the backend's {@code AdminActionHandler#handleStatus} gates every STATUS
 * action on {@code PermissionManager#hasSuperAdminSession(playerId)} and answers
 * NC-403 otherwise.
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.auth} node, same
 * {@link AdminActionPacket#createAuthPacket} with a SHA-256 hex password hash
 * and the {@code playerName} extra, same progress copy. Player-only — the
 * backend keys the session on the packet's player UUID, and the console
 * sentinel cannot hold a password-authenticated session.
 *
 * <p>Intentionally NOT {@link com.nova.chat.mod.platform.ConsoleCapableCommand}
 * and not shown in {@code /nc help}, matching the references' hidden command.
 */
public class AuthCommand extends AbstractModerationCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.auth";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 1) {
            sendUsage(context);
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String passwordHash = PasswordHasher.sha256Hex(args[0]);

        AdminActionPacket packet = AdminActionPacket.createAuthPacket(
                context.getPlayerId(),
                passwordHash
        );
        packet.addExtra("playerName", context.getPlayerName());

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.auth.progress"));
        return true;
    }

    @Override
    public String getDescription() {
        return "Authenticate as super admin";
    }

    @Override
    public String getUsage() {
        return "/nc auth <password>";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        // Never complete a password.
        return List.of();
    }
}
