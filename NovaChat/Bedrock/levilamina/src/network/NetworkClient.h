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
                  const std::string& username, const std::string& password,
                  const std::string& serverVersion, int reconnectDelay);
    ~NetworkClient();

    /**
     * AUTH-002 TLS: apply the backend transport-encryption settings. Kept as a
     * separate setter rather than a constructor/reconfigure parameter so the
     * existing 6-arg constructor + reconfigure signatures stay unchanged for
     * the plaintext default (zero regression). When tlsEnabled is true the
     * backend certificate is ALWAYS verified against caCertPath (or the system
     * CA store when empty); there is no option to disable verification. The
     * optional mTLS pair is loaded only when both clientCertPath and
     * clientKeyPath are non-empty.
     *
     * NOTE: TLS transport is not yet implemented in doConnect() — the backend
     * still connects over plaintext TCP even when TLS is configured. This
     * stores the config so the (forthcoming) OpenSSL integration can consume
     * it without re-plumbing the constructor. See the TODO in doConnect().
     */
    void setTlsConfig(bool tlsEnabled,
                      const std::string& caCertPath,
                      const std::string& clientCertPath,
                      const std::string& clientKeyPath);

    // Non-copyable
    NetworkClient(const NetworkClient&) = delete;
    NetworkClient& operator=(const NetworkClient&) = delete;

    /**
     * Connect to the backend server.
     * @return true if connection initiated successfully
     */
    bool connect();

    /**
     * Apply new connection settings and restart the client.
     * The handler registry is retained, so the owning chat interceptor keeps
     * receiving backend packets after a configuration reload.
     */
    bool reconfigure(const std::string& host, uint16_t port,
                     const std::string& username, const std::string& password,
                     const std::string& serverVersion, int reconnectDelay);

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
    // AUTH-002 challenge-response: driven from processReceivedData on the
    // network thread. mPendingClientNonce holds the nonce generated in
    // sendHandshake until the server's challenge arrives.
    void handleHandshakeChallenge(const HandshakeChallengePacket& challenge);

    // Keep-alive
    void sendKeepAlive();

    // Configuration
    std::string mHost;
    uint16_t mPort;
    std::string mUsername;
    std::string mPassword;
    std::string mServerVersion;
    int mReconnectDelay;

    // AUTH-002 TLS transport settings. Stored via setTlsConfig() but NOT yet
    // applied in doConnect() — see the TODO there. Kept on the instance so the
    // OpenSSL integration can consume them without re-plumbing the constructor
    // signature (which is shared with the test target and the plugin wiring).
    bool mTlsEnabled = false;
    std::string mTlsCaCertPath;
    std::string mTlsClientCertPath;
    std::string mTlsClientKeyPath;

    // AUTH-002: the client nonce sent in HandshakeInit, retained across select()
    // loop iterations until the matching HandshakeChallenge arrives. Owned by
    // the network thread (set in sendHandshake, consumed+cleared in
    // handleHandshakeChallenge, both on the network thread).
    std::string mPendingClientNonce;

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

    // Send buffer — bytes pulled from mOutgoingQueue but not yet fully written
    // to the socket. [mSendOffset, mSendBuffer.size()) is the range still
    // pending. Survives across sendLoop() calls so a short write or a
    // non-blocking EAGAIN no longer drops the tail of a packet. Owned by the
    // network thread: sendLoop appends/drains; doConnect/doDisconnect clear.
    std::vector<uint8_t> mSendBuffer;
    size_t mSendOffset = 0;
    static constexpr size_t SEND_BUFFER_HIGH_WATER_MARK = 1024 * 1024; // 1 MiB

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
