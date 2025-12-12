#pragma once

#include <string>
#include <filesystem>
#include <unordered_map>

namespace novachat {

/**
 * NovaChat Configuration
 * 
 * Handles loading and saving plugin configuration from YAML files.
 */
class NovaChatConfig {
public:
    explicit NovaChatConfig(const std::filesystem::path& dataDir);
    ~NovaChatConfig() = default;

    // Load/Save
    bool load();
    bool save();
    bool reload();

    // Backend settings
    [[nodiscard]] const std::string& getBackendHost() const { return mBackendHost; }
    [[nodiscard]] uint16_t getBackendPort() const { return mBackendPort; }
    [[nodiscard]] const std::string& getUsername() const { return mUsername; }
    [[nodiscard]] const std::string& getPassword() const { return mPassword; }
    [[nodiscard]] int getReconnectDelay() const { return mReconnectDelay; }

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
    void setDefaults();
    
    std::filesystem::path mDataDir;
    std::filesystem::path mConfigPath;

    // Backend settings
    std::string mBackendHost = "127.0.0.1";
    uint16_t mBackendPort = 8888;
    std::string mUsername = "LeviLamina_Server";
    std::string mPassword = "";
    int mReconnectDelay = 5;

    // Chat settings
    bool mReplaceVanilla = false;
    std::string mDefaultChannel = "local";

    // Format settings
    std::string mPrefix = "§8[§bNovaChat§8]§r ";
    std::string mErrorFormat = "§c错误: {message}";
    std::string mSuccessFormat = "§a成功: {message}";
    std::string mDefaultFormat = "§7[{channel_name}] {player}§f: {message}";
    std::unordered_map<std::string, std::string> mChannelFormats;

    // Debug
    bool mDebug = false;
};

} // namespace novachat
