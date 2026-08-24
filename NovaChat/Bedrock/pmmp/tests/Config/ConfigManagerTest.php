<?php

declare(strict_types=1);

namespace NovaChat\Tests\Config;

use NovaChat\Config\ConfigManager;
use PHPUnit\Framework\TestCase;
use UnexpectedValueException;

final class ConfigManagerTest extends TestCase {
    public function testMissingDynamicChannelMappingIsTreatedAsEmpty(): void {
        $config = $this->validConfig();
        unset($config["format"]["channels"]);

        $manager = new ConfigManager($config);

        self::assertSame([], $manager->getChannelFormats());
        self::assertSame("default", $manager->getChannelFormat("unknown"));
    }

    public function testFloatingPointPortIsRejected(): void {
        $config = $this->validConfig();
        $config["backend"]["port"] = 8888.0;

        $this->expectException(UnexpectedValueException::class);
        new ConfigManager($config);
    }

    public function testConfigVersionMustBeAnInteger(): void {
        $config = $this->validConfig();
        $config["config-version"] = 1.0;

        $this->expectException(UnexpectedValueException::class);
        new ConfigManager($config);
    }

    public function testNonEmptyChannelSequenceIsRejected(): void {
        $config = $this->validConfig();
        $config["format"]["channels"] = ["not-a-mapping"];

        $this->expectException(UnexpectedValueException::class);
        new ConfigManager($config);
    }

    /** @dataProvider invalidPortProvider */
    public function testPortMustBeWithinTcpRange(int $port): void {
        $config = $this->validConfig();
        $config["backend"]["port"] = $port;

        $this->expectException(UnexpectedValueException::class);
        new ConfigManager($config);
    }

    /** @return iterable<string, array{0: int}> */
    public static function invalidPortProvider(): iterable {
        yield "zero" => [0];
        yield "negative" => [-1];
        yield "too large" => [65536];
    }

    /** @dataProvider requiredStringProvider */
    public function testRequiredStringsCannotBeBlank(string $path): void {
        $config = $this->validConfig();
        $segments = explode(".", $path);
        $last = array_pop($segments);
        $target = &$config;
        foreach ($segments as $segment) {
            $target = &$target[$segment];
        }
        $target[$last] = " \t";

        $this->expectException(UnexpectedValueException::class);
        new ConfigManager($config);
    }

    /** @return iterable<string, array{0: string}> */
    public static function requiredStringProvider(): iterable {
        yield "backend host" => ["backend.host"];
        yield "backend username" => ["backend.username"];
        yield "server version" => ["backend.server-version"];
        yield "default channel" => ["chat.default_channel"];
    }

    /** @return array<string, mixed> */
    private function validConfig(): array {
        return [
            "config-version" => 1,
            "backend" => [
                "host" => "127.0.0.1",
                "port" => 8888,
                "username" => "PMMP_Server",
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
