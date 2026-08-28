<?php

declare(strict_types=1);

namespace NovaChat\Chat;

use NovaChat\I18n\I18n;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChannelActionResponsePacket;
use NovaChat\Protocol\ChannelUpdatePacket;
use NovaChat\Protocol\ChatMessagePacket;
use NovaChat\Protocol\ConfigSyncPacket;
use NovaChat\Protocol\ItemDisplayPacket;
use NovaChat\Protocol\MentionPacket;
use NovaChat\Protocol\TitleMessagePacket;
use pocketmine\event\Listener;
use pocketmine\event\player\PlayerChatEvent;
use pocketmine\event\player\PlayerJoinEvent;
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

    /** @var array<string, string> Player locale (UUID => locale code, default zh_CN) */
    private array $playerLocales = [];

    /** @var array<int, string> Known channel IDs (populated from ConfigSync / action responses) */
    private array $knownChannels = [];

    /**
     * Per-channel remote member cache (channelId => list of UUID strings).
     *
     * Mirrors the Java ChannelCommandService optimistic local-state update:
     * MEMBER_JOIN adds the member to the channel's local roster and
     * MEMBER_LEAVE removes it. Only remote membership pushed by the backend
     * via ChannelUpdatePacket is tracked here; the local player's own
     * active channel is held in {@see $playerChannels} and is never touched
     * by these updates. Best-effort: a bad or missing dataJson logs a
     * debug warning and leaves the cache intact.
     */
    private array $channelMembers = [];

    /** @var array<string, string> Pending channel action request ID => channelId */
    private array $pendingActions = [];
    
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

        // Check if we should replace vanilla chat
        $config = $this->plugin->getConfigManager();
        if (!$config->shouldReplaceVanilla()) {
            // HYBRID mode: let vanilla chat handle the message.
            // Forwarding here would produce duplicates (vanilla + backend).
            return;
        }

        // REPLACE mode: cancel vanilla chat and forward to the backend.
        $event->cancel();

        // Get player's current channel
        $channelId = $this->getPlayerChannel($player);

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
        unset($this->playerLocales[$uuid]);
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
     * Handles player join events to capture the client locale.
     *
     * PocketMine-MP's Player::getLocale() returns the language code the
     * Bedrock client sent in its login packet (e.g. "zh_CN", "en_US",
     * "ja_JP"). We register it with ChatHandler::setPlayerLocale() so
     * downstream i18n lookups (kick/mute notices, mentions, ...) use the
     * player's real locale instead of the hard default (zh_CN).
     *
     * @param PlayerJoinEvent $event The join event
     * @priority NORMAL
     */
    public function onPlayerJoin(PlayerJoinEvent $event): void {
        $player = $event->getPlayer();
        $uuid = $player->getUniqueId()->toString();
        $locale = $player->getLocale();
        if ($locale !== "" && $locale !== "0") {
            $this->setPlayerLocale($uuid, $locale);
        } else {
            // Default locale when the client sent no language code.
            $this->setPlayerLocale($uuid, "zh_CN");
        }
        $this->plugin->debug("Captured locale for {$player->getName()}: {$this->getPlayerLocale($uuid)}");
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
                $this->applyMemberJoin($packet->channelId, $packet->dataJson);
                break;

            case ChannelUpdatePacket::UPDATE_MEMBER_LEAVE:
                $this->applyMemberLeave($packet->channelId, $packet->dataJson);
                break;

            case ChannelUpdatePacket::UPDATE_CONFIG_CHANGED:
                $this->applyConfigChanged($packet->channelId, $packet->dataJson);
                break;
        }
    }

    /**
     * Applies a MEMBER_JOIN update: adds the member UUID carried in dataJson
     * to the local channel roster. Mirrors the Java ChannelCommandService
     * optimistic local-state update for membership. Best-effort: a missing
     * or malformed payload logs a warning and leaves the cache intact.
     *
     * dataJson shape: {"playerId": "<uuid>", "playerName": "<optional>"}
     */
    private function applyMemberJoin(string $channelId, string $dataJson): void {
        if ($channelId === "") {
            return;
        }
        $memberId = $this->extractMemberId($dataJson);
        if ($memberId === "") {
            $this->plugin->debug("ChannelUpdate MEMBER_JOIN: missing playerId, ignored");
            return;
        }
        if (!isset($this->channelMembers[$channelId])) {
            $this->channelMembers[$channelId] = [];
        }
        if (!in_array($memberId, $this->channelMembers[$channelId], true)) {
            $this->channelMembers[$channelId][] = $memberId;
        }
        // The channel now exists locally, so track it as known too.
        $this->addKnownChannel($channelId);
    }

    /**
     * Applies a MEMBER_LEAVE update: removes the member UUID carried in
     * dataJson from the local channel roster. Mirrors the Java
     * ChannelCommandService optimistic local-state update for leave.
     * Best-effort: a missing or malformed payload logs a warning and
     * leaves the cache intact.
     *
     * dataJson shape: {"playerId": "<uuid>", "playerName": "<optional>"}
     */
    private function applyMemberLeave(string $channelId, string $dataJson): void {
        if ($channelId === "") {
            return;
        }
        $memberId = $this->extractMemberId($dataJson);
        if ($memberId === "") {
            $this->plugin->debug("ChannelUpdate MEMBER_LEAVE: missing playerId, ignored");
            return;
        }
        if (!isset($this->channelMembers[$channelId])) {
            return;
        }
        $this->channelMembers[$channelId] = array_values(
            array_filter(
                $this->channelMembers[$channelId],
                static fn (string $id): bool => $id !== $memberId
            )
        );
        if ($this->channelMembers[$channelId] === []) {
            unset($this->channelMembers[$channelId]);
        }
    }

    /**
     * Applies a CONFIG_CHANGED update: refreshes the local known-channel
     * list. When dataJson carries a full ConfigSync-shaped payload the
     * same extraction logic as {@see handleConfigSync} is reused (the
     * Java ConfigSyncChannels.extract contract). When dataJson is blank
     * or carries only a per-channel config blob, we simply mark the
     * channel as known so /nc list and tab completion stay consistent.
     * Best-effort: a malformed payload logs a warning and leaves the
     * existing registry intact.
     */
    private function applyConfigChanged(string $channelId, string $dataJson): void {
        if ($dataJson !== "" && trim($dataJson) !== "") {
            $decoded = json_decode($dataJson, true);
            // ConfigSync-shaped payload (root object with global_channels /
            // clients) -> reuse the full rebuild path.
            if (is_array($decoded) && !array_is_list($decoded)
                && (array_key_exists("global_channels", $decoded)
                    || array_key_exists("clients", $decoded))
            ) {
                $this->rebuildKnownChannels($decoded);
                return;
            }
        }
        // Fall back: mark the named channel as known.
        if ($channelId !== "") {
            $this->addKnownChannel($channelId);
        }
    }

    /**
     * Rebuilds the known-channel registry from a decoded ConfigSync
     * payload. Factored out of {@see handleConfigSync} so CONFIG_CHANGED
     * can reuse the identical extraction when the backend pushes a full
     * config blob. Mirrors Java ConfigSyncChannels.extract.
     *
     * @param array<string, mixed> $decoded
     */
    private function rebuildKnownChannels(array $decoded): void {
        $channels = [];

        if (array_key_exists("global_channels", $decoded)) {
            $globals = $decoded["global_channels"];
            if (is_array($globals) && !array_is_list($globals)) {
                foreach (array_keys($globals) as $id) {
                    $channels[(string) $id] = true;
                }
            } elseif ($globals !== null) {
                $this->plugin->debug("ConfigSync: global_channels must be a mapping");
            }
        }

        $username = $this->plugin->getConfigManager()->getBackendUsername();
        if ($username !== "" && trim($username) !== "") {
            if (array_key_exists("clients", $decoded)) {
                $clients = $decoded["clients"];
                if (is_array($clients) && array_is_list($clients)) {
                    foreach ($clients as $entry) {
                        if (!is_array($entry) || !array_key_exists("username", $entry)) {
                            continue;
                        }
                        $entryUsername = $entry["username"];
                        if (!is_string($entryUsername) || $entryUsername !== $username) {
                            continue;
                        }
                        if (array_key_exists("channels", $entry)) {
                            $clientChannels = $entry["channels"];
                            if (is_array($clientChannels) && !array_is_list($clientChannels)) {
                                foreach (array_keys($clientChannels) as $id) {
                                    $channels[(string) $id] = true;
                                }
                            } elseif ($clientChannels !== null) {
                                $this->plugin->debug("ConfigSync: client.channels must be a mapping");
                            }
                        }
                        break;
                    }
                } elseif ($clients !== null) {
                    $this->plugin->debug("ConfigSync: clients must be an array");
                }
            }
        }

        $sorted = array_keys($channels);
        sort($sorted);

        $this->knownChannels = array_values($sorted);
    }

    /**
     * Extracts the member UUID from a ChannelUpdate dataJson payload.
     *
     * Accepts {"playerId": "<uuid>"} (the Java ChannelActionPacket extra
     * name) and {"playerUuid": "<uuid>"} / {"uuid": "<uuid>"} as
     * permissive fallbacks. Returns "" when the field is absent or not a
     * non-empty string.
     */
    private function extractMemberId(string $dataJson): string {
        if ($dataJson === "" || trim($dataJson) === "") {
            return "";
        }
        $decoded = json_decode($dataJson, true);
        if (!is_array($decoded)) {
            return "";
        }
        foreach (["playerId", "playerUuid", "uuid", "memberId"] as $key) {
            if (array_key_exists($key, $decoded) && is_string($decoded[$key]) && $decoded[$key] !== "") {
                return $decoded[$key];
            }
        }
        return "";
    }

    /**
     * Handles a channel action response — routes kick/mute target-side
     * notifications and tracks known channels.
     *
     * @param ChannelActionResponsePacket $packet The response packet
     */
    public function handleChannelActionResponse(ChannelActionResponsePacket $packet): void {
        // Track the channel as known regardless of outcome.
        $this->addKnownChannel($packet->channelId);

        if ($packet->success) {
            $this->plugin->debug("Channel action succeeded: {$packet->message}");
            // Route kick/mute target-side notifications via the extra map.
            $action = $packet->action;
            if ($action === ChannelActionPacket::ACTION_KICK || $action === ChannelActionPacket::ACTION_MUTE) {
                $operatorName = $packet->extra["operatorName"] ?? "";
                $targetUuid = $packet->extra["targetUuid"] ?? "";
                $duration = $packet->extra["duration"] ?? "";
                if ($targetUuid !== "") {
                    if ($action === ChannelActionPacket::ACTION_KICK) {
                        $this->notifyKickTarget($targetUuid, $operatorName, $packet->channelId);
                    } else {
                        $this->notifyMuteTarget($targetUuid, $operatorName, $packet->channelId, $duration);
                    }
                }
            }
        } else {
            $this->plugin->debug("Channel action failed: {$packet->errorCode} - {$packet->message}");
        }
    }

    /**
     * Handles a config sync packet — stores known channels from the config.
     *
     * @param ConfigSyncPacket $packet The config sync packet
     */
    public function handleConfigSync(ConfigSyncPacket $packet): void {
        $this->plugin->debug("Received config sync ({$packet->configJson})");

        // Mirrors Java ConfigSyncChannels.extract: build the known-channel set
        // from global_channels keys + the matching clients[] entry's channels
        // keys (filtered by this client's backend username), then REPLACE the
        // known-channel registry. Only knownChannels is touched; the active
        // per-player channel (playerChannels) is never overwritten. Parsing is
        // best-effort: a bad payload logs a warning and leaves the existing
        // registry intact rather than throwing.
        $configJson = $packet->configJson;
        if ($configJson === "" || trim($configJson) === "") {
            return;
        }

        $decoded = json_decode($configJson, true);
        if (!is_array($decoded)) {
            $this->plugin->debug("ConfigSync: malformed or non-object config JSON");
            return;
        }

        $this->rebuildKnownChannels($decoded);
    }

    /**
     * Handles a mention packet — highlights + title to the mentioned player.
     *
     * @param MentionPacket $packet The mention packet
     */
    public function handleMention(MentionPacket $packet): void {
        $this->plugin->debug("Mention from {$packet->mentionerName} to {$packet->mentionedId} in {$packet->channelId}");
        $locale = $this->getPlayerLocale($packet->mentionedId);
        $i18n = new I18n();
        $subtitle = $i18n->get("chat.mention.subtitle", $locale, [$packet->channelId]);

        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            if ($player->getUniqueId()->toString() === $packet->mentionedId) {
                MessageRenderer::sendTitle(
                    $player,
                    "§e@§r" . $packet->mentionerName,
                    $subtitle,
                    10,
                    40,
                    20
                );
                break;
            }
        }
    }

    /**
     * Handles an item display packet — forwards [item] display to channel members.
     *
     * @param ItemDisplayPacket $packet The item display packet
     */
    public function handleItemDisplay(ItemDisplayPacket $packet): void {
        $this->plugin->debug("ItemDisplay in {$packet->channelId}: {$packet->itemJson}");
        $formatted = "[" . $packet->senderName . ": " . $packet->itemJson . "]";
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            $playerChannel = $this->getPlayerChannel($player);
            if ($playerChannel === $packet->channelId || $packet->channelId === "global") {
                $player->sendMessage($formatted);
            }
        }
    }

    /**
     * Handles an incoming private message packet from the backend.
     *
     * The backend delivers a completed PrivateMessagePacket to BOTH the
     * sender's client (echo) and the target's client. The receiving plugin
     * renders whichever line applies to a local player:
     *  - local player == senderId              -> "sent" echo line
     *  - local player == targetId, != senderId   -> "received" line
     *
     * Private chat is per-player directed; this method never broadcasts to a
     * channel. UUID strings are compared against each online player's
     * unique id; the nil UUID (all zeros) is guarded out per field.
     *
     * @param \NovaChat\Protocol\PrivateMessagePacket $packet The private message packet
     */
    public function handlePrivateMessage(\NovaChat\Protocol\PrivateMessagePacket $packet): void {
        $this->plugin->debug("Received private message from {$packet->senderName} to {$packet->targetName}");

        $i18n = new I18n();
        $senderId = $packet->senderId;
        $targetId = $packet->targetId;
        $nilUuid = "00000000-0000-0000-0000-000000000000";

        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            $playerUuid = $player->getUniqueId()->toString();

            // Echo line: this local player is the sender.
            if ($senderId !== "" && $senderId !== $nilUuid && $playerUuid === $senderId) {
                $locale = $this->getPlayerLocale($playerUuid);
                $message = $i18n->get("chat.msg.sent", $locale, [$packet->targetName, $packet->content]);
                MessageRenderer::sendMessage($player, $message);
                continue;
            }

            // Received line: this local player is the (distinct) target.
            if ($targetId !== "" && $targetId !== $nilUuid && $playerUuid === $targetId) {
                $locale = $this->getPlayerLocale($playerUuid);
                $message = $i18n->get("chat.msg.received", $locale, [$packet->senderName, $packet->content]);
                MessageRenderer::sendMessage($player, $message);
            }
        }
    }

    /**
     * Gets the list of known channels (for /nc list + tab completion).
     *
     * @return array<int, string>
     */
    public function getKnownChannels(): array {
        return array_values($this->knownChannels);
    }

    /**
     * Adds a channel to the known channels set.
     */
    public function addKnownChannel(string $channelId): void {
        if ($channelId !== "" && !in_array($channelId, $this->knownChannels, true)) {
            $this->knownChannels[] = $channelId;
        }
    }

    /**
     * Returns the remote member UUIDs cached for a channel, or an empty
     * list when no MEMBER_JOIN/LEAVE updates have been received for it.
     *
     * @return array<int, string>
     */
    public function getChannelMembers(string $channelId): array {
        return $this->channelMembers[$channelId] ?? [];
    }

    /**
     * Sends a WHO channel action for a player.
     */
    public function whoChannel(Player $player, string $channelId): void {
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            return;
        }
        $networkClient->sendChannelAction(ChannelActionPacket::ACTION_WHO, $channelId);
    }

    /**
     * Gets a player's locale (default zh_CN).
     */
    public function getPlayerLocale(string $uuid): string {
        return $this->playerLocales[$uuid] ?? "zh_CN";
    }

    /**
     * Sets a player's locale.
     */
    public function setPlayerLocale(string $uuid, string $locale): void {
        $this->playerLocales[$uuid] = $locale;
    }

    /**
     * Notifies a target player that they were kicked from a channel.
     */
    public function notifyKickTarget(string $targetUuid, string $operatorName, string $channelId): void {
        $locale = $this->getPlayerLocale($targetUuid);
        $i18n = new I18n();
        $opName = $operatorName !== "" ? $operatorName : $i18n->get("notice.operator.fallback", $locale);

        // Title flash
        $title = $i18n->get("chat.notice.kick_title", $locale);
        $subtitle = $i18n->get("chat.notice.kick_subtitle", $locale, [$opName, $channelId]);
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            if ($player->getUniqueId()->toString() === $targetUuid) {
                MessageRenderer::sendTitle($player, $title, $subtitle, 10, 70, 20);
                $actionbar = $i18n->get("chat.notice.kick_actionbar", $locale, [$opName, $channelId]);
                $player->sendMessage($actionbar);
                break;
            }
        }
    }

    /**
     * Notifies a target player that they were muted in a channel.
     */
    public function notifyMuteTarget(string $targetUuid, string $operatorName, string $channelId, string $duration): void {
        $locale = $this->getPlayerLocale($targetUuid);
        $i18n = new I18n();
        $opName = $operatorName !== "" ? $operatorName : $i18n->get("notice.operator.fallback", $locale);
        $dur = $duration !== "" ? $duration : $i18n->get("notice.duration.unknown", $locale);

        $title = $i18n->get("chat.notice.mute_title", $locale);
        $subtitle = $i18n->get("chat.notice.mute_subtitle", $locale, [$channelId, $dur]);
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $player) {
            if ($player->getUniqueId()->toString() === $targetUuid) {
                MessageRenderer::sendTitle($player, $title, $subtitle, 10, 70, 20);
                $actionbar = $i18n->get("chat.notice.mute_actionbar", $locale, [$dur, $channelId]);
                $player->sendMessage($actionbar);
                break;
            }
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
