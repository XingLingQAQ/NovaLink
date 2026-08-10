<?php

declare(strict_types=1);

namespace NovaChat\Command;

use NovaChat\Chat\MessageRenderer;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelActionPacket;
use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\player\Player;
use pocketmine\plugin\PluginOwned;
use pocketmine\utils\TextFormat;

/**
 * Main NovaChat command handler.
 * 
 * Requirements:
 * - 8.1: THE NovaChat-PMMP SHALL 注册 /novachat 命令
 * 
 * Provides all NovaChat commands including:
 * - help: Show help information
 * - join: Join a channel
 * - leave: Leave current channel
 * - create: Create a private channel
 * - invite: Invite a player to a channel
 * - accept: Accept a channel invitation
 * - toggle: Toggle chat on/off
 * - mute: Mute a player (admin)
 * - kick: Kick a player from channel (admin)
 * - announce: Send an announcement (admin)
 * - reload: Reload configuration (admin)
 * - debug: Toggle debug mode (admin)
 */
class NovaChatCommand extends Command implements PluginOwned {
    
    /** @var NovaChatPlugin Plugin instance */
    private NovaChatPlugin $plugin;
    
    /**
     * Creates a new NovaChat command.
     * 
     * @param NovaChatPlugin $plugin Plugin instance
     */
    public function __construct(NovaChatPlugin $plugin) {
        parent::__construct("novachat", "NovaChat main command", "/novachat <subcommand>", ["nc"]);
        $this->setPermission("novachat.use");
        $this->plugin = $plugin;
    }
    
    /**
     * Gets the owning plugin.
     * 
     * @return NovaChatPlugin The plugin
     */
    public function getOwningPlugin(): NovaChatPlugin {
        return $this->plugin;
    }
    
    /**
     * Executes the command.
     * 
     * @param CommandSender $sender Command sender
     * @param string $commandLabel Command label used
     * @param array $args Command arguments
     * @return bool True if command was handled
     */
    public function execute(CommandSender $sender, string $commandLabel, array $args): bool {
        if (!$this->testPermission($sender)) {
            return false;
        }
        
        if (count($args) === 0) {
            $this->sendHelp($sender);
            return true;
        }
        
        $subCommand = strtolower(array_shift($args));
        
        return match ($subCommand) {
            "help" => $this->handleHelp($sender),
            "join" => $this->handleJoin($sender, $args),
            "leave" => $this->handleLeave($sender),
            "list" => $this->handleList($sender),
            "who" => $this->handleWho($sender, $args),
            "create" => $this->handleCreate($sender, $args),
            "invite" => $this->handleInvite($sender, $args),
            "accept" => $this->handleAccept($sender, $args),
            "toggle" => $this->handleToggle($sender),
            "mute" => $this->handleMute($sender, $args),
            "kick" => $this->handleKick($sender, $args),
            "announce" => $this->handleAnnounce($sender, $args),
            "reload" => $this->handleReload($sender),
            "debug" => $this->handleDebug($sender),
            "status" => $this->handleStatus($sender),
            default => $this->handleUnknown($sender, $subCommand),
        };
    }
    
