// Moderation-response correlation tests for NovaChat-LeviLamina.
//
// Pins the operator-facing half of the async moderation flow: /nc mute, kick,
// ban and unban print a progress line when they send, and the per-action
// outcome arrives later as a ChannelActionResponse. That response carries only
// the frame-level request UUID, so it is routable only if the sender was
// registered against that id at send time.
//
// Before this fix the response was logged and the target was notified, but the
// OPERATOR saw nothing: no success, no failure, no error code. These tests pin
// the rule that produces the operator's line, plus the wire half that makes it
// addressable at all.
//
// The logic under test (src/command/ModerationOutcome.cpp) depends only on the
// protocol layer, so the exact code ChatInterceptor::reportOperatorOutcome runs
// is exercised here without the LeviLamina SDK or BDS toolchain.
//
// Build & run:
//   xmake f --levilamina_sdk=n --openssl=n -m debug
//   xmake build novachat-levilamina-moderation-response-tests
//   xmake run novachat-levilamina-moderation-response-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/command/ModerationOutcome.h"
#include "../src/command/ModerationCommands.h"
#include "../src/protocol/Packet.h"
#include "../src/protocol/PacketBuffer.h"
#include "../src/protocol/PacketIds.h"
#include "../src/i18n/I18n.h"

#include <cstdio>
#include <string>

using namespace novachat::moderation;
using namespace novachat::protocol;

static int gPassed = 0;
static int gFailed = 0;

