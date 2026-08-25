<?php

declare(strict_types=1);

namespace NovaChat\Tests\Network;

use Eris\Generator;
use Eris\TestTrait;
use NovaChat\Config\ConfigManager;
use NovaChat\Network\NetworkClient;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\HandshakeResponsePacket;
use NovaChat\Protocol\Packet;
use NovaChat\Protocol\PacketBuffer;
use NovaChat\Protocol\ProtocolLimits;
use NovaChat\Protocol\VarInt;
use PHPUnit\Framework\TestCase;
use ReflectionMethod;
use ReflectionProperty;

/**
 * VERIFY-005 PMMP — Fuzz tests for the packet decode path.
 *
 * Entry point: NetworkClient::handlePacket() delegates to the pure static
 * Packet::fromBytes(string): ?Packet decoder. These tests fuzz that pure
 * decode boundary directly (no socket, no network) for four attack scenarios
 * named in the VERIFY-005 audit doc:
 *
 *  1. Unknown packet ID      → fromBytes returns null (no throw). The
 *                               connection survives (design choice, R2).
 *  2. Bad VarInt             → InvalidArgumentException (truncated / overflow).
 *                               R1 fix: handlePacket catches and calls
 *                               handleDisconnect() (close+release+reconnect).
 *  3. Bad UTF-8 in strings   → R3 fix: readString validates via
 *                               mb_check_encoding and throws
 *                               InvalidArgumentException("Invalid UTF-8..."),
 *                               caught by R1's try/catch → handleDisconnect().
 *  4. Oversized fields       → InvalidArgumentException before allocation
 *                               (no memory explosion). R1 catches it too.
 *
 * Pure-decode contract verified:
 *  - Packet::fromBytes is a pure static method; no instance state, no I/O.
 *  - It either returns a Packet, returns null (unknown ID), or throws
 *    InvalidArgumentException (malformed payload). It never returns a
 *    partially-decoded packet, never silently corrupts, and never allocates
    *    more than MAX_FRAME_LENGTH bytes for a single string field.
 *
 * Production-caller behavior (after R1/R3 fixes):
 *
 *  R1. NetworkClient::handlePacket wraps fromBytes in try/catch
 *      (InvalidArgumentException + Throwable). On decode failure it logs a
 *      warning with the packet id, byte length, and exception message (no
 *      full-frame dump, no secrets — the decode boundary has no secrets to
 *      leak), then calls handleDisconnect() which cancels read+keepalive
 *      tasks, closes the socket, clears the buffer, resets flags, and
 *      schedules reconnect with exponential backoff. This mirrors Endstone
 *      _read_loop semantics.
 *
 *  R2. NetworkClient::handlePacket does NOT close the connection on an
 *      unknown packet ID; it logs "Received unknown packet" and continues.
 *      This is by design (forward-compat: the connection survives a benign
 *      unknown id from a newer peer), matching Java + Endstone UnknownPacket
 *      behavior. Tests continue to assert this current behavior.
 *
 *  R3. PacketBuffer::readString validates UTF-8 via mb_check_encoding and
 *      throws InvalidArgumentException("Invalid UTF-8 in string field") on
 *      invalid sequences. The exception bubbles up through decode →
 *      fromBytes → handlePacket's try/catch (R1) → handleDisconnect(). All
 *      string fields in the protocol are text (senderName, channelId,
 *      errorCode, message, configJson, itemJson, placeholders...), so this
 *      is safe at the decode layer. itemJson (NBT/JSON serialized) is JSON
 *      text, not raw binary.
 *
 *  R4. VarInt::read on the 5th byte silently masks the upper 4 bits (0x70)
 *      with 0x0F — a 5-byte VarInt whose 5th byte carries bits 4-6 is
 *      silently truncated rather than rejected. The JVM may reject this;
 *      the PHP decoder accepts it. This is existing behavior and is NOT
 *      modified. Tests continue to assert this current behavior.
 */
final class PacketDecodeFuzzTest extends TestCase {
    use TestTrait;

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Builds a raw frame body (without length prefix) for Packet::fromBytes.
     *
     * Format: | PacketId (1 byte) | RequestId (16 bytes) | Payload |
     */
    private function buildFrame(int $packetId, string $payload = ''): string {
        return chr($packetId & 0xFF) . random_bytes(16) . $payload;
    }

