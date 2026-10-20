// Moderation command packet-contract tests for NovaChat-LeviLamina.
//
// These pin what /nc mute, /nc kick, /nc ban and /nc unban put on the wire,
// without the LeviLamina SDK: the builders in src/command/ModerationCommands.cpp
// depend only on the protocol layer, so the same code the plugin runs is
// exercised here.
//
// The cases mirror what the backend actually reads (see
// StarLink/core/.../network/ChannelActionHandler.java):
//   - resolveOperatorId() reads extra["operatorId"]
//   - requireModerationPermission() bypasses on the all-zeros console sentinel
//   - resolveTargetId() reads extra["targetId"], falling back to
//     extra["targetName"] for a cross-server target
//   - parseDurationMs() reads extra["duration"] as SECONDS
// and the reference implementations in
// NovaChat/Plugin/bukkit/.../command/{Mute,Kick,Ban,Unban}Command.java.
//
// Build & run:
//   xmake f --sdk=n --openssl=n -m debug
//   xmake build novachat-levilamina-moderation-tests
//   xmake run novachat-levilamina-moderation-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/command/ModerationCommands.h"
#include "../src/protocol/Packet.h"
#include "../src/protocol/PacketBuffer.h"
#include "../src/protocol/PacketIds.h"

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

/// Round-trips a built packet through the real encoder/decoder so the
/// assertions run against the wire representation, not the builder's intent.
std::unique_ptr<ChannelActionPacket> roundTrip(const ChannelActionPacket& packet) {
    PacketBuffer buf;
    packet.write(buf);
    buf.resetReaderIndex();
    auto decoded = std::make_unique<ChannelActionPacket>();
    decoded->read(buf);
    return decoded;
}

std::string extraOf(const ChannelActionPacket& packet, const std::string& key) {
    auto const& extra = packet.getExtra();
    auto it = extra.find(key);
    return it != extra.end() ? it->second : std::string{};
}

bool hasExtra(const ChannelActionPacket& packet, const std::string& key) {
    return packet.getExtra().count(key) > 0;
}

ModerationSender playerSender() {
    return ModerationSender::player("Admin", "11111111-2222-3333-4444-555555555555");
}

} // namespace

// ==================== Action ids ====================

static void testActionIds() {
    // The wire ids the backend dispatches on; a wrong value silently performs
    // a different moderation action.
    auto mute = buildMuteRequest(1, playerSender(), "Bob", "", "10m", "global");
    auto kick = buildKickRequest(1, playerSender(), "Bob", "", "global");
    auto ban  = buildBanRequest(1, playerSender(), "Bob", "", "", "");
    auto unban = buildUnbanRequest(1, playerSender(), "Bob", "");

    CHECK(mute.ok());
    CHECK(kick.ok());
    CHECK(ban.ok());
    CHECK(unban.ok());

    CHECK(mute.packet->getAction() == ChannelAction::MUTE);
    CHECK(kick.packet->getAction() == ChannelAction::KICK);
    CHECK(ban.packet->getAction() == ChannelAction::BAN);
    CHECK(unban.packet->getAction() == ChannelAction::UNBAN);

    // Numeric values must match the Java ChannelAction enum exactly.
    CHECK_EQ(static_cast<int>(mute.packet->getAction()), 7);
    CHECK_EQ(static_cast<int>(kick.packet->getAction()), 6);
    CHECK_EQ(static_cast<int>(ban.packet->getAction()), 9);
    CHECK_EQ(static_cast<int>(unban.packet->getAction()), 10);

    CHECK(mute.packet->getPacketId() == PacketIds::CHANNEL_ACTION);
}

// ==================== Player sender ====================

