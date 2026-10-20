<?php

declare(strict_types=1);

namespace NovaChat\Tests\Command;

use NovaChat\Chat\ChatHandler;
use NovaChat\Command\NovaChatCommand;
use NovaChat\Config\ConfigManager;
use NovaChat\Network\NetworkClient;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\AdminActionPacket;
use NovaChat\Protocol\ChannelActionPacket;
use PHPUnit\Framework\TestCase;
use pocketmine\command\CommandSender;
use pocketmine\lang\Translatable;
use pocketmine\permission\Permission;
use pocketmine\permission\PermissionManager;
use pocketmine\player\Player;
use pocketmine\plugin\PluginBase;
use pocketmine\Server;
use Ramsey\Uuid\Uuid;
use Ramsey\Uuid\UuidInterface;
use ReflectionProperty;

/**
 * No-op logger for fake Player instances. PocketMine's Entity::__destruct
 * calls $logger->debug(); a Player that was never constructed through the
 * real session path has no logger, and the real MainLogger needs pmmpthread
 * classes that are not installed in the composer test environment.
 */
final class TestLogger implements \Logger {
    public function emergency($message) {}
    public function alert($message) {}
    public function critical($message) {}
    public function error($message) {}
    public function warning($message) {}
    public function notice($message) {}
    public function info($message) {}
    public function debug($message) {}
    public function log($level, $message) {}
    public function logException(\Throwable $e, $trace = null) {}
}

/**
 * Minimal Player double for command-level tests.
 *
 * PocketMine's Player cannot be stubbed with createStub(): its destructor runs
 * Entity::__destruct -> close() -> onDispose(), which touches typed properties
 * (cursorInventory / craftingGrid / location) that only the real session
 * constructor initialises, and the process dies with a fatal error at
 * shutdown. Subclassing with $closed = true makes close() a no-op, and a no-op
 * logger satisfies the destructor.
 */
final class FakePlayer extends Player {
    /** @var list<string> */
    public array $messages = [];
    private string $fakeName;
    private string $fakeUuid;
    /** @var list<string> */
    private array $denied;

    /**
     * @param list<string> $denied permission names this player lacks
     */
    public function __construct(
        string $name = "Alice",
        string $uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        array $denied = []
    ) {
        $this->fakeName = $name;
        $this->fakeUuid = $uuid;
        $this->denied = $denied;
        $this->closed = true;
        $this->logger = new TestLogger();
    }

    public function getName(): string {
        return $this->fakeName;
    }

    public function getUniqueId(): UuidInterface {
        return Uuid::fromString($this->fakeUuid);
    }

    public function hasPermission(Permission|string $name): bool {
        $name = $name instanceof Permission ? $name->getName() : $name;
        return !in_array($name, $this->denied, true);
    }

    public function sendMessage(Translatable|string $message): void {
        $this->messages[] = (string) $message;
    }

    /** @return string the concatenated messages, for assertions */
    public function text(): string {
        return implode("\n", $this->messages);
    }
}

/**
 * Minimal Server double: the command only needs getPlayerExact().
 *
 * The parent constructor is skipped deliberately — it builds the whole server
 * (config, scheduler, network threads), which this test does not need and
 * which requires pmmpthread classes absent from the composer test install.
 */
final class FakeServer extends Server {
    /** @var array<string, Player> */
    public array $players = [];

    public function __construct() {}

    public function getPlayerExact(string $name): ?Player {
        return $this->players[strtolower($name)] ?? null;
    }
}

/**
 * Captures the packets the command hands to the network client.
 *
 * Extends NetworkClient so it satisfies the type the command expects, and
 * overrides every method the command can reach — the parent's typed
 * properties are never initialised and must not be touched.
 */
final class RecordingNetworkClient extends NetworkClient {
    /** @var list<array{action: int, channelId: string, password: string, extra: array<string, string>}> */
    public array $channelActions = [];
    /** @var list<array{packet: AdminActionPacket, playerUuid: string}> */
    public array $adminActions = [];

    public function __construct() {}

    public function isConnected(): bool {
        return true;
    }

    public function isAuthenticated(): bool {
        return true;
    }