    /**
     * Sends help information.
     * 
     * @param CommandSender $sender Command sender
     */
    private function sendHelp(CommandSender $sender): void {
        $prefix = $this->plugin->getConfigManager()->getPrefix();
        
        $sender->sendMessage($prefix . TextFormat::YELLOW . "NovaChat Commands:");
        $sender->sendMessage(TextFormat::GRAY . "/nc help" . TextFormat::WHITE . " - Show this help");
        $sender->sendMessage(TextFormat::GRAY . "/nc join <channel>" . TextFormat::WHITE . " - Join a channel");
        $sender->sendMessage(TextFormat::GRAY . "/nc leave" . TextFormat::WHITE . " - Leave current channel");
        $sender->sendMessage(TextFormat::GRAY . "/nc list" . TextFormat::WHITE . " - List available channels");
        $sender->sendMessage(TextFormat::GRAY . "/nc who [channel]" . TextFormat::WHITE . " - List online members");
        $sender->sendMessage(TextFormat::GRAY . "/nc toggle" . TextFormat::WHITE . " - Toggle chat on/off");
        $sender->sendMessage(TextFormat::GRAY . "/nc status" . TextFormat::WHITE . " - Show connection status");
        
        if ($sender->hasPermission("novachat.create")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc create <name> [password]" . TextFormat::WHITE . " - Create private channel");
        }
        if ($sender->hasPermission("novachat.invite")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc invite <player>" . TextFormat::WHITE . " - Invite player to channel");
            $sender->sendMessage(TextFormat::GRAY . "/nc accept <code>" . TextFormat::WHITE . " - Accept channel invitation");
        }
        if ($sender->hasPermission("novachat.admin.mute")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc mute <player> [duration]" . TextFormat::WHITE . " - Mute a player");
        }
        if ($sender->hasPermission("novachat.admin.kick")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc kick <player>" . TextFormat::WHITE . " - Kick player from channel");
        }
        if ($sender->hasPermission("novachat.admin.announce")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc announce <message>" . TextFormat::WHITE . " - Send announcement");
        }
        if ($sender->hasPermission("novachat.admin.reload")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc reload" . TextFormat::WHITE . " - Reload configuration");
        }
        if ($sender->hasPermission("novachat.debug")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc debug" . TextFormat::WHITE . " - Toggle debug mode");
        }
    }
    
    /**
     * Handles the help subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleHelp(CommandSender $sender): bool {
        $this->sendHelp($sender);
        return true;
    }
    
    /**
     * Handles the join subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleJoin(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        if (!$sender->hasPermission("novachat.join")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to join channels.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc join <channel>");
            return true;
        }
        
        $channelId = $args[0];
        $password = $args[1] ?? "";
        $chatHandler = $this->plugin->getChatHandler();

        if ($chatHandler !== null) {
            // Send JOIN action to backend if connected.
            $networkClient = $this->plugin->getNetworkClient();
            if ($networkClient !== null && $networkClient->isAuthenticated()) {
                $networkClient->sendChannelAction(
                    ChannelActionPacket::ACTION_JOIN,
                    $channelId,
                    $password
                );
            }
            $chatHandler->setPlayerChannel($sender, $channelId);
            $chatHandler->addKnownChannel($channelId);
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Joined channel: " . TextFormat::YELLOW . $channelId);
        }

        return true;
    }
    
    /**
     * Handles the leave subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleLeave(CommandSender $sender): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        if (!$sender->hasPermission("novachat.leave")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to leave channels.");
            return true;
        }
        
        $chatHandler = $this->plugin->getChatHandler();
        $defaultChannel = $this->plugin->getConfigManager()->getDefaultChannel();

        if ($chatHandler !== null) {
            $currentChannel = $chatHandler->getPlayerChannel($sender);
            // Send LEAVE action to backend if connected and not already on default.
            if ($currentChannel !== $defaultChannel) {
                $networkClient = $this->plugin->getNetworkClient();
                if ($networkClient !== null && $networkClient->isAuthenticated()) {
                    $networkClient->sendChannelAction(
                        ChannelActionPacket::ACTION_LEAVE,
                        $currentChannel
                    );
                }
            }
            $chatHandler->setPlayerChannel($sender, $defaultChannel);
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Returned to default channel: " . TextFormat::YELLOW . $defaultChannel);
        }

        return true;
    }

    /**
     * Handles the list subcommand — lists known channels.
     *
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleList(CommandSender $sender): bool {
        $chatHandler = $this->plugin->getChatHandler();
        $prefix = $this->plugin->getConfigManager()->getPrefix();

        $sender->sendMessage($prefix . TextFormat::YELLOW . "NovaChat Channels:");

        if ($chatHandler !== null) {
            $channels = $chatHandler->getKnownChannels();
            if (count($channels) === 0) {
                $sender->sendMessage(TextFormat::GRAY . "No known channels yet, please wait for the server to push the channel list.");
            } else {
                sort($channels);
                foreach ($channels as $channelId) {
                    $sender->sendMessage(TextFormat::GRAY . "- " . TextFormat::AQUA . $channelId);
                }
            }
        } else {
            $sender->sendMessage(TextFormat::RED . "Chat handler not initialized.");
        }

        return true;
    }

    /**
     * Handles the who subcommand — queries online members of a channel.
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleWho(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }

        $chatHandler = $this->plugin->getChatHandler();
        $prefix = $this->plugin->getConfigManager()->getPrefix();

        $channelId = count($args) > 0 ? $args[0] : "";
        if ($channelId === "" && $chatHandler !== null) {
            $channelId = $chatHandler->getPlayerChannel($sender);
        }

        if ($channelId === "") {
            $sender->sendMessage($prefix . TextFormat::RED . "Please specify a channel id.");
            return true;
        }

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage($prefix . TextFormat::RED . "Channel member query is unavailable (requires backend support).");
            return true;
        }

        // Fire a WHO channel action; the backend replies with a
        // ChannelActionResponse whose extra carries the member list.
        if ($chatHandler !== null) {
            $chatHandler->whoChannel($sender, $channelId);
        }
        $sender->sendMessage($prefix . TextFormat::GREEN . "Fetching online members for " . TextFormat::YELLOW . $channelId . TextFormat::GREEN . "...");

        return true;
    }

    /**
     * Handles the toggle subcommand.
     *
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleToggle(CommandSender $sender): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        $chatHandler = $this->plugin->getChatHandler();
        $prefix = $this->plugin->getConfigManager()->getPrefix();
        
        if ($chatHandler !== null) {
            $enabled = $chatHandler->toggleChat($sender);
            $status = $enabled ? TextFormat::GREEN . "enabled" : TextFormat::RED . "disabled";
            $sender->sendMessage($prefix . TextFormat::WHITE . "Chat is now " . $status);
        }
        
        return true;
    }
    
    /**
     * Handles the reload subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleReload(CommandSender $sender): bool {
        if (!$sender->hasPermission("novachat.admin.reload")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to reload configuration.");
            return true;
        }
        
        $this->plugin->reload();
        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Configuration reloaded.");
        
        return true;
    }
    
    /**
     * Handles the debug subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleDebug(CommandSender $sender): bool {
        if (!$sender->hasPermission("novachat.debug")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to toggle debug mode.");
            return true;
        }
        
        $enabled = !$this->plugin->isDebugMode();
        $this->plugin->setDebugMode($enabled);
        
        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $status = $enabled ? TextFormat::GREEN . "enabled" : TextFormat::RED . "disabled";
        $sender->sendMessage($prefix . TextFormat::WHITE . "Debug mode is now " . $status);
        
        return true;
    }
    
    /**
     * Handles unknown subcommands.
     * 
     * @param CommandSender $sender Command sender
     * @param string $subCommand The unknown subcommand
     * @return bool True
     */
    private function handleUnknown(CommandSender $sender, string $subCommand): bool {
        $sender->sendMessage(TextFormat::RED . "Unknown subcommand: " . $subCommand);
        $this->sendHelp($sender);
        return true;
    }
    
