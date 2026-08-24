// PROTO-001 partial-write acceptance tests for NovaChat-LeviLamina.
//
// These tests verify that the sendLoop() framed-buffer + DrainResult state
// machine (commits cc5fa63 + 771a04f) never truncates, reorders, or drops
// packets when the underlying non-blocking send() returns a short write,
// zero write, WOULDBLOCK/EAGAIN, or a fatal error (ECONNRESET). The
// acceptance criteria for PROTO-001 are:
//
//   1. Short write injection — a hook that returns only 4 bytes on the first
//      call must leave residual bytes in mSendBuffer; a subsequent pump must
//      complete the drain; the accumulated byte stream must equal the
//      packet's encodePacket output with no truncation.
//   2. WOULDBLOCK mid-stream — a hook that returns -1 with WSAEWOULDBLOCK /
//      EAGAIN halfway through a packet must leave the partial bytes retained
//      (DrainResult::Blocked); resuming the pump must complete the drain
//      byte-exact.
//   3. Fatal error / dead connection — a hook that returns -1 with
//      ECONNRESET must trigger doDisconnect(): mConnected == false,
//      mSendBuffer cleared.
//   4. 50-packet burst with mixed types (ConfigSync ~4 KiB, ItemDisplay ~8
//      KiB, ChatMessage small, KeepAlive tiny) — inject short writes +
//      occasional WOULDBLOCK + full drain; assert the byte stream equals the
//      concatenation of all 50 packets' encodePacket output in order,
//      byte-exact, no reordering or loss.
//   5. Production path regression — with NO hook installed (mSendHook == null
//      and mSocket == INVALID_SOCKET), sendLoop must early-return without
//      crashing and without draining the queue.
//
// The tests install a deterministic send hook via setSendHookForTest() so
// they can inject each outcome on demand without a real socket or the
// network thread. The hook accumulates every byte slice it receives into a
// std::vector<uint8_t> so the test can compare the full byte stream against
// the expected encodePacket output.
//
// Build & run:
//   xmake f --sdk=n -m debug
//   xmake build novachat-levilamina-send-tests
//   xmake run novachat-levilamina-send-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/network/NetworkClient.h"
#include "../src/protocol/Packet.h"
#include "../src/protocol/PacketBuffer.h"
#include "../src/protocol/VarInt.h"
#include "../src/protocol/PacketIds.h"
#include "../src/protocol/ProtocolLimits.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <functional>
#include <memory>
#include <utility>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <cerrno>
#define SOCKET int
#define INVALID_SOCKET -1
#define SOCKET_ERROR -1
#define closesocket close
#endif

using namespace novachat::network;
using namespace novachat::protocol;

// ---------------------------------------------------------------------------
// Test framework (same idiom as test_protocol.cpp / test_network_client_tls.cpp)
// ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
// WSA init guard (Windows only)
// ---------------------------------------------------------------------------
#ifdef _WIN32
struct WsaInit {
    WsaInit() {
        WSADATA d;
        WSAStartup(MAKEWORD(2, 2), &d);
    }
    ~WsaInit() { WSACleanup(); }
};
static WsaInit gWsaInit;
#endif

// ---------------------------------------------------------------------------
// Platform-specific error setters for the send hook.
// ---------------------------------------------------------------------------
static void setWouldBlockError() {
#ifdef _WIN32
    WSASetLastError(WSAEWOULDBLOCK);
#else
    errno = EAGAIN;
#endif
}

static void setFatalError() {
#ifdef _WIN32
    WSASetLastError(WSAECONNRESET);
#else
    errno = ECONNRESET;
#endif
}

// ---------------------------------------------------------------------------
// Create a dummy valid socket fd. The hook intercepts doRawSend() before any
// real I/O happens on this fd, so it never needs to be connected or bound.
// The caller owns the fd and must close it (unless doDisconnect already
// closed it via the fatal-drain path).
// ---------------------------------------------------------------------------
static SOCKET createDummySocket() {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    return s;
}

// ---------------------------------------------------------------------------
// Faithful re-implementation of NetworkClient::encodePacket (private) using
// the public PacketBuffer + VarInt API. This lets the test compute the
// expected wire bytes without adding another test-only accessor.
//
// The frame layout is: VarInt(length) | byte(packetId) | UUID(requestId) |
//                       packet.write(buf)
// ---------------------------------------------------------------------------
static std::vector<uint8_t> encodeExpected(const Packet& packet) {
    PacketBuffer contentBuffer;
    contentBuffer.writeByte(packet.getPacketId());
    contentBuffer.writeUUID(packet.getRequestId());
    packet.write(contentBuffer);

    auto lengthBytes = VarInt::encode(static_cast<int32_t>(contentBuffer.size()));
    std::vector<uint8_t> output;
    output.reserve(lengthBytes.size() + contentBuffer.size());
    output.insert(output.end(), lengthBytes.begin(), lengthBytes.end());
    output.insert(output.end(), contentBuffer.getData().begin(), contentBuffer.getData().end());
    return output;
}

