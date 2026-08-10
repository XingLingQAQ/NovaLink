package com.nova.link.console;

import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.link.ban.BanManager;
import com.nova.link.ban.BanResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.config.ConfigException;
import com.nova.link.config.ConfigManager;
import com.nova.link.database.BanInfo;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
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

    /** Command name -> i18n key for its one-line summary, in stable display order. */
    private static final Map<String, String> COMMAND_SUMMARY = new LinkedHashMap<>();
    static {
        COMMAND_SUMMARY.put("help", "console.summary.help");
        COMMAND_SUMMARY.put("status", "console.summary.status");
        COMMAND_SUMMARY.put("players", "console.summary.players");
        COMMAND_SUMMARY.put("clients", "console.summary.clients");
        COMMAND_SUMMARY.put("channels", "console.summary.channels");
        COMMAND_SUMMARY.put("channel", "console.summary.channel");
        COMMAND_SUMMARY.put("mute", "console.summary.mute");
        COMMAND_SUMMARY.put("unmute", "console.summary.unmute");
        COMMAND_SUMMARY.put("mutes", "console.summary.mutes");
        COMMAND_SUMMARY.put("ban", "console.summary.ban");
        COMMAND_SUMMARY.put("unban", "console.summary.unban");
        COMMAND_SUMMARY.put("bans", "console.summary.bans");
        COMMAND_SUMMARY.put("kick", "console.summary.kick");
        COMMAND_SUMMARY.put("announce", "console.summary.announce");
        COMMAND_SUMMARY.put("title", "console.summary.title");
        COMMAND_SUMMARY.put("reload", "console.summary.reload");
        COMMAND_SUMMARY.put("spies", "console.summary.spies");
        COMMAND_SUMMARY.put("spy", "console.summary.spy");
        COMMAND_SUMMARY.put("create", "console.summary.create");
        COMMAND_SUMMARY.put("delete", "console.summary.delete");
        COMMAND_SUMMARY.put("stop", "console.summary.stop");
        COMMAND_SUMMARY.put("shutdown", "console.summary.shutdown");
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
            case "ban": return handleBan(args);
            case "unban": return handleUnban(args);
            case "bans": return handleBans(args);
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
                return nl(I18n.tr("console.unknown_command", cmd));
        }
    }

    // ============================ help ============================

    private String handleHelp(String[] args) {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(I18n.tr("console.help.title")).append('\n');
            for (Map.Entry<String, String> e : COMMAND_SUMMARY.entrySet()) {
                sb.append("  ").append(pad(e.getKey(), 10)).append(" — ").append(I18n.tr(e.getValue())).append('\n');
            }
            sb.append(I18n.tr("console.help.tail"));
            return sb.toString();
        }
        String target = args[0].toLowerCase(Locale.ROOT);
        String key = "console.help." + target;
        // stop/shutdown share the same help text key; map both.
        if ("shutdown".equals(target)) {
            key = "console.help.stop";
        }
        // Verify the key exists in the bundle; if not, fall back to the
        // unknown-help message (matches the old "No help for unknown command").
        String resolved = I18n.tr(key);
        if (resolved.equals(key)) {
            return nl(I18n.tr("console.help.unknown", target));
        }
        return resolved;
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
        sb.append(I18n.tr("console.status.header")).append('\n');
        sb.append(I18n.tr("console.status.online_players", players)).append('\n');
        sb.append(I18n.tr("console.status.connections", conns, authed)).append('\n');
        sb.append(I18n.tr("console.status.channels", channels)).append('\n');
        sb.append(I18n.tr("console.status.spy_sessions", spyTotal)).append('\n');
        sb.append(I18n.tr("console.status.config_reloads", reloads)).append('\n');
        return sb.toString();
    }

    // ============================ players ============================

    private String handlePlayers() {
        Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
        if (states.isEmpty()) {
            return nl(I18n.tr("console.players.empty"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.players.header", states.size()));
        sb.append("  ").append(pad(I18n.tr("console.players.col_playerId"), 38))
          .append(pad(I18n.tr("console.players.col_name"), 16))
          .append(pad(I18n.tr("console.players.col_clientId"), 14))
          .append(I18n.tr("console.players.col_activeChannel")).append('\n');
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
            return nl(I18n.tr("console.clients.empty"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.clients.header", authed.size()));
        sb.append("  ").append(pad(I18n.tr("console.clients.col_clientId"), 16))
          .append(pad(I18n.tr("console.clients.col_remote"), 18))
          .append(pad(I18n.tr("console.clients.col_port"), 8))
          .append(I18n.tr("console.clients.col_connectedAt")).append('\n');
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
            return nl(I18n.tr("console.channels.empty"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.channels.header", all.size()));
        sb.append("  ").append(pad(I18n.tr("console.channels.col_id"), 18))
          .append(pad(I18n.tr("console.channels.col_scope"), 8))
          .append(pad(I18n.tr("console.channels.col_client"), 14))
          .append(pad(I18n.tr("console.channels.col_name"), 18))
          .append(I18n.tr("console.channels.col_members")).append('\n');
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
            return nl(I18n.tr("console.channel.usage"));
        }
        Channel ch = ctx.getChannelManager().getChannel(args[0]);
        if (ch == null) {
            return nl(I18n.tr("console.channel.not_found", args[0]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.channel.header", ch.getId())).append('\n');
        sb.append(I18n.tr("console.channel.display", ch.getDisplayName())).append('\n');
        sb.append(I18n.tr("console.channel.scope", ch.getScope())).append('\n');
        sb.append(I18n.tr("console.channel.client", ch.getClientId() != null ? ch.getClientId() : "-")).append('\n');
        sb.append(I18n.tr("console.channel.owner", ch.getOwnerId() != null ? ch.getOwnerId() : "-")).append('\n');
        sb.append(I18n.tr("console.channel.members", ch.getMemberCount(), ch.getMaxCapacity())).append('\n');
        Set<UUID> members = ctx.getChannelManager().getChannelMembers(ch.getId());
        if (!members.isEmpty()) {
            sb.append(I18n.tr("console.channel.member_uuids")).append('\n');
            for (UUID m : members) {
                sb.append(I18n.tr("console.channel.member_uuid_item", m)).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================ mute / unmute / mutes ============================

    private String handleMute(String[] args) {
        if (args.length < 3) {
            return nl(I18n.tr("console.mute.usage"));
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.mute.target_unresolved", args[0]));
        }
        String channel = args[1];
        if (!ctx.getChannelManager().channelExists(channel)) {
            return nl(I18n.tr("console.mute.channel_not_found", channel));
        }
        long durationMs;
        try {
            durationMs = parseDurationMs(args[2]);
        } catch (IllegalArgumentException e) {
            return nl(I18n.tr("console.mute.invalid_duration", args[2], e.getMessage()));
        }
        String reason = args.length >= 4 ? joinFrom(args, 3) : I18n.tr("console.mute.reason_default");

        MuteResult result = ctx.getMuteManager().mutePlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channel, durationMs, reason, null);
        if (result.isSuccess()) {
            return nl(I18n.tr("console.mute.success", args[0], target, channel,
                    describeDuration(durationMs), reason));
        }
        return nl(I18n.tr("console.mute.failed", result.getMessage(), result.getErrorCode()));
    }

    private String handleUnmute(String[] args) {
        if (args.length < 2) {
            return nl(I18n.tr("console.unmute.usage"));
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.unmute.target_unresolved", args[0]));
        }
        String channel = args[1];
        MuteResult result = ctx.getMuteManager().unmutePlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channel, null);
        if (result.isSuccess()) {
            return nl(I18n.tr("console.unmute.success", args[0], target, channel));
        }
        return nl(I18n.tr("console.unmute.failed", result.getMessage(), result.getErrorCode()));
    }

    private String handleMutes(String[] args) {
        if (args.length == 0) {
            // Aggregate across all online players (MuteManager has no list-all).
            Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
            int total = 0;
            StringBuilder sb = new StringBuilder();
            sb.append(I18n.tr("console.mutes.header")).append('\n');
            for (PlayerState s : states) {
                List<MuteInfo> mutes = ctx.getMuteManager().getActiveMutes(s.getPlayerId());
                if (mutes.isEmpty()) {
                    continue;
                }
                for (MuteInfo m : mutes) {
                    total++;
                    sb.append(I18n.tr("console.mutes.entry",
                            s.getPlayerName() != null ? s.getPlayerName() : s.getPlayerId(),
                            s.getPlayerId(),
                            m.getChannelId() != null ? m.getChannelId() : "(global)",
                            describeDuration(m.getRemainingTime()),
                            m.getReason() != null ? m.getReason() : "-")).append('\n');
                }
            }
            sb.append(I18n.tr("console.mutes.total", total)).append('\n');
            return sb.toString();
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.mutes.target_unresolved", args[0]));
        }
        List<MuteInfo> mutes = ctx.getMuteManager().getActiveMutes(target);
        if (mutes.isEmpty()) {
            return nl(I18n.tr("console.mutes.none_for", args[0]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.mutes.header_for", args[0], target)).append('\n');
        for (MuteInfo m : mutes) {
            sb.append(I18n.tr("console.mutes.entry_for",
                    m.getChannelId() != null ? m.getChannelId() : "(global)",
                    describeDuration(m.getRemainingTime()),
                    m.getReason() != null ? m.getReason() : "-")).append('\n');
        }
        return sb.toString();
    }

    // ============================ ban / unban / bans ============================

    private String handleBan(String[] args) {
        if (ctx.getBanManager() == null) {
            return nl(I18n.tr("console.ban.disabled"));
        }
        if (args.length < 3) {
            return nl(I18n.tr("console.ban.usage"));
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.ban.target_unresolved", args[0]));
        }
        // "*" or "global" => global ban (channelId null).
        String channelArg = args[1];
        String channelId;
        if (channelArg.equals("*") || channelArg.equalsIgnoreCase("global")) {
            channelId = null;
        } else {
            if (!ctx.getChannelManager().channelExists(channelArg)) {
                return nl(I18n.tr("console.ban.channel_not_found", channelArg));
            }
            channelId = channelArg;
        }
        long durationMs;
        try {
            durationMs = parseDurationMs(args[2]);
        } catch (IllegalArgumentException e) {
            return nl(I18n.tr("console.ban.invalid_duration", args[2], e.getMessage()));
        }
        String reason = args.length >= 4 ? joinFrom(args, 3) : I18n.tr("console.ban.reason_default");

        BanResult result = ctx.getBanManager().banPlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channelId, durationMs, reason, null);
        if (result.isSuccess()) {
            return nl(I18n.tr("console.ban.success", args[0], target,
                    channelId != null ? channelId : "(global)",
                    describeDuration(durationMs), reason));
        }
        return nl(I18n.tr("console.ban.failed", result.getMessage(), result.getErrorCode()));
    }

    private String handleUnban(String[] args) {
        if (ctx.getBanManager() == null) {
            return nl(I18n.tr("console.ban.disabled"));
        }
        if (args.length < 2) {
            return nl(I18n.tr("console.unban.usage"));
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.unban.target_unresolved", args[0]));
        }
        String channelArg = args[1];
        String channelId;
        if (channelArg.equals("*") || channelArg.equalsIgnoreCase("global")) {
            channelId = null;
        } else {
            channelId = channelArg;
        }
        BanResult result = ctx.getBanManager().unbanPlayer(
                ConsoleSentinel.CONSOLE_SENTINEL, target, channelId, null);
        if (result.isSuccess()) {
            return nl(I18n.tr("console.unban.success", args[0], target,
                    channelId != null ? channelId : "(global)"));
        }
        return nl(I18n.tr("console.unban.failed", result.getMessage(), result.getErrorCode()));
    }

    private String handleBans(String[] args) {
        if (ctx.getBanManager() == null) {
            return nl(I18n.tr("console.ban.disabled"));
        }
        if (args.length == 0) {
            // Aggregate across all known players (BanManager has no list-all).
            Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
            int total = 0;
            StringBuilder sb = new StringBuilder();
            sb.append(I18n.tr("console.bans.header")).append('\n');
            for (PlayerState s : states) {
                List<BanInfo> bans = ctx.getBanManager().getActiveBans(s.getPlayerId());
                if (bans.isEmpty()) {
                    continue;
                }
                for (BanInfo b : bans) {
                    total++;
                    sb.append(I18n.tr("console.bans.entry",
                            s.getPlayerName() != null ? s.getPlayerName() : s.getPlayerId(),
                            s.getPlayerId(),
                            b.getChannelId() != null ? b.getChannelId() : "(global)",
                            b.isPermanent() ? I18n.tr("console.ban.permanent")
                                    : describeDuration(b.getRemainingTime()),
                            b.getReason() != null ? b.getReason() : "-")).append('\n');
                }
            }
            sb.append(I18n.tr("console.bans.total", total)).append('\n');
            return sb.toString();
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.bans.target_unresolved", args[0]));
        }
        List<BanInfo> bans = ctx.getBanManager().getActiveBans(target);
        if (bans.isEmpty()) {
            return nl(I18n.tr("console.bans.none_for", args[0]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.bans.header_for", args[0], target)).append('\n');
        for (BanInfo b : bans) {
            sb.append(I18n.tr("console.bans.entry_for",
                    b.getChannelId() != null ? b.getChannelId() : "(global)",
                    b.isPermanent() ? I18n.tr("console.ban.permanent")
                            : describeDuration(b.getRemainingTime()),
                    b.getReason() != null ? b.getReason() : "-")).append('\n');
        }
        return sb.toString();
    }

    // ============================ kick ============================

    private String handleKick(String[] args) {
        if (args.length < 2) {
            return nl(I18n.tr("console.kick.usage"));
        }
        UUID target = resolveTarget(args[0]);
        if (target == null) {
            return nl(I18n.tr("console.kick.target_unresolved", args[0]));
        }
        String channel = args[1];
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return nl(I18n.tr("console.kick.channel_not_found", channel));
        }
        if (!ch.isMember(target)) {
            return nl(I18n.tr("console.kick.not_member", args[0], channel));
        }
        // Mirror ChannelActionHandler.handleKick: remove member + update state.
        ctx.getChannelManager().removeMember(channel, target);
        try {
            ctx.getPlayerStateManager().leaveChannel(target, channel);
        } catch (Exception e) {
            // non-fatal, matches handler
        }
        // Surface the kick to the web panel notification feed.
        if (ctx.getNotificationStore() != null) {
            try {
                ctx.getNotificationStore().createNotification(
                        "Player Kicked",
                        "console kicked " + args[0] + " (" + target + ") from " + channel,
                        "warning");
            } catch (Exception ignored) {
                // non-fatal
            }
        }
        return nl(I18n.tr("console.kick.success", args[0], target, channel));
    }

    // ============================ announce ============================

    private String handleAnnounce(String[] args) {
        if (args.length < 2) {
            return nl(I18n.tr("console.announce.usage"));
        }
        String channel = args[0];
        String content = joinFrom(args, 1);
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return nl(I18n.tr("console.announce.channel_not_found", channel));
        }
        String message = I18n.tr("console.announce.prefix", content);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("_announcement", "true");
        placeholders.put("_operator", ConsoleSentinel.CONSOLE_NAME);
        // Mirror AdminActionHandler.handleAnnounce: trusted routeMessage by id.
        Set<String> recipients = ctx.getMessageRouter().routeMessage(
                channel, ConsoleSentinel.CONSOLE_SENTINEL, ConsoleSentinel.CONSOLE_NAME, message, placeholders);
        // Surface the announcement to the web panel notification feed.
        if (ctx.getNotificationStore() != null) {
            try {
                ctx.getNotificationStore().createNotification(
                        "Announcement",
                        "Announcement sent to channel " + channel + ": " + content,
                        "info");
            } catch (Exception ignored) {
                // non-fatal
            }
        }
        return nl(I18n.tr("console.announce.success", channel, recipients.size()));
    }

    // ============================ title ============================

    private String handleTitle(String[] args) {
        if (args.length < 2) {
            return nl(I18n.tr("console.title.usage"));
        }
        String channel = args[0];
        String title = args[1];
        String subtitle = args.length >= 3 ? joinFrom(args, 2) : "";
        Channel ch = ctx.getChannelManager().getChannel(channel);
        if (ch == null) {
            return nl(I18n.tr("console.title.channel_not_found", channel));
        }
        // Mirror AdminActionHandler.handleTitle.
        TitlePacket packet = new TitlePacket(channel, title, subtitle, ConsoleSentinel.CONSOLE_SENTINEL);
        if (ch.getScope() == ChannelScope.GLOBAL) {
            ctx.getNetworkHandler().broadcastAuthenticated(packet);
            return nl(I18n.tr("console.title.global_success", channel));
        }
        String targetClientId = ch.getClientId();
        ClientConnection target = targetClientId != null ? ctx.getNetworkHandler().findByClientId(targetClientId) : null;
        if (target == null || !target.isActive() || !target.isAuthenticated()) {
            return nl(I18n.tr("console.title.client_not_connected", channel));
        }
        target.sendPacket(packet);
        return nl(I18n.tr("console.title.server_success", channel, targetClientId));
    }

    // ============================ reload ============================

    private String handleReload() {
        ConfigManager cm = ctx.getConfigManager();
        try {
            cm.reload(true);
            // Surface the reload to the web panel notification feed.
            if (ctx.getNotificationStore() != null) {
                try {
                    ctx.getNotificationStore().createNotification(
                            "Config Reloaded",
                            "Configuration was hot-reloaded from the console.",
                            "info");
                } catch (Exception ignored) {
                    // non-fatal
                }
            }
            return nl(I18n.tr("console.reload.success", cm.getReloadCount()));
        } catch (ConfigException e) {
            return nl(I18n.tr("console.reload.failed", e.getMessage()));
        }
    }

    // ============================ spies ============================

    private String handleSpies() {
        SpyManager sm = ctx.getSpyManager();
        List<String> monitored = sm.getAllMonitoredChannels();
        int total = sm.getTotalSpySessionCount();
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr("console.spies.header", total)).append('\n');
        if (monitored.isEmpty()) {
            sb.append(I18n.tr("console.spies.none")).append('\n');
        } else {
            sb.append(I18n.tr("console.spies.monitored")).append('\n');
            for (String ch : monitored) {
                sb.append(I18n.tr("console.spies.monitored_item", ch, sm.getChannelSpies(ch).size())).append('\n');
            }
        }
        return sb.toString();
    }

    // ============================ spy ============================

    private String handleSpy(String[] args) {
        if (args.length == 0) {
            return nl(I18n.tr("console.spy.usage"));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start": return handleSpyStart(args);
            case "off": return handleSpyOff(args);
            default: return nl(I18n.tr("console.spy.unknown_sub", sub));
        }
    }

    private String handleSpyStart(String[] args) {
        if (args.length < 2) {
            return nl(I18n.tr("console.spy.start.usage"));
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
            return nl(I18n.tr("console.spy.start.success", result.getMessage()));
        }
        return nl(I18n.tr("console.spy.start.failed", result.getMessage(), result.getErrorCode()));
    }

    private String handleSpyOff(String[] args) {
        UUID adminId = args.length >= 2 ? parseUuid(args[1]) : null;
        if (adminId == null) {
            adminId = ConsoleSentinel.CONSOLE_SENTINEL;
        }
        SpyResult result = ctx.getSpyManager().stopAllSpying(adminId);
        if (result.isSuccess()) {
            return nl(I18n.tr("console.spy.off.success", result.getMessage()));
        }
        return nl(I18n.tr("console.spy.off.failed", result.getMessage(), result.getErrorCode()));
    }

    // ============================ create / delete ============================

    private String handleCreate(String[] args) {
        if (args.length < 1) {
            return nl(I18n.tr("console.create.usage"));
        }
        String name = args[0];
        String password = args.length >= 2 ? args[1] : null;
        String scopeRaw = args.length >= 3 ? args[2] : "global";
        String scope = scopeRaw.trim().toLowerCase(Locale.ROOT);

        if (ctx.getChannelManager().channelExists(name)) {
            return nl(I18n.tr("console.create.already_exists", name));
        }

        if ("private".equals(scope) || password != null) {
            // Private channels require a clientId + owner; console-owned channels
            // are bound to a synthetic "console" client with the sentinel as owner.
            try {
                PrivateChannelManager.PrivateChannelCreationResult created =
                        ctx.getPrivateChannelManager().createPrivateChannel(
                                name, "console", ConsoleSentinel.CONSOLE_SENTINEL, password);
                String pwNote = created.isPasswordGenerated()
                        ? I18n.tr("console.create.private_password_auto", created.getPassword())
                        : I18n.tr("console.create.private_password_set");
                return nl(I18n.tr("console.create.private_success", created.getChannelId(), pwNote));
            } catch (Exception e) {
                return nl(I18n.tr("console.create.private_failed", e.getMessage()));
            }
        }
        if (!"global".equals(scope)) {
            return nl(I18n.tr("console.create.unknown_scope", scope));
        }
        try {
            ChannelConfig config = ChannelConfig.builder()
                    .id(name)
                    .displayName(name)
                    .scope(ChannelScope.GLOBAL)
                    .build();
            ctx.getChannelManager().createChannel(config);
            return nl(I18n.tr("console.create.global_success", name));
        } catch (Exception e) {
            return nl(I18n.tr("console.create.global_failed", e.getMessage()));
        }
    }

    private String handleDelete(String[] args) {
        if (args.length < 1) {
            return nl(I18n.tr("console.delete.usage"));
        }
        String id = args[0];
        Channel ch = ctx.getChannelManager().getChannel(id);
        if (ch == null) {
            return nl(I18n.tr("console.delete.not_found", id));
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
            return nl(I18n.tr("console.delete.success", id));
        }
        return nl(I18n.tr("console.delete.failed", id));
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

    /**
     * Ensures a rendered i18n line ends with exactly one newline. Usage/help
     * keys already embed their trailing {@code \n} in the bundle value, so this
     * is a no-op for them; single-line success/error keys get the newline added
     * so console output matches the pre-i18n format.
     */
    private static String nl(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.endsWith("\n") ? s : s + '\n';
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
