package protocol

import (
	"bytes"
	"testing"
	"testing/quick"
)

// **Feature: novachat-platform-expansion, Property 1: VarInt Encoding Round-Trip (Cross-Language)**
// **Validates: Requirements 13.2**
//
// For any valid integer value within VarInt range, encoding and decoding
// should produce the original value.
func TestVarIntRoundTrip(t *testing.T) {
	f := func(value int32) bool {
		// Encode the value
		buf := &bytes.Buffer{}
		if err := WriteVarInt(buf, value); err != nil {
			t.Logf("WriteVarInt failed for value %d: %v", value, err)
			return false
		}

		// Decode the value
		decoded, err := ReadVarInt(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Logf("ReadVarInt failed for value %d: %v", value, err)
			return false
		}

		// Verify round-trip
		if decoded != value {
			t.Logf("Round-trip failed: original=%d, decoded=%d", value, decoded)
			return false
		}

		return true
	}

	// Run property test with 100+ iterations
	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("VarInt round-trip property failed: %v", err)
	}
}

// TestVarIntSizeConsistency verifies that VarIntSize returns the correct
// number of bytes for encoding.
func TestVarIntSizeConsistency(t *testing.T) {
	f := func(value int32) bool {
		// Calculate expected size
		expectedSize := VarIntSize(value)

		// Encode and check actual size
		buf := &bytes.Buffer{}
		if err := WriteVarInt(buf, value); err != nil {
			return false
		}

		actualSize := buf.Len()
		if actualSize != expectedSize {
			t.Logf("Size mismatch for value %d: expected=%d, actual=%d", value, expectedSize, actualSize)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("VarInt size consistency property failed: %v", err)
	}
}

// TestVarIntEncodeDecodeConsistency verifies EncodeVarInt and ReadVarIntFromBytes
// are consistent with WriteVarInt and ReadVarInt.
func TestVarIntEncodeDecodeConsistency(t *testing.T) {
	f := func(value int32) bool {
		// Method 1: WriteVarInt + ReadVarInt
		buf1 := &bytes.Buffer{}
		if err := WriteVarInt(buf1, value); err != nil {
			return false
		}
		decoded1, err := ReadVarInt(bytes.NewReader(buf1.Bytes()))
		if err != nil {
			return false
		}

		// Method 2: EncodeVarInt + ReadVarIntFromBytes
		encoded := EncodeVarInt(value)
		decoded2, bytesRead, err := ReadVarIntFromBytes(encoded, 0)
		if err != nil {
			return false
		}

		// Both methods should produce the same result
		if decoded1 != decoded2 {
			t.Logf("Inconsistent decoding for value %d: method1=%d, method2=%d", value, decoded1, decoded2)
			return false
		}

		// Bytes read should match encoded length
		if bytesRead != len(encoded) {
			t.Logf("Bytes read mismatch for value %d: expected=%d, actual=%d", value, len(encoded), bytesRead)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("VarInt encode/decode consistency property failed: %v", err)
	}
}

// TestVarIntBoundaryValues tests specific boundary values for VarInt encoding.
func TestVarIntBoundaryValues(t *testing.T) {
	testCases := []struct {
		name     string
		value    int32
		expected int // expected byte size
	}{
		{"zero", 0, 1},
		{"one", 1, 1},
		{"max_1_byte", 127, 1},
		{"min_2_bytes", 128, 2},
		{"max_2_bytes", 16383, 2},
		{"min_3_bytes", 16384, 3},
		{"max_3_bytes", 2097151, 3},
		{"min_4_bytes", 2097152, 4},
		{"max_4_bytes", 268435455, 4},
		{"min_5_bytes", 268435456, 5},
		{"max_int32", 2147483647, 5},
		{"negative_one", -1, 5},
		{"min_int32", -2147483648, 5},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			// Test encoding size
			size := VarIntSize(tc.value)
			if size != tc.expected {
				t.Errorf("VarIntSize(%d) = %d, expected %d", tc.value, size, tc.expected)
			}

			// Test round-trip
			buf := &bytes.Buffer{}
			if err := WriteVarInt(buf, tc.value); err != nil {
				t.Fatalf("WriteVarInt failed: %v", err)
			}

			if buf.Len() != tc.expected {
				t.Errorf("Encoded length for %d = %d, expected %d", tc.value, buf.Len(), tc.expected)
			}

			decoded, err := ReadVarInt(bytes.NewReader(buf.Bytes()))
			if err != nil {
				t.Fatalf("ReadVarInt failed: %v", err)
			}

			if decoded != tc.value {
				t.Errorf("Round-trip failed: original=%d, decoded=%d", tc.value, decoded)
			}
		})
	}
}
