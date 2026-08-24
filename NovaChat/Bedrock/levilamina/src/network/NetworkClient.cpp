#include "NetworkClient.h"
#include "../protocol/VarInt.h"
#include "../protocol/ProtocolLimits.h"
#include "../util/Sha256.h"
#include "../util/HmacSha256.h"

#include <chrono>
#include <cstring>
#include <cerrno>
#include <random>

#include <sstream>
#include <iomanip>

namespace novachat::network {

using namespace novachat::protocol;

#ifdef _WIN32
bool NetworkClient::sWsaInitialized = false;

void NetworkClient::initWsa() {
    if (!sWsaInitialized) {
        WSADATA wsaData;
        if (WSAStartup(MAKEWORD(2, 2), &wsaData) == 0) {
            sWsaInitialized = true;
        }
    }
}
#endif

NetworkClient::NetworkClient(const std::string& host, uint16_t port,
                             const std::string& username, const std::string& password,
                             const std::string& serverVersion, int reconnectDelay)
    : mHost(host)
    , mPort(port)
    , mUsername(username)
    , mPassword(password)
    , mServerVersion(serverVersion)
    , mReconnectDelay(reconnectDelay) {
#ifdef _WIN32
    initWsa();
#endif
    mReceiveBuffer.reserve(RECEIVE_BUFFER_SIZE);
}

NetworkClient::~NetworkClient() {
    disconnect();
}

bool NetworkClient::connect() {
    if (mRunning) {
        return false;
    }

    mOutgoingQueue.clear();
    mIncomingQueue.clear();
    mOutgoingQueue.reset();
    mIncomingQueue.reset();
    mRunning = true;
    mNetworkThread = std::make_unique<std::thread>(&NetworkClient::networkThreadFunc, this);
    return true;
}

bool NetworkClient::reconfigure(const std::string& host, uint16_t port,
                                const std::string& username, const std::string& password,
                                const std::string& serverVersion, int reconnectDelay) {
    const bool wasRunning = mRunning.load();
    disconnect();

    mHost = host;
    mPort = port;
    mUsername = username;
    mPassword = password;
    mServerVersion = serverVersion;
    mReconnectDelay = reconnectDelay;

    return !wasRunning || connect();
}

void NetworkClient::setTlsConfig(bool tlsEnabled,
                                 const std::string& caCertPath,
                                 const std::string& clientCertPath,
                                 const std::string& clientKeyPath) {
    // AUTH-002 TLS: store the transport-encryption config. Applied in
    // doConnect() — when mTlsEnabled is true, the socket is wrapped in an
    // OpenSSL TLS session (SSL_CTX with SSL_VERIFY_PEER, optional mTLS
    // client cert/key). Verification is enforced unconditionally once TLS is
    // on (there is no mTlsInsecure / skipVerify member by design).
    mTlsEnabled = tlsEnabled;
    mTlsCaCertPath = caCertPath;
    mTlsClientCertPath = clientCertPath;
    mTlsClientKeyPath = clientKeyPath;
}

void NetworkClient::disconnect() {
    mRunning = false;
    mOutgoingQueue.stop();
    mIncomingQueue.stop();

    if (mNetworkThread && mNetworkThread->joinable()) {
        mNetworkThread->join();
    }
    mNetworkThread.reset();

    doDisconnect();
}

void NetworkClient::sendPacket(std::unique_ptr<Packet> packet) {
    if (mConnected) {
        mOutgoingQueue.push(std::move(packet));
    }
}

void NetworkClient::registerHandler(uint8_t packetId, PacketHandler handler) {
    std::lock_guard<std::mutex> lock(mHandlersMutex);
    mHandlers[packetId] = std::move(handler);
}

void NetworkClient::processIncomingPackets() {
    while (auto packet = mIncomingQueue.tryPop()) {
        uint8_t packetId = (*packet)->getPacketId();
        
        std::lock_guard<std::mutex> lock(mHandlersMutex);
        auto it = mHandlers.find(packetId);
        if (it != mHandlers.end()) {
            it->second(std::move(*packet));
        }
    }
}

void NetworkClient::networkThreadFunc() {
    while (mRunning) {
        if (!mConnected) {
            if (doConnect()) {
                // Defer sendHandshake() until after the TLS handshake (if any)
                // completes. For plaintext, mTlsState is Idle so the deferred
                // handshake fires on the next loop iteration. For TLS, the
                // handshake must reach Established before any application data
                // is sent — sending through SSL_write before the TLS session is
                // established would corrupt the state machine.
                mHandshakePending = true;
            } else {
                handleReconnect();
                continue;
            }
        }

        // AUTH-002 TLS: drive the non-blocking SSL_connect state machine before
        // any application I/O. SSL_connect may need to retry after select()
        // reports the fd readable or writable (SSL_want_read / SSL_want_write).
        // Only when the handshake reaches Established do we enter the normal
        // select() loop for application data; a Failed state tears down the
        // connection and schedules a reconnect.
        if (mTlsState == TlsState::Connecting) {
            TlsState next = tlsHandshakeStep();
            if (next == TlsState::Failed) {
                doDisconnect();
                handleReconnect();
                continue;
            }
            if (next == TlsState::Established) {
                // TLS session ready — send the deferred HandshakeInit now that
                // SSL_write can encrypt application data.
                if (mHandshakePending) {
                    sendHandshake();
                    mHandshakePending = false;
                }
                // Fall through to the normal select() loop.
            } else {
                // Still Connecting — SSL_connect returned WANT_READ or
                // WANT_WRITE. Do a blocking select() on the fd (with a 100ms
                // timeout) so the thread sleeps until the OS reports data
                // ready, instead of busy-looping SSL_connect. We must NOT call
                // receiveLoop/sendLoop here — the bytes on the wire are TLS
                // handshake records, not NovaProtocol application data.
                fd_set readSet, writeSet;
                FD_ZERO(&readSet);
                FD_ZERO(&writeSet);
                FD_SET(mSocket, &readSet);
                FD_SET(mSocket, &writeSet);
                struct timeval timeout;
                timeout.tv_sec = 0;
                timeout.tv_usec = 100000; // 100ms
                select(static_cast<int>(mSocket) + 1, &readSet, &writeSet,
                       nullptr, &timeout);
                continue;
            }
        } else if (mHandshakePending) {
            // Plaintext path (TLS disabled): send the handshake immediately.
            sendHandshake();
            mHandshakePending = false;
        }

        // Main network loop
        fd_set readSet, writeSet;
        FD_ZERO(&readSet);
        FD_ZERO(&writeSet);
        FD_SET(mSocket, &readSet);

        // Request writable events whenever there are queued packets OR pending
        // send-buffer bytes left from a previous short write / EAGAIN. Without
        // the residual check, bytes stuck behind EAGAIN would only flush when a
        // new packet happens to arrive.
        if (!mOutgoingQueue.empty() || mSendOffset < mSendBuffer.size()) {
            FD_SET(mSocket, &writeSet);
        }

        struct timeval timeout;
        timeout.tv_sec = 0;
        timeout.tv_usec = 100000; // 100ms

        int result = select(static_cast<int>(mSocket) + 1, &readSet, &writeSet, nullptr, &timeout);

        if (result == SOCKET_ERROR) {
            doDisconnect();
            continue;
        }

        if (result > 0) {
            // Handle readable
            if (FD_ISSET(mSocket, &readSet)) {
                receiveLoop();
            }

            // Handle writable
            if (FD_ISSET(mSocket, &writeSet)) {
                sendLoop();
            }
        }

        // Send keep-alive
        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastKeepAlive).count();
        if (elapsed >= KEEP_ALIVE_INTERVAL_MS) {
            sendKeepAlive();
            mLastKeepAlive = now;
        }
    }
}

