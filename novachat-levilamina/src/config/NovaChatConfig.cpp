#include "NovaChatConfig.h"

#include <ll/api/Config.h>
#include <nlohmann/json.hpp>
#include <fstream>

namespace novachat {

NovaChatConfig::NovaChatConfig(const std::filesystem::path& dataDir)
    : mDataDir(dataDir)
    , mConfigPath(dataDir / "config.json") {
    setDefaults();
}

void NovaChatConfig::setDefaults() {
    mBackendHost = "127.0.0.1";
    mBackendPort = 18888;
    mUsername = "LeviLamina_Server";
    mPassword = "";
    mServerVersion = "1.21.0";
    mReconnectDelay = 5;
    mReplaceVanilla = false;
    mDefaultChannel = "local";
    mPrefix = "§8[§bNovaChat§8]§r ";
    mErrorFormat = "§c错误: {message}";
    mSuccessFormat = "§a成功: {message}";
    mDefaultFormat = "§7[{channel_name}] {player}§f: {message}";
    mDebug = false;

    // Default channel formats
    mChannelFormats["global"] = "§c[全服] §7{player}§f: {message}";
    mChannelFormats["local"] = "§e[本地] §7{player}§f: {message}";
    mChannelFormats["private_default"] = "§d[私聊] §7{player}§f: {message}";
}

bool NovaChatConfig::load() {
    // Create data directory if it doesn't exist
    if (!std::filesystem::exists(mDataDir)) {
        std::filesystem::create_directories(mDataDir);
    }
    
    // If config doesn't exist, create default
    if (!std::filesystem::exists(mConfigPath)) {
        return save();
    }
    
    try {
        std::ifstream file(mConfigPath);
        if (!file.is_open()) {
            return false;
        }
        
        nlohmann::json json;
        file >> json;
        
        // Backend settings
        if (json.contains("backend")) {
            auto& backend = json["backend"];
            if (backend.contains("host")) mBackendHost = backend["host"].get<std::string>();
            if (backend.contains("port")) mBackendPort = backend["port"].get<uint16_t>();
            if (backend.contains("username")) mUsername = backend["username"].get<std::string>();
            if (backend.contains("password")) mPassword = backend["password"].get<std::string>();
            if (backend.contains("server_version")) mServerVersion = backend["server_version"].get<std::string>();
            if (backend.contains("reconnect_delay")) mReconnectDelay = backend["reconnect_delay"].get<int>();
        }
        
        // Chat settings
        if (json.contains("chat")) {
            auto& chat = json["chat"];
            if (chat.contains("replace_vanilla")) mReplaceVanilla = chat["replace_vanilla"].get<bool>();
            if (chat.contains("default_channel")) mDefaultChannel = chat["default_channel"].get<std::string>();
        }
        
        // Format settings
        if (json.contains("format")) {
            auto& format = json["format"];
            if (format.contains("prefix")) mPrefix = format["prefix"].get<std::string>();
            if (format.contains("error")) mErrorFormat = format["error"].get<std::string>();
            if (format.contains("success")) mSuccessFormat = format["success"].get<std::string>();
            if (format.contains("default")) mDefaultFormat = format["default"].get<std::string>();
            
            if (format.contains("channels")) {
                mChannelFormats.clear();
                for (auto& [key, value] : format["channels"].items()) {
                    mChannelFormats[key] = value.get<std::string>();
                }
            }
        }
        
        // Debug
        if (json.contains("debug")) {
            mDebug = json["debug"].get<bool>();
        }
        
        return true;
    } catch (const std::exception& e) {
        return false;
    }
}

bool NovaChatConfig::save() {
    try {
        nlohmann::json json;
        
        // Backend settings
        json["backend"]["host"] = mBackendHost;
        json["backend"]["port"] = mBackendPort;
        json["backend"]["username"] = mUsername;
        json["backend"]["password"] = mPassword;
        json["backend"]["server_version"] = mServerVersion;
        json["backend"]["reconnect_delay"] = mReconnectDelay;
        
        // Chat settings
        json["chat"]["replace_vanilla"] = mReplaceVanilla;
        json["chat"]["default_channel"] = mDefaultChannel;
        
        // Format settings
        json["format"]["prefix"] = mPrefix;
        json["format"]["error"] = mErrorFormat;
        json["format"]["success"] = mSuccessFormat;
        json["format"]["default"] = mDefaultFormat;
        
        for (const auto& [key, value] : mChannelFormats) {
            json["format"]["channels"][key] = value;
        }
        
        // Debug
        json["debug"] = mDebug;
        
        std::ofstream file(mConfigPath);
        if (!file.is_open()) {
            return false;
        }
        
        file << json.dump(4);
        return true;
    } catch (const std::exception& e) {
        return false;
    }
}

bool NovaChatConfig::reload() {
    return load();
}

std::string NovaChatConfig::getChannelFormat(const std::string& channelId) const {
    auto it = mChannelFormats.find(channelId);
    if (it != mChannelFormats.end()) {
        return it->second;
    }
    return mDefaultFormat;
}

} // namespace novachat
