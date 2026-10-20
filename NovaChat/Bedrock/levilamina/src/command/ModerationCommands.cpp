#include "ModerationCommands.h"

#include <algorithm>
#include <cctype>

namespace novachat::moderation {

using namespace novachat::protocol;

namespace {

/// i18n keys for the rejections this layer can produce.
constexpr char KEY_NO_PERMISSION[]        = "chat.command.no_permission_code";
constexpr char KEY_SPECIFY_CHANNEL[]      = "chat.command.specify_channel";
constexpr char KEY_MUTE_USAGE[]           = "chat.mute.usage";
constexpr char KEY_MUTE_INVALID_DURATION[] = "chat.mute.invalid_duration";
constexpr char KEY_KICK_USAGE[]           = "chat.kick.usage";
constexpr char KEY_BAN_USAGE[]            = "chat.ban.usage";
constexpr char KEY_BAN_INVALID_DURATION[] = "chat.ban.invalid_duration";
constexpr char KEY_UNBAN_USAGE[]          = "chat.unban.usage";

char lowerAscii(char c) {
    return static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
}

/// Applies the operator extras every moderation action carries.
///
/// Console sends the all-zeros sentinel UUID plus "console"="true"; a player
/// sends their own UUID. The backend's resolveOperatorId() reads operatorId and
/// its requireModerationPermission() short-circuits on the sentinel, so both
/// halves have to be right for console moderation to be attributed correctly.
void addOperatorExtras(ChannelActionPacket& packet, const ModerationSender& sender) {
    packet.addExtra("operatorId", sender.console ? CONSOLE_SENTINEL_UUID : sender.uuid);
    if (sender.console) {
        packet.addExtra("console", "true");
    }
    packet.addExtra("operatorName", sender.name);
}

/// Adds the target extras shared by mute/kick/ban/unban.
///
/// targetName is always sent so the backend can resolve a player on another
/// sub-server; targetId is only sent when the originating server could resolve
/// it locally.
void addTargetExtras(ChannelActionPacket& packet, const std::string& targetName,
                     const std::string& targetId) {
    if (!targetId.empty()) {
        packet.addExtra("targetId", targetId);
    }
    packet.addExtra("targetName", targetName);
}

ModerationRequest reject(const char* key, std::string arg = {}) {
    ModerationRequest result;
    result.errorKey = key;
    result.errorArg = std::move(arg);
    return result;
}

} // namespace

bool hasModerationPermission(int permissionLevel) {
    return permissionLevel >= MODERATION_MIN_PERMISSION_LEVEL;
}

long parseDurationSeconds(const std::string& value) {
    if (value.empty()) {
        return -1;
    }

    // Split into a numeric head and an optional single-letter unit suffix.
    // A bare number is seconds, matching AbstractSubCommand.parseDuration.
    size_t i = 0;
    while (i < value.size() && std::isdigit(static_cast<unsigned char>(value[i]))) {
        ++i;
    }
    size_t const digitEnd = i;

    if (digitEnd == 0 || digitEnd + 1 < value.size()) {
        return -1;
    }

    long long amount = 0;
    for (size_t i = 0; i < digitEnd; ++i) {
        amount = amount * 10 + (value[i] - '0');
        // A token this long overflows the seconds arithmetic below; treat it
        // as invalid rather than wrapping into a nonsensical duration.
        if (amount > 1'000'000'000LL) {
            return -1;
        }
    }

    long long multiplier = 1;
    if (digitEnd == value.size()) {
        multiplier = 1; // bare seconds
    } else {
        switch (lowerAscii(value[digitEnd])) {
            case 's': multiplier = 1;          break;
            case 'm': multiplier = 60;         break;
            case 'h': multiplier = 3600;       break;
            case 'd': multiplier = 86400;      break;
            case 'w': multiplier = 604800;     break;
            default:  return -1;
        }
    }

    long long const seconds = amount * multiplier;
    if (seconds <= 0) {
        return -1;
    }
    return static_cast<long>(seconds);
}

bool isDurationToken(const std::string& value) {
    return parseDurationSeconds(value) > 0;
}

void normalizeBanArgs(std::string& duration, std::string& channelId) {
    if (!duration.empty() && !isDurationToken(duration)) {
        // Channel-first invocation: /nc ban <player> <channel> [duration].
        // The channel slot may already hold the trailing duration.
        std::string const previousChannel = channelId;
        channelId = duration;
        duration = isDurationToken(previousChannel) ? previousChannel : std::string{};
    }
}

bool looksLikeUuid(const std::string& value) {
    // xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    static constexpr size_t UUID_LEN = 36;
    if (value.size() != UUID_LEN) {
        return false;
    }
    for (size_t i = 0; i < UUID_LEN; ++i) {
        bool const dashPos = (i == 8 || i == 13 || i == 18 || i == 23);
        char const c = value[i];
        if (dashPos) {
            if (c != '-') {
                return false;
            }
        } else if (!std::isxdigit(static_cast<unsigned char>(c))) {
            return false;
        }
    }
    return true;
}

ModerationRequest buildMuteRequest(int permissionLevel, const ModerationSender& sender,
                                   const std::string& targetName, const std::string& targetId,
                                   const std::string& duration, const std::string& channelId) {
    if (!hasModerationPermission(permissionLevel)) {
        return reject(KEY_NO_PERMISSION);
    }
    if (targetName.empty() || duration.empty()) {
        return reject(KEY_MUTE_USAGE);
    }

    long const seconds = parseDurationSeconds(duration);
    if (seconds <= 0) {
        return reject(KEY_MUTE_INVALID_DURATION, duration);
    }

    // A mute is channel-scoped: there is no meaningful network-wide mute, so a
    // missing channel is a rejection rather than an implicit global action.
    if (channelId.empty()) {
        return reject(KEY_SPECIFY_CHANNEL);
    }

    auto packet = std::make_unique<ChannelActionPacket>(ChannelAction::MUTE, channelId);
    addOperatorExtras(*packet, sender);
    addTargetExtras(*packet, targetName, targetId);
    packet->addExtra("duration", std::to_string(seconds));

    ModerationRequest result;
    result.packet = std::move(packet);
    return result;
}

ModerationRequest buildKickRequest(int permissionLevel, const ModerationSender& sender,
                                   const std::string& targetName, const std::string& targetId,
                                   const std::string& channelId) {
    if (!hasModerationPermission(permissionLevel)) {
        return reject(KEY_NO_PERMISSION);
    }
    if (targetName.empty()) {
        return reject(KEY_KICK_USAGE);
    }
    if (channelId.empty()) {
        return reject(KEY_SPECIFY_CHANNEL);
    }

    auto packet = std::make_unique<ChannelActionPacket>(ChannelAction::KICK, channelId);
    addOperatorExtras(*packet, sender);
    addTargetExtras(*packet, targetName, targetId);

    ModerationRequest result;
    result.packet = std::move(packet);
    return result;
}

ModerationRequest buildBanRequest(int permissionLevel, const ModerationSender& sender,
                                  const std::string& targetName, const std::string& targetId,
                                  const std::string& duration, const std::string& channelId) {
    if (!hasModerationPermission(permissionLevel)) {
        return reject(KEY_NO_PERMISSION);
    }
    if (targetName.empty()) {
        return reject(KEY_BAN_USAGE);
    }

    long seconds = 0; // 0 = permanent; the duration extra is omitted entirely.
    if (!duration.empty()) {
        seconds = parseDurationSeconds(duration);
        if (seconds <= 0) {
            return reject(KEY_BAN_INVALID_DURATION, duration);
        }
    }

    // An empty channel id is the network-wide form. It is deliberately NOT
    // narrowed to the sender's active channel: silently turning a global ban
    // into a single-channel ban is a worse failure than making the operator
    // name the channel.
    auto packet = std::make_unique<ChannelActionPacket>(ChannelAction::BAN, channelId);
    addOperatorExtras(*packet, sender);
    addTargetExtras(*packet, targetName, targetId);
    if (seconds > 0) {
        packet->addExtra("duration", std::to_string(seconds));
    }

    ModerationRequest result;
    result.packet = std::move(packet);
    return result;
}

ModerationRequest buildUnbanRequest(int permissionLevel, const ModerationSender& sender,
                                    const std::string& targetName, const std::string& channelId) {
    if (!hasModerationPermission(permissionLevel)) {
        return reject(KEY_NO_PERMISSION);
    }
    if (targetName.empty()) {
        return reject(KEY_UNBAN_USAGE);
    }

    // The ban target is usually offline, so the argument may be a UUID. A UUID
    // is forwarded as targetId verbatim; a name is forwarded as targetName for
    // the backend to resolve across all connected servers.
    auto packet = std::make_unique<ChannelActionPacket>(ChannelAction::UNBAN, channelId);
    addOperatorExtras(*packet, sender);
    if (looksLikeUuid(targetName)) {
        packet->addExtra("targetId", targetName);
    }
    packet->addExtra("targetName", targetName);

    ModerationRequest result;
    result.packet = std::move(packet);
    return result;
}

} // namespace novachat::moderation
