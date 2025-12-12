#include "NovaChatPlugin.h"
#include "config/NovaChatConfig.h"
#include "network/NetworkClient.h"
#include "chat/ChatInterceptor.h"
#include "command/CommandHandler.h"

#include <ll/api/Logger.h>
#include <ll/api/plugin/NativePlugin.h>
#include <ll/api/plugin/RegisterHelper.h>
#include <ll/api/event/EventBus.h>
#include <ll/api/event/server/ServerStartedEvent.h>
#include <ll/api/schedule/Scheduler.h>
#include <ll/api/schedule/Task.h>

namespace novachat {

static std::unique_ptr<NovaChatPlugin> gInstance;
static ll::event::ListenerPtr sServerStartedListener;
static ll::schedule::ServerTimeScheduler sScheduler;

NovaChatPlugin::NovaChatPlugin(ll::plugin::NativePlugin& self) : mSelf(self) {}

NovaChatPlugin::~NovaChatPlugin() = default;

NovaChatPlugin& NovaChatPlugin::getInstance() {
    return *gInstance;
}

bool NovaChatPlugin::load() {
    auto& logger = mSelf.getLogger();
    logger.info("Loading NovaChat-LeviLamina...");
    
    // Load configuration
    mConfig = std::make_unique<NovaChatConfig>(mSelf.getDataDir());
    if (!mConfig->load()) {
        logger.error("Failed to load configuration!");
        return false;
    }
    
    logger.info("NovaChat-LeviLamina loaded successfully.");
    return true;
}

bool NovaChatPlugin::enable() {
    auto& logger = mSelf.getLogger();
    logger.info("Enabling NovaChat-LeviLamina...");
    
    // Initialize network client
    mNetworkClient = std::make_unique<network::NetworkClient>(
        mConfig->getBackendHost(),
        mConfig->getBackendPort(),
        mConfig->getUsername(),
        mConfig->getPassword()
    );
    
    // Initialize chat interceptor
    mChatInterceptor = std::make_unique<ChatInterceptor>(*this);
    
    // Initialize command handler
    mCommandHandler = std::make_unique<CommandHandler>(*this);
    
    // Connect to backend
    if (!mNetworkClient->connect()) {
        logger.warn("Failed to connect to NovaLink backend. Will retry in background.");
    }
    
    // Register chat hooks
    mChatInterceptor->registerHooks();
    
    // Register commands
    mCommandHandler->registerCommands();
    
    // Schedule periodic task to process incoming packets on main thread
    // This runs every tick (50ms) to handle packets from the network thread
    sScheduler.add<ll::schedule::RepeatTask>(std::chrono::milliseconds(50), [this]() {
        if (mNetworkClient && mEnabled) {
            mNetworkClient->processIncomingPackets();
        }
    });
    
    mEnabled = true;
    logger.info("NovaChat-LeviLamina enabled successfully.");
    return true;
}

bool NovaChatPlugin::disable() {
    auto& logger = mSelf.getLogger();
    logger.info("Disabling NovaChat-LeviLamina...");
    
    mEnabled = false;
    
    // Clear scheduled tasks
    sScheduler.clear();
    
    // Remove server started listener
    if (sServerStartedListener) {
        ll::event::EventBus::getInstance().removeListener(sServerStartedListener);
        sServerStartedListener = nullptr;
    }
    
    // Unregister commands
    if (mCommandHandler) {
        mCommandHandler->unregisterCommands();
    }
    
    // Unregister hooks
    if (mChatInterceptor) {
        mChatInterceptor->unregisterHooks();
    }
    
    // Disconnect from backend
    if (mNetworkClient) {
        mNetworkClient->disconnect();
    }
    
    logger.info("NovaChat-LeviLamina disabled.");
    return true;
}

} // namespace novachat

// Plugin registration
LL_REGISTER_PLUGIN(novachat::NovaChatPlugin, novachat::gInstance);
