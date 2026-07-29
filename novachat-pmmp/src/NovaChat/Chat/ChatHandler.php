<?php

declare(strict_types=1);

namespace NovaChat\Chat;

use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\AnnouncementPacket;
use NovaChat\Protocol\ChannelUpdatePacket;
use NovaChat\Protocol\ChatMessagePacket;
use NovaChat\Protocol\TitleMessagePacket;
use pocketmine\event\Listener;
use pocketmine\event\player\PlayerChatEvent;
use pocketmine\event\player\PlayerQuitEvent;
use pocketmine\player\Player;
use pocketmine\utils\TextFormat;

/**
 * Chat handler for intercepting and processing chat messages.
 * 
 * Requirements:
 * - 8.4: WHEN 玩家发送聊天消息 THEN NovaChat-PMMP SHALL 通过 PlayerChatEvent 拦截消息
 * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
 */
class ChatHandler implements Listener {
    
    /** @var NovaChatPlugin Plugin instance */
    private NovaChatPlugin $plugin;
    
    /** @var array<string, string> Player channel assignments (UUID => channelId) */
    private array $playerChannels = [];
    
    /** @var array<string, bool> Player chat toggle states (UUID => enabled) */
    private array $chatEnabled = [];
    
    /**
     * Creates a new chat handler.
     * 
     * @param NovaChatPlugin $plugin Plugin instance
     */
    public function __construct(NovaChatPlugin $plugin) {
        $this->plugin = $plugin;
    }
    
