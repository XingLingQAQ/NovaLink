package com.nova.chat.folia.command;

import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;

/**
 * {@code /nc who [频道]} — lists the online members of a channel
 * (UX-DESIGN §8.2).
 *
 * <p>The current backend protocol does not deliver channel-member data, so
 * {@link WhoCommandService#isMemberListingSupported()} is {@code false} and
 * the command degrades to the shared explanatory prompt instead of
 * fabricating a list. No permission requirement.
 *
 * Requirements: 2.1
 */
public class WhoCommand extends AbstractSubCommand {

    public WhoCommand(NovaChatFolia plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "who";
    }

    @Override
    public String getDescription() {
        return "查看频道在线成员";
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
        messageHelper.sendMessage(sender, WhoCommandService.getUnavailablePrompt());
        return true;
    }
}
