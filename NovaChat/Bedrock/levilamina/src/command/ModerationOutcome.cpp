#include "ModerationOutcome.h"

namespace novachat::moderation {

using namespace novachat::protocol;

bool isModerationAction(ChannelAction action) {
    switch (action) {
        case ChannelAction::KICK:
        case ChannelAction::MUTE:
        case ChannelAction::UNMUTE:
        case ChannelAction::BAN:
        case ChannelAction::UNBAN:
            return true;
        default:
            return false;
    }
}

const char* moderationSuccessKey(ChannelAction action) {
    switch (action) {
        case ChannelAction::KICK:
            return "chat.action.kick_processed";
        case ChannelAction::MUTE:
            return "chat.action.mute_processed";
        case ChannelAction::UNMUTE:
            return "chat.action.unmute_processed";
        default:
            // BAN / UNBAN never had a key of their own (Java parity), and a
            // non-moderation action keeps the pre-existing generic ack.
            return "chat.action.success";
    }
}

ModerationOutcome resolveModerationOutcome(const ChannelActionResponsePacket& packet,
                                           const std::string& originUuid,
                                           const std::string& successText,
                                           const std::string& errorText) {
    ModerationOutcome outcome;
    if (originUuid.empty()) {
        // Backend push (cross-server kick/mute), or a response whose pending
        // entry was already consumed or expired: nobody to report to.
        return outcome;
    }
    outcome.correlated = true;
    outcome.originUuid = originUuid;

    if (packet.isSuccess()) {
        outcome.text = successText;
        return outcome;
    }

    // A blank error code means the transport already reported the problem at
    // send time; rendering a second, empty-derived line would only confuse.
    if (packet.getErrorCode().empty()) {
        return outcome;
    }
    outcome.text = errorText;
    return outcome;
}

} // namespace novachat::moderation
