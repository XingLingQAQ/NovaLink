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
#include <mutex>

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

// AUTH-002 TLS transport: OpenSSL headers for the non-blocking SSL state
// machine. The openssl xmake package supplies these on all platforms; on
// Windows the import libs (libssl/libcrypto) are linked via add_syslinks in
// xmake.lua. We do NOT include <openssl/ssl.h> transitatively through any
// LeviLamina header, so the direct include here is intentional.
#include <openssl/ssl.h>
#include <openssl/err.h>

namespace novachat::network {

using namespace novachat::protocol;

/**
 * AUTH-002 TLS handshake state machine phases.
 *
 * The non-blocking SSL_connect call may need to retry after select() reports
 * the fd readable or writable. The state machine below tracks where we are so
 * the select() loop in networkThreadFunc() can drive SSL_connect to
 * completion before any application data is sent or received.
 *
 *   Idle      — no TLS session in progress (TLS disabled or not yet started)
 *   Connecting — SSL_connect has been initiated but not yet completed
 *   Established — TLS handshake finished; SSL_read/SSL_write are the data path
 *   Failed    — TLS handshake failed; the connection must be torn down
 */
enum class TlsState {
    Idle,
    Connecting,
    Established,
    Failed
};

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
     * When TLS is enabled, doConnect() creates an SSL_CTX with
     * SSL_VERIFY_PEER (no insecure bypass), loads the optional client
     * cert/key for mutual TLS, and starts a non-blocking SSL_connect state
     * machine driven by the select() loop in networkThreadFunc().
     * receiveLoop() and sendLoop() use SSL_read / SSL_write in place of the
     * raw recv / send calls whenever a TLS session is established.
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
     * Test-only accessor for the connected flag (returns a reference to the
     * underlying atomic). Used by the TLS integration tests to poll the
     * connection state from the main thread without exposing the private
     * member. Not part of the stable API.
     */
    [[nodiscard]] const std::atomic<bool>& isConnectedWrapper() const { return mConnected; }

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

    // AUTH-002 TLS transport helpers (all run on the network thread).
    // setupTlsContext() builds the SSL_CTX with SSL_VERIFY_PEER and loads the
    // optional client cert/key for mTLS; tlsHandshakeStep() advances the
    // non-blocking SSL_connect state machine, returning the updated TlsState;
    // tlsRecv()/tlsSend() wrap SSL_read/SSL_write for the data path;
    // tlsShutdown() performs an orderly SSL_shutdown + resource release.
    bool setupTlsContext();
    TlsState tlsHandshakeStep();
    int tlsRecv(char* buf, int len);
    int tlsSend(const char* buf, int len);
    void tlsShutdown();

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

    // AUTH-002 TLS transport settings. Stored via setTlsConfig() and applied
    // in doConnect() — when mTlsEnabled is true, the socket is wrapped in an
    // OpenSSL TLS session (SSL_CTX with SSL_VERIFY_PEER, optional mTLS
    // client cert/key). There is no option to disable certificate
    // verification once TLS is enabled.
    bool mTlsEnabled = false;
    std::string mTlsCaCertPath;
    std::string mTlsClientCertPath;
    std::string mTlsClientKeyPath;

    // AUTH-002 TLS runtime state. The SSL_CTX is created once per connection
    // in setupTlsContext() and freed in tlsShutdown(); the SSL handle is the
    // per-connection session object. mTlsState drives the select() loop:
    //   Idle       — no TLS (plaintext path)
    //   Connecting — SSL_connect in progress (retry on want_read/want_write)
    //   Established — SSL_read/SSL_write replace recv/send
    //   Failed     — handshake failed, connection must be torn down
    // All four members are owned by the network thread (set in doConnect /
    // setupTlsContext / tlsHandshakeStep, cleared in doDisconnect).
    SSL_CTX* mSslCtx = nullptr;
    SSL* mSsl = nullptr;
    TlsState mTlsState = TlsState::Idle;

    // AUTH-002: the client nonce sent in HandshakeInit, retained across select()
    // loop iterations until the matching HandshakeChallenge arrives. Owned by
    // the network thread (set in sendHandshake, consumed+cleared in
    // handleHandshakeChallenge, both on the network thread).
    std::string mPendingClientNonce;

    // AUTH-002 TLS: set to true by doConnect() when the TCP connection
    // succeeds, cleared after sendHandshake() is called. For plaintext, this
    // happens immediately on the next loop iteration. For TLS, the handshake
    // must reach Established first — sending application data before the TLS
    // session is established would corrupt the TLS state machine.
    bool mHandshakePending = false;

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
