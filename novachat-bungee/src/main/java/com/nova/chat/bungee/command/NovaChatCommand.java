package com.nova.chat.bungee.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.chat.ChatListener;
import com.nova.chat.bungee.chat.MessageFormatter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main command handler for NovaChat BungeeCord plugin.
 * Handles /novachat and /nc commands.
 *
 * <p>Join / leave / toggle delegate channel-membership and mode intents to
 * {@link ChannelCommandService} (Architecture B client-core). Platform-owned
 * messages, command registration, and reload stay here.
 *
 * Requirements: 26.1-26.4
 */
public class NovaChatCommand extends Command implements TabExecutor {

    private final NovaChatBungee plugin;
    private final MessageFormatter messageFormatter;
    private final ChannelCommandService channelCommands;

    /** Available subcommands */
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "join", "leave", "list", "who", "toggle", "reload"
    );

    /**
     * Creates a new NovaChatCommand.
     *
     * @param plugin the plugin instance
     */
    public NovaChatCommand(NovaChatBungee plugin) {
        super("novachat", "novachat.use", "nc");
        this.plugin = plugin;
        this.messageFormatter = plugin.getChatListener().getMessageFormatter();
        this.channelCommands = plugin.getChannelCommandService();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "help":
                showHelp(sender);
                break;
            case "join":
                handleJoin(sender, subArgs);
                break;
            case "leave":
                handleLeave(sender, subArgs);
                break;
            case "list":
                handleList(sender);
                break;
            case "who":
                handleWho(sender);
                break;
            case "toggle":
                handleToggle(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                // Try to send message to channel
                handleChannelMessage(sender, subCommand, subArgs);
                break;
        }
    }

    /**
     * Shows help information.
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(new TextComponent(ChatColor.GOLD + "=== NovaChat 帮助 ==="));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc help - 显示帮助信息"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc join <频道> [密码] - 加入频道"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc leave [频道] - 离开频道"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc list - 列出可用频道"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc who [频道] - 查看频道在线成员"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc toggle - 切换聊天模式"));
        sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc <频道> <消息> - 发送消息到指定频道"));

        if (sender.hasPermission("novachat.admin")) {
            sender.sendMessage(new TextComponent(ChatColor.YELLOW + "/nc reload - 重载配置"));
        }
    }

    /**
     * Handles the join subcommand.
     *
     * <p>Sends JOIN via {@link ChannelCommandService} (optimistic local active
     * channel on accepted send). Keeps Bungee success/error copy.
     */
    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
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

        // Proxy platforms have no game-world concept; world-restricted channels (NC-435)
        // are not applicable to a forwarding proxy, so world is passed as null.
        // TODO: if world-scoped filtering is needed for proxy clients, consider the
        // connected downstream server name as the "world" value.
        CommandResult result = channelCommands.join(state, channelId, password, player.getName(), null);
        if (result.isSuccess()) {
            // §7: optimistic "joining…" receipt; the async ChannelActionResponsePacket
            // handler in ChatListener confirms with "已加入频道 X" once the backend
            // accepts, or surfaces an actionable error if it rejects.
            player.sendMessage(messageFormatter.formatSuccess(PlayerMessages.joining(channelId)));
            plugin.debug("Player " + player.getName() + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
            plugin.debug("Player " + player.getName() + " failed to join channel " + channelId
                    + ": " + result.getMessage());
        }
    }

    /**
     * Handles the leave subcommand.
     *
     * <p>Uses {@link ChannelCommandService#leave} for the LEAVE packet and
     * membership update. After a successful leave, prefers the configured
     * default channel only when it remains in local membership; selecting a
     * channel must never manufacture a JOIN the backend did not receive.
     */
    private void handleLeave(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getOrCreateState(player);

        String requested = args.length > 0 ? args[0] : null;
        String leavingChannel = (requested != null && !requested.isBlank())
                ? requested
                : state.getActiveChannel();
        if (leavingChannel == null || leavingChannel.isBlank()) {
            player.sendMessage(messageFormatter.formatError(
                    ErrorMessageFormatter.format(ErrorCode.NOT_IN_CHANNEL)));
            return;
        }

        CommandResult result = channelCommands.leave(state, leavingChannel, player.getName());
        if (result.isSuccess()) {
            // Prefer the configured default only when it is still a confirmed local
            // membership. setActiveChannel() would silently re-add a default that
            // this very LEAVE just removed without sending a matching JOIN.
            String defaultChannel = plugin.getPluginConfig().getDefaultChannel();
            state.setActiveChannelIfJoined(defaultChannel);
            player.sendMessage(messageFormatter.formatSuccess(
                    PlayerMessages.leaving(leavingChannel)));
            plugin.debug("Player " + player.getName() + " left channel: " + leavingChannel);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
            plugin.debug("Player " + player.getName() + " failed to leave channel "
                    + leavingChannel + ": " + result.getMessage());
        }
    }

    /**
     * Handles the list subcommand - shows channels the backend advertised via
     * ConfigSync, marking those the player has joined (UX-DESIGN §2.2).
     */
    private void handleList(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getState(player.getUniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        java.util.List<String> lines = com.nova.chat.client.command.ListCommandService
                .formatChannelList(plugin.getKnownChannelRegistry(), joined);

        sender.sendMessage(new TextComponent(ChatColor.GOLD + "=== NovaChat 频道列表 ==="));
        for (String line : lines) {
            player.sendMessage(messageFormatter.formatSystemMessage(line));
        }
        sender.sendMessage(new TextComponent(ChatColor.GOLD + "==========================="));
    }

    /**
     * Handles the who subcommand - degrades to the shared unavailable prompt
     * until the backend protocol delivers channel-member data (UX-DESIGN §8.2).
     * No permission requirement; any player may run it.
     */
    private void handleWho(CommandSender sender) {
        sender.sendMessage(messageFormatter.formatSystemMessage(WhoCommandService.getUnavailablePrompt()));
    }

    /**
     * Handles the toggle subcommand via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet.
     */
    private void handleToggle(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
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
        String modeText = ChatModeDescriptions.modeName(newMode);
        player.sendMessage(messageFormatter.formatSuccess("聊天模式已切换为: " + modeText));
        player.sendMessage(messageFormatter.formatSystemMessage(ChatModeDescriptions.describe(newMode)));
        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);
    }

    /**
     * Handles the reload subcommand.
     *
     * <p>{@link ChannelCommandService#reload()} is intentionally a no-op on the
     * wire; the platform still owns config reload / reconnect.
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("novachat.admin")) {
            sender.sendMessage(messageFormatter.formatError(
                    ErrorMessageFormatter.format(ErrorCode.FORBIDDEN)));
            return;
        }

        // Signal intent through shared service (documented no-op), then do platform reload.
        channelCommands.reload();
        plugin.reload();
        sender.sendMessage(messageFormatter.formatSuccess("配置已重载"));
    }

    /**
     * Handles sending a message to a specific channel.
     */
    private void handleChannelMessage(CommandSender sender, String channelId, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError("此命令只能由玩家执行"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError("用法: /nc <频道> <消息>"));
            return;
        }

        String message = String.join(" ", args);

        ChatListener chatListener = plugin.getChatListener();
        chatListener.sendToChannel(player, channelId, message);

        plugin.debug("Player " + player.getName() + " sent message to channel " + channelId + ": " + message);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                .filter(cmd -> cmd.startsWith(prefix))
                .collect(Collectors.toList());
        }

        // UX-DESIGN §2.3: complete channel names for join / leave.
        String sub = args[0].toLowerCase();
        String prefix = args[1] == null ? "" : args[1].toLowerCase();
        if (sub.equals("join")) {
            java.util.List<String> known = plugin.getKnownChannelRegistry().getKnownChannelIds(prefix);
            return known.isEmpty() ? new ArrayList<>(Arrays.asList("global", "local")) : known;
        }
        if (sub.equals("leave") && sender instanceof ProxiedPlayer player) {
            PlayerChannelState state = plugin.getChatListener().getState(player.getUniqueId());
            if (state == null) {
                return new ArrayList<>();
            }
            return state.getJoinedChannels().stream()
                    .filter(id -> id != null && id.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
