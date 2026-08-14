#include "NetworkClient.h"
#include "../protocol/VarInt.h"
#include "../util/Sha256.h"

#include <ll/api/io/Logger.h>
#include <chrono>
#include <cstring>

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
                             const std::string& serverVersion)
    : mHost(host)
    , mPort(port)
    , mUsername(username)
    , mPassword(password)
    , mServerVersion(serverVersion) {
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

    mRunning = true;
    mNetworkThread = std::make_unique<std::thread>(&NetworkClient::networkThreadFunc, this);
    return true;
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
        
        if (!mOutgoingQueue.empty()) {
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

        if (packetLength <= 0 || packetLength > 1048576) { // Max 1MB
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
    while (auto packet = mOutgoingQueue.tryPop()) {
        std::vector<uint8_t> data;
        encodePacket(**packet, data);

        std::lock_guard<std::mutex> lock(mSocketMutex);
        if (mSocket != INVALID_SOCKET) {
            send(mSocket, reinterpret_cast<const char*>(data.data()), static_cast<int>(data.size()), 0);
        }
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
    // SHA-256 hash the password (lowercase hex), matching Java's
    // MessageDigest.getInstance("SHA-256") behaviour. Empty password stays empty.
    std::string passwordHash;
    if (!mPassword.empty()) {
        passwordHash = novachat::util::Sha256::hex(mPassword);
    }

    auto packet = std::make_unique<HandshakePacket>(
        PROTOCOL_VERSION, // Protocol version 2 (v2 adds trailing serverVersion)
        mUsername,
        passwordHash,
        PlatformType::LEVILAMINA,
        mServerVersion
    );
    sendPacket(std::move(packet));
}

void NetworkClient::handleHandshakeResponse(const HandshakeResponsePacket& response) {
    mAuthenticated = response.isSuccess();
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