// ---------------------------------------------------------------------------
// Byte-vector comparison helper. Prints the first mismatch offset on failure.
// ---------------------------------------------------------------------------
static bool byteVectorsEqual(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    if (a.size() != b.size()) {
        std::printf("  size mismatch: expected %zu, got %zu\n", b.size(), a.size());
        return false;
    }
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i] != b[i]) {
            std::printf("  byte mismatch at offset %zu: expected 0x%02X, got 0x%02X\n",
                        i, b[i], a[i]);
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// Test 1: Short write injection.
//
// Hook behavior:
//   Call 1: return 4 (short write — only 4 bytes "written")
//   Call 2: return -1 with WOULDBLOCK (break the drain loop, leave residual)
//   Call 3+: return len (full remaining — complete the drain on next pump)
//
// Asserts:
//   - After pump 1: mSendOffset == 4, mSendBuffer non-empty (residual)
//   - After pump 2: mSendBuffer empty, mSendOffset 0 (fully drained)
//   - Hook accumulated bytes == encodePacket output (no truncation)
// ---------------------------------------------------------------------------
static void testShortWrite() {
    std::printf("testShortWrite...\n");

    // Build a ConfigSyncPacket with a JSON payload > 1 KiB so the encoded
    // frame is well over 1 KiB (the spec requires > 1 KiB).
    std::string largeJson(2048, 'X');
    auto packet = std::make_unique<ConfigSyncPacket>(largeJson, 1234567890LL);
    std::vector<uint8_t> expected = encodeExpected(*packet);
    CHECK(expected.size() > 1024);

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);
    SOCKET dummySock = createDummySocket();
    CHECK(dummySock != INVALID_SOCKET);
    client.setSocketForTest(dummySock);
    client.setConnectedForTest(true);

    // Stateful hook: call 1 returns 4, call 2 returns WOULDBLOCK, call 3+
    // returns full remaining.
    int callCount = 0;
    std::vector<uint8_t> accumulated;
    client.setSendHookForTest([&](const char* buf, int len) -> int {
        ++callCount;
        if (callCount == 1) {
            // Short write: only 4 bytes.
            accumulated.insert(accumulated.end(), buf, buf + 4);
            return 4;
        }
        if (callCount == 2) {
            // Backpressure: would block mid-stream.
            setWouldBlockError();
            return -1;
        }
        // Full remaining.
        accumulated.insert(accumulated.end(), buf, buf + len);
        return len;
    });

    client.enqueuePacketForTest(std::move(packet));

    // Pump 1: short write + WOULDBLOCK -> residual remains.
    client.pumpSendLoopForTest();

    auto [bufSize, bufOffset] = client.sendBufferStateForTest();
    CHECK_EQ(bufOffset, static_cast<size_t>(4));
    CHECK(bufSize > 4);
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(0));

    // Pump 2: resume and complete the drain.
    client.pumpSendLoopForTest();

    std::tie(bufSize, bufOffset) = client.sendBufferStateForTest();
    CHECK_EQ(bufSize, static_cast<size_t>(0));
    CHECK_EQ(bufOffset, static_cast<size_t>(0));

    // Byte-exact comparison: accumulated == expected.
    CHECK(byteVectorsEqual(accumulated, expected));

    client.clearSendHookForTest();
    // dummySock may or may not have been closed by doDisconnect (it wasn't in
    // this test since no fatal error occurred). Close it if still valid.
    // On Windows, closing an already-closed socket is safe (returns error).
    closesocket(dummySock);
}

