<?php

declare(strict_types=1);

namespace NovaChat\Tests\Chat;

use NovaChat\Chat\ChatHandler;
use NovaChat\Config\ConfigManager;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ConfigSyncPacket;
use PHPUnit\Framework\TestCase;

/**
 * Tests for ConfigSync known-channel extraction (PMMP).
 *
 * Mirrors the Java ConfigSyncChannels.extract contract: the known-channel
 * set is the union of global_channels keys and the channels keys of the
 * clients[] entry whose username matches this client's configured backend
 * username. The active per-player channel (playerChannels) must NOT be
 * touched. The fixture payloads live in NovaChat/Bedrock/test-fixtures/ and
 * are shared with the Endstone and LeviLamina siblings.
 *
 * NOTE: this host has no PHP binary, so these tests are syntax-reviewed only
 * and must be run on a tooled host (phpunit via tests/bootstrap.php).
 */
final class ConfigSyncChannelsTest extends TestCase {
    /** @var string Path to the shared Bedrock test-fixtures directory. */
    private string $fixturesDir;

    protected function setUp(): void {
        // __DIR__ = .../pmmp/tests/Chat ; fixtures live at .../Bedrock/test-fixtures
        // so go up 3 levels (pmmp/tests/Chat -> Bedrock).
        $this->fixturesDir = dirname(__DIR__, 3) . DIRECTORY_SEPARATOR . "test-fixtures";
    }

    private function loadFixture(string $name): string {
        return file_get_contents($this->fixturesDir . DIRECTORY_SEPARATOR . $name);
    }

    /**
     * Builds a ChatHandler with a stub plugin whose getConfigManager() returns
     * a real ConfigManager built from a minimal valid config array. Avoids
     * touching disk for templates.
     */
    private function makeHandler(string $backendUsername = "EndstoneServer"): ChatHandler {
        $config = $this->validConfig();
        $config["backend"]["username"] = $backendUsername;
        $configManager = new ConfigManager($config);

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method("getConfigManager")->willReturn($configManager);
        // ChatHandler only calls $this->plugin->debug() for logging; the stub
        // absorbs those calls. debug() is declared `: void` in NovaChatPlugin,
        // and PHPUnit 10 rejects `willReturn(null)` for a void-typed stub
        // method (IncompatibleReturnValueException). For a void method the
        // correct stub is `willReturnCallback` with a no-op closure: PHPUnit
        // treats the declared return type as `void`, a bare `willReturn()`
        // call is rejected too (the signature requires >=1 argument), but a
        // callback that returns nothing satisfies the void contract.
        $plugin->method("debug")->willReturnCallback(static function (): void {});

        return new ChatHandler($plugin);
    }

    private function syncPacket(string $configJson): ConfigSyncPacket {
        $packet = new ConfigSyncPacket();
        $packet->configJson = $configJson;
        return $packet;
    }

    public function testUnionsGlobalsAndMatchingClientChannels(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = $this->loadFixture("config-sync-payload.json");

        $handler->handleConfigSync($this->syncPacket($payload));

        // globals: global, staff ; client EndstoneServer: local, trade
        self::assertSame(
            ["global", "local", "staff", "trade"],
            $handler->getKnownChannels()
        );
    }

    public function testOtherClientsChannelsAreExcluded(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = $this->loadFixture("config-sync-payload.json");

        $handler->handleConfigSync($this->syncPacket($payload));

        $known = $handler->getKnownChannels();
        // PMMP_Server's "help" and NukkitServer's "arena-1" must NOT appear.
        self::assertNotContains("help", $known);
        self::assertNotContains("arena-1", $known);
    }

    /**
     * A blank backend.username is rejected at ConfigManager load time, not
     * tolerated as a "globals-only" filter input. This mirrors the strict
     * behavior of the other方言 (Endstone ConfigManager._validate requires
     * backend.username non-blank; Java MOD ConfigManager.requireNonBlankString
     * rejects blank; StarLink ConfigLoader.requiredNonBlankString rejects
     * blank for clients.username). The ChatHandler only reaches the blank
     * check in handleConfigSync if a ConfigManager could be constructed,
     * which a blank username prevents, so the exception surfaces here.
     */
    public function testBlankUsernameReturnsGlobalsOnly(): void {
        $this->expectException(\UnexpectedValueException::class);
        $this->expectExceptionMessage("backend.username must not be blank");
        $this->makeHandler("");
    }

