package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared core of the {@code /nc msg} and {@code /nc r} subcommands.
 *
 * <p>Validates arguments, builds the C→S {@link PrivateMessagePacket} through
 * {@link PrivateMessageService#buildPacket} and hands it to the
 * platform-supplied {@link PrivateMessagePacketSender} (pattern follows
 * {@link IgnoreCommandService}): each platform command shell only forwards the
 * raw arguments here and sends the returned lines through its own message
 * helper.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code /nc msg <player> <message...>} — whisper to a player anywhere
 *       on the network</li>
 *   <li>{@code /nc r <message...>} — reply to the most recent private-message
 *       partner (alias of reply)</li>
 * </ul>
 *
 * <p>A successful send returns no line: the backend echoes the completed
 * packet back and {@link PrivateMessageService#handleIncoming} renders the
 * "你悄悄对 {0} 说" confirmation, so the sender only ever sees
 * delivery-confirmed text. All copy is resolved through {@link I18n} with the
 * player's locale ({@code chat.msg.*} keys, aligned across zh_CN / en_US).
 */
public final class PrivateMessageCommandService {

    /**
     * Abstraction for transmitting a private-message packet, mirroring
     * {@link PacketSender} (which is typed to ChannelActionPacket).
     */
    @FunctionalInterface
    public interface PrivateMessagePacketSender {

        /**
         * Attempts to send a private-message packet.
         *
         * @param packet non-null packet to transmit
         * @return whether the send was accepted (connection active)
         */
        boolean send(PrivateMessagePacket packet);
    }

    private PrivateMessageCommandService() {
        // Utility class — not instantiated.
    }

    /**
     * Handles {@code /nc msg <player> <message...>}.
     *
     * @param sender         platform packet transmitter
     * @param playerId       the invoking player's UUID (locale + packet sender id)
     * @param playerName     the invoking player's name (self check + packet field)
     * @param senderClientId this client/server's id (may be null)
     * @param args           the arguments after the {@code msg} literal
     * @return localized receipt lines (empty on accepted send — echo confirms)
     */
    public static List<String> msg(PrivateMessagePacketSender sender, UUID playerId,
                                   String playerName, String senderClientId, String[] args) {
        if (args == null || args.length < 2 || args[0] == null || args[0].trim().isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.msg.usage"));
        }
        String target = args[0].trim();
        String content = joinContent(args, 1);
        if (content.isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.msg.usage"));
        }
        if (playerName != null && playerName.equalsIgnoreCase(target)) {
            return List.of(I18n.tr(playerId, "chat.msg.self"));
        }
        return transmit(sender, playerId, playerName, senderClientId, target, content);
    }

    /**
     * Handles {@code /nc r <message...>} (reply to the most recent partner).
     *
     * @param service        reply-target tracking service
     * @param sender         platform packet transmitter
     * @param playerId       the invoking player's UUID
     * @param playerName     the invoking player's name
     * @param senderClientId this client/server's id (may be null)
     * @param args           the arguments after the {@code r} literal
     * @return localized receipt lines (empty on accepted send — echo confirms)
     */
    public static List<String> reply(PrivateMessageService service,
                                     PrivateMessagePacketSender sender, UUID playerId,
                                     String playerName, String senderClientId, String[] args) {
        String content = joinContent(args, 0);
        if (content.isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.msg.usage_reply"));
        }
        Optional<String> target = service.getReplyTarget(playerId);
        if (target.isEmpty()) {
            return List.of(I18n.tr(playerId, "chat.msg.reply_no_target"));
        }
        return transmit(sender, playerId, playerName, senderClientId, target.get(), content);
    }

    private static List<String> transmit(PrivateMessagePacketSender sender, UUID playerId,
                                         String playerName, String senderClientId,
                                         String target, String content) {
        PrivateMessagePacket packet = PrivateMessageService.buildPacket(
                playerId, playerName, senderClientId, target, content);
        if (!sender.send(packet)) {
            return List.of(I18n.tr(playerId, "chat.msg.not_connected"));
        }
        return List.of();
    }

    /** Joins {@code args[from..]} into the message content (trimmed). */
    private static String joinContent(String[] args, int from) {
        if (args == null || args.length <= from) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (args[i] == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }
}