// ---------------------------------------------------------------------------
// Test 2: WOULDBLOCK mid-stream.
//
// Hook behavior:
//   Call 1: return len/2 (partial write — half the packet)
//   Call 2: return -1 with WOULDBLOCK (Blocked mid-stream)
//   Call 3+: return len (full remaining)
//
// Asserts:
//   - After pump 1: mSendOffset == len/2, residual retained (Blocked)
//   - After pump 2: fully drained, mSendBuffer empty
//   - Accumulated bytes == encodePacket output (byte-exact, no truncation)
// ---------------------------------------------------------------------------
static void testWouldBlockMidStream() {
    std::printf("testWouldBlockMidStream...\n");

    // ItemDisplayPacket with a large itemJson (~4 KiB).
    std::string itemJson(4096, 'I');
    auto packet = std::make_unique<ItemDisplayPacket>(
        novachat::protocol::UUID{0x0102030405060708ULL, 0x090A0B0C0D0E0F10ULL},
        "dummyPlayer", "global", itemJson, 9988776655LL);
    std::vector<uint8_t> expected = encodeExpected(*packet);
    CHECK(expected.size() > 1024);

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);
    SOCKET dummySock = createDummySocket();
    CHECK(dummySock != INVALID_SOCKET);
    client.setSocketForTest(dummySock);
    client.setConnectedForTest(true);

    int callCount = 0;
    int halfLen = 0;
    std::vector<uint8_t> accumulated;
    client.setSendHookForTest([&](const char* buf, int len) -> int {
        ++callCount;
        if (callCount == 1) {
            // Write half the bytes.
            halfLen = len / 2;
            accumulated.insert(accumulated.end(), buf, buf + halfLen);
            return halfLen;
        }
        if (callCount == 2) {
            // WOULDBLOCK mid-stream.
            setWouldBlockError();
            return -1;
        }
        // Full remaining.
        accumulated.insert(accumulated.end(), buf, buf + len);
        return len;
    });

    client.enqueuePacketForTest(std::move(packet));

    // Pump 1: partial write + WOULDBLOCK -> residual retained.
    client.pumpSendLoopForTest();

    auto [bufSize, bufOffset] = client.sendBufferStateForTest();
    CHECK(bufOffset > 0);
    CHECK(bufSize > bufOffset);
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(0));

    // Pump 2: resume and complete.
    client.pumpSendLoopForTest();

    std::tie(bufSize, bufOffset) = client.sendBufferStateForTest();
    CHECK_EQ(bufSize, static_cast<size_t>(0));
    CHECK_EQ(bufOffset, static_cast<size_t>(0));

    CHECK(byteVectorsEqual(accumulated, expected));

    client.clearSendHookForTest();
    closesocket(dummySock);
}

// ---------------------------------------------------------------------------
// Test 3: Fatal error / dead connection (zero-write / ECONNRESET).
//
// Hook behavior:
//   Call 1: return -1 with ECONNRESET (fatal)
//
// Asserts:
//   - doDisconnect fires: mConnected == false
//   - mSendBuffer cleared (doDisconnect clears it)
//   - mSocket == INVALID_SOCKET (doDisconnect closes the fd)
//   - No crash
// ---------------------------------------------------------------------------
static void testFatalError() {
    std::printf("testFatalError...\n");

    std::string smallJson(128, 'F');
    auto packet = std::make_unique<ConfigSyncPacket>(smallJson, 1111111111LL);

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);
    SOCKET dummySock = createDummySocket();
    CHECK(dummySock != INVALID_SOCKET);
    client.setSocketForTest(dummySock);
    client.setConnectedForTest(true);

    int callCount = 0;
    client.setSendHookForTest([&](const char* /*buf*/, int /*len*/) -> int {
        ++callCount;
        // Fatal error: connection reset.
        setFatalError();
        return -1;
    });

    client.enqueuePacketForTest(std::move(packet));

    // Pump: fatal error -> doDisconnect.
    client.pumpSendLoopForTest();

    // doDisconnect must have fired.
    CHECK(!client.isConnected());
    CHECK_EQ(callCount, 1);

    auto [bufSize, bufOffset] = client.sendBufferStateForTest();
    CHECK_EQ(bufSize, static_cast<size_t>(0));
    CHECK_EQ(bufOffset, static_cast<size_t>(0));

    // doDisconnect closes mSocket.
    CHECK_EQ(client.socketForTest(), INVALID_SOCKET);

    client.clearSendHookForTest();
    // dummySock was closed by doDisconnect — do NOT close it again.
}

