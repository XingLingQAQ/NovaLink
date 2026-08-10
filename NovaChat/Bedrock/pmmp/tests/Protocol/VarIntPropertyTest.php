<?php

declare(strict_types=1);

namespace NovaChat\Tests\Protocol;

use Eris\Generator;
use Eris\TestTrait;
use NovaChat\Protocol\VarInt;
use PHPUnit\Framework\TestCase;

/**
 * Property-based tests for VarInt encoding/decoding.
 * 
 * **Feature: novachat-platform-expansion, Property 1: VarInt Encoding Round-Trip (Cross-Language)**
 * **Validates: Requirements 9.1**
 * 
 * For any valid integer value within VarInt range, encoding and then decoding
 * should produce the original value.
 */
class VarIntPropertyTest extends TestCase {
    use TestTrait;

    /**
     * Property 1: VarInt Encoding Round-Trip
     * 
     * For any valid integer value within VarInt range (-2147483648 to 2147483647),
     * encoding to VarInt bytes and decoding back should produce the original value.
     */
    public function testVarIntRoundTrip(): void {
        $this->forAll(
            Generator\choose(VarInt::MIN_VALUE, VarInt::MAX_VALUE)
        )
        ->withMaxSize(100)
        ->then(function (int $value): void {
            // Encode the value
            $encoded = VarInt::write($value);
            
            // Decode the value
            $offset = 0;
            $decoded = VarInt::read($encoded, $offset);
            
            // Assert round-trip produces original value
            $this->assertSame(
                $value, 
                $decoded, 
                "VarInt round-trip failed for value: $value"
            );
            
            // Assert all bytes were consumed
            $this->assertSame(
                strlen($encoded), 
                $offset, 
                "Not all bytes consumed for value: $value"
            );
        });
    }

    /**
     * Property: VarInt size calculation is consistent with actual encoding.
     * 
     * For any valid integer, the calculated size should match the actual encoded length.
     */
    public function testVarIntSizeConsistency(): void {
        $this->forAll(
            Generator\choose(VarInt::MIN_VALUE, VarInt::MAX_VALUE)
        )
        ->withMaxSize(100)
        ->then(function (int $value): void {
            $encoded = VarInt::write($value);
            $calculatedSize = VarInt::size($value);
            
            $this->assertSame(
                strlen($encoded),
                $calculatedSize,
                "VarInt size mismatch for value: $value"
            );
        });
    }

    /**
     * Property: VarInt encoding produces valid byte sequences.
     * 
     * For any valid integer, the encoded bytes should follow VarInt format:
     * - Each byte except the last has the high bit set
     * - The last byte has the high bit clear
     */
    public function testVarIntEncodingFormat(): void {
        $this->forAll(
            Generator\choose(VarInt::MIN_VALUE, VarInt::MAX_VALUE)
        )
        ->withMaxSize(100)
        ->then(function (int $value): void {
            $encoded = VarInt::write($value);
            $length = strlen($encoded);
            
            // Check all bytes except the last have high bit set
            for ($i = 0; $i < $length - 1; $i++) {
                $byte = ord($encoded[$i]);
                $this->assertTrue(
                    ($byte & 0x80) !== 0,
                    "Byte $i should have high bit set for value: $value"
                );
            }
            
            // Check the last byte has high bit clear
            $lastByte = ord($encoded[$length - 1]);
            $this->assertTrue(
                ($lastByte & 0x80) === 0,
                "Last byte should have high bit clear for value: $value"
            );
        });
    }

    /**
     * Property: VarInt encoding length is bounded.
     * 
     * For any valid 32-bit integer, the encoded length should be at most 5 bytes.
     */
    public function testVarIntEncodingLengthBounded(): void {
        $this->forAll(
            Generator\choose(VarInt::MIN_VALUE, VarInt::MAX_VALUE)
        )
        ->withMaxSize(100)
        ->then(function (int $value): void {
            $encoded = VarInt::write($value);
            
            $this->assertLessThanOrEqual(
                VarInt::MAX_BYTES,
                strlen($encoded),
                "VarInt encoding too long for value: $value"
            );
        });
    }
}