bool NetworkClient::doConnect() {
    std::lock_guard<std::mutex> lock(mSocketMutex);

    // Create socket
    mSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (mSocket == INVALID_SOCKET) {
        return false;
    }

    // Set TCP_NODELAY
    int flag = 1;
    setsockopt(mSocket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&flag), sizeof(flag));

    // Resolve hostname
    struct addrinfo hints{}, *result = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    std::string portStr = std::to_string(mPort);
    if (getaddrinfo(mHost.c_str(), portStr.c_str(), &hints, &result) != 0) {
        closesocket(mSocket);
        mSocket = INVALID_SOCKET;
        return false;
    }

    // Connect
    int connectResult = ::connect(mSocket, result->ai_addr, static_cast<int>(result->ai_addrlen));
    freeaddrinfo(result);

    if (connectResult == SOCKET_ERROR) {
        closesocket(mSocket);
        mSocket = INVALID_SOCKET;
        return false;
    }

    // AUTH-002 TLS: when TLS is enabled, build the SSL_CTX (SSL_VERIFY_PEER,
    // no insecure bypass), optionally load the mTLS client cert/key, create
    // the SSL session, and bind it to the socket. The actual handshake is
    // driven by tlsHandshakeStep() from the select() loop below — we do NOT
    // call SSL_connect here because the socket is still in blocking mode at
    // this point (set to non-blocking immediately after). Calling SSL_connect
    // on a blocking socket would block the network thread until the handshake
    // completes or fails, defeating the non-blocking design.
    if (mTlsEnabled) {
        if (!setupTlsContext()) {
            // setupTlsContext logs the OpenSSL error and cleans up any
            // partially-built state. Close the raw socket and bail.
            closesocket(mSocket);
            mSocket = INVALID_SOCKET;
            return false;
        }

        // Bind the SSL session to the socket. SSL_set_fd must come before
        // SSL_connect (the handshake needs to know which fd to use).
        SSL_set_fd(mSsl, static_cast<int>(mSocket));

        // Set the TLS state to Connecting so the select() loop knows to drive
        // the handshake. SSL_connect is NOT called here — it is called in
        // tlsHandshakeStep() after the socket is set to non-blocking mode.
        mTlsState = TlsState::Connecting;
    } else {
        mTlsState = TlsState::Idle;
    }

    // Set non-blocking mode — must happen BEFORE the first SSL_connect call
    // (in tlsHandshakeStep) so the non-blocking state machine works.
