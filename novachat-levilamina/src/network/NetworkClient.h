#pragma once

#include "ThreadSafeQueue.h"
#include "../protocol/Packet.h"
#include "../protocol/PacketBuffer.h"

#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <functional>
#include <unordered_map>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#define SOCKET int
#define INVALID_SOCKET -1
#define SOCKET_ERROR -1
#define closesocket close
#endif

namespace novachat::network {

using namespace novachat::protocol;

/**
 * Async network client for NovaLink backend communication.
 * 
 * Features:
 * - Non-blocking socket in separate thread
 * - Thread-safe message queues for send/receive
 * - Automatic reconnection
 * - Big-endian byte order conversion
 */
class NetworkClient {
public:
    using PacketHandler = std::function<void(std::unique_ptr<Packet>)>;

    NetworkClient(const std::string& host, uint16_t port,
                  const std::string& username, const std::string& password);
    ~NetworkClient();

    // Non-copyable
    NetworkClient(const NetworkClient&) = delete;
    NetworkClient& operator=(const NetworkClient&) = delete;

    /**
     * Connect to the backend server.
     * @return true if connection initiated successfully
     */
    bool connect();

    /**
     * Disconnect from the backend server.
     */
    void disconnect();

    /**
     * Check if connected to the backend.
     * @return true if connected
     */
    [[nodiscard]] bool isConnected() const { return mConnected; }

    /**
     * Check if authenticated with the backend.
     * @return true if authenticated
     */
    [[nodiscard]] bool isAuthenticated() const { return mAuthenticated; }

    /**
     * Send a packet to the backend.
     * @param packet the packet to send
     */
    void sendPacket(std::unique_ptr<Packet> packet);

    /**
     * Register a handler for a specific packet type.
     * @param packetId the packet ID to handle
     * @param handler the handler function
     */
    void registerHandler(uint8_t packetId, PacketHandler handler);

    /**
     * Process received packets on the main thread.
     * Should be called periodically from the main thread.
     */
    void processIncomingPackets();

    /**
     * Set reconnect delay in seconds.
     * @param seconds delay between reconnection attempts
     */
    void setReconnectDelay(int seconds) { mReconnectDelay = seconds; }

private:
    // Network thread functions
    void networkThreadFunc();
    void receiveLoop();
    void sendLoop();
    bool doConnect();
    void doDisconnect();
    void handleReconnect();

    // Packet processing
    void processReceivedData();
    std::unique_ptr<Packet> decodePacket(PacketBuffer& buffer);
    void encodePacket(const Packet& packet, std::vector<uint8_t>& output);

    // Authentication
    void sendHandshake();
    void handleHandshakeResponse(const HandshakeResponsePacket& response);

    // Keep-alive
    void sendKeepAlive();

    // Configuration
    std::string mHost;
    uint16_t mPort;
    std::string mUsername;
    std::string mPassword;
    int mReconnectDelay = 5;

    // Socket
    SOCKET mSocket = INVALID_SOCKET;
    std::atomic<bool> mConnected{false};
    std::atomic<bool> mAuthenticated{false};
    std::atomic<bool> mRunning{false};

    // Threading
    std::unique_ptr<std::thread> mNetworkThread;
    std::mutex mSocketMutex;

    // Message queues
    ThreadSafeQueue<std::unique_ptr<Packet>> mOutgoingQueue;
    ThreadSafeQueue<std::unique_ptr<Packet>> mIncomingQueue;

    // Receive buffer
    std::vector<uint8_t> mReceiveBuffer;
    static constexpr size_t RECEIVE_BUFFER_SIZE = 65536;

    // Packet handlers
    std::unordered_map<uint8_t, PacketHandler> mHandlers;
    std::mutex mHandlersMutex;

    // Keep-alive
    std::chrono::steady_clock::time_point mLastKeepAlive;
    static constexpr int KEEP_ALIVE_INTERVAL_MS = 15000;

#ifdef _WIN32
    static bool sWsaInitialized;
    static void initWsa();
#endif
};

} // namespace novachat::network
