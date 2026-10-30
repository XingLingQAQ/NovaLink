<?php

declare(strict_types=1);

namespace NovaChat\Command;

use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\AdminActionPacket;
use NovaChat\Protocol\ChannelActionPacket;
use pocketmine\command\Command;
use pocketmine\command\CommandExecutor;
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
 * - unmute: Lift a mute (admin)
 * - kick: Kick a player from channel (admin)
 * - ban: Ban a player, network-wide unless a channel is named (admin)
 * - unban: Lift a ban by player name or UUID (admin)
 * - auth: Start a super-admin session (player-only)
 * - announce: Send an announcement (super admin)
 * - title: Send a title to a channel (super admin)
 * - reload: Reload configuration (admin)
 * - debug: Toggle debug mode (admin)
 */
class NovaChatCommand extends Command implements PluginOwned, CommandExecutor {
    
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
            "unmute" => $this->handleUnmute($sender, $args),
            "kick" => $this->handleKick($sender, $args),
            "ban" => $this->handleBan($sender, $args),
            "unban" => $this->handleUnban($sender, $args),
            "auth" => $this->handleAuth($sender, $args),
            "announce" => $this->handleAnnounce($sender, $args),
            "title" => $this->handleTitle($sender, $args),
            "reload" => $this->handleReload($sender),
            "debug" => $this->handleDebug($sender),
            "status" => $this->handleStatus($sender),
            default => $this->handleUnknown($sender, $subCommand),
        };
    }

    /**
     * CommandExecutor bridge for the descriptor-pre-registered PluginCommand.
     *
     * PocketMine's PluginManager::parseYamlCommands pre-registers a PluginCommand
     * for every entry under `commands:` in plugin.yml (here: `novachat` with
     * alias `nc`). That pre-registered PluginCommand OWNS the "novachat"/"nc"
     * command slots. registerCommands() in NovaChatPlugin used to call
     * `$commandMap->register("novachat", new NovaChatCommand($this))`, which
     * SILENTLY returned false (no log, no exception) because the alias slot was
     * already taken by the descriptor's PluginCommand. The pre-registered
     * PluginCommand had no executor, so its execute() returned false and PMMP
     * echoed the plugin.yml `usage:` string ("Usage: /novachat <subcommand>
     * [args]") instead of dispatching -- every `/nc help|join|toggle|...` from
     * the E2E bot got the usage echo, never the real handler.
     *
     * The fix (mirrors the nukkit/pnx registerCommands fix, see
     * real-e2e-product-findings-2026-08 Finding B2): instead of register()-ing
     * a new command, NovaChatPlugin::registerCommands() now looks up the
     * descriptor's pre-registered PluginCommand via getCommand("novachat") and
     * calls setExecutor($this NovaChatCommand). PocketMine then dispatches
     * /novachat (and /nc alias) by calling PluginCommand::execute(), which
     * delegates to this onCommand() (the CommandExecutor contract).
     *
     * onCommand bridges to the existing execute() (which has the full subcommand
     * match dispatch). Returning false from onCommand makes PMMP show the
     * usage, so we return true for "handled" (even unknown subcommands are
     * handled via handleUnknown, which sends its own message).
     */
    public function onCommand(CommandSender $sender, Command $command, string $label, array $args): bool {
        return $this->execute($sender, $label, $args);
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
            $sender->sendMessage(TextFormat::GRAY . "/nc mute <player> [30s|10m|1h|1d] [channel]" . TextFormat::WHITE . " - Mute a player");
            $sender->sendMessage(TextFormat::GRAY . "/nc unmute <player> [channel]" . TextFormat::WHITE . " - Lift a mute");
        }
        if ($sender->hasPermission("novachat.admin.kick")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc kick <player> [channel]" . TextFormat::WHITE . " - Kick player from channel");
        }
        if ($sender->hasPermission("novachat.ban")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc ban <player> [duration] [channel]" . TextFormat::WHITE . " - Ban (all channels when omitted)");
            $sender->sendMessage(TextFormat::GRAY . "/nc unban <player|UUID> [channel]" . TextFormat::WHITE . " - Lift a ban");
        }
        if ($sender->hasPermission("novachat.admin.announce")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc announce <channel> <message>" . TextFormat::WHITE . " - Send announcement");
        }
        if ($sender->hasPermission("novachat.title")) {
            $sender->sendMessage(TextFormat::GRAY . "/nc title <channel> <title> [subtitle]" . TextFormat::WHITE . " - Send title");
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
                    $password,
                    [
                        "playerId" => $sender->getUniqueId()->toString(),
                        "playerName" => $sender->getName(),
                        "platform" => "pmmp",
                    ]
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
                        $currentChannel,
                        "",
                        [
                            "playerId" => $sender->getUniqueId()->toString(),
                            "playerName" => $sender->getName(),
                            "platform" => "pmmp",
                        ]
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
        
        if ($this->plugin->reload()) {
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::GREEN . "Configuration reloaded.");
        } else {
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage(
                $prefix . TextFormat::RED
                . "Configuration reload rejected. Check the server log for the reason."
            );
        }
        
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
            $sender->sendMessage(TextFormat::RED . "Usage: /nc invite <player> [channel]");
            return true;
        }

        $targetName = (string) $args[0];

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $channelId = (string) ($args[1] ?? "");
        if ($channelId === "") {
            $channelId = $this->resolveSenderChannel($sender);
        }
        if ($channelId === "") {
            $sender->sendMessage(TextFormat::RED . "Please specify a channel: /nc invite <player> <channel>");
            return true;
        }

        // ChannelActionHandler.handleInvite identifies the inviter from
        // playerId / player_uuid — an "inviter" extra is never read — and only
        // channel admins may create an invite.
        $extra = [
            "playerId" => $sender instanceof Player ? $sender->getUniqueId()->toString() : "",
            "playerName" => $sender->getName(),
        ];
        $target = $this->findOnlinePlayer($targetName);
        if ($target !== null) {
            $extra["targetId"] = $target->getUniqueId()->toString();
        }
        $extra["targetName"] = $targetName;

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_INVITE,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Invitation sent to: " . TextFormat::YELLOW . $targetName);

        return true;
    }

    /**
     * Handles the accept subcommand.
     *
     * ChannelActionHandler.handleAccept reads the invite code from the packet's
     * CHANNEL ID (not an extra) and the accepting player from playerId /
     * player_uuid; a "player"/"code" extra pair is never read, so the old
     * shape was rejected with "Player ID is required". Mirrors the Java
     * AcceptCommand: uppercase the code and reject a malformed one locally.
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = invitation code)
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

        $invitationCode = strtoupper((string) $args[0]);
        if (preg_match('/^[A-Z0-9]{6}$/', $invitationCode) !== 1) {
            $sender->sendMessage(TextFormat::RED . "Invalid invitation code: " . $invitationCode .
                " (expected 6 letters or digits)");
            return true;
        }

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $extra = [
            "playerId" => $sender->getUniqueId()->toString(),
            "playerName" => $sender->getName(),
            "world" => $sender->getWorld()->getFolderName(),
        ];

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_ACCEPT,
            $invitationCode,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Processing invitation...");

        return true;
    }

    /**
     * Handles the mute subcommand.
     *
     * Sends ChannelAction MUTE with the extras ChannelActionHandler.handleMute
     * reads: operatorId / console / operatorName / targetId / targetName /
     * duration. The duration is ALWAYS sent in SECONDS (parseDurationMs on the
     * backend multiplies extra["duration"] by 1000); see parseMuteDuration for
     * why a bare number still means minutes here.
     *
     * A mute needs a channel — handleMute rejects a blank one — so it is taken
     * from the third argument, or from the sender's active channel. When
     * neither resolves nothing is sent and the operator is asked to name one,
     * exactly like the Java MuteCommand.
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = target, args[1] = duration,
     *                          args[2] = channel)
     * @return bool True
     */
    private function handleMute(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.mute")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to mute players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED .
                "Usage: /nc mute <player> [duration] [channel]  (30s / 10m / 1h / 1d, or minutes)");
            return true;
        }

        $targetName = (string) $args[0];

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $channelId = (string) ($args[2] ?? "");
        if ($channelId === "") {
            $channelId = $this->resolveSenderChannel($sender);
        }
        if ($channelId === "") {
            $sender->sendMessage(TextFormat::RED .
                "Please specify a channel: /nc mute <player> <duration> <channel>");
            return true;
        }

        $durationArgument = isset($args[1]) ? (string) $args[1] : "10";
        $durationSeconds = self::parseMuteDuration($durationArgument);
        if ($durationSeconds <= 0) {
            $sender->sendMessage(TextFormat::RED . "Invalid duration: " . $durationArgument .
                " (use 30s, 10m, 1h, 1d, or a number of minutes)");
            return true;
        }

        $extra = $this->buildOperatorExtras($sender);
        $target = $this->findOnlinePlayer($targetName);
        if ($target !== null) {
            $extra["targetId"] = $target->getUniqueId()->toString();
        }
        $extra["targetName"] = $targetName;
        $extra["duration"] = (string) $durationSeconds;

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_MUTE,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Muted " . TextFormat::YELLOW . $targetName .
            TextFormat::GREEN . " in " . TextFormat::YELLOW . $channelId . TextFormat::GREEN . " for " .
            TextFormat::YELLOW . self::formatDuration($durationSeconds) . TextFormat::GREEN . ".");

        return true;
    }

    /**
     * Handles the unmute subcommand.
     *
     * Mirrors the backend's UNMUTE action, which — unlike BAN — REQUIRES a
     * channel id (ChannelActionHandler.handleUnmute rejects a blank channel).
     * The channel is therefore resolved from the third argument, or from the
     * sender's active channel, exactly like the Java MuteCommand resolves the
     * channel for MUTE. Never guessed otherwise.
     *
     * Permission mirrors /nc mute (novachat.admin.mute).
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = target, args[1] = channel)
     * @return bool True
     */
    private function handleUnmute(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.mute")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to unmute players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc unmute <player> [channel]");
            return true;
        }

        $targetName = (string) $args[0];
        $channelId = (string) ($args[1] ?? "");
        if ($channelId === "") {
            $channelId = $this->resolveSenderChannel($sender);
        }
        if ($channelId === "") {
            $sender->sendMessage(TextFormat::RED . "Please specify a channel: /nc unmute <player> <channel>");
            return true;
        }

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $extra = $this->buildOperatorExtras($sender);
        $target = $this->findOnlinePlayer($targetName);
        if ($target !== null) {
            $extra["targetId"] = $target->getUniqueId()->toString();
        }
        $extra["targetName"] = $targetName;

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_UNMUTE,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Unmuting " . TextFormat::YELLOW . $targetName .
            TextFormat::GREEN . " in " . TextFormat::YELLOW . $channelId . TextFormat::GREEN . "...");

        return true;
    }

    /**
     * Handles the kick subcommand.
     *
     * Sends ChannelAction KICK with the extras ChannelActionHandler.handleKick
     * reads: operatorId / console / operatorName / targetId / targetName. A
     * kick is inherently per-channel, so an omitted channel falls back to the
     * sender's active channel; if that still resolves to nothing the operator
     * must name one (handleKick rejects a blank channel).
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = target, args[1] = channel)
     * @return bool True
     */
    private function handleKick(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.kick")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to kick players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc kick <player> [channel]");
            return true;
        }

        $targetName = (string) $args[0];

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $channelId = (string) ($args[1] ?? "");
        if ($channelId === "") {
            $channelId = $this->resolveSenderChannel($sender);
        }
        if ($channelId === "") {
            $sender->sendMessage(TextFormat::RED .
                "Please specify a channel: /nc kick <player> <channel>");
            return true;
        }

        $extra = $this->buildOperatorExtras($sender);
        $target = $this->findOnlinePlayer($targetName);
        if ($target !== null) {
            $extra["targetId"] = $target->getUniqueId()->toString();
        }
        $extra["targetName"] = $targetName;

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_KICK,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::GREEN . "Kicking " . TextFormat::YELLOW . $targetName .
            TextFormat::GREEN . " from " . TextFormat::YELLOW . $channelId . TextFormat::GREEN . "...");

        return true;
    }

    /**
     * Handles the ban subcommand.
     *
     * Sends ChannelAction BAN with the extras the backend's
     * ChannelActionHandler.handleBan reads: operatorId / console /
     * operatorName / targetId / targetName / duration (seconds).
     *
     * Argument order is interchangeable like the Java BanCommand: the second
     * argument is a duration when it looks like one (30m, 1h, 90s, 1d), a
     * channel id otherwise; a third argument is the other one.
     *
     * NO channel fallback. An omitted channel is a NETWORK-WIDE ban: the
     * backend treats a null/blank channelId as the global scope. Falling back
     * to the sender's active channel would silently narrow a global ban to one
     * channel, which is a worse failure than making the operator name it.
     * /nc kick DOES infer the channel because a kick is inherently per-channel.
     *
     * Permission: novachat.ban.
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = target, then [duration] [channel])
     * @return bool True
     */
    private function handleBan(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.ban")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to ban players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc ban <player> [duration] [channel]");
            return true;
        }

        $targetName = (string) $args[0];

        // Both are optional. No channel means a network-wide ban (see above).
        $channelId = "";
        $durationArg = null;
        if (isset($args[1])) {
            if (self::isDuration($args[1])) {
                $durationArg = (string) $args[1];
                if (isset($args[2])) {
                    $channelId = (string) $args[2];
                }
            } else {
                $channelId = (string) $args[1];
                if (isset($args[2])) {
                    $durationArg = (string) $args[2];
                }
            }
        }

        $durationSeconds = 0;
        if ($durationArg !== null) {
            // "permanent" is accepted as an explicit no-duration spelling, as
            // offered by the Java tab-completion list.
            if (strtolower($durationArg) !== "permanent") {
                $durationSeconds = self::parseDuration($durationArg);
                if ($durationSeconds <= 0) {
                    $sender->sendMessage(TextFormat::RED . "Invalid duration: " . $durationArg .
                        " (use 30m, 1h, 1d, or leave it out for a permanent ban)");
                    return true;
                }
            }
        }

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $extra = $this->buildOperatorExtras($sender);
        $target = $this->findOnlinePlayer($targetName);
        if ($target !== null) {
            $extra["targetId"] = $target->getUniqueId()->toString();
        }
        $extra["targetName"] = $targetName;
        if ($durationSeconds > 0) {
            $extra["duration"] = (string) $durationSeconds;
        }

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_BAN,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $scope = $channelId !== "" ? $channelId : "all channels";
        $sender->sendMessage($prefix . TextFormat::GREEN . "Banning " . TextFormat::YELLOW . $targetName .
            TextFormat::GREEN . " from " . TextFormat::YELLOW . $scope . TextFormat::GREEN . "...");

        return true;
    }

    /**
     * Handles the unban subcommand.
     *
     * The target is usually offline — that is the whole point of a ban — so
     * the target id is resolved from a UUID argument first, then from an
     * online player, and the NAME is always sent as well so the backend can
     * resolve it across sub-servers (ChannelActionHandler.resolveTargetId
     * falls back to targetName over every known player state). The command
     * never requires the target to be online.
     *
     * An omitted channel lifts the network-wide ban, matching /nc ban.
     *
     * Permission: novachat.ban (same node as /nc ban, like the Java shells).
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = name|UUID, args[1] = channel)
     * @return bool True
     */
    private function handleUnban(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.ban")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to unban players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc unban <player|UUID> [channel]");
            return true;
        }

        $targetArg = (string) $args[0];
        $channelId = isset($args[1]) ? (string) $args[1] : "";

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $extra = $this->buildOperatorExtras($sender);
        $targetId = self::parseUuid($targetArg);
        if ($targetId === null) {
            $target = $this->findOnlinePlayer($targetArg);
            if ($target !== null) {
                $targetId = $target->getUniqueId()->toString();
            }
        }
        if ($targetId !== null) {
            $extra["targetId"] = $targetId;
        } else {
            // Offline and not a UUID: still send the name. The backend resolves
            // names across every connected sub-server, so this can succeed even
            // though this server could not resolve it.
            $prefix = $this->plugin->getConfigManager()->getPrefix();
            $sender->sendMessage($prefix . TextFormat::YELLOW . $targetArg .
                TextFormat::GRAY . " is not online here; sending the name so the backend can resolve it. " .
                "Use the UUID from the backend records if this fails.");
        }
        $extra["targetName"] = $targetArg;

        $networkClient->sendChannelAction(
            ChannelActionPacket::ACTION_UNBAN,
            $channelId,
            "",
            $extra
        );

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $scope = $channelId !== "" ? $channelId : "all channels";
        $sender->sendMessage($prefix . TextFormat::GREEN . "Lifting the ban on " . TextFormat::YELLOW .
            $targetArg . TextFormat::GREEN . " (" . TextFormat::YELLOW . $scope . TextFormat::GREEN . ")...");

        return true;
    }

    /**
     * Console sentinel UUID. The backend AdminActionHandler reads playerId as
     * the admin identity. Console has no Player UUID, so (mirroring the Java
     * AnnounceCommand console path) we send the nil UUID and tag the request
     * with extra["console"]="true" so the backend can attribute the action.
     */
    private const CONSOLE_SENTINEL_UUID = "00000000-0000-0000-0000-000000000000";

    /**
     * Builds the operator identity extras shared by every moderation action.
     *
     * Mirrors the Java BanCommand/MuteCommand shape: a player operator sends
     * its own UUID; console/RCON sends the all-zero sentinel plus
     * extra["console"]="true", which the backend treats as full authority
     * (ConsoleSentinel.isConsole bypasses moderation permission checks).
     *
     * @param CommandSender $sender The command sender
     * @return array<string, string> extras to merge into the action packet
     */
    private function buildOperatorExtras(CommandSender $sender): array {
        if ($sender instanceof Player) {
            $operatorId = $sender->getUniqueId()->toString();
            return [
                "operatorId" => $operatorId,
                // The response is asynchronous. NetworkClient keys its pending
                // map on this extra so the ChannelActionResponse can be
                // attributed to the operator; without it the outcome has no
                // addressee and the operator never sees success or failure.
                "playerId" => $operatorId,
                "operatorName" => $sender->getName(),
            ];
        }
        return [
            "operatorId" => self::CONSOLE_SENTINEL_UUID,
            "playerId" => self::CONSOLE_SENTINEL_UUID,
            "operatorName" => $sender->getName(),
            "console" => "true",
        ];
    }

    /**
     * Resolves an online player by exact name, or null.
     *
     * Used only to enrich a moderation action with a targetId; a null result is
     * never fatal — the backend resolves names across sub-servers.
     *
     * @param string $name The player name
     * @return Player|null The player, or null when offline
     */
    private function findOnlinePlayer(string $name): ?Player {
        return $this->plugin->getServer()->getPlayerExact($name);
    }

    /**
     * Resolves the channel a per-channel moderation action applies to.
     *
     * Only a player has an active channel; console/RCON has none, so this
     * returns "" and the caller must refuse the command rather than send a
     * blank channelId the backend rejects with NC-400.
     *
     * @param CommandSender $sender The command sender
     * @return string the active channel id, or "" when there is none
     */
    private function resolveSenderChannel(CommandSender $sender): string {
        if (!$sender instanceof Player) {
            return "";
        }
        $chatHandler = $this->plugin->getChatHandler();
        return $chatHandler !== null ? (string) $chatHandler->getPlayerChannel($sender) : "";
    }

    /**
     * Parses the /nc mute duration argument to SECONDS.
     *
     * A unit suffix (30s / 10m / 1h / 1d / 1w) is read with that unit, exactly
     * like the Java shells. A BARE number keeps meaning MINUTES — that is what
     * this command has always documented and what existing operators type —
     * and is converted to seconds before it goes on the wire, because
     * ChannelActionHandler.parseDurationMs reads extra["duration"] as seconds.
     * Sending the raw number is the bug this replaced: "5" reached the backend
     * as a five-second mute instead of the five minutes the operator asked for.
     *
     * @param string $duration the duration argument
     * @return int seconds, or -1 when the value is not a valid duration
     */
    private static function parseMuteDuration(string $duration): int {
        if ($duration === "") {
            return -1;
        }
        if (preg_match('/^(\d+)([smhdw]?)$/i', $duration, $matches) !== 1) {
            return -1;
        }
        $value = (int) $matches[1];
        if ($value <= 0) {
            return -1;
        }
        switch (strtolower($matches[2])) {
            case "s":
                return $value;
            case "m":
                return $value * 60;
            case "h":
                return $value * 3600;
            case "d":
                return $value * 86400;
            case "w":
                return $value * 604800;
            default:
                // No suffix: minutes, the command's historical UI unit.
                return $value * 60;
        }
    }

    /**
     * @param int $seconds a duration in seconds
     * @return string a compact human-readable form ("5m", "1h", "90s")
     */
    private static function formatDuration(int $seconds): string {
        foreach (["w" => 604800, "d" => 86400, "h" => 3600, "m" => 60] as $unit => $size) {
            if ($seconds % $size === 0 && $seconds >= $size) {
                return ($seconds / $size) . $unit;
            }
        }
        return $seconds . "s";
    }

    /**
     * @param string $value a command argument
     * @return bool whether it looks like a duration rather than a channel id
     */
    private static function isDuration(string $value): bool {
        return preg_match('/^\d+[smhdw]?$/', $value) === 1
            || strtolower($value) === "permanent";
    }

    /**
     * Parses a duration ("90", "30s", "10m", "2h", "1d", "1w") to seconds.
     *
     * Mirrors the Java AbstractSubCommand.parseDuration contract: a bare
     * number is seconds, not minutes. /nc mute keeps its own historical
     * minutes-for-bare-numbers rule in parseMuteDuration; ban/unban use this
     * one.
     *
     * @param string $duration the duration string
     * @return int seconds, or -1 when the value is not a valid duration
     */
    private static function parseDuration(string $duration): int {
        if ($duration === "") {
            return -1;
        }
        if (preg_match('/^(\d+)([smhdw]?)$/i', $duration, $matches) !== 1) {
            return -1;
        }
        $value = (int) $matches[1];
        $unit = strtolower($matches[2]);
        switch ($unit) {
            case "s":
                return $value;
            case "m":
                return $value * 60;
            case "h":
                return $value * 3600;
            case "d":
                return $value * 86400;
            case "w":
                return $value * 604800;
            default:
                return $value;
        }
    }

    /**
     * @param string $value a command argument
     * @return string|null the UUID in canonical form, or null when it is not one
     */
    private static function parseUuid(string $value): ?string {
        if (preg_match('/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/', $value) !== 1) {
            return null;
        }
        return strtolower($value);
    }

    /**
     * Handles the auth subcommand.
     *
     * Sends an AdminAction AUTH packet to the backend carrying the SHA-256
     * (lowercase hex) of the typed password. The backend is the SOLE arbiter
     * of super-admin session state — this client never tracks auth locally
     * (mirrors the bukkit AuthCommand posture). Result is shown only when the
     * backend's AdminActionResponse arrives; on send we show progress.
     *
     * Player-only: console cannot authenticate as a super admin (no player
     * UUID to bind the session to).
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = password)
     * @return bool True
     */
    private function handleAuth(CommandSender $sender, array $args): bool {
        if (!$sender instanceof Player) {
            $sender->sendMessage(TextFormat::RED . "This command can only be used by players.");
            return true;
        }

        if (count($args) === 0) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc auth <password>");
            return true;
        }

        $password = (string) $args[0];
        // PHP hash() returns lowercase hex by default — matches Java's
        // Integer.toHexString(0xff & b) zero-padded lowercase output.
        $passwordHash = hash("sha256", $password);

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        $packet = new AdminActionPacket();
        $packet->action = AdminActionPacket::ACTION_AUTH;
        $packet->playerId = $sender->getUniqueId()->toString();
        $packet->passwordHash = $passwordHash;
        $packet->target = "";
        $packet->extra = [
            "playerName" => $sender->getName(),
        ];

        // Register the requestId -> player UUID correlation before send so the
        // eventual AdminActionResponse can be routed back to this player.
        $networkClient->sendAdminAction($packet, $sender->getUniqueId()->toString());

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::YELLOW . "Authenticating...");
        return true;
    }

    /**
     * Handles the announce subcommand.
     *
     * Sends an AdminAction STATUS packet with extra["type"]="ANNOUNCE" to the
     * backend (the backend handleStatus gate checks super-admin session, then
     * dispatches to handleAnnounce which reads target=channelId + content +
     * operatorName). The obsolete 0x0A AnnouncementPacket is deprecated and no
     * longer sent. Result is shown only when the backend's
     * AdminActionResponse arrives; on send we show progress.
     *
     * Console is allowed (playerId = sentinel UUID + extra["console"]="true"),
     * mirroring the Java AnnounceCommand console path.
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = channelId, args[1+] = message)
     * @return bool True
     */
    private function handleAnnounce(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.admin.announce")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to send announcements.");
            return true;
        }

        if (count($args) < 2) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc announce <channel> <message>");
            return true;
        }

        $channelId = (string) $args[0];
        $content = implode(" ", array_slice($args, 1));

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        if ($sender instanceof Player) {
            $playerId = $sender->getUniqueId()->toString();
            $extra = [
                "type" => "ANNOUNCE",
                "operatorName" => $sender->getName(),
                "content" => $content,
            ];
        } else {
            // Console: no Player UUID — use the sentinel and tag as console so
            // the backend attributes the announcement to the console operator.
            $playerId = self::CONSOLE_SENTINEL_UUID;
            $extra = [
                "type" => "ANNOUNCE",
                "operatorName" => $sender->getName(),
                "content" => $content,
                "console" => "true",
            ];
        }

        $packet = new AdminActionPacket();
        $packet->action = AdminActionPacket::ACTION_STATUS;
        $packet->playerId = $playerId;
        $packet->passwordHash = "";
        $packet->target = $channelId;
        $packet->extra = $extra;

        // Register requestId -> playerId correlation before send.
        $networkClient->sendAdminAction($packet, $playerId);

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::YELLOW . "Sending announcement to channel " . $channelId . "...");
        return true;
    }

    /**
     * Handles the title subcommand.
     *
     * Sends an AdminAction STATUS packet with extra["type"]="TITLE", which the
     * backend's AdminActionHandler.handleStatus dispatches to handleTitle
     * (reads target=channelId plus extra title/subtitle). Same transport and
     * same super-admin gate as /nc announce.
     *
     * Console is allowed, using the sentinel UUID plus extra["console"]="true".
     *
     * @param CommandSender $sender Command sender
     * @param array $args Arguments (args[0] = channelId, args[1] = title,
     *                          args[2+] = subtitle)
     * @return bool True
     */
    private function handleTitle(CommandSender $sender, array $args): bool {
        if (!$sender->hasPermission("novachat.title")) {
            $sender->sendMessage(TextFormat::RED . "You don't have permission to send titles.");
            return true;
        }

        if (count($args) < 2) {
            $sender->sendMessage(TextFormat::RED . "Usage: /nc title <channel> <title> [subtitle]");
            return true;
        }

        $channelId = (string) $args[0];
        $title = (string) $args[1];
        $subtitle = count($args) > 2 ? implode(" ", array_slice($args, 2)) : "";

        $networkClient = $this->plugin->getNetworkClient();
        if ($networkClient === null || !$networkClient->isAuthenticated()) {
            $sender->sendMessage(TextFormat::RED . "Not connected to backend server.");
            return true;
        }

        if ($sender instanceof Player) {
            $playerId = $sender->getUniqueId()->toString();
            $extra = [
                "type" => "TITLE",
                "operatorName" => $sender->getName(),
                "title" => $title,
                "subtitle" => $subtitle,
            ];
        } else {
            // Console: sentinel UUID + console tag, same as /nc announce.
            $playerId = self::CONSOLE_SENTINEL_UUID;
            $extra = [
                "type" => "TITLE",
                "operatorName" => $sender->getName(),
                "title" => $title,
                "subtitle" => $subtitle,
                "console" => "true",
            ];
        }

        $packet = new AdminActionPacket();
        $packet->action = AdminActionPacket::ACTION_STATUS;
        $packet->playerId = $playerId;
        $packet->passwordHash = "";
        $packet->target = $channelId;
        $packet->extra = $extra;

        // Register requestId -> playerId correlation before send.
        $networkClient->sendAdminAction($packet, $playerId);

        $prefix = $this->plugin->getConfigManager()->getPrefix();
        $sender->sendMessage($prefix . TextFormat::YELLOW . "Sending title to channel " . $channelId . "...");
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
