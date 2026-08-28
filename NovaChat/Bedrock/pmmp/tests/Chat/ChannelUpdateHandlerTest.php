<?php

declare(strict_types=1);

namespace NovaChat\Tests\Chat;

use NovaChat\Chat\ChatHandler;
use NovaChat\Config\ConfigManager;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelUpdatePacket;
use PHPUnit\Framework\TestCase;

/**
 * Tests for ChannelUpdatePacket handling in ChatHandler (PMMP).
 *
 * Covers the three previously-stubbed branches of handleChannelUpdate:
 *  - UPDATE_MEMBER_JOIN  : adds member UUID to local channel roster
 *  - UPDATE_MEMBER_LEAVE : removes member UUID from local channel roster
 *  - UPDATE_CONFIG_CHANGED: refreshes known-channel registry
 *
 * Mirrors the Java ChannelCommandService optimistic local-state update for
 * membership and the ConfigSyncChannels.extract rebuild for config changes.
 * The handler is best-effort: malformed payloads must not throw and must
 * leave existing state intact.
 */
final class ChannelUpdateHandlerTest extends TestCase {
    /** @var string Path to the shared Bedrock test-fixtures directory. */
    private string $fixturesDir;

    protected function setUp(): void {
        $this->fixturesDir = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR . "test-fixtures";
    }

    private function loadFixture(string $name): string {
        return file_get_contents($this->fixturesDir . DIRECTORY_SEPARATOR . $name);
    }

    /**
     * Builds a ChatHandler with a stub plugin whose getConfigManager() returns
     * a real ConfigManager built from a minimal valid config array.
     */
    private function makeHandler(string $backendUsername = "EndstoneServer"): ChatHandler {
        $config = $this->validConfig();
        $config["backend"]["username"] = $backendUsername;
        $configManager = new ConfigManager($config);

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method("getConfigManager")->willReturn($configManager);
        $plugin->method("debug")->willReturnCallback(static function (): void {});

        return new ChatHandler($plugin);
    }

    private function updatePacket(int $type, string $channelId, string $dataJson = ""): ChannelUpdatePacket {
        $packet = new ChannelUpdatePacket();
        $packet->updateType = $type;
        $packet->channelId = $channelId;
        $packet->dataJson = $dataJson;
        return $packet;
    }

    // ==================== MEMBER_JOIN ====================

    public function testMemberJoinAddsMemberToRoster(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame(
            ["11111111-1111-1111-1111-111111111111"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberJoinAcceptsPlayerUuidAlias(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerUuid" => "22222222-2222-2222-2222-222222222222"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame(
            ["22222222-2222-2222-2222-222222222222"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberJoinAcceptsUuidAlias(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["uuid" => "33333333-3333-3333-3333-333333333333"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame(
            ["33333333-3333-3333-3333-333333333333"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberJoinMarksChannelAsKnown(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "newchan",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        self::assertContains("newchan", $handler->getKnownChannels());
    }

    public function testMemberJoinIsIdempotent(): void {
        $handler = $this->makeHandler();
        $payload = json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR);

        $handler->handleChannelUpdate($this->updatePacket(ChannelUpdatePacket::UPDATE_MEMBER_JOIN, "trade", $payload));
        $handler->handleChannelUpdate($this->updatePacket(ChannelUpdatePacket::UPDATE_MEMBER_JOIN, "trade", $payload));

        self::assertSame(
            ["11111111-1111-1111-1111-111111111111"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberJoinAccumulatesDistinctMembers(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "22222222-2222-2222-2222-222222222222"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame(
            ["11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberJoinWithMissingPlayerIdIsIgnored(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerName" => "Steve"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame([], $handler->getChannelMembers("trade"));
    }

    public function testMemberJoinWithMalformedJsonIsIgnored(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            "{not json"
        ));

        self::assertSame([], $handler->getChannelMembers("trade"));
    }

    public function testMemberJoinWithBlankChannelIdIsIgnored(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame([], $handler->getChannelMembers(""));
    }

    // ==================== MEMBER_LEAVE ====================

    public function testMemberLeaveRemovesMemberFromRoster(): void {
        $handler = $this->makeHandler();
        $join = json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR);
        $handler->handleChannelUpdate($this->updatePacket(ChannelUpdatePacket::UPDATE_MEMBER_JOIN, "trade", $join));

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_LEAVE,
            "trade",
            $join
        ));

        self::assertSame([], $handler->getChannelMembers("trade"));
    }

    public function testMemberLeaveRemovesOnlyNamedMember(): void {
        $handler = $this->makeHandler();
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "22222222-2222-2222-2222-222222222222"], JSON_THROW_ON_ERROR)
        ));

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_LEAVE,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame(
            ["22222222-2222-2222-2222-222222222222"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberLeaveOnUnknownChannelIsNoop(): void {
        $handler = $this->makeHandler();

        // No throw, no state change.
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_LEAVE,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        self::assertSame([], $handler->getChannelMembers("trade"));
    }

    public function testMemberLeaveWithMissingPlayerIdIsIgnored(): void {
        $handler = $this->makeHandler();
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_LEAVE,
            "trade",
            json_encode(["playerName" => "Steve"], JSON_THROW_ON_ERROR)
        ));

        // The roster is unchanged.
        self::assertSame(
            ["11111111-1111-1111-1111-111111111111"],
            $handler->getChannelMembers("trade")
        );
    }

    public function testMemberLeaveWithMalformedJsonIsIgnored(): void {
        $handler = $this->makeHandler();
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_JOIN,
            "trade",
            json_encode(["playerId" => "11111111-1111-1111-1111-111111111111"], JSON_THROW_ON_ERROR)
        ));

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_MEMBER_LEAVE,
            "trade",
            "{not json"
        ));

        self::assertSame(
            ["11111111-1111-1111-1111-111111111111"],
            $handler->getChannelMembers("trade")
        );
    }

    // ==================== CONFIG_CHANGED ====================

    public function testConfigChangedWithConfigSyncPayloadRebuildsRegistry(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = $this->loadFixture("config-sync-payload.json");

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "global",
            $payload
        ));

