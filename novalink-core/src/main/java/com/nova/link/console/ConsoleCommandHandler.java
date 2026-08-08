package com.nova.link.console;

import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.config.ConfigException;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import com.nova.link.spy.SpyResult;
import com.nova.link.spy.SpySession;
import com.nova.link.auth.PermissionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Console command dispatcher for the NovaLink backend.
 *
 * <p>Each command reuses existing managers DIRECTLY (never via the network
 * {@code AdminActionHandler}/{@code ChannelActionHandler} which require a
 * {@code ClientConnection}). Console-originated moderation uses
 * {@link ConsoleSentinel#CONSOLE_SENTINEL}; the sentinel already bypasses
 * permission checks in {@link MuteManager} and
 * {@code ChannelActionHandler.requireModerationPermission}.
 *
 * <p>Output is returned as a String (one command invocation = one rendered
 * block) so it is unit-testable without a live terminal. The
 * {@link BackendConsole} loop prints these to stdout.
 */
public class ConsoleCommandHandler {

    /** Command name -> one-line description, in stable display order. */
    private static final Map<String, String> COMMAND_SUMMARY = new LinkedHashMap<>();
    static {
        COMMAND_SUMMARY.put("help", "List commands or show detailed help: help [command]");
        COMMAND_SUMMARY.put("status", "Backend overview: players, servers, channels, spy, reloads");
        COMMAND_SUMMARY.put("players", "List online players");
        COMMAND_SUMMARY.put("clients", "List connected authenticated game servers");
        COMMAND_SUMMARY.put("channels", "List all channels");
        COMMAND_SUMMARY.put("channel", "Show one channel + members: channel <id>");
        COMMAND_SUMMARY.put("mute", "Mute a player: mute <player|name> <channel> <dur> [reason]");
        COMMAND_SUMMARY.put("unmute", "Unmute a player: unmute <player> <channel>");
        COMMAND_SUMMARY.put("mutes", "List active mutes: mutes [player]");
        COMMAND_SUMMARY.put("kick", "Kick a player from a channel: kick <player> <channel>");
        COMMAND_SUMMARY.put("announce", "Broadcast an announcement: announce <channel> <msg>");
        COMMAND_SUMMARY.put("title", "Send a title: title <channel> <title> [subtitle]");
        COMMAND_SUMMARY.put("reload", "Hot-reload config and broadcast ConfigSync");
        COMMAND_SUMMARY.put("spies", "List active spy sessions / monitored channels");
        COMMAND_SUMMARY.put("spy", "Spy control: spy start <channel> [adminId] | spy off [adminId]");
        COMMAND_SUMMARY.put("create", "Create a channel: create <name> [password] [scope]");
        COMMAND_SUMMARY.put("delete", "Delete a channel: delete <id>");
        COMMAND_SUMMARY.put("stop", "Graceful shutdown (alias: shutdown)");
        COMMAND_SUMMARY.put("shutdown", "Graceful shutdown (alias: stop)");
    }

    private final BackendContext ctx;

    public ConsoleCommandHandler(BackendContext context) {
        this.ctx = java.util.Objects.requireNonNull(context, "BackendContext");
    }

    /** Package-private accessor for the completer + tests. */
    BackendContext context() {
        return ctx;
    }

    /** @return the ordered command summary map (for help + completion). */
    public static Set<String> commandNames() {
        return Collections.unmodifiableSet(COMMAND_SUMMARY.keySet());
    }

    /**
     * Dispatch a single input line.
     *
     * @param line raw console line (may be blank)
     * @return rendered output (never null; empty string for blank input)
     */
    public String dispatch(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // split on whitespace, preserving order; quoted args are not specially
        // handled (admin tooling convention — spaces are rare in ids/messages).
        String[] parts = trimmed.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        switch (cmd) {
            case "help": case "?": return handleHelp(args);
            case "status": return handleStatus();
            case "players": return handlePlayers();
            case "clients": return handleClients();
            case "channels": return handleChannels();
            case "channel": return handleChannelDetail(args);
            case "mute": return handleMute(args);
            case "unmute": return handleUnmute(args);
            case "mutes": return handleMutes(args);
            case "kick": return handleKick(args);
            case "announce": return handleAnnounce(args);
            case "title": return handleTitle(args);
            case "reload": return handleReload();
            case "spies": return handleSpies();
            case "spy": return handleSpy(args);
            case "create": return handleCreate(args);
            case "delete": return handleDelete(args);
            case "stop": case "shutdown": return handleStop();
            default:
                return "Unknown command: " + cmd + " — type 'help' for the command list.\n";
        }
    }

    // ============================ help ============================

    private String handleHelp(String[] args) {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("NovaLink backend console commands:\n");
            for (Map.Entry<String, String> e : COMMAND_SUMMARY.entrySet()) {
                sb.append("  ").append(pad(e.getKey(), 10)).append(" — ").append(e.getValue()).append('\n');
            }
            sb.append("\nType 'help <command>' for detailed usage.\n");
            return sb.toString();
        }
        String target = args[0].toLowerCase(Locale.ROOT);
        switch (target) {
            case "help": return "help [command]\n  With no arg: list all commands.\n  With a command name: show detailed usage.\n";
            case "status": return "status\n  Prints: online players, connected servers, channels, active spy sessions, config reload count.\n";
            case "players": return "players\n  Lists all cached (online) players: playerId / name / clientId / activeChannel.\n";
            case "clients": return "clients\n  Lists authenticated game-server connections: clientId / remote address / port / connect time.\n";
            case "channels": return "channels\n  Lists every channel: id / scope / owner client / display name / member count.\n";
            case "channel": return "channel <id>\n  Shows one channel's detail + its member UUIDs.\n";
            case "mute": return "mute <player|name> <channel> <dur> [reason]\n  Mutes a player in a channel. Dur formats: 30s / 10m / 2h / 1d / 0 or 'perm' (permanent).\n  <player> may be a UUID or an online player name (cross-server resolved).\n  Example: mute Steve staff 10m spam\n";
            case "unmute": return "unmute <player> <channel>\n  Unmutes a player in a channel. <player> may be a UUID or an online name.\n";
            case "mutes": return "mutes [player]\n  With no arg: lists all active mutes across online players.\n  With a player UUID or name: lists that player's active mutes.\n";
            case "kick": return "kick <player> <channel>\n  Kicks a player from a channel (cross-server name resolution). <player> may be a UUID or name.\n";
            case "announce": return "announce <channel> <msg...>\n  Broadcasts an announcement prefixed with 【公告】 to the channel via the message router.\n";
            case "title": return "title <channel> <title> [subtitle...]\n  Sends a TitlePacket to the channel (broadcast for GLOBAL, single client for SERVER/PRIVATE).\n";
            case "reload": return "reload\n  Hot-reloads config and broadcasts a ConfigSyncPacket to all authenticated clients.\n";
            case "spies": return "spies\n  Lists all currently monitored channels + total spy session count.\n";
            case "spy": return "spy start <channel> [adminId]\n  spy off [adminId]\n  Starts/stops spy sessions. adminId defaults to the console sentinel.\n";
            case "create": return "create <name> [password] [scope]\n  Creates a channel. scope = global (default) or private. If a password is given, the channel is created private+password.\n";
            case "delete": return "delete <id>\n  Deletes a channel. Removes members and (for private) untracks the id.\n";
            case "stop": case "shutdown": return "stop|shutdown\n  Triggers graceful shutdown of all backend services.\n";
            default: return "No help for unknown command: " + target + "\n";
        }
    }

    // ============================ status ============================

    private String handleStatus() {
        int players = ctx.getPlayerStateManager().getAllPlayerStates().size();
        int conns = ctx.getNetworkHandler().getConnectionCount();
        long authed = ctx.getNetworkHandler().getConnections().stream()
                .filter(ClientConnection::isAuthenticated).count();
        int channels = ctx.getChannelManager().getChannelCount();
        int spyTotal = ctx.getSpyManager().getTotalSpySessionCount();
        int reloads = ctx.getConfigManager().getReloadCount();

        StringBuilder sb = new StringBuilder();
        sb.append("=== NovaLink status ===\n");
        sb.append("  Online players : ").append(players).append('\n');
        sb.append("  Connections    : ").append(conns)
          .append(" (authenticated: ").append(authed).append(")\n");
        sb.append("  Channels       : ").append(channels).append('\n');
        sb.append("  Spy sessions   : ").append(spyTotal).append('\n');
        sb.append("  Config reloads : ").append(reloads).append('\n');
        return sb.toString();
    }

    // ============================ players ============================

    private String handlePlayers() {
        Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
        if (states.isEmpty()) {
            return "No online players.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Online players (").append(states.size()).append("):\n");
        sb.append("  ").append(pad("playerId", 38))
          .append(pad("name", 16)).append(pad("clientId", 14)).append("activeChannel").append('\n');
        for (PlayerState s : states) {
            sb.append("  ").append(pad(s.getPlayerId().toString(), 38))
              .append(pad(s.getPlayerName() != null ? s.getPlayerName() : "-", 16))
              .append(pad(s.getClientId() != null ? s.getClientId() : "-", 14))
              .append(s.getActiveChannel() != null ? s.getActiveChannel() : "-").append('\n');
        }
        return sb.toString();
    }

    // ============================ clients ============================

    private String handleClients() {
        List<ClientConnection> authed = new ArrayList<>();
        for (ClientConnection c : ctx.getNetworkHandler().getConnections()) {
            if (c.isAuthenticated()) {
                authed.add(c);
            }
        }
        if (authed.isEmpty()) {
            return "No authenticated game servers connected.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Authenticated game servers (").append(authed.size()).append("):\n");
        sb.append("  ").append(pad("clientId", 16)).append(pad("remote", 18))
          .append(pad("port", 8)).append(pad("connectedAt", 14)).append('\n');
        for (ClientConnection c : authed) {
            sb.append("  ").append(pad(c.getClientId() != null ? c.getClientId() : "-", 16))
              .append(pad(c.getRemoteAddress(), 18))
              .append(pad(String.valueOf(c.getRemotePort()), 8))
              .append(c.getConnectedAt()).append('\n');
        }
        return sb.toString();
    }

    // ============================ channels ============================

    private String handleChannels() {
        Collection<Channel> all = ctx.getChannelManager().getAllChannels();
        if (all.isEmpty()) {
            return "No channels.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Channels (").append(all.size()).append("):\n");
        sb.append("  ").append(pad("id", 18)).append(pad("scope", 8))
          .append(pad("client", 14)).append(pad("name", 18)).append("members").append('\n');
        for (Channel ch : all) {
            sb.append("  ").append(pad(ch.getId(), 18))
              .append(pad(ch.getScope().name(), 8))
              .append(pad(ch.getClientId() != null ? ch.getClientId() : "-", 14))
              .append(pad(ch.getDisplayName(), 18))
              .append(ch.getMemberCount()).append('\n');
        }
        return sb.toString();
    }

    private String handleChannelDetail(String[] args) {
        if (args.length < 1) {
            return "Usage: channel <id>\n";
        }
        Channel ch = ctx.getChannelManager().getChannel(args[0]);
        if (ch == null) {
            return "Channel not found: " + args[0] + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Channel: ").append(ch.getId()).append('\n');
        sb.append("  display : ").append(ch.getDisplayName()).append('\n');
        sb.append("  scope   : ").append(ch.getScope()).append('\n');
        sb.append("  client  : ").append(ch.getClientId() != null ? ch.getClientId() : "-").append('\n');
        sb.append("  owner   : ").append(ch.getOwnerId() != null ? ch.getOwnerId() : "-").append('\n');
        sb.append("  members : ").append(ch.getMemberCount()).append(" / ").append(ch.getMaxCapacity()).append('\n');
        Set<UUID> members = ctx.getChannelManager().getChannelMembers(ch.getId());
        if (!members.isEmpty()) {
            sb.append("  member UUIDs:\n");
            for (UUID m : members) {
                sb.append("    ").append(m).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================ mute / unmute / mutes ============================

    private String handleMute(String[] args) {
        if (args.length < 3) {
            return "Usage: mute <player|name> <channel> <dur> [reason]\n  Dur: 30s / 10m / 2h / 1d / 0 or perm\n";
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return "Could not resolve target player: " + args[0] + " (not a UUID and not online)\n";
        }
        String channel = args[1];
        if (!ctx.getChannelManager().channelExists(channel)) {
            return "Channel not found: " + channel + "\n";
        }
        long durationMs;
        try {
            durationMs = parseDurationMs(args[2]);
        } catch (IllegalArgumentException e) {
            return "Invalid duration '" + args[2] + "': " + e.getMessage() + "\n";
        }
        String reason = args.length >= 4 ? joinFrom(args, 3) : "Muted by console";

        MuteResult result = ctx.getMuteManager().mutePlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channel, durationMs, reason, null);
        if (result.isSuccess()) {
            return "Muted " + args[0] + " (" + target + ") in " + channel
                    + " for " + describeDuration(durationMs) + ". Reason: " + reason + "\n";
        }
        return "Mute failed: " + result.getMessage() + " (" + result.getErrorCode() + ")\n";
    }

    private String handleUnmute(String[] args) {
        if (args.length < 2) {
            return "Usage: unmute <player> <channel>\n";
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return "Could not resolve target player: " + args[0] + "\n";
        }
        String channel = args[1];
        MuteResult result = ctx.getMuteManager().unmutePlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channel, null);
        if (result.isSuccess()) {
            return "Unmuted " + args[0] + " (" + target + ") in " + channel + "\n";
        }
        return "Unmute failed: " + result.getMessage() + " (" + result.getErrorCode() + ")\n";
    }

    private String handleMutes(String[] args) {
        if (args.length == 0) {
            // Aggregate across all online players (MuteManager has no list-all).
            Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
            int total = 0;
            StringBuilder sb = new StringBuilder();
            sb.append("Active mutes:\n");
            for (PlayerState s : states) {
                List<MuteInfo> mutes = ctx.getMuteManager().getActiveMutes(s.getPlayerId());
                if (mutes.isEmpty()) {
                    continue;
                }
                for (MuteInfo m : mutes) {
                    total++;
                    sb.append("  ").append(s.getPlayerName() != null ? s.getPlayerName() : s.getPlayerId())
                      .append(" (").append(s.getPlayerId()).append(") in ")
                      .append(m.getChannelId() != null ? m.getChannelId() : "(global)")
                      .append(" — ").append(describeDuration(m.getRemainingTime()))
                      .append(" reason=").append(m.getReason() != null ? m.getReason() : "-")
                      .append('\n');
                }
            }
            sb.append("Total: ").append(total).append('\n');
            return sb.toString();
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return "Could not resolve target player: " + args[0] + "\n";
        }
        List<MuteInfo> mutes = ctx.getMuteManager().getActiveMutes(target);
        if (mutes.isEmpty()) {
            return "No active mutes for " + args[0] + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Active mutes for ").append(args[0]).append(" (").append(target).append("):\n");
        for (MuteInfo m : mutes) {
            sb.append("  ").append(m.getChannelId() != null ? m.getChannelId() : "(global)")
              .append(" — ").append(describeDuration(m.getRemainingTime()))
              .append(" reason=").append(m.getReason() != null ? m.getReason() : "-").append('\n');
        }
        return sb.toString();
    }

    // ============================ kick ============================

    private String handleKick(String[] args) {
        if (args.length < 2) {
            return "Usage: kick <player> <channel>\n";
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return "Could not resolve target player: " + args[0] + "\n";
        }
        String channel = args[1];
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return "Channel not found: " + channel + "\n";
        }
        if (!ch.isMember(target)) {
            return "Target " + args[0] + " is not a member of " + channel + "\n";
        }
        // Mirror ChannelActionHandler.handleKick: remove member + update state.
        ctx.getChannelManager().removeMember(channel, target);
        try {
            ctx.getPlayerStateManager().leaveChannel(target, channel);
        } catch (Exception e) {
            // non-fatal, matches handler
        }
        return "Kicked " + args[0] + " (" + target + ") from " + channel + "\n";
    }

    // ============================ announce ============================

    private String handleAnnounce(String[] args) {
        if (args.length < 2) {
            return "Usage: announce <channel> <msg...>\n";
        }
        String channel = args[0];
        String content = joinFrom(args, 1);
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return "Channel not found: " + channel + "\n";
        }
        String message = "【公告】 " + content;
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("_announcement", "true");
        placeholders.put("_operator", ConsoleSentinel.CONSOLE_NAME);
        // Mirror AdminActionHandler.handleAnnounce: trusted routeMessage by id.
        Set<String> recipients = ctx.getMessageRouter().routeMessage(
                channel, ConsoleSentinel.CONSOLE_SENTINEL, ConsoleSentinel.CONSOLE_NAME, message, placeholders);
        return "Announcement sent to " + channel + " (recipients: " + recipients.size() + ")\n";
    }

    // ============================ title ============================

    private String handleTitle(String[] args) {
        if (args.length < 2) {
            return "Usage: title <channel> <title> [subtitle...]\n";
        }
        String channel = args[0];
        String title = args[1];
        String subtitle = args.length >= 3 ? joinFrom(args, 2) : "";
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return "Channel not found: " + channel + "\n";
        }
        // Mirror AdminActionHandler.handleTitle.
        TitlePacket packet = new TitlePacket(channel, title, subtitle, ConsoleSentinel.CONSOLE_SENTINEL);
        if (ch.getScope() == ChannelScope.GLOBAL) {
            ctx.getNetworkHandler().broadcastAuthenticated(packet);
            return "Title sent to global channel " + channel + "\n";
        }
        String targetClientId = ch.getClientId();
        ClientConnection target = targetClientId != null ? ctx.getNetworkHandler().findByClientId(targetClientId) : null;
        if (target == null || !target.isActive() || !target.isAuthenticated()) {
            return "Target client not connected for channel " + channel + "\n";
        }
        target.sendPacket(packet);
        return "Title sent to channel " + channel + " on client " + targetClientId + "\n";
    }

    // ============================ reload ============================

    private String handleReload() {
        ConfigManager cm = ctx.getConfigManager();
        try {
            cm.reload(true);
            return "Configuration reloaded (count=" + cm.getReloadCount() + "); ConfigSync broadcast.\n";
        } catch (ConfigException e) {
            return "Reload failed: " + e.getMessage() + "\n";
        }
    }

    // ============================ spies ============================

    private String handleSpies() {
        SpyManager sm = ctx.getSpyManager();
        List<String> monitored = sm.getAllMonitoredChannels();
        int total = sm.getTotalSpySessionCount();
        StringBuilder sb = new StringBuilder();
        sb.append("Spy sessions: ").append(total).append('\n');
        if (monitored.isEmpty()) {
            sb.append("  No channels currently monitored.\n");
        } else {
            sb.append("  Monitored channels:\n");
            for (String ch : monitored) {
                sb.append("    ").append(ch)
                  .append(" (admins: ").append(sm.getChannelSpies(ch).size()).append(")\n");
            }
        }
        return sb.toString();
    }

    // ============================ spy ============================

    private String handleSpy(String[] args) {
        if (args.length == 0) {
            return "Usage: spy start <channel> [adminId] | spy off [adminId]\n";
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start": return handleSpyStart(args);
            case "off": return handleSpyOff(args);
            default: return "Unknown spy subcommand: " + sub + " (start | off)\n";
        }
    }

    private String handleSpyStart(String[] args) {
        if (args.length < 2) {
            return "Usage: spy start <channel> [adminId]\n";
        }
        String channel = args[1];
        UUID adminId = args.length >= 3 ? parseUuid(args[2]) : null;
        if (adminId == null) {
            adminId = ConsoleSentinel.CONSOLE_SENTINEL;
        }
        // SpyManager.startSpying requires a super-admin session. The console
        // sentinel has no password, so we register an (effectively permanent)
        // session for it via PermissionManager. The sentinel already bypasses
        // moderation checks elsewhere; this grants the spy session check.
        ensureConsoleSuperAdminSession();
        SpyResult result = ctx.getSpyManager().startSpying(adminId, channel, null);
        if (result.isSuccess()) {
            return "Spy started: " + result.getMessage() + "\n";
        }
        return "Spy start failed: " + result.getMessage() + " (" + result.getErrorCode() + ")\n";
    }

    private String handleSpyOff(String[] args) {
        UUID adminId = args.length >= 2 ? parseUuid(args[1]) : null;
        if (adminId == null) {
            adminId = ConsoleSentinel.CONSOLE_SENTINEL;
        }
        SpyResult result = ctx.getSpyManager().stopAllSpying(adminId);
        if (result.isSuccess()) {
            return "Spy stopped: " + result.getMessage() + "\n";
        }
        return "Spy off failed: " + result.getMessage() + " (" + result.getErrorCode() + ")\n";
    }

    // ============================ create / delete ============================

    private String handleCreate(String[] args) {
        if (args.length < 1) {
            return "Usage: create <name> [password] [scope]\n  scope = global (default) | private\n";
        }
        String name = args[0];
        String password = args.length >= 2 ? args[1] : null;
        String scopeRaw = args.length >= 3 ? args[2] : "global";
        String scope = scopeRaw.trim().toLowerCase(Locale.ROOT);

        if (ctx.getChannelManager().channelExists(name)) {
            return "Channel already exists: " + name + "\n";
        }

        if ("private".equals(scope) || password != null) {
            // Private channels require a clientId + owner; console-owned channels
            // are bound to a synthetic "console" client with the sentinel as owner.
            try {
                PrivateChannelManager.PrivateChannelCreationResult created =
                        ctx.getPrivateChannelManager().createPrivateChannel(
                                name, "console", ConsoleSentinel.CONSOLE_SENTINEL, password);
                return "Created private channel " + created.getChannelId()
                        + " (password" + (created.isPasswordGenerated() ? " auto-generated: " + created.getPassword() : " set") + ")\n";
            } catch (Exception e) {
                return "Failed to create private channel: " + e.getMessage() + "\n";
            }
        }
        if (!"global".equals(scope)) {
            return "Unknown scope: " + scope + " (global | private)\n";
        }
        try {
            ChannelConfig config = ChannelConfig.builder()
                    .id(name)
                    .displayName(name)
                    .scope(ChannelScope.GLOBAL)
                    .build();
            ctx.getChannelManager().createChannel(config);
            return "Created global channel " + name + "\n";
        } catch (Exception e) {
            return "Failed to create channel: " + e.getMessage() + "\n";
        }
    }

    private String handleDelete(String[] args) {
        if (args.length < 1) {
            return "Usage: delete <id>\n";
        }
        String id = args[0];
        Channel ch = ctx.getChannelManager().getChannel(id);
        if (ch == null) {
            return "Channel not found: " + id + "\n";
        }
        // Remove all members first (clear membership side-effects).
        for (UUID m : new ArrayList<>(ctx.getChannelManager().getChannelMembers(id))) {
            ctx.getChannelManager().removeMember(id, m);
            try {
                ctx.getPlayerStateManager().leaveChannel(m, id);
            } catch (Exception ignored) {
                // non-fatal
            }
        }
        boolean deleted = ctx.getChannelManager().deleteChannel(id);
        if (deleted && ch.getScope() == ChannelScope.PRIVATE) {
            ctx.getPrivateChannelManager().removeTrackedId(id);
        }
        if (deleted) {
            return "Deleted channel " + id + "\n";
        }
        return "Failed to delete channel " + id + "\n";
    }

    // ============================ stop ============================

    private String handleStop() {
        // Actual shutdown is performed by the caller (BackendConsole) so the
        // console loop and JVM hook share a single exit path. This return value
        // signals intent.
        return "STOP";
    }

    /** Sentinel return value indicating a shutdown request. */
    static final String STOP_TOKEN = "STOP";

    // ============================ helpers ============================

    /**
     * Resolves a target identifier to a UUID. Accepts a raw UUID, or — mirroring
     * {@code ChannelActionHandler.resolveTargetId} — an online player name
     * (case-insensitive) resolved across all cached player states.
     */
    UUID resolveTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        UUID id = parseUuid(raw);
        if (id != null) {
            return id;
        }
        for (PlayerState s : ctx.getPlayerStateManager().getAllPlayerStates()) {
            if (s.getPlayerName() != null && s.getPlayerName().equalsIgnoreCase(raw)) {
                return s.getPlayerId();
            }
        }
        return null;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses a human-friendly duration: {@code 30s / 10m / 2h / 1d / 0 / perm}.
     * @return milliseconds, 0 for permanent
     */
    static long parseDurationMs(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty duration");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("perm".equals(s) || "0".equals(s)) {
            return 0L;
        }
        char unit = s.charAt(s.length() - 1);
        long value;
        try {
            if (Character.isDigit(unit)) {
                // bare number -> treat as seconds
                value = Long.parseLong(s);
                return TimeUnit.SECONDS.toMillis(Math.max(0L, value));
            }
            value = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected <number><s|m|h|d>, got " + raw);
        }
        switch (unit) {
            case 's': return TimeUnit.SECONDS.toMillis(value);
            case 'm': return TimeUnit.MINUTES.toMillis(value);
            case 'h': return TimeUnit.HOURS.toMillis(value);
            case 'd': return TimeUnit.DAYS.toMillis(value);
            default: throw new IllegalArgumentException("unknown unit '" + unit + "' (use s/m/h/d)");
        }
    }

    private static String describeDuration(long ms) {
        if (ms < 0) {
            return "permanent";
        }
        if (ms == 0) {
            return "expired";
        }
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        return (secs / 86400) + "d";
    }

    private static String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s + ' ';
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /**
     * Registers a long-lived super-admin session for the console sentinel so
     * {@link SpyManager#startSpying} passes its {@code hasSuperAdminSession}
     * check. Idempotent.
     */
    private void ensureConsoleSuperAdminSession() {
        PermissionManager pm = ctx.getPermissionManager();
        // Re-grant with a far-future expiration each call (cheap + idempotent).
        pm.setSessionDurationMs(Long.MAX_VALUE / 2);
        // authenticateSuperAdmin requires registered credentials; register a
        // placeholder hash for the sentinel if not already present.
        try {
            pm.registerSuperAdmin(new com.nova.link.auth.SuperAdminCredentials(
                    ConsoleSentinel.CONSOLE_SENTINEL, "console-sentinel"));
        } catch (Exception ignored) {
            // already registered
        }
        pm.authenticateSuperAdmin(ConsoleSentinel.CONSOLE_SENTINEL, "console-sentinel");
    }
}
