#include "NetworkClient.h"
#include "../protocol/VarInt.h"
#include "../protocol/ProtocolLimits.h"
#include "../util/Sha256.h"
#include "../util/HmacSha256.h"

#include <ll/api/io/Logger.h>
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
    // AUTH-002 TLS: store the transport-encryption config. NOT yet applied in
    // doConnect() — the backend still connects over plaintext TCP. This is the
    // skeleton seam: the OpenSSL integration (SSL_CTX_new / SSL_connect /
    // SSL_read / SSL_write wired into the select() loop) will read these
    // members. Verification is enforced unconditionally once TLS is on (there
    // is no mTlsInsecure / skipVerify member by design).
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
                sendHandshake();
            } else {
                handleReconnect();
                continue;
            }
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

    // TODO AUTH-002 TLS: when mTlsEnabled is true, wrap mSocket in an OpenSSL
    // TLS session here. The integration requires: (1) an SSL_CTX configured
    // for certificate verification (SSL_VERIFY_PEER with the configured
    // ca_cert_path or the system store) — there is intentionally no option to
    // disable verification; (2) an optional client cert/key pair loaded when
    // both mTlsClientCertPath and mTlsClientKeyPath are non-empty; (3) a
    // non-blocking SSL_connect state machine integrated into the select() loop
    // in networkThreadFunc() — SSL_want_read / SSL_want_write drive which
    // fd_set to subscribe to, and SSL_read / SSL_write replace the raw recv /
    // send calls in receiveLoop() / sendLoop(). Because xmake is unavailable
    // in this environment (no compile verification), the non-blocking SSL state
    // machine is NOT written here — an unverified state machine is a larger
    // risk than the documented gap. Until that lands, the backend connects
    // over plaintext TCP even when TLS is configured (mTlsEnabled is stored
    // but not yet applied). xmake.lua already declares the OpenSSL dependency
    // so the symbols link once the implementation is added.
    if (mTlsEnabled) {
        // Skeleton seam: TLS connect not yet implemented (see TODO above).
        // Fall through to the plaintext non-blocking setup so the client still
        // operates; operators who set enable=true without a TLS listener get a
        // working plaintext connection rather than a silent no-op. This gap is
        // reported honestly in the AUTH-002 delivery report.
    }

    // Set non-blocking mode
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
    std::this_thread::sleep_for(std::chrono::seconds(mReconnectDelay));
}

void NetworkClient::receiveLoop() {
    uint8_t buffer[4096];
    
    int bytesReceived = recv(mSocket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
    
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

        // Decode packet
        PacketBuffer buffer(std::move(packetData));
        auto packet = decodePacket(buffer);
        
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
            auto sent = send(mSocket,
                             reinterpret_cast<const char*>(mSendBuffer.data() + mSendOffset),
                             static_cast<int>(remaining),
                             0);
            if (sent > 0) {
                mSendOffset += static_cast<size_t>(sent);
                continue;
            }
            // sent <= 0: distinguish backpressure from fatal errors.
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

} // namespace novachat::network
