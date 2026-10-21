<?php

declare(strict_types=1);

namespace NovaChat\Tests\Command;

use NovaChat\Chat\ChatHandler;
use NovaChat\Command\NovaChatCommand;
use NovaChat\Config\ConfigManager;
use NovaChat\Network\NetworkClient;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChannelActionResponsePacket;
use PHPUnit\Framework\TestCase;
use pocketmine\command\CommandSender;
use pocketmine\player\Player;
use pocketmine\permission\Permission;
use pocketmine\permission\PermissionManager;
use pocketmine\Server;
use ReflectionMethod;
use ReflectionProperty;

/**
 * Server double that reports a fixed online-player list.
 *
 * ModerationResponseTest::FakeServer — distinct from the one in
 * ModerationCommandTest, which only needs getPlayerExact().
 */
final class ResponseTestServer extends Server {
    /** @var list<Player> */
    public array $online = [];

    public function __construct() {}

    public function getOnlinePlayers(): array {
        return $this->online;
    }

    public function getPlayerExact(string $name): ?Player {
        return null;
    }
}

/**
 * Player double that records what it is sent.
 *
 * Subclassed (not stubbed) for the reason documented on ModerationCommandTest's
 * FakePlayer: Entity::__destruct runs close() -> onDispose(), which touches
 * typed properties only the real session constructor initialises. $closed = true
 * makes close() a no-op and a no-op logger satisfies the destructor.
 */
final class ResponseTestPlayer extends Player {
    private string $fakeName;
    private string $fakeUuid;
    /** @var list<string> */
    public array $messages = [];

    public function __construct(string $name, string $uuid) {
        $this->fakeName = $name;
        $this->fakeUuid = $uuid;
        $this->closed = true;
        $this->logger = new TestLogger();
    }

    public function getName(): string {
        return $this->fakeName;
    }

    public function getUniqueId(): \Ramsey\Uuid\UuidInterface {
        return \Ramsey\Uuid\Uuid::fromString($this->fakeUuid);
    }

    public function hasPermission(Permission|string $name): bool {
        return true;
    }

    public function sendMessage(\pocketmine\lang\Translatable|string $message): void {
        $this->messages[] = (string) $message;
    }
}

/**
 * Operator-facing outcome of the asynchronous ChannelActionResponse (PMMP).
 *
 * /nc mute, kick, ban and unban print an optimistic "...ing" line when they
 * send and return. The per-action result arrives later on 0x05 and carries
 * only the frame-level request id, so the client must remember which player
 * issued the request to be able to report back at all.
 *
 * Two breaks are pinned here:
 *  1. sendChannelAction never recorded the request id, so a response had no
 *     addressee and was dropped.
 *  2. handleChannelActionResponse only notified the TARGET of a kick/mute, so
 *     even a correlated response told the operator nothing.
 *
 * The response path is driven through the real NetworkClient methods; only the
 * transport (sendPacket), the server's player list and the logger are doubled.
 */
final class ModerationResponseTest extends TestCase {

    private const CONSOLE_SENTINEL = "00000000-0000-0000-0000-000000000000";

    /** @var array<string, true> permissions registered with PocketMine this run */
    private static array $registeredPermissions = [];

    /** @var list<string> lines delivered to the operator */
    private array $playerMessages = [];

    /** @var list<string> lines written to the server log */
    private array $consoleLog = [];

