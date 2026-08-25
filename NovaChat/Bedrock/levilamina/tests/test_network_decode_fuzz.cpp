// VERIFY-005 LeviLamina packet-decode fuzz tests.
//
// Fuzzes the pure decode boundaries of the LeviLamina receive path. The audit
// asks for four scenarios on the decode path whose entry point is
// NetworkClient::processReceivedData() -> decodePacket():
//
//   1. Unknown packet ID            -> close connection + release + log
//   2. Bad VarInt (non-terminating all-0x80 / >5 bytes) -> close + release
//   3. Bad UTF-8 (truncated/illegal) -> close + release
//   4. Oversized field (> MAX_FRAME_LENGTH 4 MiB) -> close + release, no OOM
//
// The cleanest pure boundaries are VarInt::tryPeek / VarInt::decode,
// PacketBuffer::readString(maxLength) and the Packet::read() methods. They
// throw std::runtime_error (decode/readString) or return false (tryPeek) on
// malformed input, and are exercised directly here. A frame is built by
// encoding a VarInt length prefix followed by a packet body, then handed to
// PacketBuffer + decodePacket-style decoding, so each scenario exercises the
// same code the real receive path runs after the length-prefix check.
//
// Behavior-preserving test seam: none. The pure decode functions are public
// (VarInt::decode/tryPeek, PacketBuffer::readString, Packet::read), so no
// production source is touched. The full NetworkClient::processReceivedData
// "close connection" behaviour is NOT tested here: the current production code
// only calls doDisconnect() for oversized length > MAX_FRAME_LENGTH; an unknown
// packet ID or a malformed body is dropped (decodePacket returns nullptr /
// exception is caught) WITHOUT closing the connection. That is a residual
// gap vs the audit's "close connection" expectation and is reported honestly
// in the deliverable. Testing it would require a new test seam on the private
// processReceivedData/mReceiveBuffer, and the task said: "if unsure, don't
// extract — test existing pure boundaries + honestly mark residual gaps".
//
// Build & run:
//   xmake f --sdk=n -m debug
//   xmake build novachat-levilamina-decode-fuzz-tests
//   xmake run novachat-levilamina-decode-fuzz-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/protocol/VarInt.h"
#include "../src/protocol/PacketBuffer.h"
#include "../src/protocol/Packet.h"
#include "../src/protocol/PacketIds.h"
#include "../src/protocol/ProtocolLimits.h"

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

using namespace novachat::protocol;

// ---------------------------------------------------------------------------
// Test framework (same idiom as test_protocol.cpp)
// ---------------------------------------------------------------------------
static int gPassed = 0;
static int gFailed = 0;