    public function sendChannelAction(int $action, string $channelId, string $password = "", array $extra = []): void {
        $this->channelActions[] = [
            "action" => $action,
            "channelId" => $channelId,
            "password" => $password,
            "extra" => $extra,
        ];
    }

    public function sendAdminAction(AdminActionPacket $packet, string $playerUuid): void {
        $this->adminActions[] = ["packet" => $packet, "playerUuid" => $playerUuid];
    }
}

/**
 * Moderation and admin command tests for NovaChat-PMMP.
 *
 * Covers /nc ban, /nc unban, /nc unmute and /nc title: the action constant and
 * the extras each packet carries, console vs player identity, permission
 * rejection, argument validation, and — most importantly — that an omitted
 * ban channel stays empty (network-wide) instead of falling back to the
 * sender's active channel.
 *
 * Fixtures mirror the Java ban/unban/title shells in
 * NovaChat/Plugin/{bukkit,folia}/src/main/java/com/nova/chat/*\/command/.
 */
final class ModerationCommandTest extends TestCase {

    private const CONSOLE_SENTINEL = "00000000-0000-0000-0000-000000000000";

    /** @var array<string, true> permissions registered with PocketMine this run */
    private static array $registeredPermissions = [];

    protected function setUp(): void {
        // Command::setPermission() rejects a name that is not registered in
        // PermissionManager, so every node the command references must exist.
        foreach ([
            "novachat.use",
            "novachat.join",
            "novachat.leave",
            "novachat.create",
            "novachat.invite",
            "novachat.admin",
            "novachat.admin.mute",
            "novachat.admin.kick",
            "novachat.admin.announce",
            "novachat.admin.reload",
            "novachat.ban",
            "novachat.title",
            "novachat.debug",
        ] as $name) {
            if (!isset(self::$registeredPermissions[$name])) {
                PermissionManager::getInstance()->addPermission(new Permission($name));
                self::$registeredPermissions[$name] = true;
            }
        }
    }

    private function config(): ConfigManager {
        return new ConfigManager([
            "config-version" => 1,
            "backend" => [
                "host" => "127.0.0.1",
                "port" => 18888,
                "username" => "PmmpServer",
                "password" => "secret",
                "server-version" => "5.0.0",
                "reconnect-delay" => 5,
            ],
            "chat" => ["replace_vanilla" => false, "default_channel" => "local"],
            "format" => [
                "prefix" => "[NC] ",
                "error" => "error: {message}",
                "success" => "success: {message}",
                "default" => "default",
                "channels" => ["local" => "local"],
            ],
            "debug" => false,
        ]);
    }

    /**
     * Builds the command wired to a recording client.
     *
     * @param ChatHandler|null $chatHandler handler returned by getChatHandler();
     *        a stub reporting "staff" as the sender's active channel exercises
     *        the ban-vs-kick channel-fallback distinction
     */
    private function makeCommand(
        RecordingNetworkClient $client,
        ?FakeServer $server = null,
        ?ChatHandler $chatHandler = null
    ): NovaChatCommand {
        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method("getConfigManager")->willReturn($this->config());
        $plugin->method("getNetworkClient")->willReturn($client);
        $plugin->method("getChatHandler")->willReturn($chatHandler);
        $plugin->method("debug")->willReturnCallback(static function (): void {});

        // PluginBase::getServer() is final and reads a private promoted
        // property, so it cannot be stubbed — inject the double directly.
        $property = new ReflectionProperty(PluginBase::class, "server");
        $property->setValue($plugin, $server ?? new FakeServer());

        return new NovaChatCommand($plugin);
    }

    /**
     * A ChatHandler stub whose active channel for everyone is "staff".
     */
    private function staleChannelHandler(): ChatHandler {
        $handler = $this->createStub(ChatHandler::class);
        $handler->method("getPlayerChannel")->willReturn("staff");
        return $handler;
    }

