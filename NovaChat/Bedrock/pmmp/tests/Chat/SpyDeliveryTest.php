<?php

declare(strict_types=1);

namespace NovaChat\Tests\Chat;

use NovaChat\Chat\ChatHandler;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\Packet;
use NovaChat\Protocol\SpyMessagePacket;
use NovaChat\Protocol\VarInt;
use PHPUnit\Framework\TestCase;
use pocketmine\player\Player;
use pocketmine\Server;
use Ramsey\Uuid\Uuid;

final class SpyDeliveryTest extends TestCase {
    public function testSharedFixtureAndTargetedDisplay(): void {
        $body = hex2bin(trim(file_get_contents(dirname(__DIR__, 3) . "/test-fixtures/spy-message.hex")));
        $packet = Packet::fromBytes($body);
        self::assertInstanceOf(SpyMessagePacket::class, $packet);
        self::assertSame("22222222-2222-2222-2222-222222222222", $packet->targetId);
        self::assertSame(["rank" => "member"], $packet->message->placeholders);
        self::assertSame(VarInt::write(strlen($body)) . $body, $packet->serialize());

        $target = $this->createMock(Player::class);
        (new \ReflectionProperty(\pocketmine\entity\Entity::class, "closed"))->setValue($target, true);
        (new \ReflectionProperty(Player::class, "logger"))->setValue($target, $this->createStub(\Logger::class));
        $target->method("getUniqueId")->willReturn(Uuid::fromString($packet->targetId));
        $target->expects(self::once())->method("sendMessage")->with("[SPY] [source/staff] Sender: observed");
        $other = $this->createMock(Player::class);
        (new \ReflectionProperty(\pocketmine\entity\Entity::class, "closed"))->setValue($other, true);
        (new \ReflectionProperty(Player::class, "logger"))->setValue($other, $this->createStub(\Logger::class));
        $other->method("getUniqueId")->willReturn(Uuid::fromString("44444444-4444-4444-4444-444444444444"));
        $other->expects(self::never())->method("sendMessage");
        $server = new class([$other, $target]) extends Server {
            public function __construct(private array $players) {}
            public function getOnlinePlayers(): array {
                return $this->players;
            }
        };
        $plugin = $this->createStub(NovaChatPlugin::class);
        (new \ReflectionProperty(\pocketmine\plugin\PluginBase::class, "server"))->setValue($plugin, $server);

        (new ChatHandler($plugin))->handleSpyMessage($packet);
    }
}
