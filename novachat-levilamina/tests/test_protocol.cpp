// Standalone protocol round-trip tests for NovaChat-LeviLamina.
//
// These tests exercise the pure-C++ protocol layer (PacketBuffer, VarInt,
// Packet encode/decode, Sha256, I18n) without the LeviLamina SDK, so they
// can run anywhere a C++20 compiler is available.
//
// Build & run:
//   xmake f -m debug
//   xmake build novachat-levilamina-tests
//   xmake run novachat-levilamina-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/protocol/PacketBuffer.h"
#include "../src/protocol/VarInt.h"
#include "../src/protocol/PacketIds.h"
#include "../src/protocol/Packet.h"
#include "../src/util/Sha256.h"
#include "../src/i18n/I18n.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace novachat::protocol;
using namespace novachat::util;
using namespace novachat::i18n;

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

static void testVarIntRoundTrip() {
    int32_t values[] = {0, 1, 127, 128, 255, 16384, 2097151, -1, -128};
    for (int32_t v : values) {
        auto encoded = VarInt::encode(v);
        size_t bytesRead = 0;
        int32_t decoded = VarInt::decode(encoded.data(), encoded.size(), bytesRead);
        CHECK_EQ(decoded, v);
        CHECK_EQ(bytesRead, encoded.size());
    }
}

static void testPacketBufferString() {
    PacketBuffer buf;
    buf.writeString("hello");
    buf.writeString("");
    buf.writeString("NovaChat");
    buf.resetReaderIndex();
    CHECK_EQ(buf.readString(), std::string("hello"));
    CHECK_EQ(buf.readString(), std::string(""));
    CHECK_EQ(buf.readString(), std::string("NovaChat"));
}

static void testPacketBufferUUID() {
    PacketBuffer buf;
    UUID original = UUID::random();
    buf.writeUUID(original);
    buf.resetReaderIndex();
    UUID read = buf.readUUID();
    CHECK_EQ(read.mostSigBits, original.mostSigBits);
    CHECK_EQ(read.leastSigBits, original.leastSigBits);
}

static void testHandshakePacketV2() {
    HandshakePacket pkt(
        PROTOCOL_VERSION,
        "LeviServer",
        "abc123",
        PlatformType::LEVILAMINA,
        "1.21.0"
    );
    PacketBuffer buf;
    pkt.write(buf);
    buf.resetReaderIndex();

    HandshakePacket decoded;
    decoded.read(buf);
    CHECK_EQ(decoded.getProtocolVersion(), PROTOCOL_VERSION);
    CHECK_EQ(decoded.getClientId(), std::string("LeviServer"));
    CHECK_EQ(decoded.getPasswordHash(), std::string("abc123"));
    CHECK_EQ(static_cast<int>(decoded.getPlatform()), static_cast<int>(PlatformType::LEVILAMINA));
    CHECK_EQ(decoded.getServerVersion(), std::string("1.21.0"));
}

static void testHandshakePacketBackwardCompat() {
    // Simulate a v1-style payload (no trailing server_version).
    PacketBuffer buf;
    buf.writeVarInt(PROTOCOL_VERSION);
    buf.writeString("srv");
    buf.writeString("abc");
    buf.writeByte(static_cast<uint8_t>(PlatformType::LEVILAMINA));
    buf.resetReaderIndex();

    HandshakePacket decoded;
    decoded.read(buf);
    CHECK_EQ(decoded.getServerVersion(), std::string(""));
}

static void testHandshakeResponseFieldOrder() {
    // Java requires: success | errorCode | message (errorCode BEFORE message).
    HandshakeResponsePacket pkt;
    PacketBuffer buf;
    // Manually encode to verify read order.
    buf.writeBoolean(true);
    buf.writeString("NC-200");
    buf.writeString("OK");
    buf.resetReaderIndex();
    pkt.read(buf);
    CHECK(pkt.isSuccess());
    CHECK_EQ(pkt.getErrorCode(), std::string("NC-200"));
    CHECK_EQ(pkt.getMessage(), std::string("OK"));
}