#ifdef _WIN32
    u_long mode = 1;
    ioctlsocket(mSocket, FIONBIO, &mode);
#else
    int flags = fcntl(mSocket, F_GETFL, 0);
    fcntl(mSocket, F_SETFL, flags | O_NONBLOCK);
#endif

    mConnected = true;
    mReceiveBuffer.clear();
    // Reset the send buffer: a fresh socket must not transmit bytes left over
    // from a prior connection (a short write would otherwise replay stale
    // frames on the new fd).
    mSendBuffer.clear();
    mSendOffset = 0;
    mLastKeepAlive = std::chrono::steady_clock::now();

    return true;
}

void NetworkClient::doDisconnect() {
    std::lock_guard<std::mutex> lock(mSocketMutex);

    // AUTH-002 TLS: tear down the SSL session before closing the socket.
    // SSL_shutdown sends a close_notify alert (best-effort — the peer may
    // not be listening, which is fine); SSL_free releases the session.
    // mSslCtx is freed here too since it is per-connection (created in
    // setupTlsContext, not shared across reconnects).
    if (mSsl || mSslCtx) {
        tlsShutdown();
    }
    mTlsState = TlsState::Idle;

    if (mSocket != INVALID_SOCKET) {
        closesocket(mSocket);
        mSocket = INVALID_SOCKET;
    }

    mConnected = false;
    mAuthenticated = false;
    mReceiveBuffer.clear();
    // Drop any unsent bytes — the fd is gone, and reconnection creates a new
    // stream that must start clean (doConnect also clears, but clearing here
    // keeps the state invariant true whenever mConnected is false).
    mSendBuffer.clear();
    mSendOffset = 0;
}

