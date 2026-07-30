package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.WhoCommandService;
import org.bukkit.command.CommandSender;

/**
 * {@code /nc who [频道]} — lists the online members of a channel
 * (UX-DESIGN §8.2).
 *
 * <p>The current backend protocol does not deliver channel-member data, so
 * {@link WhoCommandService#isMemberListingSupported()} is {@code false} and
 * the command degrades to the shared explanatory prompt instead of
 * fabricating a list. No permission requirement.
 */
public class WhoCommand extends AbstractSubCommand {

    public WhoCommand(NovaChatBukkit plugin) {
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
        if (WhoCommandService.isMemberListingSupported()) {
            // Future backend support: render the real member list here.
            // Intentionally unreachable today; kept so the platform is ready
            // when the backend protocol adds member delivery.
            messageHelper.sendMessage(sender, WhoCommandService.getUnavailablePrompt());
            return true;
        }
        messageHelper.sendMessage(sender, WhoCommandService.getUnavailablePrompt());
        return true;
    }
}
