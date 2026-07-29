package com.nova.chat.velocity.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.velocity.chat.MessageFormatter;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat Velocity plugin.
 * Handles /novachat and /nc commands.
 *
 * <p>Join / leave / toggle / reload delegate channel-membership and mode intents
 * to {@link ChannelCommandService} (Architecture B client-core). Platform-owned
 * messages, command registration, and reload stay here.
 *
 * Requirements: 26.1-26.4
 */
public class NovaChatCommand implements SimpleCommand {

    private final NovaChatVelocity plugin;
    private final MessageFormatter messageFormatter;
    private final ChannelCommandService channelCommands;

    /** Available subcommands */
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "join", "leave", "toggle", "reload"
    );

    /**
     * Creates a new NovaChatCommand.
     *
     * @param plugin the plugin instance
     */
    public NovaChatCommand(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.messageFormatter = plugin.getChatListener().getMessageFormatter();
        this.channelCommands = plugin.getChannelCommandService();
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            showHelp(invocation);
            return;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "help":
                showHelp(invocation);
                break;
            case "join":
                handleJoin(invocation, subArgs);
                break;
            case "leave":
                handleLeave(invocation, subArgs);
                break;
            case "toggle":
                handleToggle(invocation);
                break;
            case "reload":
                handleReload(invocation);
                break;
            default:
                // Try to send message to channel
                handleChannelMessage(invocation, subCommand, subArgs);
                break;
        }
    }

    /**
     * Shows help information.
     */
    private void showHelp(Invocation invocation) {
        invocation.source().sendMessage(Component.text("=== NovaChat 帮助 ===", NamedTextColor.GOLD));
        invocation.source().sendMessage(Component.text("/nc help - 显示帮助信息", NamedTextColor.YELLOW));
        invocation.source().sendMessage(Component.text("/nc join <频道> [密码] - 加入频道", NamedTextColor.YELLOW));
        invocation.source().sendMessage(Component.text("/nc leave [频道] - 离开频道", NamedTextColor.YELLOW));
        invocation.source().sendMessage(Component.text("/nc toggle - 切换聊天模式", NamedTextColor.YELLOW));
        invocation.source().sendMessage(Component.text("/nc <频道> <消息> - 发送消息到指定频道", NamedTextColor.YELLOW));

        if (invocation.source().hasPermission("novachat.admin")) {
            invocation.source().sendMessage(Component.text("/nc reload - 重载配置", NamedTextColor.YELLOW));
        }
    }

    /**
     * Handles the join subcommand.
     *
     * <p>Sends JOIN via {@link ChannelCommandService} (optimistic local active
     * channel on accepted send). Keeps Velocity success/error copy.
     */
    private void handleJoin(Invocation invocation, String[] args) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError("用法: /nc join <频道> [密码]"));
            return;
        }

        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getOrCreateState(player);

        CommandResult result = channelCommands.join(state, channelId, password, player.getUsername());
        if (result.isSuccess()) {
            // Match previous Velocity UX (immediate "joined" rather than service English text).
            player.sendMessage(messageFormatter.formatSuccess("已加入频道: " + channelId));
            plugin.debug("Player " + player.getUsername() + " joined channel: " + channelId);
        } else {
            player.sendMessage(messageFormatter.formatError("未连接到聊天服务器，请稍后再试"));
            plugin.debug("Player " + player.getUsername() + " failed to join channel " + channelId
                    + ": " + result.getMessage());
        }
    }

    /**
     * Handles the leave subcommand.
     *
     * <p>Uses {@link ChannelCommandService#leave} for the LEAVE packet and
     * membership update. After a successful leave, restores the configured
     * default channel so Velocity leave UX stays "leave → default".
     */
    private void handleLeave(Invocation invocation, String[] args) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getOrCreateState(player);

        String requested = args.length > 0 ? args[0] : null;
        String leavingChannel = (requested != null && !requested.isBlank())
                ? requested
                : state.getActiveChannel();
        if (leavingChannel == null || leavingChannel.isBlank()) {
            player.sendMessage(messageFormatter.formatError("你当前不在任何频道中"));
            return;
        }

        CommandResult result = channelCommands.leave(state, leavingChannel, player.getUsername());
        if (result.isSuccess()) {
            // Preserve prior Velocity leave UX: always land on the configured default.
            String defaultChannel = plugin.getConfig().getDefaultChannel();
            if (!defaultChannel.equals(state.getActiveChannel())) {
                state.setActiveChannel(defaultChannel);
            }
            player.sendMessage(messageFormatter.formatSuccess(
                    "已离开频道: " + leavingChannel + "，已切换到默认频道: " + defaultChannel));
            plugin.debug("Player " + player.getUsername() + " left channel: " + leavingChannel);
        } else {
            // Distinguish "not in channel" from network failure when possible.
            if (result.getMessage() != null && result.getMessage().contains("Not in a channel")) {
                player.sendMessage(messageFormatter.formatError("你当前不在任何频道中"));
            } else {
                player.sendMessage(messageFormatter.formatError("未连接到聊天服务器，请稍后再试"));
            }
            plugin.debug("Player " + player.getUsername() + " failed to leave channel "
                    + leavingChannel + ": " + result.getMessage());
        }
    }

    /**
     * Handles the toggle subcommand via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet.
     */
    private void handleToggle(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getOrCreateState(player);

        CommandResult result = channelCommands.toggle(state);
        if (!result.isSuccess()) {
            player.sendMessage(messageFormatter.formatError(result.getMessage()));
            return;
        }

        ChatMode newMode = state.getChatMode();
        String modeText = newMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        player.sendMessage(messageFormatter.formatSuccess("聊天模式已切换为: " + modeText));
        plugin.debug("Player " + player.getUsername() + " toggled chat mode to: " + newMode);
    }

    /**
     * Handles the reload subcommand.
     *
     * <p>{@link ChannelCommandService#reload()} is intentionally a no-op on the
     * wire; the platform still owns config reload / reconnect.
     */
    private void handleReload(Invocation invocation) {
        if (!invocation.source().hasPermission("novachat.admin")) {
            invocation.source().sendMessage(messageFormatter.formatError("你没有权限执行此命令"));
            return;
        }

        // Signal intent through shared service (documented no-op), then do platform reload.
        channelCommands.reload();
        plugin.reload();
        invocation.source().sendMessage(messageFormatter.formatSuccess("配置已重载"));
    }

    /**
     * Handles sending a message to a specific channel.
     */
    private void handleChannelMessage(Invocation invocation, String channelId, String[] args) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError("用法: /nc <频道> <消息>"));
            return;
        }

        String message = String.join(" ", args);

        ChatListener chatListener = plugin.getChatListener();
        chatListener.sendToChannel(player, channelId, message);

        plugin.debug("Player " + player.getUsername() + " sent message to channel " + channelId + ": " + message);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(cmd -> cmd.startsWith(prefix))
                .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("novachat.use");
    }
}