// ---------------------------------------------------------------------------
// Test 4: 50-packet burst with mixed types.
//
// Enqueues 50 packets of mixed types:
//   - 10 ConfigSyncPackets (~4 KiB JSON each)
//   - 10 ItemDisplayPackets (~8 KiB JSON each)
//   - 20 ChatMessagePackets (~200 bytes each)
//   - 10 KeepAlivePackets (tiny)
//
// Hook behavior (deterministic, call-counter driven):
//   Every 7th call: return 4 (short write)
//   Every 13th call: return -1 with WOULDBLOCK (Blocked)
//   All other calls: return len (full write)
//
// The test pumps until the outgoing queue is empty AND the send buffer is
// fully drained (both size and offset 0). Then asserts:
//   - Accumulated bytes == concatenation of all 50 encodePacket outputs
//   - No truncation, no reordering, no loss
//   - Outgoing queue fully drained
// ---------------------------------------------------------------------------
static void testBurstMixedPackets() {
    std::printf("testBurstMixedPackets...\n");

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);
    SOCKET dummySock = createDummySocket();
    CHECK(dummySock != INVALID_SOCKET);
    client.setSocketForTest(dummySock);
    client.setConnectedForTest(true);

    // Build the expected byte stream and enqueue the 50 packets.
    std::vector<uint8_t> expectedStream;
    int packetCount = 0;

    // 10 ConfigSyncPackets (~4 KiB JSON each)
    for (int i = 0; i < 10; ++i) {
        std::string json(4096, 'C');
        json[0] = static_cast<char>('0' + i); // make each unique
        auto pkt = std::make_unique<ConfigSyncPacket>(json, 1000LL + i);
        auto encoded = encodeExpected(*pkt);
        expectedStream.insert(expectedStream.end(), encoded.begin(), encoded.end());
        client.enqueuePacketForTest(std::move(pkt));
        ++packetCount;
    }

    // 10 ItemDisplayPackets (~8 KiB JSON each)
    for (int i = 0; i < 10; ++i) {
        std::string json(8192, 'D');
        json[0] = static_cast<char>('0' + i);
        auto pkt = std::make_unique<ItemDisplayPacket>(
            novachat::protocol::UUID{static_cast<uint64_t>(i + 1), 0xABCDEF0012345678ULL},
            "dummyPlayer", "global", json, 2000LL + i);
        auto encoded = encodeExpected(*pkt);
        expectedStream.insert(expectedStream.end(), encoded.begin(), encoded.end());
        client.enqueuePacketForTest(std::move(pkt));
        ++packetCount;
    }

    // 20 ChatMessagePackets (~200 bytes each)
    for (int i = 0; i < 20; ++i) {
        std::string content(200, 'M');
        content[0] = static_cast<char>('a' + (i % 26));
        auto pkt = std::make_unique<ChatMessagePacket>(
            novachat::protocol::UUID{static_cast<uint64_t>(i + 100), 0},
            "dummySender", "dummyClient", "global", content);
        auto encoded = encodeExpected(*pkt);
        expectedStream.insert(expectedStream.end(), encoded.begin(), encoded.end());
        client.enqueuePacketForTest(std::move(pkt));
        ++packetCount;
    }

    // 10 KeepAlivePackets (tiny)
    for (int i = 0; i < 10; ++i) {
        auto pkt = std::make_unique<KeepAlivePacket>(3000LL + i);
        auto encoded = encodeExpected(*pkt);
        expectedStream.insert(expectedStream.end(), encoded.begin(), encoded.end());
        client.enqueuePacketForTest(std::move(pkt));
        ++packetCount;
    }

    CHECK_EQ(packetCount, 50);
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(50));

    // Deterministic hook: every 7th call short-writes 4 bytes, every 13th
    // call returns WOULDBLOCK, all others return full len.
    int callCount = 0;
    std::vector<uint8_t> accumulated;
    client.setSendHookForTest([&](const char* buf, int len) -> int {
        ++callCount;
        if (callCount % 13 == 0) {
            // WOULDBLOCK: backpressure.
            setWouldBlockError();
            return -1;
        }
        if (callCount % 7 == 0) {
            // Short write: only 4 bytes.
            int n = (len < 4) ? len : 4;
            accumulated.insert(accumulated.end(), buf, buf + n);
            return n;
        }
        // Full write.
        accumulated.insert(accumulated.end(), buf, buf + len);
        return len;
    });

    // Pump until the queue is empty AND the send buffer is fully drained.
    // The high-water mark (1 MiB) and WOULDBLOCK backpressure mean multiple
    // pumps are needed.
    int maxPumps = 500;
    int pumps = 0;
    while (pumps < maxPumps) {
        client.pumpSendLoopForTest();
        ++pumps;

        auto [bufSize, bufOffset] = client.sendBufferStateForTest();
        if (client.outgoingQueueSizeForTest() == 0 && bufSize == 0 && bufOffset == 0) {
            break;
        }
    }

    CHECK(pumps < maxPumps); // did not time out

    auto [finalBufSize, finalBufOffset] = client.sendBufferStateForTest();
    CHECK_EQ(finalBufSize, static_cast<size_t>(0));
    CHECK_EQ(finalBufOffset, static_cast<size_t>(0));
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(0));

    // Byte-exact: accumulated == expectedStream (all 50 packets in order).
    CHECK(byteVectorsEqual(accumulated, expectedStream));

    client.clearSendHookForTest();
    closesocket(dummySock);
}