    // ==================================================================
    // Scenario 1: Unknown packet ID → fromBytes returns null
    // ==================================================================

    public function testUnknownPacketIdReturnsNull(): void {
        // 0x00 is not a valid packet ID in the factory
        $frame = $this->buildFrame(0x00);
        self::assertNull(Packet::fromBytes($frame));
    }

    public function testDeprecatedAnnouncementIdReturnsNull(): void {
        // 0x0A (ANNOUNCEMENT) is deprecated; the factory has no arm for it,
        // so fromBytes treats it as unknown and returns null.
        $frame = $this->buildFrame(Packet::ANNOUNCEMENT);
        self::assertNull(Packet::fromBytes($frame));
    }

    public function testPlayerStateIdReturnsNull(): void {
        // 0x08 (PLAYER_STATE) is defined as a constant but NOT in the
        // createPacket factory match — treated as unknown by fromBytes.
        $frame = $this->buildFrame(Packet::PLAYER_STATE);
        self::assertNull(Packet::fromBytes($frame));
    }

    public function testHighBytePacketIdReturnsNull(): void {
        $frame = $this->buildFrame(0xFF);
        self::assertNull(Packet::fromBytes($frame));
    }

    /**
     * Exhaustive: for every byte value 0-255 that is NOT a factory packet
     * ID, fromBytes returns null without throwing.
     *
     * Factory IDs (from createPacket match):
     *   0x01 0x02 0x03 0x04 0x05 0x06 0x07
     *   0x09 0x0B 0x0C 0x0D
     *   0x10 0x12 0x14
     *   0x15 0x16 0x17
     *
     * Non-factory IDs (239 values) should all return null.
     */
    public function testAllNonFactoryByteIdsReturnNull(): void {
        $factoryIds = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x09, 0x0B, 0x0C, 0x0D,
            0x10, 0x12, 0x14,
            0x15, 0x16, 0x17,
        ];
        $factorySet = array_flip($factoryIds);

