package com.nova.chat.proxy.command;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Platform-agnostic {@code /novachat} + {@code /nc} command core.
 *
 * <p>Owns all eleven subcommands (help, join, leave, list, who, toggle, ignore,
 * unignore, msg, r, reload) plus the channel-message fallback and the Tab
 * completions. The platform shells keep only the command registration glue:
 * Velocity's {@code SimpleCommand} (execute/suggest/hasPermission) and
 * BungeeCord's {@code Command} + {@code TabExecutor} (execute/onTabComplete).
 *
 * <p>Every line is produced as a legacy-coded string and handed to
 * {@link ProxyCommandSender#sendMessage(String)}, so the shells render through
 * their own pipeline exactly as before.
 *
 * <p>Requirements: 26.1-26.4.
 */
public class ProxyCommandCore {

    /** Subcommands in help order; also the first-level Tab completion set. */
    public static final List<String> SUBCOMMANDS = List.of(
            "help", "join", "leave", "list", "who", "toggle",
            "ignore", "unignore", "msg", "r", "reload");

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;
    private final ChannelCommandService channelCommands;
    private final IgnoreListService ignoreListService;
    private final PrivateMessageService privateMessageService;
    private final KnownChannelRegistry knownChannelRegistry;
    /** Supplies the current config snapshot so a reload is picked up on the next call. */
    private final Supplier<? extends ProxyConfigView> configSupplier;

    /**
     * @param platform the platform adapter
     * @param chatCore the shared chat core (state + outbound send + formatter)
     * @param channelCommands shared channel command intents
     * @param ignoreListService shared ignore lists
     * @param privateMessageService shared private-message core
     * @param knownChannelRegistry shared channel roster
     * @param configSupplier supplies the current config snapshot
     */
    public ProxyCommandCore(ProxyPlatform platform,
                            ProxyChatCore chatCore,
                            ChannelCommandService channelCommands,
                            IgnoreListService ignoreListService,
                            PrivateMessageService privateMessageService,
                            KnownChannelRegistry knownChannelRegistry,
                            Supplier<? extends ProxyConfigView> configSupplier) {
        this.platform = platform;
        this.chatCore = chatCore;
        this.channelCommands = channelCommands;
        this.ignoreListService = ignoreListService;
        this.privateMessageService = privateMessageService;
        this.knownChannelRegistry = knownChannelRegistry;
        this.configSupplier = configSupplier;
    }

    /**
     * Dispatches {@code /nc ...}.
     *
     * @param sender the command sender
     * @param args the arguments after the command label
     */
    public void execute(ProxyCommandSender sender, String[] args) {
        if (args == null || args.length == 0) {
            showHelp(sender);
            return;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "help" -> showHelp(sender);
            case "join" -> handleJoin(sender, subArgs);
            case "leave" -> handleLeave(sender, subArgs);
            case "list" -> handleList(sender);
            case "who" -> handleWho(sender, subArgs);
            case "toggle" -> handleToggle(sender);
            case "ignore" -> handleIgnore(sender, subArgs);
            case "unignore" -> handleUnignore(sender, subArgs);
            case "msg" -> handleMsg(sender, subArgs);
            case "r" -> handleReply(sender, subArgs);
            case "reload" -> handleReload(sender);
            // Anything else is treated as a channel name (e.g. /nc global hi)
            default -> handleChannelMessage(sender, subCommand, subArgs);
        }
    }

    /**
     * @param sender the command sender
     * @param args the raw arguments
     * @return Tab completions for the argument position
     */
    public List<String> suggest(ProxyCommandSender sender, String[] args) {
        if (args == null || args.length <= 1) {
            String prefix = args == null || args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(cmd -> cmd.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        // UX-DESIGN §2.3: complete channel names for join / leave.
        String sub = args[0].toLowerCase();
        String prefix = args[1] == null ? "" : args[1].toLowerCase();
        switch (sub) {
            case "join": {
                List<String> known = knownChannelRegistry != null
                        ? knownChannelRegistry.getKnownChannelIds(prefix) : List.of();
                return known.isEmpty()
                        ? new ArrayList<>(Arrays.asList("global", "local")) : known;
            }
            case "leave": {
                UUID playerId = sender.getPlayerId();
                if (playerId == null) {
                    return new ArrayList<>();
                }
                PlayerChannelState state = chatCore.getState(playerId);
                if (state == null) {
                    return new ArrayList<>();
                }
                return state.getJoinedChannels().stream()
                        .filter(id -> id != null && id.toLowerCase().startsWith(prefix))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
            }
            case "ignore": {
                List<String> completions = new ArrayList<>();
                if (IgnoreCommandService.LIST_ARG.startsWith(prefix)) {
                    completions.add(IgnoreCommandService.LIST_ARG);
                }
                platform.getOnlinePlayers().stream()
                        .map(ProxyPlayer::getName)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(completions::add);
                return completions;
            }
            case "msg": {
                if (args.length == 2) {
                    // First argument of /nc msg: online player names (UX §2.3).
                    return platform.getOnlinePlayers().stream()
                            .map(ProxyPlayer::getName)
                            .filter(name -> name.toLowerCase().startsWith(prefix))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.toList());
                }
                return new ArrayList<>();
            }
            case "unignore": {
                UUID playerId = sender.getPlayerId();
                if (playerId == null || ignoreListService == null) {
                    return new ArrayList<>();
                }
                return ignoreListService.listIgnored(playerId).stream()
                        .filter(name -> name.startsWith(prefix))
                        .collect(Collectors.toList());
            }
            default:
                return new ArrayList<>();
        }
    }

    /**
     * @param sender the command sender
     * @return whether the sender may run {@code /nc} at all. Always {@code true}
     *         for players: basic-user dispatch must not be permission-gated (see
     *         the Velocity 4.1.0 node-pruning regression).
     */
    public boolean canUse(ProxyCommandSender sender) {
        // `novachat.use` is a basic-user permission that must default to TRUE for
        // all players (mirrors BungeeCord's default-grant-all provider).
        //
        // Velocity 4.1.0 changed Brigadier dispatch: when a SimpleCommand's
        // hasPermission returns false for a sender, Velocity prunes the command
        // node's children from that sender's dispatch tree (3.4.0 did not). An
        // offline-mode player has NO granted permissions, so gating on
        // `novachat.use` returned false, which pruned the `nc` node's subcommand
        // children (help/join/leave/...) and made every `/nc <sub>` report
        // "Incorrect argument at position 3: nc <--[HERE]" — a permission denial
        // masquerading as a syntax error.
        //
        // The admin subcommand `/nc reload` independently gates on
        // `novachat.admin` inside handleReload, so the admin gate is preserved.
        return true;
    }

    // ------------------------------------------------------------- subcommands

    /**
     * Shows help information.
     *
     * @param sender the command sender
     */
    public void showHelp(ProxyCommandSender sender) {
        UUID playerId = sender.getPlayerId();
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.title")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_help")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_join")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_leave")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_list")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_who")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_toggle")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_ignore")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_unignore")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_pm")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_reply")));
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(playerId, "chat.command.help.line_msg")));

        if (sender.hasPermission("novachat.admin")) {
            sender.sendMessage(chatCore.getFormatter().parseColors(
                    I18n.tr(playerId, "chat.command.help.line_reload")));
        }
    }

    /**
     * Handles {@code /nc join <channel> [password]} via
     * {@link ChannelCommandService} (optimistic local active channel on accepted
     * send).
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleJoin(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr(player.getUniqueId(), "chat.command.usage.join")));
            return;
        }

        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";

        PlayerChannelState state = chatCore.getOrCreateState(player);

        // Proxy platforms have no game-world concept; world-restricted channels
        // (NC-435) are not applicable to a forwarding proxy, so world is passed
        // as null by design. If world-scoped filtering ever becomes a proxy
        // requirement, the connected downstream server name is the natural
        // "world" value to forward here.
        CommandResult result = channelCommands.join(
                state, channelId, password, player.getName(), null);
        if (result.isSuccess()) {
            // §7: optimistic "joining…" receipt; the async ChannelActionResponsePacket
            // handler confirms with "已加入频道 X" once the backend accepts, or
            // surfaces an actionable error if it rejects.
            playerReply(player, chatCore.getFormatter().formatSuccess(
                    PlayerMessages.joining(player.getUniqueId(), channelId)));
            platform.logDebug("Player " + player.getName()
                    + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            playerReply(player, chatCore.getFormatter().formatError(
                    ErrorMessageFormatter.format(code)));
            platform.logDebug("Player " + player.getName() + " failed to join channel "
                    + channelId + ": " + result.getMessage());
        }
    }

    /**
     * Handles {@code /nc leave [channel]} via {@link ChannelCommandService}.
     *
     * <p>After a successful leave, prefers the configured default channel only
     * when it remains in local membership; selecting a channel must never
     * manufacture a JOIN the backend did not receive.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleLeave(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }

        PlayerChannelState state = chatCore.getOrCreateState(player);

        String requested = args.length > 0 ? args[0] : null;
        String leavingChannel = (requested != null && !requested.isBlank())
                ? requested : state.getActiveChannel();
        if (leavingChannel == null || leavingChannel.isBlank()) {
            playerReply(player, chatCore.getFormatter().formatError(
                    ErrorMessageFormatter.format(ErrorCode.NOT_IN_CHANNEL)));
            return;
        }

        CommandResult result = channelCommands.leave(state, leavingChannel, player.getName());
        if (result.isSuccess()) {
            // Prefer the configured default only when it is still a confirmed local
            // membership. setActiveChannel() would silently re-add a default that
            // this very LEAVE just removed without sending a matching JOIN.
            state.setActiveChannelIfJoined(config().getDefaultChannel());
            playerReply(player, chatCore.getFormatter().formatSuccess(
                    PlayerMessages.leaving(player.getUniqueId(), leavingChannel)));
            platform.logDebug("Player " + player.getName()
                    + " left channel: " + leavingChannel);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            playerReply(player, chatCore.getFormatter().formatError(
                    ErrorMessageFormatter.format(code)));
            platform.logDebug("Player " + player.getName() + " failed to leave channel "
                    + leavingChannel + ": " + result.getMessage());
        }
    }

    /**
     * Handles {@code /nc list}: shows the channels the backend advertised via
     * ConfigSync, marking those the player has joined (UX-DESIGN §2.2).
     *
     * @param sender the command sender
     */
    public void handleList(ProxyCommandSender sender) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }

        PlayerChannelState state = chatCore.getState(player.getUniqueId());
        Set<String> joined = state != null ? state.getJoinedChannels() : Set.of();

        List<String> lines = ListCommandService.formatChannelList(knownChannelRegistry, joined);

        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(player.getUniqueId(), "chat.command.list.title")));
        for (String line : lines) {
            playerReply(player, chatCore.getFormatter().formatSystemMessage(line));
        }
        sender.sendMessage(chatCore.getFormatter().parseColors(
                I18n.tr(player.getUniqueId(), "chat.command.list.tail")));
    }

    /**
     * Handles {@code /nc who [channel]} — sends a
     * {@link ChannelAction#WHO} request and shows the interim
     * {@code chat.who.fetching} prompt (UX-DESIGN §8.2). The asynchronous
     * response is rendered by the shared dispatcher adapter.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleWho(ProxyCommandSender sender, String[] args) {
        if (!WhoCommandService.isMemberListingSupported()) {
            sender.sendMessage(chatCore.getFormatter().formatSystemMessage(
                    WhoCommandService.getUnavailablePrompt()));
            return;
        }
        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client == null || !client.isConnected() || !client.isAuthenticated()) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr("chat.network.not_connected")));
            return;
        }

        UUID requesterId = sender.getPlayerId();
        ProxyPlayer requester = requesterId != null ? platform.getPlayer(requesterId) : null;
        String channelId = null;
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            channelId = args[0];
        } else if (requester != null) {
            PlayerChannelState state = chatCore.getOrCreateState(requester);
            String active = state != null ? state.getActiveChannel() : null;
            if (active != null && !active.isBlank()) {
                channelId = active;
            }
        }
        if (channelId == null || channelId.isBlank()) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr(requesterId, "chat.who.no_channel")));
            return;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, channelId);
        packet.addExtra("playerId", requesterId != null ? requesterId.toString() : "");
        if (requester != null) {
            packet.addExtra("requesterName", requester.getName());
        }
        if (requesterId != null) {
            packet.addExtra("requesterId", requesterId.toString());
        }
        client.sendPacket(packet);
        sender.sendMessage(chatCore.getFormatter().formatSystemMessage(
                WhoCommandService.getFetchingPrompt(channelId)));
    }

    /**
     * Handles {@code /nc toggle} via {@link ChannelCommandService#toggle}.
     * Local-only; no network packet.
     *
     * @param sender the command sender
     */
    public void handleToggle(ProxyCommandSender sender) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }

        PlayerChannelState state = chatCore.getOrCreateState(player);

        CommandResult result = channelCommands.toggle(state);
        if (!result.isSuccess()) {
            playerReply(player, chatCore.getFormatter().formatError(result.getMessage()));
            return;
        }

        ChatMode newMode = state.getChatMode();
        String modeText = ChatModeDescriptions.modeName(newMode);
        playerReply(player, chatCore.getFormatter().formatSuccess(
                I18n.tr(player.getUniqueId(), "chat.command.toggle.switched", modeText)));
        playerReply(player, chatCore.getFormatter().formatSystemMessage(
                ChatModeDescriptions.describe(newMode)));
        platform.logDebug("Player " + player.getName()
                + " toggled chat mode to: " + newMode);
    }

    /**
     * Handles {@code /nc ignore [<player>|list]} — validation, service calls and
     * receipt copy live in the shared {@link IgnoreCommandService}; this method
     * forwards arguments and renders the returned lines. Local-only.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleIgnore(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        for (String line : IgnoreCommandService.ignore(
                ignoreListService, player.getUniqueId(), player.getName(), args)) {
            playerReply(player, chatCore.getFormatter().parseColors(line));
        }
    }

    /**
     * Handles {@code /nc unignore <player>}.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleUnignore(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        for (String line : IgnoreCommandService.unignore(
                ignoreListService, player.getUniqueId(), args)) {
            playerReply(player, chatCore.getFormatter().parseColors(line));
        }
    }

    /**
     * Handles {@code /nc msg <player> <message...>} through the shared
     * {@link PrivateMessageCommandService}; the success confirmation is rendered
     * from the backend echo.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleMsg(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        for (String line : PrivateMessageCommandService.msg(
                this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(), config().getClientId(), args)) {
            playerReply(player, chatCore.getFormatter().parseColors(line));
        }
    }

    /**
     * Handles {@code /nc r <message...>} — replies to the most recent
     * private-message partner tracked by the shared
     * {@link PrivateMessageService}.
     *
     * @param sender the command sender
     * @param args the sub-arguments
     */
    public void handleReply(ProxyCommandSender sender, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        for (String line : PrivateMessageCommandService.reply(
                privateMessageService, this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(), config().getClientId(), args)) {
            playerReply(player, chatCore.getFormatter().parseColors(line));
        }
    }

    /**
     * Handles {@code /nc reload}. {@link ChannelCommandService#reload()} is
     * intentionally a no-op on the wire; the platform shell still owns the actual
     * config reload / reconnect, so it supplies a callback.
     *
     * @param sender the command sender
     */
    public void handleReload(ProxyCommandSender sender) {
        if (!sender.hasPermission("novachat.admin")) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    ErrorMessageFormatter.format(ErrorCode.FORBIDDEN)));
            return;
        }

        // Signal intent through shared service (documented no-op), then do platform reload.
        channelCommands.reload();
        platform.reloadConfiguration();
        sender.sendMessage(chatCore.getFormatter().formatSuccess(
                I18n.tr(sender.getPlayerId(), "chat.command.reload.success")));
    }

    /**
     * Handles {@code /nc <channel> <message...>}: sends a message to a specific
     * channel.
     *
     * @param sender the command sender
     * @param channelId the target channel id (the first argument)
     * @param args the remaining arguments (the message words)
     */
    public void handleChannelMessage(ProxyCommandSender sender, String channelId, String[] args) {
        ProxyPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 1) {
            playerReply(player, chatCore.getFormatter().formatError(
                    I18n.tr(player.getUniqueId(), "chat.command.usage.msg")));
            return;
        }
        String message = String.join(" ", args);
        chatCore.sendToChannel(player, channelId, message);
        platform.logDebug("Player " + player.getName()
                + " sent message to channel " + channelId + ": " + message);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return the current config snapshot
     */
    private ProxyConfigView config() {
        return configSupplier.get();
    }

    /**
     * Resolves the sender to a player handle, replying with the shared
     * "players only" error for console senders.
     *
     * @param sender the command sender
     * @return the player, or null (after the error was sent)
     */
    private ProxyPlayer requirePlayer(ProxyCommandSender sender) {
        UUID playerId = sender.getPlayerId();
        if (playerId == null) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr("chat.command.player_only")));
            return null;
        }
        ProxyPlayer player = platform.getPlayer(playerId);
        if (player == null) {
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr("chat.command.player_only")));
        }
        return player;
    }

    /**
     * Sends a pre-formatted line to a player through the platform adapter.
     *
     * @param player the recipient
     * @param legacyText the legacy-coded line
     */
    private void playerReply(ProxyPlayer player, String legacyText) {
        platform.sendMessage(player, legacyText);
    }

    /** Transmits a private-message packet when the backend link is up. */
    private boolean sendPrivateMessagePacket(PrivateMessagePacket packet) {
        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client == null || !client.isConnected()) {
            return false;
        }
        client.sendPacket(packet);
        return true;
    }
}