    /**
     * Builds a console sender. CommandSender is an interface, so PHPUnit can
     * mock it; the command's only requirement is that it is not a Player.
     *
     * @param list<string> $denied permission names this sender lacks
     * @param list<string>|null $messages receives every sent line
     */
    private function consoleSender(array $denied = [], ?array &$messages = null): CommandSender {
        $messages = [];
        $sender = $this->createMock(CommandSender::class);
        $sender->method("getName")->willReturn("CONSOLE");
        $sender->method("hasPermission")->willReturnCallback(
            static function (Permission|string $name) use ($denied): bool {
                $name = $name instanceof Permission ? $name->getName() : $name;
                return !in_array($name, $denied, true);
            }
        );
        $sender->method("sendMessage")->willReturnCallback(
            static function (Translatable|string $message) use (&$messages): void {
                $messages[] = (string) $message;
            }
        );
        return $sender;
    }

    // ==================================================================
    // ban — action constant + extras
    // ==================================================================

    public function testBanFromConsoleSendsSentinelAndConsoleTag(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["ban", "Bob"]);

        self::assertCount(1, $client->channelActions);
        $action = $client->channelActions[0];
        self::assertSame(ChannelActionPacket::ACTION_BAN, $action["action"]);
        self::assertSame(9, $action["action"], "the wire id must stay BAN=9");
        self::assertSame(self::CONSOLE_SENTINEL, $action["extra"]["operatorId"]);
        self::assertSame("true", $action["extra"]["console"]);
        self::assertSame("CONSOLE", $action["extra"]["operatorName"]);
        self::assertSame("Bob", $action["extra"]["targetName"]);
    }

    public function testBanFromPlayerUsesPlayerUuidAndNoConsoleTag(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $player = new FakePlayer("Alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        $command->execute($player, "nc", ["ban", "Bob", "30m"]);

        self::assertCount(1, $client->channelActions);
        $extra = $client->channelActions[0]["extra"];
        self::assertSame("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", $extra["operatorId"]);
        self::assertArrayNotHasKey("console", $extra);
        self::assertSame("Alice", $extra["operatorName"]);
        // 30m is 1800 seconds, matching the Java parseDuration contract.
        self::assertSame("1800", $extra["duration"]);
    }

    /**
     * The load-bearing assertion: an omitted ban channel must stay empty even
     * though the sender HAS an active channel. Blank means network-wide on the
     * backend; silently narrowing it to one channel is the failure this guards.
     */
    public function testBanOmittingChannelDoesNotFallBackToActiveChannel(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, null, $this->staleChannelHandler());
        $player = new FakePlayer("Alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        $command->execute($player, "nc", ["ban", "Bob"]);

        self::assertCount(1, $client->channelActions);
        self::assertSame(
            "",
            $client->channelActions[0]["channelId"],
            "an omitted ban channel must stay blank (network-wide), not fall back to the sender's channel"
        );
    }

    public function testBanWithNamedChannelSendsThatChannel(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["ban", "Bob", "staff"]);

        self::assertSame("staff", $client->channelActions[0]["channelId"]);
        self::assertArrayNotHasKey("duration", $client->channelActions[0]["extra"]);
    }

    public function testBanAcceptsChannelBeforeDuration(): void {
        // Java accepts either order: /nc ban <player> [duration] [channel] and
        // /nc ban <player> [channel] [duration].
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["ban", "Bob", "staff", "1h"]);

        $action = $client->channelActions[0];
        self::assertSame("staff", $action["channelId"]);
        self::assertSame("3600", $action["extra"]["duration"]);
    }

    public function testBanResolvesOnlineTargetId(): void {
        $server = new FakeServer();
        $server->players["bob"] = new FakePlayer("Bob", "11111111-1111-1111-1111-111111111111");
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, $server);

        $command->execute($this->consoleSender(), "nc", ["ban", "Bob"]);

        self::assertSame(
            "11111111-1111-1111-1111-111111111111",
            $client->channelActions[0]["extra"]["targetId"]
        );
    }

    public function testBanOfflineTargetOmitsTargetIdButSendsName(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["ban", "OfflineGuy"]);

        $extra = $client->channelActions[0]["extra"];
        self::assertArrayNotHasKey("targetId", $extra);
        self::assertSame("OfflineGuy", $extra["targetName"]);
    }

    // ==================================================================
    // ban — validation and permission
    // ==================================================================

    public function testBanWithoutArgumentsSendsUsageAndNoPacket(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender([], $messages);

        $command->execute($sender, "nc", ["ban"]);

        self::assertSame([], $client->channelActions);
        self::assertStringContainsString("/nc ban", implode("\n", $messages));
    }

    public function testBanRejectsInvalidDuration(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender([], $messages);

        // "10" is a duration (seconds), so "1h30m" is read as the channel —
        // but the duration itself must be rejected before anything is sent.
        $command->execute($sender, "nc", ["ban", "Bob", "1h30m", "staff"]);

        self::assertSame([], $client->channelActions, "an invalid duration must send nothing");
        self::assertStringContainsString("Invalid duration", implode("\n", $messages));
    }

    public function testBanPermissionDeniedSendsNothing(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        // Passes the generic novachat.use gate, lacks novachat.ban — so this
        // exercises the SUBCOMMAND gate, not the top-level one.
        $sender = $this->consoleSender(["novachat.ban"], $messages);

        $command->execute($sender, "nc", ["ban", "Bob"]);

        self::assertSame([], $client->channelActions);
        self::assertStringContainsString("permission", implode("\n", $messages));
    }

    public function testBanPermissionGateOnlyAcceptsNovachatBan(): void {
        // Holding the old admin.mute/kick nodes must not grant ban.
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender(["novachat.ban"], $messages);

        $command->execute($sender, "nc", ["ban", "Bob", "staff"]);

        self::assertSame([], $client->channelActions);
    }

    public function testBanAcceptsPermanentKeywordAsNoDuration(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["ban", "Bob", "permanent"]);

        $action = $client->channelActions[0];
        self::assertSame("", $action["channelId"]);
        self::assertArrayNotHasKey("duration", $action["extra"]);
    }

    // ==================================================================
    // unban
    // ==================================================================

    public function testUnbanByUuidSendsTargetIdForOfflinePlayer(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $uuid = "deadbeef-0000-1111-2222-333344445555";

        $command->execute($this->consoleSender(), "nc", ["unban", $uuid]);

        $action = $client->channelActions[0];
        self::assertSame(ChannelActionPacket::ACTION_UNBAN, $action["action"]);
        self::assertSame(10, $action["action"], "the wire id must stay UNBAN=10");
        self::assertSame($uuid, $action["extra"]["targetId"]);
        self::assertSame($uuid, $action["extra"]["targetName"]);
        self::assertSame("", $action["channelId"], "an omitted channel lifts the global ban");
    }

    public function testUnbanNormalisesUppercaseUuidWithNoOnlinePlayers(): void {
        // A ban exists precisely so the target is away; resolution must not
        // depend on them being online.
        $server = new FakeServer();
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, $server);

        $command->execute(
            $this->consoleSender(),
            "nc",
            ["unban", "DEADBEEF-0000-1111-2222-333344445555"]
        );

        self::assertCount(1, $client->channelActions);
        self::assertSame(
            "deadbeef-0000-1111-2222-333344445555",
            $client->channelActions[0]["extra"]["targetId"]
        );
    }

    public function testUnbanByNameStillSendsNameWhenPlayerOffline(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender([], $messages);

        $command->execute($sender, "nc", ["unban", "Bob", "staff"]);

        $action = $client->channelActions[0];
        self::assertSame("staff", $action["channelId"]);
        self::assertSame("Bob", $action["extra"]["targetName"]);
        self::assertArrayNotHasKey("targetId", $action["extra"]);
        self::assertStringContainsString("not online", implode("\n", $messages));
    }

    public function testUnbanResolvesOnlinePlayerByName(): void {
        $server = new FakeServer();
        $server->players["bob"] = new FakePlayer("Bob", "11111111-1111-1111-1111-111111111111");
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, $server);

        $command->execute($this->consoleSender(), "nc", ["unban", "Bob"]);

        self::assertSame(
            "11111111-1111-1111-1111-111111111111",
            $client->channelActions[0]["extra"]["targetId"]
        );
    }

    public function testUnbanPermissionDeniedSendsNothing(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender(["novachat.ban"]);

        $command->execute($sender, "nc", ["unban", "Bob"]);

        self::assertSame([], $client->channelActions);
    }

    // ==================================================================
    // unmute
    // ==================================================================

    public function testUnmuteConsoleSendsConstantChannelAndExtras(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["unmute", "Bob", "staff"]);

        $action = $client->channelActions[0];
        self::assertSame(ChannelActionPacket::ACTION_UNMUTE, $action["action"]);
        self::assertSame(8, $action["action"]);
        self::assertSame("staff", $action["channelId"]);
        self::assertSame(self::CONSOLE_SENTINEL, $action["extra"]["operatorId"]);
        self::assertSame("true", $action["extra"]["console"]);
        self::assertSame("Bob", $action["extra"]["targetName"]);
    }

    public function testUnmuteInfersSenderChannelWhenOmitted(): void {
        // Unlike ban, unmute REQUIRES a channel on the backend, so inferring
        // the sender's active channel (as /nc kick does) is correct here.
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, null, $this->staleChannelHandler());
        $player = new FakePlayer("Alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        $command->execute($player, "nc", ["unmute", "Bob"]);

        self::assertSame("staff", $client->channelActions[0]["channelId"]);
    }

    public function testUnmuteFromConsoleWithoutChannelIsRejected(): void {
        // Console has no active channel, so there is nothing to infer.
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender([], $messages);

        $command->execute($sender, "nc", ["unmute", "Bob"]);

        self::assertSame([], $client->channelActions);
        self::assertStringContainsString("channel", implode("\n", $messages));
    }

    // ==================================================================
    // title
    // ==================================================================

    public function testTitleSendsStatusTransportWithTitleExtras(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $player = new FakePlayer("Alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        $command->execute($player, "nc", ["title", "global", "Hello", "world", "sub"]);

        self::assertCount(1, $client->adminActions);
        $packet = $client->adminActions[0]["packet"];
        self::assertSame(AdminActionPacket::ACTION_STATUS, $packet->action);
        self::assertSame("global", $packet->target);
        self::assertSame("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", $packet->playerId);
        self::assertSame("TITLE", $packet->extra["type"]);
        self::assertSame("Hello", $packet->extra["title"]);
        self::assertSame("world sub", $packet->extra["subtitle"]);
        self::assertArrayNotHasKey("console", $packet->extra);
    }

    public function testTitleFromConsoleUsesSentinelAndConsoleTag(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["title", "global", "Hello"]);

        $packet = $client->adminActions[0]["packet"];
        self::assertSame(self::CONSOLE_SENTINEL, $packet->playerId);
        self::assertSame("true", $packet->extra["console"]);
        self::assertSame("", $packet->extra["subtitle"]);
        // The response must be routable back to the console.
        self::assertSame(self::CONSOLE_SENTINEL, $client->adminActions[0]["playerUuid"]);
    }

    public function testTitleRequiresTwoArguments(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender([], $messages);

        $command->execute($sender, "nc", ["title", "global"]);

        self::assertSame([], $client->adminActions);
        self::assertStringContainsString("/nc title", implode("\n", $messages));
    }

    public function testTitlePermissionDeniedSendsNothing(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);
        $sender = $this->consoleSender(["novachat.title"]);

        $command->execute($sender, "nc", ["title", "global", "Hello"]);

        self::assertSame([], $client->adminActions);
    }

    // ==================================================================
    // Existing behaviour must not regress
    // ==================================================================

    public function testExistingMuteStillSendsMinutesVerbatim(): void {
        // /nc mute is deliberately left untouched: its argument is MINUTES and
        // is passed through unchanged (unlike /nc ban, which converts to
        // seconds like the Java shell).
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client);

        $command->execute($this->consoleSender(), "nc", ["mute", "Bob", "5"]);

        $action = $client->channelActions[0];
        self::assertSame(ChannelActionPacket::ACTION_MUTE, $action["action"]);
        self::assertSame("5", $action["extra"]["duration"]);
    }

    public function testExistingKickStillInfersSenderChannel(): void {
        $client = new RecordingNetworkClient();
        $command = $this->makeCommand($client, null, $this->staleChannelHandler());
        $player = new FakePlayer("Alice", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        $command->execute($player, "nc", ["kick", "Bob"]);

        $action = $client->channelActions[0];
        self::assertSame(ChannelActionPacket::ACTION_KICK, $action["action"]);
        self::assertSame("staff", $action["channelId"]);
    }
}
