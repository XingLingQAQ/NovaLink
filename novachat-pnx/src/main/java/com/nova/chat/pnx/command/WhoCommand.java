package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;

/**
 * {@code /nc who [频道]} — lists the online members of a channel
 * (UX-DESIGN §8.2).
 *
 * <p>The current backend protocol does not deliver channel-member data, so
 * {@link WhoCommandService#isMemberListingSupported()} is {@code false} and
 * the command degrades to the shared explanatory prompt instead of
 * fabricating a list. No permission requirement.
 *
 * Requirements: 29.1, 29.2
 */
public class WhoCommand extends AbstractSubCommand {

    public WhoCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "who";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.who");
    }

    @Override
    public String getUsage() {
        return "/nc who [频道]";
    }

    @Override
    public String getPermission() {
        return null; // No permission required (UX-DESIGN §8.2)
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        sendMessage(sender, WhoCommandService.getUnavailablePrompt());
        return true;
    }
}