void NetworkClient::handleReconnect() {
    // Sleep for mReconnectDelay, but wake up promptly when mRunning goes false
    // so disconnect() can join the network thread without waiting the full
    // delay. Polling at 100ms keeps shutdown responsive without a busy loop.
    auto deadline = std::chrono::steady_clock::now() +
                    std::chrono::seconds(mReconnectDelay);
    while (mRunning.load() && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

void NetworkClient::receiveLoop() {
    uint8_t buffer[4096];

    // AUTH-002 TLS: when a TLS session is established, read decrypted
    // application data via SSL_read instead of raw recv(). SSL_read returns
    // the same semantics as recv (positive byte count, 0 = EOF, <0 = error),
    // but the error codes come from SSL_get_error, not errno/WSAGetLastError.
    int bytesReceived;
    if (mTlsState == TlsState::Established && mSsl != nullptr) {
        bytesReceived = tlsRecv(reinterpret_cast<char*>(buffer), static_cast<int>(sizeof(buffer)));
    } else {
        bytesReceived = recv(mSocket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
    }

    if (bytesReceived <= 0) {
        if (bytesReceived == 0 ||
#ifdef _WIN32
            WSAGetLastError() != WSAEWOULDBLOCK
#else
            errno != EWOULDBLOCK && errno != EAGAIN
#endif
        ) {
            doDisconnect();
        }
        return;
    }

    // Append to receive buffer
    mReceiveBuffer.insert(mReceiveBuffer.end(), buffer, buffer + bytesReceived);

    // Process complete packets
    processReceivedData();
}

void NetworkClient::processReceivedData() {
    while (mReceiveBuffer.size() >= 2) { // Minimum: 1 byte length + 1 byte packet ID
        // Try to read packet length (VarInt)
        int32_t packetLength;
        size_t lengthBytes;
        
        if (!VarInt::tryPeek(mReceiveBuffer.data(), mReceiveBuffer.size(), packetLength, lengthBytes)) {
            break; // Incomplete length
        }

        if (packetLength <= 0 || packetLength > static_cast<int32_t>(ProtocolLimits::MAX_FRAME_LENGTH)) { // PROTO-002: unified 4 MiB ceiling
            doDisconnect();
            return;
        }

        size_t totalLength = lengthBytes + static_cast<size_t>(packetLength);
        if (mReceiveBuffer.size() < totalLength) {
            break; // Incomplete packet
        }

        // Extract packet data
        std::vector<uint8_t> packetData(
            mReceiveBuffer.begin() + lengthBytes,
            mReceiveBuffer.begin() + totalLength
        );

        // Remove processed data from buffer
        mReceiveBuffer.erase(mReceiveBuffer.begin(), mReceiveBuffer.begin() + totalLength);

        // Decode packet. A short/malformed payload (e.g. a partial frame that
        // passed the VarInt length check but has fewer bytes than the packet
        // body needs) throws std::runtime_error from PacketBuffer. Rather than
        // letting that propagate and terminate the network thread, treat it as
        // an undecodable frame: drop it and continue processing the rest of the
        // buffer. This keeps a single bad byte from killing the connection.
        PacketBuffer buffer(std::move(packetData));
        std::unique_ptr<Packet> packet;
        try {
            packet = decodePacket(buffer);
        } catch (const std::exception&) {
            packet.reset();
        }

        if (packet) {
            // Handle handshake response internally
            if (packet->getPacketId() == PacketIds::HANDSHAKE_RESPONSE) {
                handleHandshakeResponse(static_cast<HandshakeResponsePacket&>(*packet));
            } else if (packet->getPacketId() == PacketIds::HANDSHAKE_CHALLENGE) {
                // AUTH-002: server's challenge. Drive the challenge-response
                // here on the network thread; handleHandshakeChallenge queues
                // the HandshakeAuthenticate reply via sendPacket so it leaves
                // on the next sendLoop pass. Not forwarded to the incoming
                // queue (it is a transport-layer handshake packet).
                handleHandshakeChallenge(static_cast<HandshakeChallengePacket&>(*packet));
                continue;
            }

            // Queue for main thread processing
            mIncomingQueue.push(std::move(packet));
        }
    }
}

std::unique_ptr<Packet> NetworkClient::decodePacket(PacketBuffer& buffer) {
    if (buffer.readableBytes() < 1) {
        return nullptr;
    }

    uint8_t packetId = buffer.readByte();
    UUID requestId = buffer.readUUID();

    std::unique_ptr<Packet> packet;

    switch (packetId) {
        case PacketIds::HANDSHAKE_RESPONSE:
            packet = std::make_unique<HandshakeResponsePacket>();
            break;
        case PacketIds::HANDSHAKE_INIT:
            packet = std::make_unique<HandshakeInitPacket>();
            break;
        case PacketIds::HANDSHAKE_CHALLENGE:
            packet = std::make_unique<HandshakeChallengePacket>();
            break;
        case PacketIds::HANDSHAKE_AUTHENTICATE:
            packet = std::make_unique<HandshakeAuthenticatePacket>();
            break;
        case PacketIds::CHAT_MESSAGE:
            packet = std::make_unique<ChatMessagePacket>();
            break;
        case PacketIds::KEEP_ALIVE:
            packet = std::make_unique<KeepAlivePacket>();
            break;
        case PacketIds::CHANNEL_ACTION_RESPONSE:
            packet = std::make_unique<ChannelActionResponsePacket>();
            break;
        case PacketIds::CONFIG_SYNC:
            packet = std::make_unique<ConfigSyncPacket>();
            break;
        case PacketIds::TITLE:
            packet = std::make_unique<TitlePacket>();
            break;
        case PacketIds::ADMIN_ACTION_RESPONSE:
            packet = std::make_unique<AdminActionResponsePacket>();
            break;
        case PacketIds::ITEM_DISPLAY:
            packet = std::make_unique<ItemDisplayPacket>();
            break;
        case PacketIds::MENTION:
            packet = std::make_unique<MentionPacket>();
            break;
        case PacketIds::PRIVATE_MESSAGE:
            packet = std::make_unique<PrivateMessagePacket>();
            break;
        default:
            return nullptr;
    }

    packet->setRequestId(requestId);
    packet->read(buffer);
    return packet;
}

void NetworkClient::sendLoop() {
    // Drain the pending send buffer [mSendOffset, mSendBuffer.size()) directly
    // to the socket. Returns one of:
    //   Drained — buffer fully flushed and cleared;
    //   Blocked — non-blocking send would block (EAGAIN/WSAEWOULDBLOCK),
    //            residual retained for the next writable notification;
    //   Fatal   — socket error (EPIPE/ECONNRESET/ENOTSOCK/...), caller
    //            must disconnect.
    enum class DrainResult { Drained, Blocked, Fatal };

    auto drain = [this]() -> DrainResult {
        while (mSendOffset < mSendBuffer.size()) {
            const size_t remaining = mSendBuffer.size() - mSendOffset;
            const int sent = doRawSend(
                reinterpret_cast<const char*>(mSendBuffer.data() + mSendOffset),
                static_cast<int>(remaining));
            if (sent > 0) {
                mSendOffset += static_cast<size_t>(sent);
                continue;
            }
            // sent <= 0: distinguish backpressure from fatal errors.
            // doRawSend already set WSAGetLastError / errno to the right
            // value (tlsSend and the test hook both use WSASetLastError /
            // errno assignment; ::send sets it natively). We only need to
            // read it here to classify the outcome.
#ifdef _WIN32
            const int err = WSAGetLastError();
            if (err == WSAEWOULDBLOCK) {
                return DrainResult::Blocked;
            }
            // WSAECONNRESET / WSAECONNABORTED / WSAENOTSOCK / WSAESHUTDOWN / ...
            return DrainResult::Fatal;
#else
            // EINTR (signal interruption) is transient — retry the same send
            // without dropping bytes or falling through to the fatal branch.
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return DrainResult::Blocked;
            }
            // EPIPE / ECONNRESET / ENOTSOCK / EBADF / ...
            return DrainResult::Fatal;
#endif
        }
        // Fully flushed — drop the dead prefix and reset the offset.
        mSendBuffer.clear();
        mSendOffset = 0;
        return DrainResult::Drained;
    };

    bool fatalError = false;
    {
        // Hold the socket lock for the whole pass so mSocket cannot be closed
        // mid-drain. doDisconnect() is called *outside* this scope (after the
        // guard releases) to avoid a self-deadlock on this non-recursive mutex.
        std::lock_guard<std::mutex> lock(mSocketMutex);
        if (mSocket == INVALID_SOCKET) {
            return;
        }

        // Step 1: flush residual bytes from a prior short write / EAGAIN. If
        // the kernel still won't take them, leave them buffered and wait for
        // the next writable notification — do not pop new packets this pass.
        DrainResult result = drain();
        if (result == DrainResult::Fatal) {
            fatalError = true;
        } else if (result == DrainResult::Blocked) {
            return;
        }

        // Step 2: residual is empty — pop new packets while below the high-water
        // mark, appending each to the buffer and immediately attempting to flush.
        // Stops on backpressure (residual retained), fatal error, empty queue, or
        // when the high-water mark is reached (backpressure: remaining packets
        // stay in mOutgoingQueue for the next sendLoop pass).
        while (!fatalError &&
               (mSendBuffer.size() - mSendOffset) < SEND_BUFFER_HIGH_WATER_MARK) {
            auto packet = mOutgoingQueue.tryPop();
            if (!packet) {
                break;
            }
            std::vector<uint8_t> data;
            encodePacket(**packet, data);
            mSendBuffer.insert(mSendBuffer.end(), data.begin(), data.end());

            result = drain();
            if (result == DrainResult::Fatal) {
                fatalError = true;
                break;
            }
            if (result == DrainResult::Blocked) {
                break;
            }
            // Drained: loop and pop the next packet.
        }
    } // mSocketMutex released

    if (fatalError) {
        doDisconnect();
    }
}

int NetworkClient::doRawSend(const char* buf, int len) {
    // PROTO-001 test seam: when a test hook is installed, route the pending
    // send-buffer slice through it. The hook is responsible for setting
    // WSAGetLastError / errno to WOULDBLOCK/EAGAIN for backpressure or a
    // fatal code (ECONNRESET/...) to force a disconnect — mirroring the
    // contract of ::send / SSL_write. When no hook is installed (the
    // production default), behaviour is byte-for-byte identical to the
    // pre-seam sendLoop: TLS-established sessions encrypt via tlsSend,
    // everything else calls ::send directly on mSocket.
    if (mSendHook) {
        return mSendHook(buf, len);
    }
    if (mTlsState == TlsState::Established && mSsl != nullptr) {
        return tlsSend(buf, len);
    }
    return send(mSocket, buf, len, 0);
}

void NetworkClient::pumpSendLoopForTest() {
    // PROTO-001 test seam: drive exactly one sendLoop() pass from the calling
    // (test) thread. This re-enters sendLoop() as if the network thread's
    // select() reported the socket writable, WITHOUT spinning up the network
    // thread or a real socket. The test installs mSendHook, enqueues packets
    // via enqueuePacketForTest, then calls this method and asserts on the
    // observable post-state via sendBufferStateForTest / isConnectedForTest.
    sendLoop();
}

void NetworkClient::encodePacket(const Packet& packet, std::vector<uint8_t>& output) {
    // Write packet content to temporary buffer
    PacketBuffer contentBuffer;
    contentBuffer.writeByte(packet.getPacketId());
    contentBuffer.writeUUID(packet.getRequestId());
    packet.write(contentBuffer);

    // Write length prefix (VarInt)
    auto lengthBytes = VarInt::encode(static_cast<int32_t>(contentBuffer.size()));
    
    output.clear();
    output.reserve(lengthBytes.size() + contentBuffer.size());
    output.insert(output.end(), lengthBytes.begin(), lengthBytes.end());
    output.insert(output.end(), contentBuffer.getData().begin(), contentBuffer.getData().end());
}

void NetworkClient::sendHandshake() {
    // AUTH-002: protocol v3 challenge-response. Step 1 — send HandshakeInit
    // (0x15) with a cryptographically-secure 16-byte client nonce (hex).
    // The nonce is retained in mPendingClientNonce until the server's
    // HandshakeChallenge arrives, at which point handleHandshakeChallenge
    // computes the HMAC and sends HandshakeAuthenticate (0x17). The password
    // is no longer sent as a static SHA-256 hash, closing the replay vector.
    mPendingClientNonce.clear();

    static constexpr size_t NONCE_BYTES = 16;
    std::array<uint8_t, NONCE_BYTES> raw{};
    // std::random_device is the standard CSPRNG-seeded source on MSVC (uses
    // CryptGenRandom under the hood) and on libc++/libstdc++. Used only for
    // the 16-byte nonce, never as a stream cipher keystream.
    std::random_device rd;
    for (size_t i = 0; i < NONCE_BYTES; ++i) {
        raw[i] = static_cast<uint8_t>(rd());
    }

    std::ostringstream oss;
    oss << std::hex << std::setw(2) << std::setfill('0');
    for (uint8_t b : raw) {
        oss << static_cast<int>(b);
    }
    mPendingClientNonce = oss.str();

    auto packet = std::make_unique<HandshakeInitPacket>(
        PROTOCOL_VERSION, // Protocol version 3 (v3 AUTH-002 challenge-response)
        mUsername,
        PlatformType::LEVILAMINA,
        mServerVersion,
        mPendingClientNonce
    );
    sendPacket(std::move(packet));
}

void NetworkClient::handleHandshakeChallenge(const HandshakeChallengePacket& challenge) {
    // AUTH-002 step 2: the server replied with its nonce. Build the HMAC
    // response keyed by sha256hex(password) over (serverNonce || clientNonce),
    // matching the Java backend's HmacSHA256 expectation, and queue
    // HandshakeAuthenticate (0x17). The pending client nonce is consumed and
    // cleared; a missing/empty pending nonce means the init packet was never
    // sent (or a duplicate challenge arrived) — bail out and let the server
    // reject the (never-arriving) authenticate, which surfaces as a reconnect.
    const std::string& serverNonce = challenge.getServerNonce();
    if (mPendingClientNonce.empty()) {
        return;
    }

    std::string key;
    if (!mPassword.empty()) {
        key = novachat::util::Sha256::hex(mPassword);
    }

    const std::string message = serverNonce + mPendingClientNonce;
    const std::string hmac = novachat::util::HmacSha256::hex(key, message);

    auto packet = std::make_unique<HandshakeAuthenticatePacket>(
        mUsername,
        mPendingClientNonce,
        hmac
    );
    mPendingClientNonce.clear();
    sendPacket(std::move(packet));
}

void NetworkClient::handleHandshakeResponse(const HandshakeResponsePacket& response) {
    mAuthenticated = response.isSuccess();
    if (!mAuthenticated) {
        // Auth failed — disconnect so the network loop triggers reconnect via doDisconnect + handleReconnect
        doDisconnect();
    }
}

void NetworkClient::sendKeepAlive() {
    auto now = std::chrono::system_clock::now();
    auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()
    ).count();

    auto packet = std::make_unique<KeepAlivePacket>(timestamp);
    sendPacket(std::move(packet));
}

// ==================== AUTH-002 TLS transport ====================

bool NetworkClient::setupTlsContext() {
    // Create a TLS 1.2+ client context. SSL_CTX_new(SSLv23_client_method())
    // is the OpenSSL idiom for a client-side flexible-method context; the
    // SSL_OP_NO_SSLv2/SSLv3/TLSv1/TLSv1.1 options lock the floor at TLS 1.2
    // (matching the JVM client's default, which disabled TLS 1.0/1.1 in
    // Java 8u292+). SSLv23_client_method is not deprecated in OpenSSL 1.1.1
    // (the TLS_method API is 1.1.0+, but SSLv23_client_method is still the
    // portable alias on the versions xmake packages).
    mSslCtx = SSL_CTX_new(SSLv23_client_method());
    if (mSslCtx == nullptr) {
        return false;
    }

    // Disable legacy protocols. TLS 1.0/1.1 are disabled by default in modern
    // OpenSSL but we set the options explicitly for older builds (the options
    // are no-ops on builds that already disable them at compile time).
    SSL_CTX_set_options(mSslCtx, SSL_OP_NO_SSLv2 | SSL_OP_NO_SSLv3
                                   | SSL_OP_NO_TLSv1 | SSL_OP_NO_TLSv1_1);

    // Enforce certificate verification. SSL_VERIFY_PEER is mandatory (there
    // is intentionally no option to disable verification — no
    // SSL_VERIFY_NONE path exists when mTlsEnabled is true). The verify
    // callback is NULL so OpenSSL uses its default behaviour: abort the
    // handshake on the first untrusted / expired / mismatched certificate.
    SSL_CTX_set_verify(mSslCtx, SSL_VERIFY_PEER, nullptr);

    // Load the CA store. When mTlsCaCertPath is a file, load it as a PEM bundle;
    // when empty, fall back to the system default CA store
    // (SSL_CTX_set_default_verify_paths). The system-store fallback covers the
    // common production case where the backend uses a cert signed by a public
    // CA (Let's Encrypt, etc.); the explicit file covers the self-signed test
    // CA and private-CA deployments.
    if (!mTlsCaCertPath.empty()) {
        if (SSL_CTX_load_verify_locations(mSslCtx, mTlsCaCertPath.c_str(), nullptr) != 1) {
            SSL_CTX_free(mSslCtx);
            mSslCtx = nullptr;
            return false;
        }
    } else {
        // System CA store. If this fails (minimal container images without
        // ca-certificates), every connection will fail the handshake — which
        // is the correct fail-closed behaviour, NOT a silent plaintext
        // downgrade.
        SSL_CTX_set_default_verify_paths(mSslCtx);
    }

    // Optional mutual TLS: load the client cert/key pair. Only when BOTH paths
    // are non-empty (the plugin wiring in NovaChatPlugin ensures they are
    // either both set or both empty). A partial pair (cert without key or
    // vice-versa) is a configuration error — bail out.
    if (!mTlsClientCertPath.empty() && !mTlsClientKeyPath.empty()) {
        if (SSL_CTX_use_certificate_file(mSslCtx, mTlsClientCertPath.c_str(),
                                         SSL_FILETYPE_PEM) != 1) {
            SSL_CTX_free(mSslCtx);
            mSslCtx = nullptr;
            return false;
        }
        if (SSL_CTX_use_PrivateKey_file(mSslCtx, mTlsClientKeyPath.c_str(),
                                       SSL_FILETYPE_PEM) != 1) {
            SSL_CTX_free(mSslCtx);
            mSslCtx = nullptr;
            return false;
        }
        // Validate the cert/key pair match. If they don't, every handshake
        // would fail at the ServerKeyExchange/ClientKeyExchange step with a
        // confusing error — fail fast here with a clear signal.
        if (SSL_CTX_check_private_key(mSslCtx) != 1) {
            SSL_CTX_free(mSslCtx);
            mSslCtx = nullptr;
            return false;
        }
    } else if (!mTlsClientCertPath.empty() || !mTlsClientKeyPath.empty()) {
        // Partial mTLS config — cert without key or vice-versa. Reject.
        SSL_CTX_free(mSslCtx);
        mSslCtx = nullptr;
        return false;
    }

    // Create the SSL session and bind it to the socket. SSL_set_fd must come
    // before SSL_connect (the handshake needs to know which fd to use).
    mSsl = SSL_new(mSslCtx);
    if (mSsl == nullptr) {
        SSL_CTX_free(mSslCtx);
        mSslCtx = nullptr;
        return false;
    }

    // Set the SNI hostname so the server can pick the right certificate when
    // it hosts multiple virtual backends. Use mHost as-is (may be a hostname
    // or an IP — SSL_set_tlsext_host_name with an IP is a no-op on most
    // OpenSSL builds, which is fine).
    if (!mHost.empty()) {
        SSL_set_tlsext_host_name(mSsl, mHost.c_str());
    }

    // NOTE: SSL_set_fd is called in doConnect() after this function returns,
    // because the socket fd is not available here (doConnect owns it).
    return true;
}

TlsState NetworkClient::tlsHandshakeStep() {
    if (mSsl == nullptr) {
        return TlsState::Failed;
    }

    // SSL_connect returns 1 on success, <=0 on error/would-block. The
    // non-blocking path: SSL_ERROR_WANT_READ means wait for the fd to become
    // readable then retry; SSL_ERROR_WANT_WRITE means wait for writable.
    int ret = SSL_connect(mSsl);
    if (ret == 1) {
        // Verify the peer certificate post-handshake. SSL_VERIFY_PEER handles
        // the chain validation during the handshake, but the final
        // SSL_get_verify_result() check catches any verification that was
        // deferred (e.g. when a verify callback is installed — we use none,
        // but this is defence in depth). X509_V_OK means the chain is trusted.
        if (SSL_get_verify_result(mSsl) == X509_V_OK) {
            return TlsState::Established;
        }
        return TlsState::Failed;
    }

    int err = SSL_get_error(mSsl, ret);
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) {
        // Handshake needs more I/O — stay in Connecting. The select() loop
        // in networkThreadFunc() will poll the fd and call us again.
        // NOTE: we do not manually set the fd_set based on SSL_want_read/
        // SSL_want_write here because the existing select() subscribes to
        // both readable and writable (read always, write when there is
        // pending send data). For the handshake phase there is no send
        // data, so we rely on the 100ms timeout to retry. This is correct
        // but slightly less efficient than subscribing to the exact fd_set
        // — acceptable for a game-server plugin where handshakes are rare.
        return TlsState::Connecting;
    }

    // Any other error (SSL_ERROR_SSL, SSL_ERROR_SYSCALL, SSL_ERROR_ZERO_RETURN)
    // is a handshake failure. The connection must be torn down.
    return TlsState::Failed;
}

