package com.nova.chat.bungee.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
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
        "help", "join", "leave", "list", "who", "toggle", "ignore", "unignore", "msg", "r", "reload"
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
                handleWho(sender, subArgs);
                break;
            case "toggle":
                handleToggle(sender);
                break;
            case "ignore":
                handleIgnore(sender, subArgs);
                break;
            case "unignore":
                handleUnignore(sender, subArgs);
                break;
            case "msg":
                handleMsg(sender, subArgs);
                break;
            case "r":
                handleReply(sender, subArgs);
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
        java.util.UUID playerId = (sender instanceof ProxiedPlayer p) ? p.getUniqueId() : null;
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.title"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_help"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_join"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_leave"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_list"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_who"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_toggle"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_ignore"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_unignore"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_pm"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_reply"))));
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(playerId, "chat.command.help.line_msg"))));

        if (sender.hasPermission("novachat.admin")) {
            sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                    I18n.tr(playerId, "chat.command.help.line_reload"))));
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
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError(I18n.tr(player.getUniqueId(), "chat.command.usage.join")));
            return;
        }

        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getOrCreateState(player);

        // Proxy platforms have no game-world concept; world-restricted channels
        // (NC-435) are not applicable to a forwarding proxy, so world is passed
        // as null by design. If world-scoped filtering ever becomes a proxy
        // requirement, the connected downstream server name is the natural
        // "world" value to forward here.
        CommandResult result = channelCommands.join(state, channelId, password, player.getName(), null);
        if (result.isSuccess()) {
            // §7: optimistic "joining…" receipt; the async ChannelActionResponsePacket
            // handler in ChatListener confirms with "已加入频道 X" once the backend
            // accepts, or surfaces an actionable error if it rejects.
            player.sendMessage(messageFormatter.formatSuccess(PlayerMessages.joining(player.getUniqueId(), channelId)));
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
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
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
                    PlayerMessages.leaving(player.getUniqueId(), leavingChannel)));
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
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getState(player.getUniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        java.util.List<String> lines = com.nova.chat.client.command.ListCommandService
                .formatChannelList(plugin.getKnownChannelRegistry(), joined);

        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(player.getUniqueId(), "chat.command.list.title"))));
        for (String line : lines) {
            player.sendMessage(messageFormatter.formatSystemMessage(line));
        }
        sender.sendMessage(new TextComponent(ChatColor.translateAlternateColorCodes('&',
                I18n.tr(player.getUniqueId(), "chat.command.list.tail"))));
    }

    /**
     * Handles the who subcommand — sends a {@link com.nova.chat.common.protocol.ChannelAction#WHO}
     * request to the backend and shows an interim {@code chat.who.fetching}
     * prompt (UX-DESIGN §8.2). The asynchronous response is rendered by the
     * {@code ChatListener}'s {@code ChannelResponseDispatcher} adapter, which
     * calls {@link WhoCommandService#formatMemberList} and sends the result to
     * the requesting player. No permission requirement; any player may run it.
     */
    private void handleWho(CommandSender sender, String[] args) {
        if (!WhoCommandService.isMemberListingSupported()) {
            sender.sendMessage(messageFormatter.formatSystemMessage(WhoCommandService.getUnavailablePrompt()));
            return;
        }
        com.nova.chat.bungee.network.NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected() || !client.isAuthenticated()) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.network.not_connected")));
            return;
        }

        java.util.UUID requesterId = (sender instanceof ProxiedPlayer p) ? p.getUniqueId() : null;
        String channelId = null;
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            channelId = args[0];
        } else if (sender instanceof ProxiedPlayer player) {
            PlayerChannelState state = plugin.getChatListener().getOrCreateState(player);
            String active = state != null ? state.getActiveChannel() : null;
            if (active != null && !active.isBlank()) {
                channelId = active;
            }
        }
        if (channelId == null || channelId.isBlank()) {
            sender.sendMessage(messageFormatter.formatError(
                    I18n.tr(requesterId, "chat.who.no_channel")));
            return;
        }

        com.nova.chat.common.protocol.packets.ChannelActionPacket packet =
                new com.nova.chat.common.protocol.packets.ChannelActionPacket(
                        com.nova.chat.common.protocol.ChannelAction.WHO, channelId);
        packet.addExtra("playerId", requesterId != null ? requesterId.toString() : "");
        if (sender instanceof ProxiedPlayer player) {
            packet.addExtra("requesterName", player.getName());
        }
        if (requesterId != null) {
            packet.addExtra("requesterId", requesterId.toString());
        }
        client.sendPacket(packet);
        sender.sendMessage(messageFormatter.formatSystemMessage(
                WhoCommandService.getFetchingPrompt(channelId)));
    }

    /**
     * Handles the toggle subcommand via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet.
     */
    private void handleToggle(CommandSender sender) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
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
        player.sendMessage(messageFormatter.formatSuccess(
                I18n.tr(player.getUniqueId(), "chat.command.toggle.switched", modeText)));
        player.sendMessage(messageFormatter.formatSystemMessage(ChatModeDescriptions.describe(newMode)));
        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);
    }

    /**
     * Handles {@code /nc ignore [<player>|list]} — validation, service calls
     * and receipt copy live in the shared
     * {@link com.nova.chat.client.command.IgnoreCommandService}; this shell
     * forwards arguments and renders the returned lines. Local-only.
     */
    private void handleIgnore(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }
        java.util.List<String> lines = com.nova.chat.client.command.IgnoreCommandService.ignore(
                plugin.getIgnoreListService(), player.getUniqueId(), player.getName(), args);
        for (String line : lines) {
            player.sendMessage(messageFormatter.parseColors(line));
        }
    }

    /**
     * Handles {@code /nc unignore <player>} (see {@link #handleIgnore}).
     */
    private void handleUnignore(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }
        java.util.List<String> lines = com.nova.chat.client.command.IgnoreCommandService.unignore(
                plugin.getIgnoreListService(), player.getUniqueId(), args);
        for (String line : lines) {
            player.sendMessage(messageFormatter.parseColors(line));
        }
    }

    /**
     * Handles {@code /nc msg <player> <message...>} — validation, packet
     * construction and receipt copy live in the shared
     * {@link com.nova.chat.client.command.PrivateMessageCommandService}; this
     * shell forwards arguments and renders the returned lines. The success
     * confirmation is rendered from the backend echo (see
     * {@code ChatListener#handlePrivateMessage}).
     */
    private void handleMsg(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }
        java.util.List<String> lines = com.nova.chat.client.command.PrivateMessageCommandService.msg(
                this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(),
                plugin.getPluginConfig() != null ? plugin.getPluginConfig().getUsername() : null, args);
        for (String line : lines) {
            player.sendMessage(messageFormatter.parseColors(line));
        }
    }

    /**
     * Handles {@code /nc r <message...>} — reply to the most recent
     * private-message partner tracked by the shared
     * {@link com.nova.chat.client.privatemsg.PrivateMessageService}.
     */
    private void handleReply(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }
        java.util.List<String> lines = com.nova.chat.client.command.PrivateMessageCommandService.reply(
                plugin.getPrivateMessageService(),
                this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(),
                plugin.getPluginConfig() != null ? plugin.getPluginConfig().getUsername() : null, args);
        for (String line : lines) {
            player.sendMessage(messageFormatter.parseColors(line));
        }
    }

    /** Transmits a private-message packet when the backend link is up. */
    private boolean sendPrivateMessagePacket(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        com.nova.chat.bungee.network.NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            return false;
        }
        client.sendPacket(packet);
        return true;
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
        java.util.UUID playerId = (sender instanceof ProxiedPlayer p) ? p.getUniqueId() : null;
        sender.sendMessage(messageFormatter.formatSuccess(I18n.tr(playerId, "chat.command.reload.success")));
    }

    /**
     * Handles sending a message to a specific channel.
     */
    private void handleChannelMessage(CommandSender sender, String channelId, String[] args) {
        if (!(sender instanceof ProxiedPlayer player)) {
            sender.sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError(I18n.tr(player.getUniqueId(), "chat.command.usage.msg")));
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
        if (sub.equals("ignore")) {
            List<String> completions = new ArrayList<>();
            if (com.nova.chat.client.command.IgnoreCommandService.LIST_ARG.startsWith(prefix)) {
                completions.add(com.nova.chat.client.command.IgnoreCommandService.LIST_ARG);
            }
            plugin.getProxy().getPlayers().stream()
                    .map(ProxiedPlayer::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(completions::add);
            return completions;
        }
        if (sub.equals("msg") && args.length == 2) {
            // First argument of /nc msg: online player names (UX §2.3).
            return plugin.getProxy().getPlayers().stream()
                    .map(ProxiedPlayer::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        if (sub.equals("unignore") && sender instanceof ProxiedPlayer player
                && plugin.getIgnoreListService() != null) {
            return plugin.getIgnoreListService()
                    .listIgnored(player.getUniqueId()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