    public function testUnknownUsernameReturnsGlobalsOnly(): void {
        $handler = $this->makeHandler("NobodyMatches");
        $payload = $this->loadFixture("config-sync-payload.json");

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame(["global", "staff"], $handler->getKnownChannels());
    }

    public function testEmptyPayloadYieldsEmptyRegistry(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = $this->loadFixture("config-sync-empty.json");

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame([], $handler->getKnownChannels());
    }

    public function testMalformedJsonLeavesExistingRegistryIntact(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // Seed a known-good registry first.
        $handler->handleConfigSync($this->syncPacket(
            $this->loadFixture("config-sync-payload.json")
        ));
        $before = $handler->getKnownChannels();
        self::assertNotEmpty($before);

        $malformed = $this->loadFixture("config-sync-malformed.json");
        $handler->handleConfigSync($this->syncPacket($malformed));

        // Bad JSON must not clear the registry (audit acceptance line 323).
        self::assertSame($before, $handler->getKnownChannels());
    }

    public function testDoesNotOverwriteActivePlayerChannel(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // Seed the active channel via reflection — the field is private and
        // there is no public setter; the real code path sets it from chat
        // commands. We only assert ConfigSync does not touch it.
        $ref = new \ReflectionProperty(ChatHandler::class, "playerChannels");
        $ref->setAccessible(true);
        $ref->setValue($handler, ["player-1" => "global"]);

        $handler->handleConfigSync($this->syncPacket(
            $this->loadFixture("config-sync-payload.json")
        ));

        self::assertSame(["player-1" => "global"], $ref->getValue($handler));
    }

    public function testNullOrMissingGlobalChannelsIsTolerated(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // No global_channels key; a client entry for this username still wins.
        $payload = json_encode([
            "clients" => [
                ["username" => "EndstoneServer", "channels" => ["local" => []]]
            ]
        ], JSON_THROW_ON_ERROR);

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame(["local"], $handler->getKnownChannels());
    }

    public function testWrongTypesLogWarningAndContinue(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // global_channels as an array (wrong type) + clients as a mapping
        // (wrong type): both must be tolerated; the client entry for the
        // username is unreachable, so the result is the empty set.
        $payload = json_encode([
            "global_channels" => ["not", "a", "mapping"],
            "clients" => ["username" => "EndstoneServer"]
        ], JSON_THROW_ON_ERROR);

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame([], $handler->getKnownChannels());
    }

    public function testTopLevelArrayIsRejected(): void {
        $handler = $this->makeHandler("EndstoneServer");
        // Root is an array, not an object. json_decode(..., true) yields a
        // list (is_array true, but not a mapping); the handler must bail
        // without raising.
        $handler->handleConfigSync($this->syncPacket(json_encode([1, 2, 3], JSON_THROW_ON_ERROR)));

        self::assertSame([], $handler->getKnownChannels());
    }

    public function testMissingClientsKeyIsTolerated(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = json_encode(["global_channels" => ["global" => []]], JSON_THROW_ON_ERROR);

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame(["global"], $handler->getKnownChannels());
    }

    public function testClientEntryWithoutUsernameIsSkipped(): void {
        $handler = $this->makeHandler("EndstoneServer");
        $payload = json_encode([
            "global_channels" => ["global" => []],
            "clients" => [
                ["channels" => ["local" => []]],  // no username field
                ["username" => "EndstoneServer", "channels" => ["trade" => []]]
            ]
        ], JSON_THROW_ON_ERROR);

        $handler->handleConfigSync($this->syncPacket($payload));

        self::assertSame(["global", "trade"], $handler->getKnownChannels());
    }

    public function testDoesNotRestoreTemplateExamples(): void {
        // Audit acceptance (line 323): an unknown/empty payload must NOT
        // restore template example channels (local/global).
        $handler = $this->makeHandler("EndstoneServer");
        $handler->handleConfigSync($this->syncPacket(
            $this->loadFixture("config-sync-empty.json")
        ));

        $known = $handler->getKnownChannels();
        self::assertNotContains("local", $known);
        self::assertNotContains("global", $known);
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