    protected function setUp(): void {
        $this->playerMessages = [];
        $this->consoleLog = [];

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

    /**
     * Minimal valid config (mirrors ModerationCommandTest::config).
     */
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
     * A ChatHandler double: fixed locale, no active channel, and the real
     * target-side notification path stubbed (it needs a live server).
     */
    private function chatHandler(): ChatHandler {
        $handler = $this->createStub(ChatHandler::class);
        $handler->method("getPlayerLocale")->willReturn("en_US");
        $handler->method("getPlayerChannel")->willReturn("");
        return $handler;
    }

    /**
     * Builds a NetworkClient over a server whose online list is `$server`.
     *
     * sendPacket is doubled so nothing touches a socket, while the real
     * sendChannelAction correlation logic still runs. The authenticated flag is
     * set directly: only a completed handshake sets it in production, and
     * sendChannelAction returns early when it is false.
     */
    private function makeClient(Server $server): NetworkClient {
        $configManager = $this->config();

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method("getConfigManager")->willReturn($configManager);
        $plugin->method("getChatHandler")->willReturn($this->chatHandler());
        $plugin->method("debug")->willReturnCallback(static function (): void {});

        // PluginBase::getLogger() is typed \AttachableLogger (a pure interface,
        // so it stubs cleanly; the real MainLogger needs pmmpthread).
        $logger = $this->createStub(\AttachableLogger::class);
        $logger->method("info")->willReturnCallback(function (string $m): void {
            $this->consoleLog[] = $m;
        });
        $plugin->method("getLogger")->willReturn($logger);

        // PluginBase::getServer() is final over a private promoted property.
        $property = new ReflectionProperty(\pocketmine\plugin\PluginBase::class, "server");
        $property->setValue($plugin, $server);

        $client = $this->getMockBuilder(NetworkClient::class)
            ->setConstructorArgs([$plugin, $configManager])
            ->onlyMethods(["sendPacket", "isAuthenticated"])
            ->getMock();
        $client->method("sendPacket")->willReturn(true);
        $client->method("isAuthenticated")->willReturn(true);

        // sendChannelAction reads the private flag, not isAuthenticated().
        $authenticated = new ReflectionProperty(NetworkClient::class, "authenticated");
        $authenticated->setValue($client, true);

        return $client;
    }

    /**
     * A server with no players online.
     */
    private function emptyServer(): ResponseTestServer {
        return new ResponseTestServer();
    }

    /**
     * A server reporting exactly one online operator, whose messages the test
     * captures.
     */
    private function serverWithOperator(string $uuid): ResponseTestServer {
        $server = new ResponseTestServer();
        $player = new ResponseTestPlayer("Alice", $uuid);
        $server->online = [$player];
        return $server;
    }

    /**
     * The client's pending requestId -> player UUID map.
     *
     * @return array<string, string>
     */
    private function pending(NetworkClient $client): array {
        $property = new ReflectionProperty(NetworkClient::class, "pendingChannelActions");
        return $property->getValue($client);
    }

    /**
     * Invokes the real response handler.
     */
    private function deliver(NetworkClient $client, ChannelActionResponsePacket $response): void {
        $method = new ReflectionMethod(NetworkClient::class, "handleChannelActionResponse");
        $method->invoke($client, $response);
    }

    /**
     * Builds a response echoing `$requestId`.
     */
    private function response(int $action, bool $success, string $requestId, string $errorCode = ""): ChannelActionResponsePacket {
        $packet = new ChannelActionResponsePacket();
        $packet->setRequestId($requestId);
        $packet->action = $action;
        $packet->success = $success;
        $packet->channelId = "global";
        $packet->errorCode = $errorCode;
        $packet->message = "";
        $packet->extra = [];
        return $packet;
    }

    /**
     * Registers a send for `$uuid` and returns the request id to echo back.
     */
    private function trackSend(NetworkClient $client, int $action, string $uuid): string {
        $client->sendChannelAction($action, "global", "", ["playerId" => $uuid]);
        $pending = $this->pending($client);
        self::assertNotSame([], $pending, "sendChannelAction must record the origin");
        return (string) array_key_first($pending);
    }

    /**
     * The lines the operator was sent, read back off the server double.
     *
     * @return list<string>
     */
    private function operatorMessages(Server $server): array {
        $lines = [];
        foreach ($server->getOnlinePlayers() as $player) {
            if ($player instanceof ResponseTestPlayer) {
                foreach ($player->messages as $m) {
                    $lines[] = $m;
                }
            }
        }
        return $lines;
    }

    // ==================== Correlation ====================

    public function testSendChannelActionRecordsTheOperatorForTheResponse(): void {
        $client = $this->makeClient($this->emptyServer());
        $uuid = "11111111-1111-1111-1111-111111111111";

        $requestId = $this->trackSend($client, ChannelActionPacket::ACTION_MUTE, $uuid);

        self::assertSame($uuid, $this->pending($client)[$requestId]);
    }

    public function testSendWithoutAnOperatorIsNotTracked(): void {
        $client = $this->makeClient($this->emptyServer());

        // A caller that supplies no identity has nothing to report to, so the
        // map must not grow an unusable entry.
        $client->sendChannelAction(ChannelActionPacket::ACTION_MUTE, "global", "", ["playerId" => ""]);

        self::assertCount(0, $this->pending($client));
    }

    public function testNonModerationActionsAreNotTracked(): void {
        $client = $this->makeClient($this->emptyServer());
        $uuid = "33333333-3333-3333-3333-333333333333";

        // JOIN/LEAVE/WHO have their own rendering; tracking them here would add
        // a second, moderation-shaped line to those flows.
        foreach ([
            ChannelActionPacket::ACTION_JOIN,
            ChannelActionPacket::ACTION_LEAVE,
            ChannelActionPacket::ACTION_WHO,
        ] as $action) {
            $client->sendChannelAction($action, "global", "", ["playerId" => $uuid]);
        }

        self::assertCount(0, $this->pending($client));
    }

    public function testPendingEntryIsConsumedByTheResponse(): void {
        $client = $this->makeClient($this->emptyServer());
        $uuid = "22222222-2222-2222-2222-222222222222";
        $requestId = $this->trackSend($client, ChannelActionPacket::ACTION_KICK, $uuid);

        $this->deliver($client, $this->response(ChannelActionPacket::ACTION_KICK, true, $requestId));

        self::assertCount(0, $this->pending($client), "a response must not leave its entry behind");
    }

    // ==================== The operator sees a SUCCESS line ====================

    /**
     * @param int    $action
     * @param string $key
     * @dataProvider successKeyProvider
     */
    public function testSuccessReachesTheOperator(int $action, string $key): void {
        $uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        $server = $this->serverWithOperator($uuid);
        $client = $this->makeClient($server);
        $requestId = $this->trackSend($client, $action, $uuid);

        $this->deliver($client, $this->response($action, true, $requestId));

        $messages = $this->operatorMessages($server);
        self::assertNotSame([], $messages, "the operator must be told the action succeeded");
        $i18n = new \NovaChat\I18n\I18n();
        $expected = $i18n->get($key, "en_US");
        self::assertNotSame($key, $expected, "the key must resolve in the shipped bundle");
        self::assertStringContainsString($expected, $messages[0]);
    }

    /**
     * @return array<string, array{int, string}>
     */
    public static function successKeyProvider(): array {
        return [
            "kick"   => [ChannelActionPacket::ACTION_KICK, "chat.action.kick_processed"],
            "mute"   => [ChannelActionPacket::ACTION_MUTE, "chat.action.mute_processed"],
            "unmute" => [ChannelActionPacket::ACTION_UNMUTE, "chat.action.unmute_processed"],
            // BAN/UNBAN have no key of their own (Java parity).
            "ban"    => [ChannelActionPacket::ACTION_BAN, "chat.action.success"],
            "unban"  => [ChannelActionPacket::ACTION_UNBAN, "chat.action.success"],
        ];
    }

    // ==================== The operator sees a FAILURE line ====================

    public function testFailureReachesTheOperator(): void {
        $uuid = "cccccccc-dddd-eeee-ffff-000000000000";
        $server = $this->serverWithOperator($uuid);
        $client = $this->makeClient($server);
        $requestId = $this->trackSend($client, ChannelActionPacket::ACTION_BAN, $uuid);

        $this->deliver($client, $this->response(
            ChannelActionPacket::ACTION_BAN, false, $requestId, "NC-403"
        ));

        $messages = $this->operatorMessages($server);
        self::assertNotSame([], $messages, "the operator must be told the ban was rejected");
        $i18n = new \NovaChat\I18n\I18n();
        self::assertStringContainsString($i18n->get("error.NC-403.message", "en_US"), $messages[0]);
        // Translated, not the raw code alone.
        self::assertStringNotContainsString("NC-403", $messages[0]);
    }

    public function testFailureWithBlankCodeStaysSilent(): void {
        $uuid = "dddddddd-eeee-ffff-0000-111111111111";
        $server = $this->serverWithOperator($uuid);
        $client = $this->makeClient($server);
        $requestId = $this->trackSend($client, ChannelActionPacket::ACTION_KICK, $uuid);

        $this->deliver($client, $this->response(
            ChannelActionPacket::ACTION_KICK, false, $requestId, ""
        ));

        // The transport already reported the problem at send time.
        self::assertSame([], $this->operatorMessages($server));
    }

    // ==================== Dropped cases ====================

    public function testUnmatchedResponseIsDropped(): void {
        $uuid = "eeeeeeee-ffff-0000-1111-222222222222";
        $server = $this->serverWithOperator($uuid);
        $client = $this->makeClient($server);

        // No sendChannelAction: this request id was never ours (backend push).
        $this->deliver($client, $this->response(
            ChannelActionPacket::ACTION_KICK, true, "00000000-0000-0000-0000-0000000000ff"
        ));

        self::assertSame([], $this->operatorMessages($server), "a backend push has no operator to report to");
    }

    public function testConsoleOutcomeIsLoggedNotDropped(): void {
        $client = $this->makeClient($this->emptyServer());
        $requestId = $this->trackSend($client, ChannelActionPacket::ACTION_KICK, self::CONSOLE_SENTINEL);

        $this->deliver($client, $this->response(
            ChannelActionPacket::ACTION_KICK, true, $requestId
        ));

        self::assertNotSame([], $this->consoleLog, "console has no player; the outcome must reach the log");
        self::assertStringContainsString("[NovaChat console]", $this->consoleLog[0]);
        self::assertStringContainsString("Kick request processed", $this->consoleLog[0]);
    }

    // ==================== The send side stamps playerId ====================

    public function testModerationCommandStampsPlayerIdForCorrelation(): void {
        $uuid = "ffffffff-0000-1111-2222-333333333333";

        $client = new RecordingNetworkClient();
        $plugin = $this->commandPlugin($client);
        $command = new NovaChatCommand($plugin);

        $sender = new ResponseTestPlayer("Admin", $uuid);
        $command->execute($sender, "nc", ["kick", "Bob", "global"]);

        self::assertNotSame([], $client->channelActions, "the kick must reach the network client");
        $extra = $client->channelActions[0]["extra"];
        self::assertArrayHasKey("playerId", $extra, "the key the client correlates its pending map on");
        self::assertSame($uuid, $extra["playerId"]);
        self::assertSame($uuid, $extra["operatorId"]);
    }

    public function testConsoleModerationStampsTheSentinelAsPlayerId(): void {
        $client = new RecordingNetworkClient();
        $plugin = $this->commandPlugin($client);
        $command = new NovaChatCommand($plugin);

        $messages = [];
        $sender = $this->createMock(CommandSender::class);
        $sender->method("getName")->willReturn("CONSOLE");
        $sender->method("hasPermission")->willReturnCallback(
            static fn(Permission|string $name): bool => true
        );
        $sender->method("sendMessage")->willReturnCallback(
            static function ($m) use (&$messages): void {
                $messages[] = (string) $m;
            }
        );

        // Console must name the channel explicitly (has no active channel).
        $command->execute($sender, "nc", ["kick", "Bob", "global"]);

        self::assertNotSame([], $client->channelActions);
        $extra = $client->channelActions[0]["extra"];
        self::assertSame(self::CONSOLE_SENTINEL, $extra["playerId"]);
        self::assertSame(self::CONSOLE_SENTINEL, $extra["operatorId"]);
        self::assertSame("true", $extra["console"]);
    }

    /**
     * Plugin stub for the command's dependencies.
     *
     * NovaChatPlugin cannot be subclassed here (Plugin::__construct has a fixed
     * six-argument signature), so the existing suite's createStub idiom is
     * used, plus a reflection injection of the final getServer().
     */
    private function commandPlugin(RecordingNetworkClient $client): NovaChatPlugin {
        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method("getConfigManager")->willReturn($this->config());
        $plugin->method("getNetworkClient")->willReturn($client);
        $plugin->method("getChatHandler")->willReturn($this->chatHandler());
        $plugin->method("debug")->willReturnCallback(static function (): void {});

        $property = new ReflectionProperty(\pocketmine\plugin\PluginBase::class, "server");
        $property->setValue($plugin, new ResponseTestServer());

        return $plugin;
    }
}