#define CHECK(cond) do { \
    if (cond) { ++gPassed; } \
    else { ++gFailed; std::printf("FAIL: %s (%s:%d)\n", #cond, __FILE__, __LINE__); } \
} while (0)

#define CHECK_THROWS(expr) do { \
    bool threw = false; \
    try { expr; } catch (const std::exception&) { threw = true; } \
    if (threw) { ++gPassed; } \
    else { ++gFailed; std::printf("FAIL: %s did not throw (%s:%d)\n", #expr, __FILE__, __LINE__); } \
} while (0)

#define CHECK_NOTHROWS(expr) do { \
    bool threw = false; \
    try { expr; } catch (const std::exception& e) { threw = true; std::printf("FAIL: %s threw: %s (%s:%d)\n", #expr, e.what(), __FILE__, __LINE__); } \
    if (!threw) { ++gPassed; } \
} while (0)

// ---------------------------------------------------------------------------
// Helpers: build a framed payload (VarInt length + body) and a PacketBuffer
// positioned at the body, mirroring what processReceivedData hands to
// decodePacket after stripping the length prefix.
// ---------------------------------------------------------------------------

// Build a frame: VarInt(length) || body. Returns the raw bytes.
static std::vector<uint8_t> buildFrame(const std::vector<uint8_t>& body) {
    auto lenBytes = VarInt::encode(static_cast<int32_t>(body.size()));
    std::vector<uint8_t> frame;
    frame.reserve(lenBytes.size() + body.size());
    frame.insert(frame.end(), lenBytes.begin(), lenBytes.end());
    frame.insert(frame.end(), body.begin(), body.end());
    return frame;
}

// Build a body: packetId || UUID(16 bytes) || payload.
static std::vector<uint8_t> buildBody(uint8_t packetId, const uint8_t* payload, size_t payloadLen) {
    std::vector<uint8_t> body;
    body.reserve(1 + 16 + payloadLen);
    body.push_back(packetId);
    // 16 zero bytes for the request-id UUID (decodePacket reads readUUID).
    for (int i = 0; i < 16; ++i) body.push_back(0);
    if (payload && payloadLen) {
        body.insert(body.end(), payload, payload + payloadLen);
    }
    return body;
}

// Strip the VarInt length prefix from `frame` and return a PacketBuffer over
// the body, positioned at the first byte of the body (packetId). Mirrors the
// state of the PacketBuffer that processReceivedData constructs for
// decodePacket.
static PacketBuffer bodyBufferFromFrame(const std::vector<uint8_t>& frame) {
    size_t bytesRead = 0;
    int32_t length = 0;
    bool ok = VarInt::tryPeek(frame.data(), frame.size(), length, bytesRead);
    if (!ok) {
        throw std::runtime_error("test harness: bad length prefix");
    }
    std::vector<uint8_t> body(frame.begin() + bytesRead, frame.end());
    return PacketBuffer(std::move(body));
}

// Decode a body buffer the way decodePacket does, returning the decoded
// packet (or nullptr on unknown id / short buffer). Re-implements the switch
// inline so the fuzz does not depend on a private NetworkClient method.
static std::unique_ptr<Packet> decodeBody(PacketBuffer& buffer) {
    if (buffer.readableBytes() < 1) return nullptr;
    uint8_t packetId = buffer.readByte();
    UUID requestId = buffer.readUUID();
    std::unique_ptr<Packet> packet;
    switch (packetId) {
        case PacketIds::HANDSHAKE_RESPONSE:    packet = std::make_unique<HandshakeResponsePacket>(); break;
        case PacketIds::HANDSHAKE_INIT:        packet = std::make_unique<HandshakeInitPacket>(); break;
        case PacketIds::HANDSHAKE_CHALLENGE:   packet = std::make_unique<HandshakeChallengePacket>(); break;
        case PacketIds::HANDSHAKE_AUTHENTICATE:packet = std::make_unique<HandshakeAuthenticatePacket>(); break;
        case PacketIds::CHAT_MESSAGE:          packet = std::make_unique<ChatMessagePacket>(); break;
        case PacketIds::KEEP_ALIVE:            packet = std::make_unique<KeepAlivePacket>(); break;
        case PacketIds::CHANNEL_ACTION_RESPONSE: packet = std::make_unique<ChannelActionResponsePacket>(); break;
        case PacketIds::CONFIG_SYNC:           packet = std::make_unique<ConfigSyncPacket>(); break;
        case PacketIds::TITLE:                 packet = std::make_unique<TitlePacket>(); break;
        case PacketIds::ADMIN_ACTION_RESPONSE: packet = std::make_unique<AdminActionResponsePacket>(); break;
        case PacketIds::ITEM_DISPLAY:          packet = std::make_unique<ItemDisplayPacket>(); break;
        case PacketIds::MENTION:                packet = std::make_unique<MentionPacket>(); break;
        case PacketIds::PRIVATE_MESSAGE:       packet = std::make_unique<PrivateMessagePacket>(); break;
        default: return nullptr; // unknown id
    }
    packet->setRequestId(requestId);
    packet->read(buffer);
    return packet;
}

// ---------------------------------------------------------------------------
// Scenario 1: Unknown packet ID -> decodePacket returns nullptr (frame
// dropped). RESIDUAL GAP: production code does NOT close the connection on
// unknown ID — it returns nullptr from decodePacket and processReceivedData
// just skips the frame. The audit expects "close connection"; that behaviour
// change is out of scope for a test-only commit, so we assert the actual
// production contract (nullptr, no throw, no OOM) and document the gap.
// ---------------------------------------------------------------------------
static void testUnknownPacketId() {
    std::printf("testUnknownPacketId...\n");

    // An ID that is not in the switch (0xFF is not a known packet id).
    std::vector<uint8_t> body = buildBody(0xFF, nullptr, 0);
    std::vector<uint8_t> frame = buildFrame(body);

    PacketBuffer buf = bodyBufferFromFrame(frame);
    std::unique_ptr<Packet> packet;
    CHECK_NOTHROWS(packet = decodeBody(buf));
    CHECK(packet == nullptr); // unknown id -> nullptr, no throw

    // The length prefix decoded fine (it is a legal VarInt); only the body
    // was undecodable. Assert the frame's VarInt length prefix is accepted.
    int32_t len = 0; size_t consumed = 0;
    CHECK(VarInt::tryPeek(frame.data(), frame.size(), len, consumed));
    CHECK(len == static_cast<int32_t>(body.size()));

    // A second known-id frame after the bad one is still decodable (the bad
    // frame is dropped, the stream stays aligned). Build a KEEP_ALIVE body
    // (0x07 || uuid || int64 timestamp).
    std::vector<uint8_t> tsPayload(8, 0);
    std::vector<uint8_t> goodBody = buildBody(PacketIds::KEEP_ALIVE, tsPayload.data(), tsPayload.size());
    std::vector<uint8_t> goodFrame = buildFrame(goodBody);
    PacketBuffer goodBuf = bodyBufferFromFrame(goodFrame);
    std::unique_ptr<Packet> good;
    CHECK_NOTHROWS(good = decodeBody(goodBuf));
    CHECK(good != nullptr);
    CHECK(good->getPacketId() == PacketIds::KEEP_ALIVE);
}

// ---------------------------------------------------------------------------
// Scenario 2: Bad VarInt in the length prefix.
//   (a) Non-terminating all-0x80 bytes: tryPeek returns false (incomplete /
//       too big). decode throws "VarInt is too big" once position >= 32.
//   (b) >5 bytes with a continuation bit: tryPeek caps at MAX_VARINT_SIZE (5)
//       and returns false. decode throws once position >= 32 (the 5th byte
//       still has CONTINUE_BIT -> 6th iteration has position 35 >= 32).
//   (c) Truncated VarInt (1 byte with CONTINUE_BIT, no more data): tryPeek
//       returns false (incomplete); decode throws "Incomplete VarInt".
// ---------------------------------------------------------------------------
static void testBadVarIntLengthPrefix() {
    std::printf("testBadVarIntLengthPrefix...\n");

    // (a) All-0x80 bytes: never terminates. tryPeek reads up to 5 bytes and
    // returns false. decode reads up to 5 bytes, position hits 35 >= 32 on
    // the 6th byte and throws "VarInt is too big".
    {
        std::vector<uint8_t> bad(8, 0x80);
        int32_t v = 0; size_t br = 0;
        bool ok = VarInt::tryPeek(bad.data(), bad.size(), v, br);
        CHECK(!ok); // non-terminating -> false (no disconnect in production)
        CHECK_THROWS({ VarInt::decode(bad.data(), bad.size(), br); });
    }

    // (b) Exactly 6 bytes, all with CONTINUE_BIT. tryPeek returns false at
    // the 5-byte cap. decode throws "VarInt is too big".
    {
        std::vector<uint8_t> bad(6, 0x80);
        for (auto& b : bad) b = 0x80 | 0x01;
        int32_t v = 0; size_t br = 0;
        bool ok = VarInt::tryPeek(bad.data(), bad.size(), v, br);
        CHECK(!ok);
        CHECK_THROWS({ VarInt::decode(bad.data(), bad.size(), br); });
    }

    // (c) Truncated: 1 byte with CONTINUE_BIT, size == 1. tryPeek returns
    // false (incomplete); decode throws "Incomplete VarInt".
    {
        std::vector<uint8_t> bad(1, 0x80);
        int32_t v = 0; size_t br = 0;
        bool ok = VarInt::tryPeek(bad.data(), bad.size(), v, br);
        CHECK(!ok);
        CHECK_THROWS({ VarInt::decode(bad.data(), bad.size(), br); });
    }

    // (d) A valid VarInt length prefix that decodes to a huge value
    // (> MAX_FRAME_LENGTH). This is the ONE case production code disconnects
    // on. We assert the decode succeeds (the VarInt itself is well-formed)
    // and the value exceeds the ceiling — the disconnect decision is the
    // caller's responsibility (processReceivedData).
    {
        // Encode MAX_FRAME_LENGTH + 1 as a VarInt.
        int32_t over = static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH) + 1;
        auto bytes = VarInt::encode(over);
        int32_t v = 0; size_t br = 0;
        bool ok = VarInt::tryPeek(bytes.data(), bytes.size(), v, br);
        CHECK(ok);
        CHECK(v == over);
        CHECK(v > static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH));
    }
}

// ---------------------------------------------------------------------------
// Scenario 3: Bad UTF-8 (truncated / illegal). PacketBuffer::readString
// copies the raw bytes without validating UTF-8 (it stores std::string), so
// "bad UTF-8" surfaces as a truncated frame: the declared string length
// exceeds the remaining buffer -> checkReadable throws "Buffer underflow".
// A declared length that is negative or exceeds MAX_FRAME_LENGTH is rejected
// up front. This matches the production behaviour: decodePacket catches the
// std::runtime_error and drops the frame (residual gap: no disconnect).
// ---------------------------------------------------------------------------
static void testBadUtf8TruncatedString() {
    std::printf("testBadUtf8TruncatedString...\n");

    // (a) Declared string length > remaining buffer -> "Buffer underflow".
    // Build a ChatMessage body whose senderName VarInt length claims 200
    // bytes but only 1 byte follows. PacketBuffer::readString(64) sees
    // length=200 (> maxLength 64) -> "exceeds maximum" BEFORE underflow.
    {
        std::vector<uint8_t> payload;
        // senderName VarInt length = 200 (exceeds MAX_SENDER_NAME 64).
        auto lenBytes = VarInt::encode(200);
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        payload.push_back('x'); // only 1 byte of the claimed 200
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        CHECK_THROWS(packet = decodeBody(buf)); // readString(64) -> "exceeds maximum"
        CHECK(packet == nullptr);
    }

    // (b) Declared length within the field cap but greater than remaining
    // buffer -> "Buffer underflow".
    {
        std::vector<uint8_t> payload;
        // senderName length = 10 (<= MAX_SENDER_NAME 64) but only 1 byte follows.
        auto lenBytes = VarInt::encode(10);
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        payload.push_back('x');
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        CHECK_THROWS(packet = decodeBody(buf)); // checkReadable -> "Buffer underflow"
        CHECK(packet == nullptr);
    }

    // (c) Illegal/overlong UTF-8 sequences: readString stores raw bytes, so
    // illegal UTF-8 does NOT throw by itself — it is preserved. The audit's
    // "bad UTF-8 -> close + release" is only observable as a protocol
    // violation at a higher layer (the backend rejects it). At the decode
    // layer, the only failure mode is a declared length mismatch (truncated
    // or oversized), covered in (a)/(b). Assert that illegal UTF-8 bytes
    // round-trip through readString without throwing (documenting the layer
    // boundary honestly).
    {
        // 0xFF is never a valid UTF-8 lead byte; 0xC0 0x80 is an overlong NUL.
        std::vector<uint8_t> illegal = {0xFF, 0xFE, 0xC0, 0x80};
        std::vector<uint8_t> payload;
        auto lenBytes = VarInt::encode(static_cast<int32_t>(illegal.size()));
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        payload.insert(payload.end(), illegal.begin(), illegal.end());
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        // readString accepts the raw bytes (no UTF-8 validation); the next
        // read (clientId) underflows because the buffer is exhausted ->
        // throws "Buffer underflow".
        CHECK_THROWS(packet = decodeBody(buf));
        CHECK(packet == nullptr);
    }

    // (d) Truncated multi-byte UTF-8 inside a field: declared length covers
    // the lead byte but the continuation bytes are missing. readString copies
    // the declared-length slice verbatim; if that exhausts the buffer, the
    // NEXT read throws. Assert the decode throws (frame dropped).
    {
        std::vector<uint8_t> payload;
        // senderName length = 1, value = 0xC3 (start of a 2-byte UTF-8 char
        // with no continuation byte). readString(64) reads the 1 byte fine;
        // the next read (clientId length) underflows.
        auto lenBytes = VarInt::encode(1);
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        payload.push_back(0xC3);
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        CHECK_THROWS(packet = decodeBody(buf));
        CHECK(packet == nullptr);
    }
}

// ---------------------------------------------------------------------------
// Scenario 4: Oversized field (declared length > ProtocolLimits::MAX_FRAME_LENGTH
// 4 MiB). readString rejects it BEFORE allocating ("Invalid string length")
// so no OOM. A field length just over MAX_FRAME_LENGTH must throw immediately
// and must NOT allocate a 4 MiB+ buffer.
// ---------------------------------------------------------------------------
static void testOversizedField() {
    std::printf("testOversizedField...\n");

    // (a) Declared string length > MAX_FRAME_LENGTH -> "Invalid string
    // length" thrown before allocation. We assert no exception escapes
    // beyond the decode try/catch (i.e. decodeBody throws, packet == null).
    {
        std::vector<uint8_t> payload;
        int32_t huge = static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH) + 1;
        auto lenBytes = VarInt::encode(huge);
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        // No actual payload bytes needed: readString checks the declared
        // length against MAX_FRAME_LENGTH BEFORE checkReadable, so it throws
        // without needing the bytes to be present.
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        CHECK_THROWS(packet = decodeBody(buf));
        CHECK(packet == nullptr);
    }

    // (b) readString(maxLength) with a declared length exceeding the field
    // cap but under MAX_FRAME_LENGTH -> "exceeds maximum" (not "Invalid
    // string length"). e.g. senderName declared 100 bytes but cap is 64.
    {
        std::vector<uint8_t> payload;
        auto lenBytes = VarInt::encode(100); // > MAX_SENDER_NAME (64)
        payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
        // Provide the 100 bytes so checkReadable would pass — the cap check
        // fires first.
        payload.insert(payload.end(), 100, 'x');
        std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
        std::vector<uint8_t> frame = buildFrame(body);
        PacketBuffer buf = bodyBufferFromFrame(frame);
        std::unique_ptr<Packet> packet;
        CHECK_THROWS(packet = decodeBody(buf)); // "exceeds maximum 64"
        CHECK(packet == nullptr);
    }

    // (c) A frame whose declared packet length (the outer VarInt) exceeds
    // MAX_FRAME_LENGTH. This is the ONE case the production receive path
    // calls doDisconnect() on. We assert the VarInt decodes to a value over
    // the ceiling (the disconnect decision is processReceivedData's; here
    // we only verify the decode layer sees the oversized length).
    {
        int32_t over = static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH) + 1;
        auto lenBytes = VarInt::encode(over);
        int32_t v = 0; size_t br = 0;
        bool ok = VarInt::tryPeek(lenBytes.data(), lenBytes.size(), v, br);
        CHECK(ok);
        CHECK(v > static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH));
    }

    // (d) No OOM: repeat the oversized-declared-length decode many times
    // and assert the process stays alive (no bad_alloc). This is the
    // audit's "no OOM" acceptance criterion. A bad_alloc here would crash
    // the test process.
    {
        for (int i = 0; i < 1000; ++i) {
            std::vector<uint8_t> payload;
            int32_t huge = static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH) + 1;
            auto lenBytes = VarInt::encode(huge);
            payload.insert(payload.end(), lenBytes.begin(), lenBytes.end());
            std::vector<uint8_t> body = buildBody(PacketIds::CHAT_MESSAGE, payload.data(), payload.size());
            std::vector<uint8_t> frame = buildFrame(body);
            PacketBuffer buf = bodyBufferFromFrame(frame);
            std::unique_ptr<Packet> packet;
            try { packet = decodeBody(buf); }
            catch (const std::exception&) { /* expected */ }
            CHECK(packet == nullptr);
        }
    }
}

// ---------------------------------------------------------------------------
// Extra: golden round-trip sanity. A well-formed KEEP_ALIVE frame decodes to
// a non-null packet with the right id, so the fuzz harness is known-good
// before any negative case.
// ---------------------------------------------------------------------------
static void testWellFormedKeepAliveDecodes() {
    std::printf("testWellFormedKeepAliveDecodes...\n");
    // payload: int64 timestamp (big-endian, 8 bytes)
    std::vector<uint8_t> ts(8, 0);
    ts[7] = 0x01; // timestamp = 1
    std::vector<uint8_t> body = buildBody(PacketIds::KEEP_ALIVE, ts.data(), ts.size());
    std::vector<uint8_t> frame = buildFrame(body);
    PacketBuffer buf = bodyBufferFromFrame(frame);
    std::unique_ptr<Packet> packet;
    CHECK_NOTHROWS(packet = decodeBody(buf));
    CHECK(packet != nullptr);
    CHECK(packet->getPacketId() == PacketIds::KEEP_ALIVE);
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);

    testWellFormedKeepAliveDecodes();
    testUnknownPacketId();
    testBadVarIntLengthPrefix();
    testBadUtf8TruncatedString();
    testOversizedField();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
