package com.nova.chat.velocity.command;

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
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.chat.ChatListener;
import com.nova.chat.velocity.chat.MessageFormatter;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

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
        "help", "join", "leave", "list", "who", "toggle", "reload"
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
            case "list":
                handleList(invocation);
                break;
            case "who":
                handleWho(invocation);
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
        java.util.UUID playerId = (invocation.source() instanceof Player p) ? p.getUniqueId() : null;
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.title")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_help")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_join")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_leave")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_list")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_who")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_toggle")));
        invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_msg")));

        if (invocation.source().hasPermission("novachat.admin")) {
            invocation.source().sendMessage(messageFormatter.parseColors(I18n.tr(playerId, "chat.command.help.line_reload")));
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
            invocation.source().sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
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

        // Proxy platforms have no game-world concept; world-restricted channels (NC-435)
        // are not applicable to a forwarding proxy, so world is passed as null.
        // TODO: if world-scoped filtering is needed for proxy clients, consider the
        // connected downstream server name as the "world" value.
        CommandResult result = channelCommands.join(state, channelId, password, player.getUsername(), null);
        if (result.isSuccess()) {
            // §7: optimistic "joining…" receipt; the async ChannelActionResponsePacket
            // handler in ChatListener confirms with "已加入频道 X" once the backend
            // accepts, or surfaces an actionable error if it rejects.
            player.sendMessage(messageFormatter.formatSuccess(PlayerMessages.joining(player.getUniqueId(), channelId)));
            plugin.debug("Player " + player.getUsername() + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
            plugin.debug("Player " + player.getUsername() + " failed to join channel " + channelId
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
    private void handleLeave(Invocation invocation, String[] args) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
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

        CommandResult result = channelCommands.leave(state, leavingChannel, player.getUsername());
        if (result.isSuccess()) {
            // Prefer the configured default only when it is still a confirmed local
            // membership. setActiveChannel() would silently re-add a default that
            // this very LEAVE just removed without sending a matching JOIN.
            String defaultChannel = plugin.getConfig().getDefaultChannel();
            state.setActiveChannelIfJoined(defaultChannel);
            player.sendMessage(messageFormatter.formatSuccess(
                    PlayerMessages.leaving(player.getUniqueId(), leavingChannel)));
            plugin.debug("Player " + player.getUsername() + " left channel: " + leavingChannel);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
            plugin.debug("Player " + player.getUsername() + " failed to leave channel "
                    + leavingChannel + ": " + result.getMessage());
        }
    }

    /**
     * Handles the list subcommand - shows channels the backend advertised via
     * ConfigSync, marking those the player has joined (UX-DESIGN §2.2).
     */
    private void handleList(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }

        ChatListener chatListener = plugin.getChatListener();
        PlayerChannelState state = chatListener.getState(player.getUniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        java.util.List<String> lines = com.nova.chat.client.command.ListCommandService
                .formatChannelList(plugin.getKnownChannelRegistry(), joined);

        player.sendMessage(messageFormatter.parseColors(I18n.tr(player.getUniqueId(), "chat.command.list.title")));
        for (String line : lines) {
            player.sendMessage(messageFormatter.formatSystemMessage(line));
        }
        player.sendMessage(messageFormatter.parseColors(I18n.tr(player.getUniqueId(), "chat.command.list.tail")));
    }

    /**
     * Handles the who subcommand - degrades to the shared unavailable prompt
     * until the backend protocol delivers channel-member data (UX-DESIGN §8.2).
     * No permission requirement; any player may run it.
     */
    private void handleWho(Invocation invocation) {
        invocation.source().sendMessage(
                messageFormatter.formatSystemMessage(WhoCommandService.getUnavailablePrompt()));
    }

    /**
     * Handles the toggle subcommand via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet.
     */
    private void handleToggle(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
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
            invocation.source().sendMessage(messageFormatter.formatError(
                    ErrorMessageFormatter.format(ErrorCode.FORBIDDEN)));
            return;
        }

        // Signal intent through shared service (documented no-op), then do platform reload.
        channelCommands.reload();
        plugin.reload();
        java.util.UUID playerId = (invocation.source() instanceof Player p) ? p.getUniqueId() : null;
        invocation.source().sendMessage(messageFormatter.formatSuccess(I18n.tr(playerId, "chat.command.reload.success")));
    }

    /**
     * Handles sending a message to a specific channel.
     */
    private void handleChannelMessage(Invocation invocation, String channelId, String[] args) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messageFormatter.formatError(I18n.tr("chat.command.player_only")));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(messageFormatter.formatError(I18n.tr(player.getUniqueId(), "chat.command.usage.msg")));
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

        // UX-DESIGN §2.3: complete channel names for join / leave.
        String sub = args[0].toLowerCase();
        String prefix = args[1] == null ? "" : args[1].toLowerCase();
        if (sub.equals("join")) {
            List<String> known = plugin.getKnownChannelRegistry().getKnownChannelIds(prefix);
            return known.isEmpty() ? new ArrayList<>(Arrays.asList("global", "local")) : known;
        }
        if (sub.equals("leave") && invocation.source() instanceof Player player) {
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

    @Override
    public boolean hasPermission(Invocation invocation) {
        // `novachat.use` is a basic-user permission that must default to TRUE for
        // all players (mirrors BungeeCord's default-grant-all permission provider).
        //
        // Velocity 4.1.0 changed Brigadier dispatch: when a SimpleCommand's
        // hasPermission returns false for a sender, Velocity prunes the command
        // node's children from that sender's dispatch tree (3.4.0 did not). An
        // offline-mode player has NO granted permissions, so the previous
        // `hasPermission("novachat.use")` check returned false, which pruned the
        // `nc` node's subcommand children (help/join/leave/...) and made every
        // `/nc <sub>` report "Incorrect argument at position 3: nc <--[HERE]" —
        // a permission denial masquerading as a syntax error.
        //
        // The admin subcommand `/nc reload` independently gates on
        // `novachat.admin` inside handleReload (line 272), so the admin gate is
        // preserved. Default-allowing here only restores basic-user dispatch.
        return true;
    }
}
