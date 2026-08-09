package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;
import java.util.UUID;

/**
 * Debug sub-command - shows debug information and toggles debug mode.
 *
 * Requirements: 29.1, 29.2
 */
public class DebugCommand extends AbstractSubCommand {

    public DebugCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.debug");
    }

    @Override
    public String getUsage() {
        return "/nc debug [on|off]";
    }

    @Override
    public String getPermission() {
        return "novachat.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        UUID playerId = sender instanceof cn.nukkit.Player ? ((cn.nukkit.Player) sender).getUniqueId() : null;

        // If argument provided, toggle debug mode
        if (args.length > 0) {
            String toggle = args[0].toLowerCase();
            if (toggle.equals("on") || toggle.equals("true")) {
                plugin.setDebugMode(true);
                sendSuccess(sender, I18n.tr(playerId, "chat.debug.mode_on"));
            } else if (toggle.equals("off") || toggle.equals("false")) {
                plugin.setDebugMode(false);
                sendSuccess(sender, I18n.tr(playerId, "chat.debug.mode_off"));
            } else {
                sendError(sender, I18n.tr(playerId, "chat.error.usage_prefix", getUsage()));
            }
            return true;
        }

        // Show debug information
        boolean connected = plugin.getNetworkClient() != null && plugin.getNetworkClient().isConnected();
        boolean authenticated = plugin.getNetworkClient() != null && plugin.getNetworkClient().isAuthenticated();

        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.panel_title")));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.backend_connection",
                connected ? I18n.tr(playerId, "chat.debug.value_connected") : I18n.tr(playerId, "chat.debug.value_disconnected"))));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.auth_status",
                authenticated ? I18n.tr(playerId, "chat.debug.value_authenticated") : I18n.tr(playerId, "chat.debug.value_unauthenticated"))));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.backend_address",
                plugin.getNovaChatConfig().getBackendHost() + ":" + plugin.getNovaChatConfig().getBackendPort())));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.client_id",
                plugin.getNovaChatConfig().getBackendUsername())));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.mode_state",
                plugin.isDebugMode() ? I18n.tr(playerId, "chat.debug.value_on") : I18n.tr(playerId, "chat.debug.value_off"))));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.online_players",
                String.valueOf(plugin.getServer().getOnlinePlayers().size()))));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.default_channel_label",
                plugin.getNovaChatConfig().getDefaultChannel())));
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.debug.world_routing_label",
                plugin.getNovaChatConfig().isWorldRoutingEnabled() ? I18n.tr(playerId, "chat.debug.value_on") : I18n.tr(playerId, "chat.debug.value_off"))));

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("on", "off");
        }
        return List.of();
    }
}
