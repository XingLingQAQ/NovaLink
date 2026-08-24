<?php

declare(strict_types=1);

namespace NovaChat\Tests\Protocol;

use NovaChat\Protocol\PacketBuffer;
use NovaChat\Protocol\ProtocolLimits;
use PHPUnit\Framework\TestCase;

/**
 * PROTO-002 / PROTO-003 contract tests for the PMMP ProtocolLimits mirror.
 *
 * Mirrors the JVM `ProtocolLimitsTest` (constant pinning + invariant that
 * the ConfigSync budget is strictly under the frame ceiling and every
 * per-field limit is <= MAX_FRAME_LENGTH) and `StringFieldLimitTest` (for a
 * representative set of packets, a bounded string field round-trips at
 * `max-1` and `max` and is rejected at `max+1` with an
 * InvalidArgumentException whose message contains "exceeds maximum").
 *
 * The byte values MUST stay byte-for-byte identical to the Java
 * `com.nova.chat.common.protocol.ProtocolLimits` source of truth; this suite
 * pins them so a drift is caught on the next PHP test run.
 *
 * NOTE: the host that produced this mirror has no `php` binary on PATH
 * (Get-Command php => not recognized, EXIT 127). This file is written and
 * line-by-line self-reviewed but NOT executed here. It must be verified on
 * a tooled host (`vendor/bin/phpunit tests/Protocol/ProtocolLimitsTest.php`
 * via the existing tests/bootstrap.php autoloader).
 *
 * @medium
 */
final class ProtocolLimitsTest extends TestCase {

    // ==================== Constant pinning (mirrors JVM ProtocolLimitsTest) ====================

    public function test_max_frame_length_is_4_mib(): void {
        self::assertSame(4 * 1024 * 1024, ProtocolLimits::MAX_FRAME_LENGTH);
    }

    public function test_max_config_sync_json_is_2_mib(): void {
        self::assertSame(2 * 1024 * 1024, ProtocolLimits::MAX_CONFIG_SYNC_JSON);
    }

    public function test_config_sync_budget_under_frame_ceiling(): void {
        self::assertLessThan(ProtocolLimits::MAX_FRAME_LENGTH, ProtocolLimits::MAX_CONFIG_SYNC_JSON);
    }

    public function test_identifier_fields_are_64(): void {
        self::assertSame(64, ProtocolLimits::MAX_CHANNEL_ID);
        self::assertSame(64, ProtocolLimits::MAX_CLIENT_ID);
        self::assertSame(64, ProtocolLimits::MAX_SENDER_NAME);
        self::assertSame(64, ProtocolLimits::MAX_TARGET_NAME);
        self::assertSame(64, ProtocolLimits::MAX_NONCE);
        self::assertSame(64, ProtocolLimits::MAX_SERVER_VERSION);
    }

    public function test_error_fields(): void {
        self::assertSame(64, ProtocolLimits::MAX_ERROR_CODE);
        self::assertSame(256, ProtocolLimits::MAX_ERROR_MESSAGE);
        self::assertSame(256, ProtocolLimits::MAX_MESSAGE_PREVIEW);
        self::assertSame(256, ProtocolLimits::MAX_CHANNEL_PASSWORD);
        self::assertSame(256, ProtocolLimits::MAX_PASSWORD_HASH);
    }

    public function test_display_and_auth_fields(): void {
        self::assertSame(512, ProtocolLimits::MAX_TITLE);
        self::assertSame(512, ProtocolLimits::MAX_SUBTITLE);
        self::assertSame(128, ProtocolLimits::MAX_HMAC);
    }

    public function test_message_content_is_2048(): void {
        self::assertSame(2048, ProtocolLimits::MAX_MESSAGE_CONTENT);
    }

    public function test_json_fields_are_8192(): void {
        self::assertSame(8192, ProtocolLimits::MAX_ITEM_JSON);
        self::assertSame(8192, ProtocolLimits::MAX_ACTION_JSON);
    }

    public function test_metadata_fields(): void {
        self::assertSame(128, ProtocolLimits::MAX_METADATA_KEY);
        self::assertSame(512, ProtocolLimits::MAX_METADATA_VALUE);
    }

    public function test_every_per_field_limit_under_frame_ceiling(): void {
        foreach (ProtocolLimits::allFieldLimits() as $limit) {
            self::assertLessThanOrEqual(
                ProtocolLimits::MAX_FRAME_LENGTH,
                $limit,
                "field limit $limit must not exceed MAX_FRAME_LENGTH"
            );
        }
    }

    // ==================== Bounded readString (mirrors JVM StringFieldLimitTest) ====================
    //
    // Each case writes a VarInt-prefixed ASCII string of exactly $byteLength
    // 'a' bytes (one byte per char, so the on-wire length equals the string
    // length) directly into a PacketBuffer, then reads it back via the
    // bounded readString($maxLength) overload and asserts:
    //   - max-1 and max round-trip;
    //   - max+1 raises InvalidArgumentException whose message contains
    //     "exceeds maximum" and the limit value (mirrors the Java
    //     IllegalArgumentException contract).

    private function writeBoundedString(PacketBuffer $buffer, int $byteLength): void {
        $buffer->writeVarInt($byteLength);
        $buffer->writeBytes(str_repeat('a', $byteLength));
    }

    public function test_readString_below_max_round_trips(): void {
        $max = ProtocolLimits::MAX_CHANNEL_ID;
        $buffer = new PacketBuffer();
        $this->writeBoundedString($buffer, $max - 1);
        $buffer->reset();
        self::assertSame(str_repeat('a', $max - 1), $buffer->readString($max));
    }

    public function test_readString_at_max_round_trips(): void {
        $max = ProtocolLimits::MAX_CHANNEL_ID;
        $buffer = new PacketBuffer();
        $this->writeBoundedString($buffer, $max);
        $buffer->reset();
        self::assertSame(str_repeat('a', $max), $buffer->readString($max));
    }

    public function test_readString_above_max_rejected(): void {
        $max = ProtocolLimits::MAX_CHANNEL_ID;
        $buffer = new PacketBuffer();
        $this->writeBoundedString($buffer, $max + 1);
        $buffer->reset();

        try {
            $buffer->readString($max);
            self::fail('Expected InvalidArgumentException for oversized string field');
        } catch (\InvalidArgumentException $e) {
            self::assertStringContainsString('exceeds maximum', $e->getMessage());
            self::assertStringContainsString((string) $max, $e->getMessage());
        }
    }

    public function test_readString_unbounded_no_arg_still_works(): void {
        // Backward-compat: zero-arg readString() must still decode a long
        // string (well over the per-field bounds but under the frame ceiling).
        $byteLength = 1024 * 1024; // 1 MiB, under MAX_FRAME_LENGTH (4 MiB)
        $buffer = new PacketBuffer();
        $this->writeBoundedString($buffer, $byteLength);
        $buffer->reset();
        self::assertSame(str_repeat('a', $byteLength), $buffer->readString());
    }

    public function test_readString_oversized_frame_rejected_even_unbounded(): void {
        // The hard cap at MAX_FRAME_LENGTH applies even on the unbounded path,
        // mirroring the JVM Varint21FrameDecoder ceiling.
        $buffer = new PacketBuffer();
        // Declare a length one byte over the frame ceiling.
        $buffer->writeVarInt(ProtocolLimits::MAX_FRAME_LENGTH + 1);

        $this->expectException(\InvalidArgumentException::class);
        $buffer->readString();
    }
}