int NetworkClient::tlsRecv(char* buf, int len) {
    // SSL_read returns >0 on success, 0 on EOF, <0 on error. The error is
    // retrieved via SSL_get_error: SSL_ERROR_WANT_READ means "retry later"
    // (the TLS analogue of EWOULDBLOCK). We translate that to the errno/WSA
    // model so the existing receiveLoop() backpressure logic works unchanged.
    ERR_clear_error();
    int n = SSL_read(mSsl, buf, len);
    if (n > 0) {
        return n;
    }

    int err = SSL_get_error(mSsl, n);
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) {
        // Non-blocking retry — set the errno/WSA error to WOULDBLOCK so the
        // caller (receiveLoop) treats it as backpressure, not a fatal error.
#ifdef _WIN32
        WSASetLastError(WSAEWOULDBLOCK);
#else
        errno = EAGAIN;
#endif
        return -1;
    }

    // SSL_ERROR_ZERO_RETURN (graceful close) or SSL_ERROR_SSL / SSL_ERROR_SYSCALL
    // (fatal). Return 0 for EOF (so receiveLoop disconnects cleanly) and -1
    // for fatal errors (so receiveLoop sees a non-WOULDBLOCK error and disconnects).
    if (err == SSL_ERROR_ZERO_RETURN) {
        return 0;
    }
    // Fatal: set errno to a non-retryable value so receiveLoop disconnects.