        for ($id = 0; $id <= 255; $id++) {
            if (isset($factorySet[$id])) {
                continue;
            }
            $frame = chr($id) . random_bytes(16); // 17 bytes: ID + UUID
            $result = Packet::fromBytes($frame);
            self::assertNull(
                $result,
                sprintf('packetId 0x%02X should return null (not in factory)', $id)
            );
        }
    }

    /**
     * Property: for any byte value not in the factory, fromBytes returns null.
     */
    public function testAnyUnknownIdReturnsNull(): void {
        $factoryIds = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x09, 0x0B, 0x0C, 0x0D,
            0x10, 0x12, 0x14,
            0x15, 0x16, 0x17,
        ];
        $factorySet = array_flip($factoryIds);

        $this->forAll(Generator\choose(0, 255))
            ->withMaxSize(100)
            ->then(function (int $id) use ($factorySet): void {
                if (isset($factorySet[$id])) {
                    return; // Skip known factory IDs
                }
                $frame = chr($id) . random_bytes(16);
                self::assertNull(
                    Packet::fromBytes($frame),
                    sprintf('packetId 0x%02X should return null', $id)
                );
            });
    }

    // ==================================================================
    // Scenario 2: Bad VarInt → InvalidArgumentException (R1: handlePacket
    // catches and calls handleDisconnect)
    // ==================================================================

    public function testEmptyFrameThrowsBufferUnderflow(): void {
        $this->expectException(\InvalidArgumentException::class);
        Packet::fromBytes('');
    }

    public function testFrameTooShortForUuidThrowsBufferUnderflow(): void {
        // 1 byte ID + 15 bytes (need 16 for UUID) → readUUID underflow
        $this->expectException(\InvalidArgumentException::class);
        Packet::fromBytes(chr(0x07) . random_bytes(15));
    }

    /**
     * VarInt with continuation bit set but no following byte → buffer underflow.
     */
    public function testTruncatedVarIntThrows(): void {
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Buffer underflow');
        $offset = 0;
        VarInt::read(chr(0x80), $offset);
    }

    /**
     * 6 bytes all with continuation → "VarInt is too big" on the 6th
     * iteration (shift=35 >= 32). The offset check passes (offset=5 < 6),
     * then the shift check fires.
     */
    public function testOverflowVarIntThrows(): void {
        $buffer = str_repeat(chr(0xFF), 6);
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('VarInt is too big');
        $offset = 0;
        VarInt::read($buffer, $offset);
    }

    /**
     * 5 bytes all with continuation but no 6th byte → "Buffer underflow"
     * (offset check fires before the shift check because offset=5 >= 5).
     *
     * This documents the edge: the error message depends on whether a 6th
     * byte exists, not just on the continuation pattern.
     */
    public function testVarInt5ContBytesNo6thThrowsBufferUnderflow(): void {
        $buffer = str_repeat(chr(0x80), 5);
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Buffer underflow');
        $offset = 0;
        VarInt::read($buffer, $offset);
    }

    /**
     * Bad VarInt in a real packet context: HandshakePacket::decode calls
     * readVarInt for protocolVersion. A truncated VarInt payload causes
     * fromBytes to throw InvalidArgumentException.
     */
    public function testBadVarIntInHandshakePacketThrows(): void {
        // HandshakePacket payload starts with VarInt(protocolVersion).
        // 0x80 = continuation bit, no more bytes → underflow.
        $frame = $this->buildFrame(Packet::HANDSHAKE, chr(0x80));
        $this->expectException(\InvalidArgumentException::class);
        Packet::fromBytes($frame);
    }

    /**
     * Residual gap R4: the 5th byte of a VarInt is masked with 0x0F, so
     * bits 4-6 are silently discarded. This is NOT an error — the decoder
     * accepts the truncated value. The JVM may reject this; PHP accepts it.
     */
    public function testVarInt5thByteHighBitsMaskedNotError(): void {
        // 0x80 0x80 0x80 0x80 0x1F
        // 5th byte = 0x1F, but only 0x0F used (bits 4 is masked away)
        // Result: 0x0F << 28 = 0xF0000000 (unsigned) → -268435456 (signed)
        $buffer = chr(0x80) . chr(0x80) . chr(0x80) . chr(0x80) . chr(0x1F);
        $offset = 0;
        $value = VarInt::read($buffer, $offset);
        self::assertSame(-268435456, $value);
    }

    // ==================================================================
    // Scenario 3: Bad UTF-8 → R3 fix — readString validates and throws
    //             InvalidArgumentException("Invalid UTF-8 in string field")
    // ==================================================================

    /**
     * R3 fix: PacketBuffer::readString now validates UTF-8 via
     * mb_check_encoding. Invalid byte sequences cause decode to throw
     * InvalidArgumentException("Invalid UTF-8 in string field") instead of
     * passing the raw bytes through.
     *
     * HandshakeResponsePacket reads errorCode (valid) then message (invalid
     * UTF-8). The message read throws before decode completes.
     */
    public function testInvalidUtf8InStringFieldThrows(): void {
        $invalidUtf8 = "\xFF\xFE\x80\xBF"; // invalid UTF-8 sequences

        $payloadBuf = new PacketBuffer();
        $payloadBuf->writeBoolean(true);           // success
        $payloadBuf->writeString('NC-200');        // errorCode (valid)
        $payloadBuf->writeString($invalidUtf8);    // message (invalid UTF-8)

        $frame = $this->buildFrame(Packet::HANDSHAKE_RESPONSE, $payloadBuf->getBuffer());

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid UTF-8');
        Packet::fromBytes($frame);
    }

    /**
     * R3 fix (direct): readString on a buffer containing invalid UTF-8
     * throws InvalidArgumentException with "Invalid UTF-8 in string field".
     */
    public function testReadStringRejectsInvalidUtf8(): void {
        $invalidUtf8 = "\xFF\xFE\x80\xBF";
        $buf = new PacketBuffer();
        $buf->writeString($invalidUtf8);
        $buf->reset();

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid UTF-8 in string field');
        $buf->readString();
    }

    /**
     * R3 fix (direct): readString accepts valid UTF-8 (ASCII + multibyte).
     */
    public function testReadStringAcceptsValidUtf8(): void {
        $validAscii = 'hello';
        $validMultibyte = "héllo 世界 ✓"; // UTF-8 multibyte sequences

        $buf = new PacketBuffer();
        $buf->writeString($validAscii);
        $buf->writeString($validMultibyte);
        $buf->reset();

        self::assertSame($validAscii, $buf->readString());
        self::assertSame($validMultibyte, $buf->readString());
    }

    /**
     * Property: R3 fix — for any 4-byte sequence that is NOT valid UTF-8,
     * readString throws InvalidArgumentException containing "Invalid UTF-8".
     * For any valid UTF-8 4-byte sequence, readString returns the bytes.
     */
    public function testReadStringValidatesUtf8ForArbitraryBytes(): void {
        $this->forAll(
            Generator\choose(0, 255),
            Generator\choose(0, 255),
            Generator\choose(0, 255),
            Generator\choose(0, 255)
        )
        ->withMaxSize(50)
        ->then(function (int $b1, int $b2, int $b3, int $b4): void {
            $rawBytes = chr($b1) . chr($b2) . chr($b3) . chr($b4);
            $buf = new PacketBuffer();
            $buf->writeString($rawBytes);
            $buf->reset();

            if (mb_check_encoding($rawBytes, 'UTF-8')) {
                // Valid UTF-8 → readString returns the bytes unchanged
                self::assertSame($rawBytes, $buf->readString());
            } else {
                // Invalid UTF-8 → readString throws
                try {
                    $buf->readString();
                    self::fail(
                        'Expected InvalidArgumentException for invalid UTF-8 '
                        . bin2hex($rawBytes)
                    );
                } catch (\InvalidArgumentException $e) {
                    self::assertStringContainsString('Invalid UTF-8', $e->getMessage());
                }
            }
        });
    }

    // ==================================================================
    // Scenario 4: Oversized fields → InvalidArgumentException, no OOM
    // (R1: handlePacket catches and calls handleDisconnect)
    // ==================================================================

    /**
     * A string field exceeding its ProtocolLimits bound is rejected with
     * "exceeds maximum" before the bytes are allocated.
     *
     * HandshakeResponsePacket reads errorCode with MAX_ERROR_CODE=64.
     * Declaring length 65 triggers the bound check.
     */
    public function testOversizedStringFieldThrowsBeforeAllocation(): void {
        $uuid = random_bytes(16);
        $oversizedLen = ProtocolLimits::MAX_ERROR_CODE + 1; // 65

        $payloadBuf = new PacketBuffer();
        $payloadBuf->writeBoolean(true);                  // success
        $payloadBuf->writeVarInt($oversizedLen);           // errorCode length
        $payloadBuf->writeBytes(str_repeat('a', $oversizedLen));

        $frame = chr(Packet::HANDSHAKE_RESPONSE) . $uuid . $payloadBuf->getBuffer();

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('exceeds maximum');
        Packet::fromBytes($frame);
    }

    /**
     * Negative string length (VarInt-decoded -1) → "Invalid string length".
     */
    public function testNegativeStringLengthThrows(): void {
        $buf = new PacketBuffer();
        $buf->writeBytes(VarInt::write(-1));
        $buf->reset();

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid string length');
        $buf->readString();
    }

    /**
     * Declared length > MAX_FRAME_LENGTH → "Invalid string length" (hard
     * cap, mirrors the JVM Varint21FrameDecoder ceiling).
     */
    public function testStringLengthExceedsFrameCeilingThrows(): void {
        $buf = new PacketBuffer();
        $buf->writeVarInt(ProtocolLimits::MAX_FRAME_LENGTH + 1);
        $buf->reset();

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid string length');
        $buf->readString();
    }

    /**
     * Declared length <= MAX_FRAME_LENGTH but > remaining bytes →
     * "Invalid string length".
     */
    public function testStringLengthExceedsRemainingThrows(): void {
        $buf = new PacketBuffer();
        $buf->writeVarInt(100);
        $buf->writeBytes('short'); // only 5 bytes, declared 100
        $buf->reset();

        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('Invalid string length');
        $buf->readString();
    }

    /**
     * No memory explosion: an absurd declared length (MAX_FRAME_LENGTH + 1)
     * is rejected BEFORE any substr() allocation. The memory delta should
     * be tiny (just the exception object), not ~4 MiB.
     */
    public function testNoMemoryExplosionOnAbsurdLength(): void {
        $buf = new PacketBuffer();
        $buf->writeVarInt(ProtocolLimits::MAX_FRAME_LENGTH + 1);
        $buf->reset();

        $memoryBefore = memory_get_usage();
        try {
            $buf->readString();
            self::fail('Expected InvalidArgumentException for oversized length');
        } catch (\InvalidArgumentException $e) {
            // Expected
        }
        $memoryAfter = memory_get_usage();
        $delta = abs($memoryAfter - $memoryBefore);
        self::assertLessThan(
            1024 * 1024, // less than 1 MiB delta — no 4 MiB allocation
            $delta,
            'readString must not allocate MAX_FRAME_LENGTH bytes for an oversized declared length'
        );
    }

    /**
     * Property: for any length exceeding a field's max bound, readString
     * with that max throws InvalidArgumentException containing "exceeds
     * maximum". Only the VarInt length prefix is written (no string bytes)
     * because the bound check fires before the byte read.
     */
    public function testOversizedFieldAlwaysRejected(): void {
        $max = ProtocolLimits::MAX_CHANNEL_ID; // 64

        $this->forAll(Generator\choose($max + 1, $max + 10000))
            ->withMaxSize(100)
            ->then(function (int $oversizedLen) use ($max): void {
                $buf = new PacketBuffer();
                $buf->writeVarInt($oversizedLen);
                $buf->reset();

                try {
                    $buf->readString($max);
                    self::fail(
                        "Expected InvalidArgumentException for length $oversizedLen > max $max"
                    );
                } catch (\InvalidArgumentException $e) {
                    self::assertStringContainsString('exceeds maximum', $e->getMessage());
                    self::assertStringContainsString((string) $max, $e->getMessage());
                }
            });
    }

    // ==================================================================
    // Random garbage fuzz — fromBytes never crashes (R1: handlePacket
    // catches every InvalidArgumentException)
    // ==================================================================

    /**
     * Property: for any random byte sequence, fromBytes either:
     *  - returns null (unknown packet ID with enough bytes for ID+UUID), or
     *  - throws InvalidArgumentException (underflow, bad VarInt, oversized,
     *    invalid UTF-8), or
     *  - returns a valid Packet (if the bytes happen to form a valid frame).
     *
     * It never throws a non-InvalidArgumentException, never returns a
     * partial/corrupt packet, and never allocates more than MAX_FRAME_LENGTH
     * for a string field.
     */
    public function testRandomBytesNeverCrashes(): void {
        // PHP's random_bytes() rejects length <= 0, so start at 1.
        $this->forAll(Generator\choose(1, 256))
            ->withMaxSize(100)
            ->then(function (int $length): void {
                $garbage = random_bytes($length);
                try {
                    $result = Packet::fromBytes($garbage);
                    if ($result !== null) {
                        self::assertInstanceOf(Packet::class, $result);
                    }
                } catch (\InvalidArgumentException $e) {
                    // Expected: buffer underflow, bad VarInt, oversized field,
                    // invalid UTF-8 (R3)
                    $this->addToAssertionCount(1);
                }
                // Any other exception type propagates and fails the test
            });
    }

    // ==================================================================
    // R1 integration: handlePacket catches decode exceptions and calls
    // handleDisconnect (close + release + reconnect). The pure decode
    // boundary (fromBytes) throws; the production caller (handlePacket)
    // catches and tears down the connection.
    // ==================================================================

    /**
     * R1 integration: handlePacket catches InvalidArgumentException from
     * fromBytes (triggered by R3's invalid UTF-8 in a string field), logs a
     * warning, and calls handleDisconnect(). After the call:
     *  - connected is false (disconnect() cleared it)
     *  - authenticated is false (disconnect() cleared it)
     *  - readBuffer is "" (disconnect() cleared it)
     *  - socket is null (disconnect() closed and nulled it)
     *  - reconnectScheduled is true (scheduleReconnect() set it)
     *
     * This is the Endstone _read_loop parity guarantee: a malformed frame
     * tears down the connection and schedules reconnect instead of
     * crashing the read loop.
     */
    public function testHandlePacketCatchesInvalidUtf8AndDisconnects(): void {
        $client = $this->makeConnectedClient();

        // Pre-state: the fake "connected" socket is non-null and flags are
        // set, simulating a live connection right before a malformed frame.
        $socketProp = new ReflectionProperty(NetworkClient::class, 'connected');
        self::assertTrue($socketProp->getValue($client));

        $invalidUtf8 = "\xFF\xFE\x80\xBF";
        $payloadBuf = new PacketBuffer();
        $payloadBuf->writeBoolean(true);
        $payloadBuf->writeString('NC-200');
        $payloadBuf->writeString($invalidUtf8);    // invalid UTF-8 → R3 throws
        $frame = $this->buildFrame(Packet::HANDSHAKE_RESPONSE, $payloadBuf->getBuffer());

        $ref = new ReflectionMethod($client, 'handlePacket');
        $ref->invoke($client, $frame);

        // handlePacket caught the exception and called handleDisconnect.
        self::assertFalse(
            $socketProp->getValue($client),
            'connected must be false after handleDisconnect'
        );
        $authProp = new ReflectionProperty(NetworkClient::class, 'authenticated');
        self::assertFalse($authProp->getValue($client));
        $readBufferProp = new ReflectionProperty(NetworkClient::class, 'readBuffer');
        self::assertSame('', $readBufferProp->getValue($client));
        $reconnectProp = new ReflectionProperty(NetworkClient::class, 'reconnectScheduled');
        self::assertTrue(
            $reconnectProp->getValue($client),
            'reconnectScheduled must be true after handleDisconnect'
        );
    }

    /**
     * R1 integration: handlePacket catches InvalidArgumentException from
     * fromBytes (bad VarInt — buffer underflow), logs a warning, and calls
     * handleDisconnect(). Same post-conditions as the UTF-8 case.
     */
    public function testHandlePacketCatchesBadVarIntAndDisconnects(): void {
        $client = $this->makeConnectedClient();

        $connectedProp = new ReflectionProperty(NetworkClient::class, 'connected');
        self::assertTrue($connectedProp->getValue($client));

        // Truncated VarInt in HandshakePacket payload → underflow.
        $frame = $this->buildFrame(Packet::HANDSHAKE, chr(0x80));

        $ref = new ReflectionMethod($client, 'handlePacket');
        $ref->invoke($client, $frame);

        self::assertFalse($connectedProp->getValue($client));
        $reconnectProp = new ReflectionProperty(NetworkClient::class, 'reconnectScheduled');
        self::assertTrue($reconnectProp->getValue($client));
    }

    /**
     * R1 integration: handlePacket catches InvalidArgumentException from
     * fromBytes (oversized string field), logs a warning, and calls
     * handleDisconnect().
     */
    public function testHandlePacketCatchesOversizedFieldAndDisconnects(): void {
        $client = $this->makeConnectedClient();

        $connectedProp = new ReflectionProperty(NetworkClient::class, 'connected');
        self::assertTrue($connectedProp->getValue($client));

        $uuid = random_bytes(16);
        $oversizedLen = ProtocolLimits::MAX_ERROR_CODE + 1;
        $payloadBuf = new PacketBuffer();
        $payloadBuf->writeBoolean(true);
        $payloadBuf->writeVarInt($oversizedLen);
        $payloadBuf->writeBytes(str_repeat('a', $oversizedLen));
        $frame = chr(Packet::HANDSHAKE_RESPONSE) . $uuid . $payloadBuf->getBuffer();

        $ref = new ReflectionMethod($client, 'handlePacket');
        $ref->invoke($client, $frame);

        self::assertFalse($connectedProp->getValue($client));
        $reconnectProp = new ReflectionProperty(NetworkClient::class, 'reconnectScheduled');
        self::assertTrue($reconnectProp->getValue($client));
    }

    /**
     * R2 retention: an unknown packet ID does NOT close the connection.
     * handlePacket logs "Received unknown packet" and returns; the
     * connection survives. This is the documented forward-compat design
     * choice (mirrors Java + Endstone UnknownPacket behavior).
     */
    public function testHandlePacketKeepsConnectionOnUnknownId(): void {
        $client = $this->makeConnectedClient();

        $connectedProp = new ReflectionProperty(NetworkClient::class, 'connected');
        self::assertTrue($connectedProp->getValue($client));

        // 0x00 is not in the factory → fromBytes returns null.
        $frame = $this->buildFrame(0x00);

        $ref = new ReflectionMethod($client, 'handlePacket');
        $ref->invoke($client, $frame);

        // Connection survives — handleDisconnect was NOT called.
        self::assertTrue(
            $connectedProp->getValue($client),
            'connected must remain true after an unknown packet id (R2 design choice)'
        );
        $reconnectProp = new ReflectionProperty(NetworkClient::class, 'reconnectScheduled');
        self::assertFalse(
            $reconnectProp->getValue($client),
            'reconnect must NOT be scheduled for an unknown packet id (R2)'
        );
    }

    // ==================================================================
    // R1 integration helpers
    // ==================================================================

    /**
     * Builds a NetworkClient with the stubbed plugin + a real ConfigManager,
     * then sets the connected/authenticated flags and a fake readBuffer to
     * simulate a live connection. handleDisconnect() cancels read+keepalive
     * tasks (both null here), closes the socket (null here), clears the
     * buffer, resets flags, and calls scheduleReconnect(). Because no socket
     * and no tasks are set, the close path is a no-op, but scheduleReconnect
     * still runs — which is what we assert (reconnectScheduled becomes true).
     *
     * The plugin stub:
     *  - debug(): void — absorbed (no-op callback for void return type).
     *  - getLogger(): returns a stub \AttachableLogger (the pocketmine/log
     *    package interface, no native C extension deps). We only verify
     *    side-effects on NetworkClient state, not log output, because the
     *    real logger is PMMP-specific (MainLogger extends ThreadSafe which
     *    needs the pmmp/thread native extension — not loaded in test mode).
     *  - getScheduler(): returns a stub TaskScheduler. scheduleDelayedTask /
     *    scheduleRepeatingTask are typed : TaskHandler, so PHPUnit auto-
     *    stubs return a stub TaskHandler. scheduleReconnect only reads the
     *    side effect of setting reconnectScheduled=true, so the stub is safe.
     */
    private function makeConnectedClient(): NetworkClient {
        $config = $this->validConfig();
        $configManager = new ConfigManager($config);

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method('debug')->willReturnCallback(static function (): void {});

        // AttachableLogger is a pure interface (no native extension deps),
        // so PHPUnit can stub it without loading pmmp\thread\ThreadSafe.
        $logger = $this->createStub(\AttachableLogger::class);
        $logger->method('warning')->willReturnCallback(static function (): void {});
        $logger->method('info')->willReturnCallback(static function (): void {});
        $plugin->method('getLogger')->willReturn($logger);

        $scheduler = $this->createStub(\pocketmine\scheduler\TaskScheduler::class);
        $plugin->method('getScheduler')->willReturn($scheduler);

        $client = new NetworkClient($plugin, $configManager);

        // Simulate a live connection: set the flags and a non-empty buffer.
        $connectedProp = new ReflectionProperty(NetworkClient::class, 'connected');
        $connectedProp->setValue($client, true);
        $authProp = new ReflectionProperty(NetworkClient::class, 'authenticated');
        $authProp->setValue($client, true);
        $readBufferProp = new ReflectionProperty(NetworkClient::class, 'readBuffer');
        $readBufferProp->setValue($client, 'leftover-bytes');

        return $client;
    }

    /** @return array<string, mixed> */
    private function validConfig(): array {
        return [
            'config-version' => 1,
            'backend' => [
                'host' => '127.0.0.1',
                'port' => 18888,
                'username' => 'PMMP_Server',
                'password' => 'secret',
                'server-version' => '5.0.0',
                'reconnect-delay' => 5,
            ],
            'chat' => [
                'replace_vanilla' => false,
                'default_channel' => 'local',
            ],
            'format' => [
                'prefix' => '[NovaChat] ',
                'error' => 'error: {message}',
                'success' => 'success: {message}',
                'default' => 'default',
                'channels' => ['local' => 'local'],
            ],
            'debug' => false,
        ];
    }
}
