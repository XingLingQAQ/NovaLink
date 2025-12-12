#pragma once

#include <ll/api/plugin/NativePlugin.h>
#include <ll/api/plugin/RegisterHelper.h>
#include <memory>
#include <string>

namespace novachat {

// Forward declarations
class ChatInterceptor;
class NovaChatConfig;
class CommandHandler;

namespace network {
    class NetworkClient;
}
using NetworkClient = network::NetworkClient;

/**
 * NovaChat-LeviLamina Plugin
 * 
 * Main plugin class for the LeviLamina (BDS) implementation of NovaChat.
 * Handles plugin lifecycle, configuration, and component management.
 */
class NovaChatPlugin {
public:
    NovaChatPlugin(ll::plugin::NativePlugin& self);
    ~NovaChatPlugin();

    // Plugin lifecycle
    bool load();
    bool enable();
    bool disable();

    // Accessors
    [[nodiscard]] ll::plugin::NativePlugin& getSelf() const { return mSelf; }
    [[nodiscard]] NetworkClient* getNetworkClient() const { return mNetworkClient.get(); }
    [[nodiscard]] ChatInterceptor* getChatInterceptor() const { return mChatInterceptor.get(); }
    [[nodiscard]] NovaChatConfig* getConfig() const { return mConfig.get(); }
    [[nodiscard]] CommandHandler* getCommandHandler() const { return mCommandHandler.get(); }
    
    // Singleton access
    static NovaChatPlugin& getInstance();

private:
    ll::plugin::NativePlugin& mSelf;
    
    // Components
    std::unique_ptr<NovaChatConfig> mConfig;
    std::unique_ptr<NetworkClient> mNetworkClient;
    std::unique_ptr<ChatInterceptor> mChatInterceptor;
    std::unique_ptr<CommandHandler> mCommandHandler;
    
    // State
    bool mEnabled = false;
};

} // namespace novachat