#ifdef _WIN32
    WSASetLastError(WSAECONNRESET);
#else
    errno = ECONNRESET;
#endif
    return -1;
}

int NetworkClient::tlsSend(const char* buf, int len) {
    // SSL_write returns >0 on success, <=0 on error. Same error mapping as
    // tlsRecv: WANT_WRITE becomes WOULDBLOCK (backpressure), everything else
    // is fatal.
    ERR_clear_error();
    int n = SSL_write(mSsl, buf, len);
    if (n > 0) {
        return n;
    }

    int err = SSL_get_error(mSsl, n);
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) {
#ifdef _WIN32
        WSASetLastError(WSAEWOULDBLOCK);
#else
        errno = EAGAIN;
#endif
        return -1;
    }

    // Fatal error (SSL_ERROR_SSL, SSL_ERROR_SYSCALL, SSL_ERROR_ZERO_RETURN on
    // write is an unexpected EOF). Set errno to a non-retryable value so the
    // sendLoop drain lambda classifies it as DrainResult::Fatal.
#ifdef _WIN32
    WSASetLastError(WSAECONNRESET);
#else
    errno = ECONNRESET;
#endif
    return -1;
}

void NetworkClient::tlsShutdown() {
    // Best-effort SSL_shutdown. The peer may not be listening (e.g. on a
    // forceful disconnect), so we do not retry — a single bidirectional or
    // unidirectional shutdown attempt is enough to send close_notify if the
    // connection is still alive; if it isn't, SSL_shutdown returns -1 and we
    // proceed to SSL_free anyway.
    if (mSsl != nullptr) {
        SSL_shutdown(mSsl);
        SSL_free(mSsl);
        mSsl = nullptr;
    }
    if (mSslCtx != nullptr) {
        SSL_CTX_free(mSslCtx);
        mSslCtx = nullptr;
    }
}

} // namespace novachat::network