    /**
     * Handles the create subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleCreate(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        if (!$sender->hasPermission("novachat.create")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to create channels.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc create <name> [password]");
            return true;
        }
        
        $channelName = $args[0];
        $password = $args[1] ?? "";
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send channel create action to backend
            $extra = [
                "name" => $channelName,
                "owner" => $sender->getUniqueId()->toString(),
            ];
            $networkClient->sendChannelAction(
                ChannelActionPacket::ACTION_CREATE,
                $channelName,
                $password,
                $extra
            );

            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Creating channel: " . TextFormat::YELLOW . $channelName);
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the invite subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleInvite(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        if (!$sender->hasPermission("novachat.invite")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to invite players.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc invite <player>");
            return true;
        }
        
        $targetName = $args[0];
        $chatHandler = $this->plugin->getChatHandler();
        $currentChannel = $chatHandler !== null ? $chatHandler->getPlayerChannel($sender) : "local";
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send invite action to backend
            $extra = [
                "inviter" => $sender->getName(),
                "target" => $targetName,
            ];
            $networkClient->sendChannelAction(
                ChannelActionPacket::ACTION_INVITE,
                $currentChannel,
                "",
                $extra
            );
            
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Invitation sent to: " . TextFormat::YELLOW . $targetName);
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the accept subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleAccept(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc accept <invitation_code>");
            return true;
        }
        
        $invitationCode = $args[0];
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send accept action to backend
            $extra = [
                "player" => $sender->getName(),
                "code" => $invitationCode,
            ];
            $networkClient->sendChannelAction(
                ChannelActionPacket::ACTION_ACCEPT,
                "",
                "",
                $extra
            );
            
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Processing invitation...");
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the mute subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleMute(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.mute")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to mute players.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc mute <player> [duration_minutes]");
            return true;
        }
        
        $targetName = $args[0];
        $duration = isset($args[1]) ? (int) $args[1] : 10; // Default 10 minutes
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send mute action to backend
            $extra = [
                "target" => $targetName,
                "duration" => (string)$duration,
                "operatorName" => $sender->getName(),
            ];
            $networkClient->sendChannelAction(
                ChannelActionPacket::ACTION_MUTE,
                "",
                "",
                $extra
            );
            
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Muted " . TextFormat::YELLOW . $targetName . 
                TextFormat::GREEN . " for " . TextFormat::YELLOW . $duration . TextFormat::GREEN . " minutes.");
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the kick subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleKick(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.kick")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to kick players.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc kick <player>");
            return true;
        }
        
        $targetName = $args[0];
        $chatHandler = $this->plugin->getChatHandler();
        $currentChannel = "";
        
        if ($sender instanceof Player && $chatHandler !== null) {
            $currentChannel = $chatHandler->getPlayerChannel($sender);
        }
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send kick action to backend
            $extra = [
                "target" => $targetName,
                "operatorName" => $sender->getName(),
            ];
            $networkClient->sendChannelAction(
                ChannelActionPacket::ACTION_KICK,
                $currentChannel,
                "",
                $extra
            );
            
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Kicked " . TextFormat::YELLOW . $targetName . 
                TextFormat::GREEN . " from channel.");
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the announce subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @param array $args Arguments
     * @return bool True
     */
    private function handleAnnounce(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.announce")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to send announcements.");
            return true;
        }
        
        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc announce <message>");
            return true;
        }
        
        $message = implode(" ", $args);
        
        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient !== null && $networkClient->isAuthenticated()) {
            // Send announcement packet to backend
            $packet = new \NovaChat\Protocol\AnnouncementPacket();
            $packet->announcementId = uniqid("announce_", true);
            $packet->content = $message;
            $packet->type = \NovaChat\Protocol\AnnouncementPacket::TYPE_CHAT;
            $networkClient->sendPacket($packet);

            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Announcement sent.");
        } else {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
        }
        
        return true;
    }
    
    /**
     * Handles the status subcommand.
     * 
     * @param CommandSender $sender Command sender
     * @return bool True
     */
    private function handleStatus(CommandSender $sender): bool {
        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $networkClient = $this->plugin->getNetworkClient();
        
        $sender->sendMessage($prefix . TextFormat::YELLOW . "NovaChat Status:");
        
        if ($networkClient !== null) {
            $connected = $networkClient->isConnected();
            $authenticated = $networkClient->isAuthenticated();
            
            $connStatus = $connected ? TextFormat::GREEN . "Connected" : TextFormat::RED . "Disconnected";
            $authStatus = $authenticated ? TextFormat::GREEN . "Authenticated" : TextFormat::RED . "Not authenticated";
            
            $sender->sendMessage(TextFormat::GRAY . "Connection: " . $connStatus);
            $sender->sendMessage(TextFormat::GRAY . "Authentication: " . $authStatus);
            
            $config = $this->plugin->getConfigManager();
            $sender->sendMessage(TextFormat::GRAY . "Backend: " . TextFormat::WHITE . 
                $config->getBackendHost() . ":" . $config->getBackendPort());
        } else {
            $sender->sendMessage(TextFormat::RED . "Network client not initialized.");
        }
        
        if ($sender instanceof Player) {
            $chatHandler = $this->plugin->getChatHandler();
            if ($chatHandler !== null) {
                $channel = $chatHandler->getPlayerChannel($sender);
                $chatEnabled = $chatHandler->isChatEnabled($sender);
                
                $sender->sendMessage(TextFormat::GRAY . "Current channel: " . TextFormat::YELLOW . $channel);
                $sender->sendMessage(TextFormat::GRAY . "Chat enabled: " . 
                    ($chatEnabled ? TextFormat::GREEN . "Yes" : TextFormat::RED . "No"));
            }
        }
        
        return true;
    }
}
