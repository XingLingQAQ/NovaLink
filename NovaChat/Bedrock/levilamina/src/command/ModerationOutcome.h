#pragma once

#include "../protocol/Packet.h"
#include "../protocol/PacketIds.h"

#include <string>

namespace novachat::moderation {

/// Translation of one ChannelActionResponse into the line the operator gets.
///
/// Split out of ChatInterceptor so it stays ICU-free: ChatInterceptor.cpp pulls
/// ll::/mc:: headers and only compiles with the BDS toolchain, while this file
/// depends on the protocol layer alone. Same rationale as the
/// ModerationCommands builders.
struct ModerationOutcome {
    /// Player UUID (or the all-zeros console sentinel) that issued the action,
    /// echoed so the caller knows where the line goes. Empty when uncorrelated.
    std::string originUuid;

    /// The line to render, already localized by the caller.
    /// Empty means "render nothing" (see shouldRender).
    std::string text;

    /// True when this response answers one of our own requests. A backend push
    /// or a late/duplicate response carries no pending entry and is dropped.
    bool correlated = false;

    bool shouldRender() const { return correlated && !text.empty(); }
};

/// Whether `action` is one of the operator-issued moderation actions that must
/// report an outcome. Mirrors the Java
/// ChannelResponseDispatcher.isModerationAction set.
bool isModerationAction(protocol::ChannelAction action);

/// Resolve which i18n key announces an accepted moderation action.
///
/// Mirrors the shared Java moderationSuccessText: KICK/MUTE/UNMUTE have their
/// own `chat.action.*_processed` keys; BAN/UNBAN never had one and fall back to
/// the generic `chat.action.success`.
const char* moderationSuccessKey(protocol::ChannelAction action);

/// Translate a response into the operator-facing outcome.
///
/// Pure: every localized string is supplied by the caller (`successText` for an
/// accepted action, `errorText` for a coded failure), so the decision logic can
/// be pinned without an I18n bundle or the BDS runtime.
///
/// Rules, matching the Java ChannelResponseDispatcher:
///  * not one of our pending requests      -> correlated=false
///  * success                              -> successText
///  * failure with a blank error code      -> correlated=true, text empty (the
///                                            transport already reported it)
///  * failure with a code                  -> errorText
///
/// @param packet the decoded response
/// @param originUuid the pending origin for this request, or empty when the
///        caller has no pending entry for it
/// @param successText localized confirmation for an accepted action
/// @param errorText localized error text for a coded failure
ModerationOutcome resolveModerationOutcome(const protocol::ChannelActionResponsePacket& packet,
                                           const std::string& originUuid,
                                           const std::string& successText,
                                           const std::string& errorText);

} // namespace novachat::moderation