static void testHandshakeResponseRoundTrip() {
    HandshakeResponsePacket pkt;
    PacketBuffer buf;
    buf.writeBoolean(false);
    buf.writeString("NC-401");
    buf.writeString("Auth failed");
    buf.resetReaderIndex();
    pkt.read(buf);
    CHECK(!pkt.isSuccess());
    CHECK_EQ(pkt.getErrorCode(), std::string("NC-401"));
    CHECK_EQ(pkt.getMessage(), std::string("Auth failed"));
}

static void testChannelActionPacket() {
    ChannelActionPacket pkt(ChannelAction::JOIN, "global", "secret");
    pkt.addExtra("world", "overworld");
    PacketBuffer buf;
    pkt.write(buf);
    buf.resetReaderIndex();

    ChannelActionPacket decoded;
    decoded.read(buf);
    CHECK_EQ(static_cast<int>(decoded.getAction()), static_cast<int>(ChannelAction::JOIN));
    CHECK_EQ(decoded.getChannelId(), std::string("global"));
    CHECK_EQ(decoded.getPassword(), std::string("secret"));
    CHECK_EQ(decoded.getExtra().at("world"), std::string("overworld"));
}

static void testChannelActionIdsMatchJava() {
    // 0-based, matching Java ChannelAction enum.
    CHECK_EQ(static_cast<int>(ChannelAction::JOIN), 0);
    CHECK_EQ(static_cast<int>(ChannelAction::LEAVE), 1);
    CHECK_EQ(static_cast<int>(ChannelAction::CREATE), 2);
    CHECK_EQ(static_cast<int>(ChannelAction::DELETE), 3);
    CHECK_EQ(static_cast<int>(ChannelAction::INVITE), 4);
    CHECK_EQ(static_cast<int>(ChannelAction::ACCEPT), 5);
    CHECK_EQ(static_cast<int>(ChannelAction::KICK), 6);
    CHECK_EQ(static_cast<int>(ChannelAction::MUTE), 7);
    CHECK_EQ(static_cast<int>(ChannelAction::UNMUTE), 8);
    CHECK_EQ(static_cast<int>(ChannelAction::BAN), 9);
    CHECK_EQ(static_cast<int>(ChannelAction::UNBAN), 10);
    CHECK_EQ(static_cast<int>(ChannelAction::WHO), 11);
}

static void testAdminActionIds() {
    CHECK_EQ(static_cast<int>(AdminAction::AUTH), 0);
    CHECK_EQ(static_cast<int>(AdminAction::LOGOUT), 1);
    CHECK_EQ(static_cast<int>(AdminAction::SPY_START), 2);
    CHECK_EQ(static_cast<int>(AdminAction::SPY_STOP), 3);
    CHECK_EQ(static_cast<int>(AdminAction::RELOAD), 4);
    CHECK_EQ(static_cast<int>(AdminAction::STATUS), 5);
}

static void testPlatformIds() {
    CHECK_EQ(static_cast<int>(PlatformType::LEVILAMINA), 4);
    CHECK_EQ(static_cast<int>(PlatformType::POCKETMINE), 9);
    CHECK_EQ(static_cast<int>(PlatformType::ENDSTONE), 10);
}

static void testConfigSyncPacket() {
    ConfigSyncPacket pkt("{\"channels\":[\"global\",\"local\"]}", 12345);
    PacketBuffer buf;
    pkt.write(buf);
    buf.resetReaderIndex();
    ConfigSyncPacket decoded;
    decoded.read(buf);
    CHECK_EQ(decoded.getConfigJson(), std::string("{\"channels\":[\"global\",\"local\"]}"));
    CHECK_EQ(decoded.getTimestamp(), 12345);
}

static void testMentionPacket() {
    UUID mentioner = UUID::random();
    UUID mentioned = UUID::random();
    MentionPacket pkt(mentioner, "Steve", mentioned, "global", "hi @Steve", 999);
    PacketBuffer buf;
    pkt.write(buf);
    buf.resetReaderIndex();
    MentionPacket decoded;
    decoded.read(buf);
    CHECK_EQ(decoded.getMentionerName(), std::string("Steve"));
    CHECK_EQ(decoded.getChannelId(), std::string("global"));
    CHECK_EQ(decoded.getMessagePreview(), std::string("hi @Steve"));
    CHECK_EQ(decoded.getTimestamp(), 999);
}

