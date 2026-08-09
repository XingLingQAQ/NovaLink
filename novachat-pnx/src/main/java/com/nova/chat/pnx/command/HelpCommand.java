package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Help sub-command - displays available commands.
 *
 * Requirements: 29.1, 29.2
 */
public class HelpCommand extends AbstractSubCommand {

    private final NovaChatCommand parentCommand;

    public HelpCommand(NovaChatPNX plugin, NovaChatCommand parentCommand) {
        super(plugin);
        this.parentCommand = parentCommand;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.help");
    }

    @Override
    public String getUsage() {
        return "/nc help";
    }

    @Override
    public String getPermission() {
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        UUID playerId = sender instanceof cn.nukkit.Player ? ((cn.nukkit.Player) sender).getUniqueId() : null;
        sender.sendMessage(TextFormat.colorize(I18n.tr(playerId, "chat.command.help.title")));

        Map<String, SubCommand> subCommands = parentCommand.getSubCommands();

        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            SubCommand cmd = entry.getValue();

            // Skip hidden commands
            if (cmd.isHidden()) {
                continue;
            }

            // Skip commands the sender doesn't have permission for
            if (!cmd.hasPermission(sender)) {
                continue;
            }

            sender.sendMessage(TextFormat.YELLOW + cmd.getUsage() + TextFormat.WHITE + " - " + cmd.getDescription());
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
