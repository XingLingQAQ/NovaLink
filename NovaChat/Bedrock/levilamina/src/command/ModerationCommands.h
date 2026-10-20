#pragma once

#include "../protocol/Packet.h"

#include <memory>
#include <string>

namespace novachat::moderation {

/**
 * Pure builder layer for the /nc moderation commands (mute / kick / ban /
 * unban).
 *
 * Everything that decides what goes on the wire — the ChannelAction id, the
 * extras the backend reads (operatorId / console / operatorName / targetId /
 * targetName / duration), the console sentinel UUID and the argument
 * validation — lives here rather than in CommandHandler's execute() lambdas.
 *
 * That split is deliberate: CommandHandler.cpp pulls in ll:: and mc:: headers
 * and therefore only compiles on a host with the LeviLamina SDK, while this
 * file depends on nothing but the protocol layer. The standalone test target
 * can compile it and pin the wire contract without the BDS toolchain, and the
 * command handler is left as a thin adapter that only gathers sender identity
 * and raw argument tokens.
 *
 * Reference implementations (same protocol):
 *   - NovaChat/Plugin/bukkit/.../command/{Mute,Kick,Ban,Unban}Command.java
 *   - NovaChat/Bedrock/pmmp/src/NovaChat/Command/NovaChatCommand.php
 */

/** Console sentinel UUID, mirroring NovaConstants.CONSOLE_SENTINEL_UUID. */
inline constexpr char CONSOLE_SENTINEL_UUID[] = "00000000-0000-0000-0000-000000000000";

/** Display name for console-originated actions, mirroring ConsoleSentinel.CONSOLE_NAME. */
inline constexpr char CONSOLE_NAME[] = "Console";

/**
 * Minimum vanilla permission level for moderation, matching
 * CommandPermissionLevel::GameDirectors (OP = 1).
 *
 * Expressed as a plain int so this header stays free of mc/ headers. The same
 * gate /nc reload uses; LeviLamina's Overload builder exposes no .permission()
 * chain, so the check happens against the CommandOrigin before any packet is
 * built.
 */
inline constexpr int MODERATION_MIN_PERMISSION_LEVEL = 1;

/**
 * Who issued a moderation command.
 *
 * Console/RCON has no player UUID, so it is identified by the all-zeros
 * sentinel plus the "console"="true" extra — the backend's ConsoleSentinel
 * recognises that pair and attributes the action to the server console.
 */
struct ModerationSender {
    std::string name;
    std::string uuid;
    bool console = false;

    static ModerationSender player(std::string playerName, std::string playerUuid) {
        return ModerationSender{std::move(playerName), std::move(playerUuid), false};
    }

    static ModerationSender consoleSender() {
        return ModerationSender{CONSOLE_NAME, CONSOLE_SENTINEL_UUID, true};
    }
};

/**
 * Outcome of building a moderation request: either a ready-to-send packet, or
 * an i18n key describing why the arguments/permission were rejected.
 */
struct ModerationRequest {
    std::unique_ptr<protocol::ChannelActionPacket> packet;

    /** i18n key for the rejection (usage line or validation error). */
    std::string errorKey;

    /** Offending token substituted into {0} of the error, when relevant. */
    std::string errorArg;

    [[nodiscard]] bool ok() const { return packet != nullptr; }
};

/**
 * @param permissionLevel CommandOrigin::getPermissionsLevel() as an int
 * @return whether the sender may run a moderation command
 */
[[nodiscard]] bool hasModerationPermission(int permissionLevel);

/**
 * Parses a duration token to seconds, mirroring
 * AbstractSubCommand.parseDuration: "30s", "10m", "1h", "1d", "1w", or a bare
 * seconds count ("90"). The unit is case-insensitive.
 *
 * @return seconds, or -1 when the token is not a valid positive duration
 */
[[nodiscard]] long parseDurationSeconds(const std::string& value);

/**
 * @return whether the token looks like a duration rather than a channel id.
 *         Used by /nc ban, whose first optional argument may be either.
 */
[[nodiscard]] bool isDurationToken(const std::string& value);

/**
 * @return whether the token is a hyphenated UUID (xxxxxxxx-xxxx-...).
 *         /nc unban accepts a UUID because the target is usually offline.
 */
[[nodiscard]] bool looksLikeUuid(const std::string& value);

/**
 * Normalizes the two optional /nc ban arguments.
 *
 * Both the duration and the channel are optional, so `/nc ban Bob 1h global`
 * and `/nc ban Bob global 1h` are both valid and the operator is not required
 * to remember an order. Mirrors bukkit BanCommand: the token that parses as a
 * duration is the duration, the other one is the channel.
 *
 * @param duration  token from the duration position (may hold a channel)
 * @param channelId token from the channel position (may hold a duration)
 */
void normalizeBanArgs(std::string& duration, std::string& channelId);

/**
 * Builds a ChannelAction(MUTE) packet: channel-scoped and always carries a
 * duration.
 *
 * @param permissionLevel sender's permission level; rejects below GameDirectors
 * @param targetName      target player name (required)
 * @param targetId        target UUID string, or empty when not resolvable locally
 * @param duration        duration token ("10m", "600", ...)
 * @param channelId       channel to mute in (required; the caller resolves the
 *                        sender's active channel before calling)
 */
[[nodiscard]] ModerationRequest buildMuteRequest(int permissionLevel,
                                                 const ModerationSender& sender,
                                                 const std::string& targetName,
                                                 const std::string& targetId,
                                                 const std::string& duration,
                                                 const std::string& channelId);

/**
 * Builds a ChannelAction(KICK) packet. A kick is inherently per-channel, so a
 * missing channel is a rejection rather than a network-wide action.
 *
 * @param channelId channel to kick from (required)
 */
[[nodiscard]] ModerationRequest buildKickRequest(int permissionLevel,
                                                 const ModerationSender& sender,
                                                 const std::string& targetName,
                                                 const std::string& targetId,
                                                 const std::string& channelId);

/**
 * Builds a ChannelAction(BAN) packet.
 *
 * An empty channelId is a network-wide ban, which is the only moderation
 * action with a global form; it is deliberately NOT narrowed to the sender's
 * active channel. An empty duration is a permanent ban, and the duration extra
 * is omitted entirely in that case.
 *
 * @param targetName target player name (required)
 * @param duration   duration token, or empty for a permanent ban
 * @param channelId  channel to ban from, or empty for a network-wide ban
 */
[[nodiscard]] ModerationRequest buildBanRequest(int permissionLevel,
                                                const ModerationSender& sender,
                                                const std::string& targetName,
                                                const std::string& targetId,
                                                const std::string& duration,
                                                const std::string& channelId);

/**
 * Builds a ChannelAction(UNBAN) packet.
 *
 * The target is usually offline (that is what a ban is for), so the argument
 * may be a UUID or a name; a name is forwarded for the backend to resolve
 * across all connected servers and the handler warns that offline-by-name
 * cannot be resolved.
 *
 * @param targetName argument as typed (name or UUID)
 * @param channelId  channel to lift the ban in, or empty for a network-wide ban
 */
[[nodiscard]] ModerationRequest buildUnbanRequest(int permissionLevel,
                                                  const ModerationSender& sender,
                                                  const std::string& targetName,
                                                  const std::string& channelId);

} // namespace novachat::moderation