static void testItemDisplayPacket() {
    UUID sender = UUID::random();
    ItemDisplayPacket pkt(sender, "Alex", "local", "{\"id\":\"diamond\"}", 42);
    PacketBuffer buf;
    pkt.write(buf);
    buf.resetReaderIndex();
    ItemDisplayPacket decoded;
    decoded.read(buf);
    CHECK_EQ(decoded.getSenderName(), std::string("Alex"));
    CHECK_EQ(decoded.getChannelId(), std::string("local"));
    CHECK_EQ(decoded.getItemJson(), std::string("{\"id\":\"diamond\"}"));
    CHECK_EQ(decoded.getTimestamp(), 42);
}

static void testSha256() {
    // SHA-256("abc") known digest.
    std::string hash = Sha256::hex("abc");
    CHECK_EQ(hash, std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    // SHA-256("") known digest.
    std::string empty = Sha256::hex("");
    CHECK_EQ(empty, std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
}

static void testI18nZhCN() {
    auto& i18n = I18n::getInstance();
    std::string msg = i18n.get("chat.join.joined", "zh_CN", {"global"});
    CHECK(msg.find("已加入频道") != std::string::npos);
    CHECK(msg.find("global") != std::string::npos);
}

static void testI18nEnUS() {
    auto& i18n = I18n::getInstance();
    std::string msg = i18n.get("chat.join.joined", "en_US", {"global"});
    CHECK(msg.find("Joined channel") != std::string::npos);
    CHECK(msg.find("global") != std::string::npos);
}

static void testI18nFallback() {
    auto& i18n = I18n::getInstance();
    // Missing key falls back to the key itself.
    std::string msg = i18n.get("nonexistent.key", "en_US");
    CHECK_EQ(msg, std::string("nonexistent.key"));
}

static void testI18nErrorMessage() {
    auto& i18n = I18n::getInstance();
    std::string msg = i18n.errorMessage("NC-404", "zh_CN");
    CHECK(msg.find("资源不存在") != std::string::npos);
    CHECK(msg.find("请检查频道ID或玩家名称是否正确") != std::string::npos);
}

static void testI18nKickMuteNotice() {
    auto& i18n = I18n::getInstance();
    std::string kickZh = i18n.get("chat.notice.kick_title", "zh_CN");
    std::string muteZh = i18n.get("chat.notice.mute_title", "zh_CN");
    CHECK(kickZh.find("踢出") != std::string::npos);
    CHECK(muteZh.find("禁言") != std::string::npos);

    std::string kickEn = i18n.get("chat.notice.kick_title", "en_US");
    std::string muteEn = i18n.get("chat.notice.mute_title", "en_US");
    // Lowercase comparison for "kicked"/"muted".
    std::string kickEnLower = kickEn;
    std::string muteEnLower = muteEn;
    for (char& c : kickEnLower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    for (char& c : muteEnLower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    CHECK(kickEnLower.find("kicked") != std::string::npos);
    CHECK(muteEnLower.find("muted") != std::string::npos);
}

int main() {
    std::printf("Running NovaChat-LeviLamina protocol tests...\n");

    testVarIntRoundTrip();
    testPacketBufferString();
    testPacketBufferUUID();
    testHandshakePacketV2();
    testHandshakePacketBackwardCompat();
    testHandshakeResponseFieldOrder();
    testHandshakeResponseRoundTrip();
    testChannelActionPacket();
    testChannelActionIdsMatchJava();
    testAdminActionIds();
    testPlatformIds();
    testConfigSyncPacket();
    testMentionPacket();
    testItemDisplayPacket();
    testSha256();
    testI18nZhCN();
    testI18nEnUS();
    testI18nFallback();
    testI18nErrorMessage();
    testI18nKickMuteNotice();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
