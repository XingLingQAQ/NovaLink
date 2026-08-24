#pragma once

#include <cstdint>
#include <filesystem>
#include <string>
#include <unordered_map>

namespace novachat {

/**
 * NovaChat Configuration
 * 
 * Handles template-backed loading and updating of config.json.
 */
class NovaChatConfig {
public:
    explicit NovaChatConfig(
        const std::filesystem::path& dataDir,
        std::filesystem::path templatePath = {});
    ~NovaChatConfig() = default;

    // Load/reload
    bool load();
    bool reload();

    [[nodiscard]] bool wasCreated() const { return mWasCreated; }
    [[nodiscard]] bool wasUpdated() const { return mWasUpdated; }
    [[nodiscard]] const std::filesystem::path& getBackupPath() const { return mBackupPath; }
    [[nodiscard]] const std::string& getLastError() const { return mLastError; }

    // Backend settings
    [[nodiscard]] const std::string& getBackendHost() const { return mBackendHost; }
    [[nodiscard]] uint16_t getBackendPort() const { return mBackendPort; }
    [[nodiscard]] const std::string& getUsername() const { return mUsername; }
    [[nodiscard]] const std::string& getPassword() const { return mPassword; }
    [[nodiscard]] const std::string& getServerVersion() const { return mServerVersion; }
    [[nodiscard]] int getReconnectDelay() const { return mReconnectDelay; }

    // AUTH-002 TLS: backend transport encryption. When isTlsEnabled() is false
    // (the default) the transport stays plaintext (zero regression). When true
    // the backend certificate is ALWAYS verified against getTlsCaCertPath() (or
    // the system CA store when empty) — there is no option to disable
    // verification. The optional mTLS pair is loaded only when both paths are
    // set. Defaults live in code (NovaChatConfig.cpp) because the bundled
    // default-config.json template does not ship a tls block.
    [[nodiscard]] bool isTlsEnabled() const { return mTlsEnabled; }
    [[nodiscard]] const std::string& getTlsCaCertPath() const { return mTlsCaCertPath; }
    [[nodiscard]] const std::string& getTlsClientCertPath() const { return mTlsClientCertPath; }
    [[nodiscard]] const std::string& getTlsClientKeyPath() const { return mTlsClientKeyPath; }

    // Chat settings
    [[nodiscard]] bool isReplaceVanilla() const { return mReplaceVanilla; }
    [[nodiscard]] const std::string& getDefaultChannel() const { return mDefaultChannel; }

    // Format settings
    [[nodiscard]] const std::string& getPrefix() const { return mPrefix; }
    [[nodiscard]] const std::string& getErrorFormat() const { return mErrorFormat; }
    [[nodiscard]] const std::string& getSuccessFormat() const { return mSuccessFormat; }
    [[nodiscard]] const std::string& getDefaultFormat() const { return mDefaultFormat; }
    [[nodiscard]] std::string getChannelFormat(const std::string& channelId) const;

    // Debug
    [[nodiscard]] bool isDebug() const { return mDebug; }
    void setDebug(bool debug) { mDebug = debug; }

private:
    std::filesystem::path mDataDir;
    std::filesystem::path mConfigPath;
    std::filesystem::path mTemplatePath;
    std::filesystem::path mBackupPath;
    std::string mLastError;
    bool mWasCreated = false;
    bool mWasUpdated = false;

    // Backend settings
    std::string mBackendHost;
    uint16_t mBackendPort{};
    std::string mUsername;
    std::string mPassword;
    std::string mServerVersion;
    int mReconnectDelay{};

    // AUTH-002 TLS transport settings (schema defaults are applied in code —
    // see NovaChatConfig::load — because default-config.json is not shipped
    // with a tls block).
    bool mTlsEnabled{false};
    std::string mTlsCaCertPath;
    std::string mTlsClientCertPath;
    std::string mTlsClientKeyPath;

    // Chat settings
    bool mReplaceVanilla{};
    std::string mDefaultChannel;

    // Format settings
    std::string mPrefix;
    std::string mErrorFormat;
    std::string mSuccessFormat;
    std::string mDefaultFormat;
    std::unordered_map<std::string, std::string> mChannelFormats;

    // Debug
    bool mDebug{};
};

} // namespace novachat