static void testPlayerSenderMute() {
    auto req = buildMuteRequest(1, playerSender(), "Bob", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                "10m", "global");
    CHECK(req.ok());

    auto decoded = roundTrip(*req.packet);
    CHECK(decoded->getAction() == ChannelAction::MUTE);
    CHECK_EQ(decoded->getChannelId(), std::string("global"));
    CHECK_EQ(extraOf(*decoded, "operatorId"), std::string("11111111-2222-3333-4444-555555555555"));
    CHECK_EQ(extraOf(*decoded, "operatorName"), std::string("Admin"));
    CHECK_EQ(extraOf(*decoded, "targetName"), std::string("Bob"));
    CHECK_EQ(extraOf(*decoded, "targetId"), std::string("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

    // A player sender must never claim to be console: the backend's
    // permission check is bypassed entirely on the sentinel UUID.
    CHECK(!hasExtra(*decoded, "console"));
    CHECK(extraOf(*decoded, "operatorId") != std::string(CONSOLE_SENTINEL_UUID));
}

static void testPlayerSenderKick() {
    auto req = buildKickRequest(1, playerSender(), "Bob", "", "pvp");
    CHECK(req.ok());

    auto decoded = roundTrip(*req.packet);
    CHECK(decoded->getAction() == ChannelAction::KICK);
    CHECK_EQ(decoded->getChannelId(), std::string("pvp"));
    CHECK_EQ(extraOf(*decoded, "operatorName"), std::string("Admin"));
    CHECK_EQ(extraOf(*decoded, "targetName"), std::string("Bob"));
    // Unresolved locally: targetId is omitted so the backend resolves by name.
    CHECK(!hasExtra(*decoded, "targetId"));
    CHECK(!hasExtra(*decoded, "console"));
}

static void testPlayerSenderBan() {
    auto req = buildBanRequest(1, playerSender(), "Bob", "", "1h", "global");
    CHECK(req.ok());

    auto decoded = roundTrip(*req.packet);
    CHECK(decoded->getAction() == ChannelAction::BAN);
    CHECK_EQ(decoded->getChannelId(), std::string("global"));
    CHECK_EQ(extraOf(*decoded, "duration"), std::string("3600"));
    CHECK_EQ(extraOf(*decoded, "operatorName"), std::string("Admin"));
}

static void testPlayerSenderUnban() {
    auto req = buildUnbanRequest(1, playerSender(), "Bob", "");
    CHECK(req.ok());

    auto decoded = roundTrip(*req.packet);
    CHECK(decoded->getAction() == ChannelAction::UNBAN);
    CHECK_EQ(decoded->getChannelId(), std::string(""));
    CHECK_EQ(extraOf(*decoded, "targetName"), std::string("Bob"));
    CHECK(!hasExtra(*decoded, "targetId"));
}

// ==================== Console sender ====================

static void testConsoleSenderUsesSentinel() {
    auto sender = ModerationSender::consoleSender();
    CHECK_EQ(sender.name, std::string("Console"));
    CHECK(sender.console);

    auto req = buildMuteRequest(3, sender, "Bob", "", "10m", "global");
    CHECK(req.ok());

    auto decoded = roundTrip(*req.packet);
    // The sentinel UUID is what makes the backend attribute the action to the
    // console and skip the per-channel permission check.
    CHECK_EQ(extraOf(*decoded, "operatorId"), std::string(CONSOLE_SENTINEL_UUID));
    CHECK_EQ(extraOf(*decoded, "console"), std::string("true"));
    CHECK_EQ(extraOf(*decoded, "operatorName"), std::string("Console"));
}

static void testConsoleEveryAction() {
    auto sender = ModerationSender::consoleSender();

    auto kick = buildKickRequest(2, sender, "Bob", "", "global");
    auto ban = buildBanRequest(2, sender, "Bob", "", "", "global");
    auto unban = buildUnbanRequest(2, sender, "Bob", "global");

    CHECK(kick.ok());
    CHECK(ban.ok());
    CHECK(unban.ok());

    for (auto const* p : {kick.packet.get(), ban.packet.get(), unban.packet.get()}) {
        CHECK_EQ(extraOf(*p, "operatorId"), std::string(CONSOLE_SENTINEL_UUID));
        CHECK_EQ(extraOf(*p, "console"), std::string("true"));
    }
}

static void testConsoleStaticFactories() {
    auto player = ModerationSender::player("Alice", "11111111-1111-1111-1111-111111111111");
    CHECK_EQ(player.name, std::string("Alice"));
    CHECK(!player.console);
}

// ==================== Permission rejection ====================

static void testPermissionRejectedBelowGameDirectors() {
    // Any = 0 is the default for ordinary players; GameDirectors (OP) = 1.
    auto const anyLevel = 0;

    auto mute = buildMuteRequest(anyLevel, playerSender(), "Bob", "", "10m", "global");
    auto kick = buildKickRequest(anyLevel, playerSender(), "Bob", "", "global");
    auto ban  = buildBanRequest(anyLevel, playerSender(), "Bob", "", "", "");
    auto unban = buildUnbanRequest(anyLevel, playerSender(), "Bob", "");

    CHECK(!mute.ok());
    CHECK(!kick.ok());
    CHECK(!ban.ok());
    CHECK(!unban.ok());

    // Rejection must carry the permission key, not a generic usage line.
    CHECK_EQ(mute.errorKey, std::string("chat.command.no_permission_code"));
    CHECK_EQ(kick.errorKey, std::string("chat.command.no_permission_code"));
    CHECK_EQ(ban.errorKey, std::string("chat.command.no_permission_code"));
    CHECK_EQ(unban.errorKey, std::string("chat.command.no_permission_code"));

    // No packet is produced at all, so nothing can leak onto the wire.
    CHECK(mute.packet == nullptr);
    CHECK(kick.packet == nullptr);
}

static void testPermissionAcceptsGameDirectorsAndAbove() {
    CHECK(!hasModerationPermission(0));
    CHECK(hasModerationPermission(1));
    CHECK(hasModerationPermission(2));
    CHECK(hasModerationPermission(4));
    CHECK_EQ(MODERATION_MIN_PERMISSION_LEVEL, 1);
}

static void testPermissionCheckedBeforeArgumentValidation() {
    // A permission rejection must win over a malformed-duration rejection, so a
    // non-admin cannot probe validation behaviour.
    auto req = buildMuteRequest(0, playerSender(), "", "", "not-a-duration", "");
    CHECK(!req.ok());
    CHECK_EQ(req.errorKey, std::string("chat.command.no_permission_code"));
}

// ==================== Argument validation ====================

static void testMuteRequiresTargetAndDuration() {
    auto noTarget = buildMuteRequest(1, playerSender(), "", "", "10m", "global");
    CHECK(!noTarget.ok());
    CHECK_EQ(noTarget.errorKey, std::string("chat.mute.usage"));

    auto noDuration = buildMuteRequest(1, playerSender(), "Bob", "", "", "global");
    CHECK(!noDuration.ok());
    CHECK_EQ(noDuration.errorKey, std::string("chat.mute.usage"));
}

static void testMuteRequiresChannel() {
    auto req = buildMuteRequest(1, playerSender(), "Bob", "", "10m", "");
    CHECK(!req.ok());
    CHECK_EQ(req.errorKey, std::string("chat.command.specify_channel"));
}

static void testMuteRejectsInvalidDuration() {
    auto req = buildMuteRequest(1, playerSender(), "Bob", "", "soon", "global");
    CHECK(!req.ok());
    CHECK_EQ(req.errorKey, std::string("chat.mute.invalid_duration"));
    // The offending token is surfaced to the operator.
    CHECK_EQ(req.errorArg, std::string("soon"));

    auto zero = buildMuteRequest(1, playerSender(), "Bob", "", "0", "global");
    CHECK(!zero.ok());
    CHECK_EQ(zero.errorKey, std::string("chat.mute.invalid_duration"));
}

static void testKickRequiresTargetAndChannel() {
    auto noTarget = buildKickRequest(1, playerSender(), "", "", "global");
    CHECK(!noTarget.ok());
    CHECK_EQ(noTarget.errorKey, std::string("chat.kick.usage"));

    auto noChannel = buildKickRequest(1, playerSender(), "Bob", "", "");
    CHECK(!noChannel.ok());
    CHECK_EQ(noChannel.errorKey, std::string("chat.command.specify_channel"));
}

static void testBanUsageAndChannelOptional() {
    auto noTarget = buildBanRequest(1, playerSender(), "", "", "", "");
    CHECK(!noTarget.ok());
    CHECK_EQ(noTarget.errorKey, std::string("chat.ban.usage"));

    // No channel is the network-wide ban, the only global moderation form.
    auto global = buildBanRequest(1, playerSender(), "Bob", "", "", "");
    CHECK(global.ok());
    CHECK_EQ(global.packet->getChannelId(), std::string(""));
    // A permanent ban omits the duration extra entirely (the backend reads a
    // missing duration as 0 = permanent).
    CHECK(!hasExtra(*global.packet, "duration"));
}

static void testBanRejectsInvalidDuration() {
    auto req = buildBanRequest(1, playerSender(), "Bob", "", "forever", "global");
    CHECK(!req.ok());
    CHECK_EQ(req.errorKey, std::string("chat.ban.invalid_duration"));
    CHECK_EQ(req.errorArg, std::string("forever"));
}

static void testBanTemporaryCarriesSeconds() {
    auto req = buildBanRequest(1, playerSender(), "Bob", "", "1d", "global");
    CHECK(req.ok());
    CHECK_EQ(extraOf(*req.packet, "duration"), std::string("86400"));
}

static void testUnbanUsage() {
    auto req = buildUnbanRequest(1, playerSender(), "", "");
    CHECK(!req.ok());
    CHECK_EQ(req.errorKey, std::string("chat.unban.usage"));
}

static void testUnbanAcceptsUuidTarget() {
    // The ban target is usually offline, so a UUID is accepted and forwarded
    // as targetId rather than looked up among online players.
    auto req = buildUnbanRequest(1, playerSender(),
                                 "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "");
    CHECK(req.ok());
    CHECK_EQ(extraOf(*req.packet, "targetId"),
             std::string("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    CHECK_EQ(extraOf(*req.packet, "targetName"),
             std::string("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
}

// ==================== Duration parsing ====================

static void testParseDurationUnits() {
    CHECK_EQ(parseDurationSeconds("30s"), 30L);
    CHECK_EQ(parseDurationSeconds("10m"), 600L);
    CHECK_EQ(parseDurationSeconds("1h"), 3600L);
    CHECK_EQ(parseDurationSeconds("1d"), 86400L);
    CHECK_EQ(parseDurationSeconds("1w"), 604800L);
    CHECK_EQ(parseDurationSeconds("90"), 90L);
    // Unit is case-insensitive, matching hasPermission-style leniency in the
    // Java reference.
    CHECK_EQ(parseDurationSeconds("10M"), 600L);
    CHECK_EQ(parseDurationSeconds("1D"), 86400L);
}

static void testParseDurationRejectsGarbage() {
    CHECK_EQ(parseDurationSeconds(""), -1L);
    CHECK_EQ(parseDurationSeconds("m"), -1L);
    CHECK_EQ(parseDurationSeconds("10x"), -1L);
    CHECK_EQ(parseDurationSeconds("0"), -1L);
    CHECK_EQ(parseDurationSeconds("0s"), -1L);
    CHECK_EQ(parseDurationSeconds("-5m"), -1L);
    CHECK_EQ(parseDurationSeconds("10mm"), -1L);
    CHECK_EQ(parseDurationSeconds("1.5h"), -1L);
    CHECK_EQ(parseDurationSeconds("global"), -1L);
    // Absurd magnitudes are rejected rather than wrapped.
    CHECK_EQ(parseDurationSeconds("999999999999"), -1L);
}

static void testDurationTokenDetection() {
    CHECK(isDurationToken("30m"));
    CHECK(isDurationToken("90"));
    CHECK(!isDurationToken("global"));
    CHECK(!isDurationToken(""));
}

static void testNormalizeBanArgs() {
    // Duration-first: unchanged.
    {
        std::string duration = "1h";
        std::string channel = "global";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string("1h"));
        CHECK_EQ(channel, std::string("global"));
    }
    // Channel-first with a trailing duration: swapped back.
    {
        std::string duration = "global";
        std::string channel = "1h";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string("1h"));
        CHECK_EQ(channel, std::string("global"));
    }
    // Channel only, no duration: permanent, channel preserved.
    {
        std::string duration = "pvp";
        std::string channel = "";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string(""));
        CHECK_EQ(channel, std::string("pvp"));
    }
    // Neither: network-wide permanent ban.
    {
        std::string duration = "";
        std::string channel = "";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string(""));
        CHECK_EQ(channel, std::string(""));
    }
    // Two non-duration tokens: the first is the channel, the second is dropped
    // rather than being sent as a bogus duration.
    {
        std::string duration = "pvp";
        std::string channel = "survival";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string(""));
        CHECK_EQ(channel, std::string("pvp"));
    }
    // Two durations: the positional reading wins (duration first).
    {
        std::string duration = "1h";
        std::string channel = "30m";
        normalizeBanArgs(duration, channel);
        CHECK_EQ(duration, std::string("1h"));
        CHECK_EQ(channel, std::string("30m"));
    }
}

static void testLooksLikeUuid() {
    CHECK(looksLikeUuid("00000000-0000-0000-0000-000000000000"));
    CHECK(looksLikeUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    CHECK(looksLikeUuid("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"));
    CHECK(!looksLikeUuid("Bob"));
    CHECK(!looksLikeUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-extra"));
    CHECK(!looksLikeUuid("aaaaaaaa0bbbb0cccc0dddd0eeeeeeeeeeee"));
    // A 'g' is not a hex digit.
    CHECK(!looksLikeUuid("gggggggg-bbbb-cccc-dddd-eeeeeeeeeeee"));
}

// ==================== Wire framing ====================

static void testRequestIdIsFresh() {
    auto a = buildKickRequest(1, playerSender(), "Bob", "", "global");
    auto b = buildKickRequest(1, playerSender(), "Bob", "", "global");
    CHECK(a.ok());
    CHECK(b.ok());
    // Each request gets its own request id so responses can be correlated.
    CHECK(!(a.packet->getRequestId() == b.packet->getRequestId()));
}

static void testPasswordFieldStaysEmpty() {
    // Moderation does not carry a channel password; the field must be empty on
    // the wire rather than leaking anything from the sender side.
    auto req = buildMuteRequest(1, playerSender(), "Bob", "", "10m", "global");
    CHECK(req.ok());
    auto decoded = roundTrip(*req.packet);
    CHECK_EQ(decoded->getPassword().size(), static_cast<size_t>(0));
}

static void testExtraCountMatches() {
    auto req = buildBanRequest(1, playerSender(), "Bob", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                               "1h", "global");
    CHECK(req.ok());
    // operatorId / operatorName / targetId / targetName / duration
    CHECK_EQ(req.packet->getExtra().size(), static_cast<size_t>(5));

    auto consoleReq = buildBanRequest(1, ModerationSender::consoleSender(), "Bob", "", "", "");
    CHECK(consoleReq.ok());
    // operatorId / console / operatorName / targetName
    CHECK_EQ(consoleReq.packet->getExtra().size(), static_cast<size_t>(4));
}

int main() {
    std::printf("Running NovaChat-LeviLamina moderation command tests...\n");

    testActionIds();

    testPlayerSenderMute();
    testPlayerSenderKick();
    testPlayerSenderBan();
    testPlayerSenderUnban();

    testConsoleSenderUsesSentinel();
    testConsoleEveryAction();
    testConsoleStaticFactories();

    testPermissionRejectedBelowGameDirectors();
    testPermissionAcceptsGameDirectorsAndAbove();
    testPermissionCheckedBeforeArgumentValidation();

    testMuteRequiresTargetAndDuration();
    testMuteRequiresChannel();
    testMuteRejectsInvalidDuration();
    testKickRequiresTargetAndChannel();
    testBanUsageAndChannelOptional();
    testBanRejectsInvalidDuration();
    testBanTemporaryCarriesSeconds();
    testUnbanUsage();
    testUnbanAcceptsUuidTarget();

    testParseDurationUnits();
    testParseDurationRejectsGarbage();
    testDurationTokenDetection();
    testLooksLikeUuid();
    testNormalizeBanArgs();

    testRequestIdIsFresh();
    testPasswordFieldStaysEmpty();
    testExtraCountMatches();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
