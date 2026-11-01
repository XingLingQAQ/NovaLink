package com.nova.chat.proxy.command;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.lite.LocalAuthorityClient;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class LiteModerationCommands {

    static final List<String> COMMANDS = List.of("mute", "unmute", "kick", "ban", "unban");

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;
    private final KnownChannelRegistry channels;

    LiteModerationCommands(ProxyPlatform platform, ProxyChatCore chatCore,
                           KnownChannelRegistry channels) {
        this.platform = platform;
        this.chatCore = chatCore;
        this.channels = channels;
    }

    boolean canUse(ProxyCommandSender sender, String command) {
        String permission = "novachat." + switch (command) {
            case "unmute" -> "mute";
            case "unban" -> "ban";
            default -> command;
        };
        return sender.getPlayerId() == null
                ? platform.consoleHasPermission(permission) : sender.hasPermission(permission);
    }

    void execute(ProxyCommandSender sender, String command, String[] args) {
        if (!canUse(sender, command)) {
            error(sender, ErrorCode.FORBIDDEN);
            return;
        }

        boolean mute = command.equals("mute");
        boolean ban = command.equals("ban");
        int minimum = mute ? 2 : 1;
        int maximum = mute || ban ? 3 : 2;
        if (args.length < minimum || args.length > maximum || args[0].isBlank()) {
            error(sender, ErrorCode.BAD_REQUEST);
            sender.sendMessage(chatCore.getFormatter().formatSystemMessage(usage(command)));
            return;
        }

        String channel = null;
        String duration = null;
        if (mute) {
            duration = args[1];
            channel = args.length == 3 ? args[2] : activeChannel(sender);
        } else if (ban) {
            if (args.length == 3) {
                duration = args[1];
                channel = args[2];
            } else if (args.length == 2) {
                if (args[1].matches("(?i)[0-9]+[smhdw]?")) {
                    duration = args[1];
                } else {
                    channel = args[1];
                }
            }
        } else {
            channel = args.length == 2 ? args[1]
                    : command.equals("unban") ? null : activeChannel(sender);
        }

        long seconds = duration != null ? durationSeconds(duration) : 0L;
        if (seconds < 0L) {
            error(sender, ErrorCode.INVALID_DURATION);
            return;
        }
        if (channel == null && !ban && !command.equals("unban")) {
            error(sender, ErrorCode.BAD_REQUEST);
            sender.sendMessage(chatCore.getFormatter().formatError(
                    I18n.tr(sender.getPlayerId(), "chat.command.specify_channel")));
            return;
        }
        if (channel != null && channel.isBlank()) {
            error(sender, ErrorCode.BAD_REQUEST);
            return;
        }

        var client = chatCore.getNetworkClientForPlayer(sender.getPlayerId());
        if (!(client instanceof LocalAuthorityClient local) || !local.isConnected()) {
            error(sender, ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }

        ChannelAction action = ChannelAction.valueOf(command.toUpperCase(Locale.ROOT));
        ChannelActionPacket packet = new ChannelActionPacket(action, channel != null ? channel : "");
        UUID operatorId = sender.getPlayerId();
        packet.addExtra("operatorId", operatorId != null
                ? operatorId.toString() : NovaConstants.CONSOLE_SENTINEL_UUID);
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("targetName", args[0]);
        packet.addExtra("duration", Long.toString(seconds));
        UUID targetId = targetId(args[0]);
        if (targetId != null) {
            packet.addExtra("targetId", targetId.toString());
        }
        if (operatorId == null) {
            packet.addExtra("console", "true");
        }

        // Console receipts need a callback; the shared dispatcher owns target notices.
        if (!local.sendChannelAction(packet, response -> platform.execute(() -> {
            if (response.isSuccess()) {
                String key = switch (action) {
                    case MUTE -> "chat.action.mute_processed";
                    case UNMUTE -> "chat.action.unmute_processed";
                    case KICK -> "chat.action.kick_processed";
                    default -> "chat.action.success";
                };
                sender.sendMessage(chatCore.getFormatter().formatSuccess(
                        I18n.tr(sender.getPlayerId(), key)));
            } else {
                sender.sendMessage(chatCore.getFormatter().formatError(
                        ErrorMessageFormatter.format(response.getErrorCode())));
            }
        }))) {
            error(sender, ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    List<String> suggest(ProxyCommandSender sender, String command, String[] args) {
        if (!canUse(sender, command)) {
            return List.of();
        }
        String value = args[args.length - 1];
        String prefix = value != null ? value.toLowerCase(Locale.ROOT) : "";
        if (args.length == 2) {
            return platform.getOnlinePlayers().stream().map(ProxyPlayer::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if ((command.equals("mute") || command.equals("ban")) && args.length == 3) {
            return List.of("30s", "10m", "1h", "1d", "0").stream()
                    .filter(duration -> duration.startsWith(prefix)).toList();
        }
        int channelPosition = command.equals("mute") || command.equals("ban") ? 4 : 3;
        return args.length == channelPosition && channels != null
                ? channels.getKnownChannelIds(prefix) : List.of();
    }

    private String activeChannel(ProxyCommandSender sender) {
        PlayerChannelState state = sender.getPlayerId() != null
                ? chatCore.getState(sender.getPlayerId()) : null;
        return state != null ? state.getActiveChannel() : null;
    }

    private UUID targetId(String name) {
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player.getUniqueId();
            }
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long durationSeconds(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9]+[smhdw]?")) {
            return -1L;
        }
        char unit = normalized.charAt(normalized.length() - 1);
        long multiplier = switch (unit) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 3600L;
            case 'd' -> 86400L;
            case 'w' -> 604800L;
            default -> 1L;
        };
        String digits = Character.isDigit(unit) ? normalized
                : normalized.substring(0, normalized.length() - 1);
        try {
            long seconds = Math.multiplyExact(Long.parseLong(digits), multiplier);
            return seconds <= (Long.MAX_VALUE - System.currentTimeMillis()) / 1000L
                    ? seconds : -1L;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String usage(String command) {
        return "/nc " + command + " <player|UUID>"
                + (command.equals("mute") ? " <duration>" : command.equals("ban") ? " [duration]" : "")
                + " [channel]";
    }

    private void error(ProxyCommandSender sender, ErrorCode code) {
        sender.sendMessage(chatCore.getFormatter().formatError(ErrorMessageFormatter.format(code)));
    }
}
