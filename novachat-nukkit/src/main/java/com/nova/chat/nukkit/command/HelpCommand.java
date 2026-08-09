package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.Map;
import java.util.UUID;

/**
 * Help command - displays available commands.
 *
 * Requirements: 26.1-26.4
 */
public class HelpCommand extends AbstractSubCommand {

    private final NovaChatCommand mainCommand;

    public HelpCommand(NovaChatNukkit plugin, NovaChatCommand mainCommand) {
        super(plugin);
        this.mainCommand = mainCommand;
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
        messageHelper.sendRawMessage(sender, I18n.tr(playerId, "chat.command.help.title"));

        for (Map.Entry<String, SubCommand> entry : mainCommand.getSubCommands().entrySet()) {
            SubCommand cmd = entry.getValue();

            // Skip hidden commands
            if (cmd.isHidden()) {
                continue;
            }

            // Skip commands the sender doesn't have permission for
            if (!cmd.hasPermission(sender)) {
                continue;
            }

            messageHelper.sendRawMessage(sender,
                "&e" + cmd.getUsage() + " &r- " + cmd.getDescription());
        }

        messageHelper.sendRawMessage(sender, "&6===========================");

        return true;
    }
}