// ---------------------------------------------------------------------------
// Test 5: Production path regression (no hook installed).
//
// With mSendHook == null (the production default) and mSocket ==
// INVALID_SOCKET (no connect() called), sendLoop() must early-return at the
// mSocket guard without crashing, without draining the queue, and without
// changing mConnected.
//
// This guards the seam invariant: when no hook is installed, doRawSend()
// falls through to ::send / tlsSend exactly as before the seam. Since
// mSocket == INVALID_SOCKET, sendLoop returns before doRawSend is ever
// called — proving the production early-return path is intact.
// ---------------------------------------------------------------------------
static void testProductionPathRegression() {
    std::printf("testProductionPathRegression...\n");

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);

    // No hook installed — production default.
    client.clearSendHookForTest();

    // No socket set — mSocket == INVALID_SOCKET (the production guard).
    CHECK_EQ(client.socketForTest(), INVALID_SOCKET);

    // mConnected stays false (no connect() called).
    CHECK(!client.isConnected());

    // Enqueue a packet directly (bypasses the mConnected check in
    // sendPacket) so the queue is non-empty.
    auto packet = std::make_unique<KeepAlivePacket>(42424242LL);
    client.enqueuePacketForTest(std::move(packet));
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(1));

    // Pump: sendLoop must early-return (mSocket == INVALID_SOCKET guard).
    // No crash, no drain, no disconnect.
    client.pumpSendLoopForTest();

    // Queue must still have the packet (not drained).
    CHECK_EQ(client.outgoingQueueSizeForTest(), static_cast<size_t>(1));

    // mConnected unchanged.
    CHECK(!client.isConnected());

    // mSocket still INVALID_SOCKET.
    CHECK_EQ(client.socketForTest(), INVALID_SOCKET);

    // Send buffer still empty (no packet was encoded into it).
    auto [bufSize, bufOffset] = client.sendBufferStateForTest();
    CHECK_EQ(bufSize, static_cast<size_t>(0));
    CHECK_EQ(bufOffset, static_cast<size_t>(0));
}

// ---------------------------------------------------------------------------
// Test 6: Production path with a valid socket but no hook.
//
// With mSendHook == null and a valid mSocket (but not connected to anything),
// sendLoop() reaches doRawSend which calls ::send() on the unconnected
// socket. On Windows, send() on an unconnected TCP socket returns SOCKET_ERROR
// with WSAENOTSOCK or WSAENOTCONN — a fatal error that triggers doDisconnect.
//
// This test guards that the production ::send path (no hook) still works:
// the fatal error from ::send on a bad socket triggers doDisconnect, not a
// crash. It proves doRawSend falls through to ::send when mSendHook is null.
// ---------------------------------------------------------------------------
static void testProductionPathSendFallback() {
    std::printf("testProductionPathSendFallback...\n");

    NetworkClient client("127.0.0.1", 8888, "dummy", "dummy", "dummy", 1);

    // No hook — production path.
    client.clearSendHookForTest();

    // Create a valid socket fd but do NOT connect it. ::send on this fd
    // will fail with a fatal error.
    SOCKET dummySock = createDummySocket();
    CHECK(dummySock != INVALID_SOCKET);
    client.setSocketForTest(dummySock);
    client.setConnectedForTest(true);

    auto packet = std::make_unique<KeepAlivePacket>(77777777LL);
    client.enqueuePacketForTest(std::move(packet));

    // Pump: doRawSend falls through to ::send (no hook), ::send fails on
    // the unconnected socket -> Fatal -> doDisconnect.
    client.pumpSendLoopForTest();

    // doDisconnect must have fired: mConnected == false, mSocket closed.
    CHECK(!client.isConnected());
    CHECK_EQ(client.socketForTest(), INVALID_SOCKET);

    auto [bufSize, bufOffset] = client.sendBufferStateForTest();
    CHECK_EQ(bufSize, static_cast<size_t>(0));
    CHECK_EQ(bufOffset, static_cast<size_t>(0));

    // dummySock was closed by doDisconnect — do NOT close it again.
}

int main() {
    // Unbuffer stdout so crash output is not lost in a buffer.
    setvbuf(stdout, nullptr, _IONBF, 0);

    testShortWrite();
    testWouldBlockMidStream();
    testFatalError();
    testBurstMixedPackets();
    testProductionPathRegression();
    testProductionPathSendFallback();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