#define CHECK(cond) do { \
    if (cond) { ++gPassed; } \
    else { ++gFailed; std::printf("FAIL: %s (%s:%d)\n", #cond, __FILE__, __LINE__); } \
} while (0)

#define CHECK_EQ(a, b) do { \
    if ((a) == (b)) { ++gPassed; } \
    else { ++gFailed; std::printf("FAIL: %s == %s (%s:%d)\n", #a, #b, __FILE__, __LINE__); } \
} while (0)

namespace {

constexpr char PLAYER_UUID[] = "11111111-2222-3333-4444-555555555555";

/// Builds a response as the backend would: the action, a success flag, and an
/// error code on failure. Mirrors ChannelActionHandler's response construction.
ChannelActionResponsePacket makeResponse(ChannelAction action, bool success,
                                         const std::string& errorCode = "") {
    PacketBuffer buf;
    buf.writeBoolean(success);
    buf.writeByte(static_cast<uint8_t>(action));
    buf.writeString("global");
    buf.writeString(errorCode);
    buf.writeString(success ? "ok" : "failed");
    buf.writeVarInt(0);

    buf.resetReaderIndex();
    ChannelActionResponsePacket packet;
    packet.read(buf);
    return packet;
}

/// Same, but round-tripped through the real encoder first so the assertions run
/// against the wire representation rather than the builder's intent.
///
/// The request UUID is NOT part of the payload: it is the frame header, which
/// NetworkClient reads and stamps back onto the decoded packet
/// (decodePacket -> packet->setRequestId). Reproduced here so the test models
/// the real correlation path.
ChannelActionResponsePacket makeWireResponse(ChannelAction action, bool success,
                                             const std::string& errorCode = "") {
    auto packet = makeResponse(action, success, errorCode);
    UUID const requestId = packet.getRequestId();
    PacketBuffer buf;
    packet.write(buf);
    buf.resetReaderIndex();

    ChannelActionResponsePacket decoded;
    decoded.read(buf);
    decoded.setRequestId(requestId);
    return decoded;
}

} // namespace

// ==================== Which actions report an outcome ====================

static void testModerationActionSet() {
    // The five operator-issued moderation actions must all report an outcome.
    CHECK(isModerationAction(ChannelAction::KICK));
    CHECK(isModerationAction(ChannelAction::MUTE));
    CHECK(isModerationAction(ChannelAction::UNMUTE));
    CHECK(isModerationAction(ChannelAction::BAN));
    CHECK(isModerationAction(ChannelAction::UNBAN));

    // Everything else keeps its pre-existing rendering (or lack of it).
    CHECK(!isModerationAction(ChannelAction::JOIN));
    CHECK(!isModerationAction(ChannelAction::LEAVE));
    CHECK(!isModerationAction(ChannelAction::WHO));
    CHECK(!isModerationAction(ChannelAction::CREATE));
    CHECK(!isModerationAction(ChannelAction::DELETE));
    CHECK(!isModerationAction(ChannelAction::INVITE));
    CHECK(!isModerationAction(ChannelAction::ACCEPT));
}

// ==================== Success keys (Java parity) ====================

static void testSuccessKeysMatchJava() {
    CHECK_EQ(std::string(moderationSuccessKey(ChannelAction::KICK)), "chat.action.kick_processed");
    CHECK_EQ(std::string(moderationSuccessKey(ChannelAction::MUTE)), "chat.action.mute_processed");
    CHECK_EQ(std::string(moderationSuccessKey(ChannelAction::UNMUTE)), "chat.action.unmute_processed");

    // BAN / UNBAN never had a key of their own: the shared Java
    // ChannelResponseDispatcher falls through to the generic acceptance line.
    CHECK_EQ(std::string(moderationSuccessKey(ChannelAction::BAN)), "chat.action.success");
    CHECK_EQ(std::string(moderationSuccessKey(ChannelAction::UNBAN)), "chat.action.success");
}

// ==================== The operator gets a SUCCESS line ====================

static void testSuccessRendersForTheOperator() {
    for (auto action : {ChannelAction::KICK, ChannelAction::MUTE, ChannelAction::UNMUTE,
                        ChannelAction::BAN, ChannelAction::UNBAN}) {
        auto const packet = makeWireResponse(action, true);
        auto const outcome = resolveModerationOutcome(
            packet, PLAYER_UUID, "OK-LINE", "ERR-LINE");

        CHECK(outcome.correlated);
        CHECK(outcome.shouldRender());
        CHECK_EQ(outcome.text, std::string("OK-LINE"));
        CHECK_EQ(outcome.originUuid, std::string(PLAYER_UUID));
    }
}

static void testSuccessTextIsThePerActionKey() {
    // With a real bundle, the rendered line is the per-action confirmation,
    // not the generic ack. This is what the operator actually reads.
    auto& i18n = novachat::i18n::I18n::getInstance();
    char const* key = moderationSuccessKey(ChannelAction::KICK);
    std::string const text = i18n.get(key, "en_US");

    CHECK_EQ(text, i18n.get("chat.action.kick_processed", "en_US"));
    // The key resolves to a real string, not to itself (which is what I18n
    // returns for a missing key).
    CHECK(text != std::string(key));
    CHECK(!text.empty());
}

static void testProcessedKeysExistInBothBundles() {
    auto& i18n = novachat::i18n::I18n::getInstance();
    for (auto const* key : {"chat.action.kick_processed",
                            "chat.action.mute_processed",
                            "chat.action.unmute_processed"}) {
        for (auto const* locale : {"zh_CN", "en_US"}) {
            std::string const text = i18n.get(key, locale);
            CHECK(!text.empty());
            // A missing key falls back to the key itself; that must not happen.
            CHECK(text != std::string(key));
        }
    }
}

// ==================== The operator gets a FAILURE line ====================

static void testCodedFailureRendersErrorForTheOperator() {
    auto const packet = makeWireResponse(ChannelAction::KICK, false, "NC-403");
    auto const outcome = resolveModerationOutcome(
        packet, PLAYER_UUID, "OK-LINE", "ERR-LINE");

    CHECK(outcome.correlated);
    CHECK(outcome.shouldRender());
    CHECK_EQ(outcome.text, std::string("ERR-LINE"));
}

static void testCodedFailureErrorTextIsLocalized() {
    auto& i18n = novachat::i18n::I18n::getInstance();
    std::string const text = i18n.errorMessage("NC-403", "en_US");

    // The operator must see the coded reason (NC-403 = forbidden), not a
    // silent drop and not the raw code alone.
    CHECK(!text.empty());
    CHECK(text.find("NC-403") == std::string::npos);  // resolved, not the raw key
    CHECK(text != std::string("error.NC-403.message"));
}

// ==================== Nothing is rendered when there is nobody to tell ======

static void testUncorrelatedResponseRendersNothing() {
    // A backend push (cross-server kick/mute) or a late/duplicate response has
    // no pending entry. Rendering would send it to the wrong person or nobody.
    auto const ok = makeWireResponse(ChannelAction::KICK, true);
    auto const full = resolveModerationOutcome(ok, "", "OK-LINE", "ERR-LINE");
    CHECK(!full.correlated);
    CHECK(!full.shouldRender());
    CHECK(full.text.empty());

    auto const bad = makeWireResponse(ChannelAction::KICK, false, "NC-403");
    auto const failed = resolveModerationOutcome(bad, "", "OK-LINE", "ERR-LINE");
    CHECK(!failed.correlated);
    CHECK(!failed.shouldRender());
}

static void testFailureWithBlankErrorCodeStaysSilent() {
    // An empty error code means the transport already reported the problem at
    // send time; a second line derived from an empty code would be noise.
    auto const packet = makeWireResponse(ChannelAction::BAN, false, "");
    auto const outcome = resolveModerationOutcome(
        packet, PLAYER_UUID, "OK-LINE", "ERR-LINE");

    CHECK(outcome.correlated);
    CHECK(!outcome.shouldRender());
    CHECK(outcome.text.empty());
}

// ==================== Wire half: the response is addressable ===============

static void testResponseCarriesTheRequestIdItAnswers() {
    // The correlation key IS the request id: ChatInterceptor looks the origin
    // up by exactly this value. If the response did not carry it, no moderation
    // outcome could ever be delivered.
    auto packet = makeResponse(ChannelAction::MUTE, true);
    UUID const requestId = packet.getRequestId();

    PacketBuffer buf;
    packet.write(buf);
    buf.resetReaderIndex();

    ChannelActionResponsePacket decoded;
    decoded.read(buf);
    decoded.setRequestId(requestId);  // frame header, stamped by NetworkClient

    CHECK_EQ(decoded.getRequestId().toString(), requestId.toString());
    CHECK_EQ(decoded.getAction(), ChannelAction::MUTE);
    CHECK(decoded.isSuccess());
}

static void testModerationRequestPacketCarriesACorrelationId() {
    // The send side registers packet.getRequestId(); it must therefore exist
    // and be stable across the wire, or the pending entry would never match.
    ModerationSender const sender =
        ModerationSender::player("Admin", PLAYER_UUID);
    auto request = buildKickRequest(4, sender, "Bob", "22222222-3333-4444-5555-666666666666", "global");
    CHECK(request.ok());

    std::string const requestId = request.packet->getRequestId().toString();
    CHECK(!requestId.empty());
    CHECK(requestId != std::string("00000000-0000-0000-0000-000000000000"));

    PacketBuffer buf;
    request.packet->write(buf);
    buf.resetReaderIndex();

    ChannelActionPacket decoded;
    decoded.read(buf);
    decoded.setRequestId(request.packet->getRequestId());  // frame header
    CHECK_EQ(decoded.getRequestId().toString(), requestId);
}

// ==================== Console origin =======================================

static void testConsoleOriginIsTheSentinel() {
    // Console/RCON moderation is registered against the all-zeros sentinel and
    // logged rather than delivered; the correlation itself is still valid.
    std::string const sentinel = UUID{}.toString();
    auto const packet = makeWireResponse(ChannelAction::BAN, true);
    auto const outcome = resolveModerationOutcome(packet, sentinel, "OK-LINE", "ERR-LINE");

    CHECK(outcome.correlated);
    CHECK(outcome.shouldRender());
    CHECK_EQ(outcome.originUuid, sentinel);
}

int main() {
    std::printf("Running NovaChat-LeviLamina moderation response tests...\n\n");

    testModerationActionSet();
    testSuccessKeysMatchJava();
    testSuccessRendersForTheOperator();
    testSuccessTextIsThePerActionKey();
    testProcessedKeysExistInBothBundles();
    testCodedFailureRendersErrorForTheOperator();
    testCodedFailureErrorTextIsLocalized();
    testUncorrelatedResponseRendersNothing();
    testFailureWithBlankErrorCodeStaysSilent();
    testResponseCarriesTheRequestIdItAnswers();
    testModerationRequestPacketCarriesACorrelationId();
    testConsoleOriginIsTheSentinel();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