        // globals: global, staff ; client EndstoneServer: local, trade
        self::assertSame(
            ["global", "local", "staff", "trade"],
            $handler->getKnownChannels()
        );
    }

    public function testConfigChangedWithConfigSyncReplacesPreviousRegistry(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // Seed with the full payload first.
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "global",
            $this->loadFixture("config-sync-payload.json")
        ));
        self::assertNotEmpty($handler->getKnownChannels());

        // Push an empty config -> registry must be cleared, not merged.
        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "global",
            $this->loadFixture("config-sync-empty.json")
        ));

        self::assertSame([], $handler->getKnownChannels());
    }

    public function testConfigChangedWithPerChannelBlobMarksChannelKnown(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "staff",
            json_encode(["name" => "Staff", "description" => "Staff only"], JSON_THROW_ON_ERROR)
        ));

        self::assertContains("staff", $handler->getKnownChannels());
    }

    public function testConfigChangedWithBlankDataMarksChannelKnown(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "arena-1",
            ""
        ));

        self::assertContains("arena-1", $handler->getKnownChannels());
    }

    public function testConfigChangedWithMalformedJsonFallsBackToChannelMark(): void {
        $handler = $this->makeHandler();

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "arena-1",
            "{not json"
        ));

        // Not ConfigSync-shaped, so the per-channel fallback path fires.
        self::assertContains("arena-1", $handler->getKnownChannels());
    }

    public function testConfigChangedDoesNotTouchPlayerChannels(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $ref = new \ReflectionProperty(ChatHandler::class, "playerChannels");
        if (PHP_VERSION_ID < 80100) {
            $ref->setAccessible(true);
        }
        $ref->setValue($handler, ["player-1" => "global"]);

        $handler->handleChannelUpdate($this->updatePacket(
            ChannelUpdatePacket::UPDATE_CONFIG_CHANGED,
            "global",
            $this->loadFixture("config-sync-payload.json")
        ));

        self::assertSame(["player-1" => "global"], $ref->getValue($handler));
    }

    // ==================== Integration: ConfigSync still works ====================

    public function testConfigSyncStillRebuildsRegistryAfterRefactor(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = $this->loadFixture("config-sync-payload.json");

        $syncPacket = new \NovaChat\Protocol\ConfigSyncPacket();
        $syncPacket->configJson = $payload;
        $handler->handleConfigSync($syncPacket);

        self::assertSame(
            ["global", "local", "staff", "trade"],
            $handler->getKnownChannels()
        );
    }

    /**
     * @return array<string, mixed>
     */
    private function validConfig(): array {
        return [
            "config-version" => 1,
            "backend" => [
                "host" => "127.0.0.1",
                "port" => 18888,
                "username" => "EndstoneServer",
                "password" => "secret",
                "server-version" => "5.0.0",
                "reconnect-delay" => 5,
            ],
            "chat" => [
                "replace_vanilla" => false,
                "default_channel" => "local",
            ],
            "format" => [
                "prefix" => "[NovaChat] ",
                "error" => "error: {message}",
                "success" => "success: {message}",
                "default" => "default",
                "channels" => ["local" => "local"],
            ],
            "debug" => false,
        ];
    }
}