    /**
     * Handles player chat events.
     * 
     * Requirements:
     * - 8.4: WHEN 玩家发送聊天消息 THEN NovaChat-PMMP SHALL 通过 PlayerChatEvent 拦截消息
     * 
     * @param PlayerChatEvent $event The chat event
     * @priority NORMAL
     */
    public function onPlayerChat(PlayerChatEvent $event): void {
        $player = $event->getPlayer();
        $message = $event->getMessage();
        
        // Check if chat is enabled for this player
        $uuid = $player->getUniqueId()->toString();
        if (isset($this->chatEnabled[$uuid]) && !$this->chatEnabled[$uuid]) {
            return;
        }
        
        // Get player's current channel
        $channelId = $this->getPlayerChannel($player);
        
        // Check if we should replace vanilla chat
        $config = $this->plugin->getConfigManager();
        if ($config->shouldReplaceVanilla()) {
            $event->cancel();
        }
        
        // Send message to backend
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            $networkClient->sendChatMessage(
                $uuid,
                $player->getName(),
                $channelId,
                $message
            );
            $this->plugin->debug("Sent chat message to backend: [$channelId] {$player->getName()}: $message");
        }
    }
    
    /**
     * Displays a formatted message to a player.
     * 
     * Requirements:
     * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
     * 
     * @param Player $player The player to send the message to
     * @param string $message The formatted message
     */
    public function displayMessage(Player $player, string $message): void {
        MessageRenderer::sendMessage($player, $message);
    }
    
    /**
     * Broadcasts a message to all online players.
     * 
     * @param string $message The formatted message
     */
    public function broadcastMessage(string $message): void {
        MessageRenderer::broadcast($this->plugin->getServer(), $message);
    }
    
    /**
     * Gets the current channel for a player.
     * 
     * @param Player $player The player
     * @return string The channel ID
     */
    public function getPlayerChannel(Player $player): string {
        $uuid = $player->getUniqueId()->toString();
        return $this->playerChannels[$uuid] ?? $this->plugin->getConfigManager()->getDefaultChannel();
    }
    
    /**
     * Sets the current channel for a player.
     * 
     * @param Player $player The player
     * @param string $channelId The channel ID
     */
    public function setPlayerChannel(Player $player, string $channelId): void {
        $uuid = $player->getUniqueId()->toString();
        $this->playerChannels[$uuid] = $channelId;
    }
    
    /**
     * Toggles chat for a player.
     * 
     * @param Player $player The player
     * @return bool The new chat state
     */
    public function toggleChat(Player $player): bool {
        $uuid = $player->getUniqueId()->toString();
        $enabled = !($this->chatEnabled[$uuid] ?? true);
        $this->chatEnabled[$uuid] = $enabled;
        return $enabled;
    }
    
    /**
     * Checks if chat is enabled for a player.
     * 
     * @param Player $player The player
     * @return bool True if chat is enabled
     */
    public function isChatEnabled(Player $player): bool {
        $uuid = $player->getUniqueId()->toString();
        return $this->chatEnabled[$uuid] ?? true;
    }
    
    /**
     * Clears player data when they disconnect.
     * 
     * @param string $uuid Player UUID
     */
    public function clearPlayerData(string $uuid): void {
        unset($this->playerChannels[$uuid]);
        unset($this->chatEnabled[$uuid]);
    }
    
    /**
     * Handles an incoming chat message from the backend.
     * 
     * Requirements:
     * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
     * 
     * @param ChatMessagePacket $packet The chat message packet
     */
    public function handleIncomingMessage(ChatMessagePacket $packet): void {
        $this->plugin->debug("Received chat message from backend: [{$packet->channelId}] {$packet->senderName}: {$packet->content}");
        
        // Format the message using config template and MessageRenderer
        $config = $this->plugin->getConfigManager();
        $format = $config->getChannelFormat($packet->channelId);
        
        $formatted = MessageRenderer::format($format, [
            "player" => $packet->senderName,
            "message" => $packet->content,
            "channel" => $packet->channelId,
            "channel_name" => $packet->channelId,
            "server" => $packet->clientId,
        ]);
        
        // Send to players in the same channel or if it's a global message
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            $playerChannel = $this->getPlayerChannel($player);
            
            if ($playerChannel === $packet->channelId || $packet->channelId === "global") {
                $player->sendMessage($formatted);
            }
        }
    }
    
    /**
     * Handles player quit events to clean up data.
     * 
     * @param PlayerQuitEvent $event The quit event
     * @priority NORMAL
     */
    public function onPlayerQuit(PlayerQuitEvent $event): void {
        $uuid = $event->getPlayer()->getUniqueId()->toString();
        $this->clearPlayerData($uuid);
        $this->plugin->debug("Cleared player data for: " . $event->getPlayer()->getName());
    }
    
    /**
     * Handles an incoming announcement packet from the backend.
     * 
     * Requirements:
     * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
     * 
     * @param AnnouncementPacket $packet The announcement packet
     */
    public function handleAnnouncement(AnnouncementPacket $packet): void {
        $this->plugin->debug("Received announcement: [{$packet->announcementId}] {$packet->content}");
        
        $server = $this->plugin->getServer();
        
        switch ($packet->type) {
            case AnnouncementPacket::TYPE_CHAT:
                // Broadcast as chat message using MessageRenderer
                MessageRenderer::broadcast($server, $packet->content);
                break;
                
            case AnnouncementPacket::TYPE_TITLE:
                // Display as title to all players using MessageRenderer
                MessageRenderer::broadcastTitle($server, $packet->content);
                break;
                
            case AnnouncementPacket::TYPE_ACTIONBAR:
                // Display as action bar to all players using MessageRenderer
                MessageRenderer::broadcastActionBar($server, $packet->content);
                break;
        }
    }
    
    /**
     * Handles an incoming title message packet from the backend.
     * 
     * Requirements:
     * - 8.7: WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码
     * 
     * @param TitleMessagePacket $packet The title message packet
     */
    public function handleTitleMessage(TitleMessagePacket $packet): void {
        $channelId = trim($packet->channelId);
        $this->plugin->debug("Received title message for channel: " . ($channelId !== "" ? $channelId : "broadcast"));
        
        if ($channelId === "" || strcasecmp($channelId, "global") === 0) {
            MessageRenderer::broadcastTitle(
                $this->plugin->getServer(),
                $packet->title,
                $packet->subtitle,
                $packet->fadeIn,
                $packet->stay,
                $packet->fadeOut
            );
            return;
        }
        
        // Send to players currently in the target channel.
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            $uuid = $player->getUniqueId()->toString();
            $playerChannel = $this->playerChannels[$uuid] ?? $this->plugin->getConfigManager()->getDefaultChannel();
            if ($playerChannel === $channelId) {
                MessageRenderer::sendTitle(
                    $player,
                    $packet->title,
                    $packet->subtitle,
                    $packet->fadeIn,
                    $packet->stay,
                    $packet->fadeOut
                );
            }
        }
    }
    
    /**
     * Handles a channel update packet from the backend.
     * 
     * @param ChannelUpdatePacket $packet The channel update packet
     */
    public function handleChannelUpdate(ChannelUpdatePacket $packet): void {
        $this->plugin->debug("Received channel update: type={$packet->updateType}, channel={$packet->channelId}");
        
        // Handle different update types
        switch ($packet->updateType) {
            case ChannelUpdatePacket::UPDATE_DELETED:
                // Move players from deleted channel to default
                $defaultChannel = $this->plugin->getConfigManager()->getDefaultChannel();
                foreach ($this->playerChannels as $uuid => $channelId) {
                    if ($channelId === $packet->channelId) {
                        $this->playerChannels[$uuid] = $defaultChannel;
                        
                        // Notify player if online - find by iterating online players
                        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
                            if ($player->getUniqueId()->toString() === $uuid) {
                                $prefix = $this->plugin->getConfigManager()->getPrefix();
                                MessageRenderer::sendMessage(
                                    $player,
                                    $prefix . "§eChannel '{$packet->channelId}' was deleted. You've been moved to '{$defaultChannel}'."
                                );
                                break;
                            }
                        }
                    }
                }
                break;
                
            case ChannelUpdatePacket::UPDATE_MEMBER_JOIN:
            case ChannelUpdatePacket::UPDATE_MEMBER_LEAVE:
                // Could notify channel members about joins/leaves
                break;
                
            case ChannelUpdatePacket::UPDATE_CONFIG_CHANGED:
                // Channel configuration changed, could refresh local cache
                break;
        }
    }
    
    /**
     * Reloads the chat handler configuration.
     */
    public function reload(): void {
        // Configuration is reloaded from ConfigManager
        $this->plugin->debug("Chat handler reloaded");
    }
}
