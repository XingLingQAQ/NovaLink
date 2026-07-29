package protocol

import (
	"bytes"
	"encoding/hex"
	"testing"
	"testing/quick"
)

// **Feature: novachat-platform-expansion, Property 4: Go-Java Protocol Compatibility**
// **Validates: Requirements 19.1-19.5**
//
// For any packet sent from a NovaChat client, both NovaLink-Java and NovaLink-Go
// should parse it identically and produce the same response.

// TestVarIntJavaCompatibility verifies VarInt encoding matches Java implementation.
// Java uses unsigned right shift (>>>) which is equivalent to Go's >> on uint32.
func TestVarIntJavaCompatibility(t *testing.T) {
	// Test cases with known Java-encoded values
	testCases := []struct {
		name     string
		value    int32
		expected []byte
	}{
		{"zero", 0, []byte{0x00}},
		{"one", 1, []byte{0x01}},
		{"127", 127, []byte{0x7F}},
		{"128", 128, []byte{0x80, 0x01}},
		{"255", 255, []byte{0xFF, 0x01}},
		{"16383", 16383, []byte{0xFF, 0x7F}},
		{"16384", 16384, []byte{0x80, 0x80, 0x01}},
		{"2097151", 2097151, []byte{0xFF, 0xFF, 0x7F}},
		{"2097152", 2097152, []byte{0x80, 0x80, 0x80, 0x01}},
		{"268435455", 268435455, []byte{0xFF, 0xFF, 0xFF, 0x7F}},
		{"268435456", 268435456, []byte{0x80, 0x80, 0x80, 0x80, 0x01}},
		{"max_int32", 2147483647, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0x07}},
		// Negative numbers use 5 bytes (sign extension)
		{"negative_one", -1, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0x0F}},
		{"min_int32", -2147483648, []byte{0x80, 0x80, 0x80, 0x80, 0x08}},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			encoded := EncodeVarInt(tc.value)
			if !bytes.Equal(encoded, tc.expected) {
				t.Errorf("VarInt encoding mismatch for %d:\n  got:      %s\n  expected: %s",
					tc.value, hex.EncodeToString(encoded), hex.EncodeToString(tc.expected))
			}

			// Verify round-trip
			decoded, _, err := ReadVarIntFromBytes(encoded, 0)
			if err != nil {
				t.Fatalf("Failed to decode: %v", err)
			}
			if decoded != tc.value {
				t.Errorf("Round-trip failed: original=%d, decoded=%d", tc.value, decoded)
			}
		})
	}
}

// TestStringEncodingJavaCompatibility verifies string encoding matches Java.
// Java uses UTF-8 encoding with VarInt length prefix.
func TestStringEncodingJavaCompatibility(t *testing.T) {
	testCases := []struct {
		name     string
		value    string
		expected []byte
	}{
		{"empty", "", []byte{0x00}},
		{"hello", "Hello", []byte{0x05, 'H', 'e', 'l', 'l', 'o'}},
		{"unicode", "你好", []byte{0x06, 0xE4, 0xBD, 0xA0, 0xE5, 0xA5, 0xBD}},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			buf := NewPacketBuffer()
			if err := buf.WriteString(tc.value); err != nil {
				t.Fatalf("WriteString failed: %v", err)
			}

			if !bytes.Equal(buf.Bytes(), tc.expected) {
				t.Errorf("String encoding mismatch for %q:\n  got:      %s\n  expected: %s",
					tc.value, hex.EncodeToString(buf.Bytes()), hex.EncodeToString(tc.expected))
			}

			// Verify round-trip
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadString()
			if err != nil {
				t.Fatalf("ReadString failed: %v", err)
			}
			if decoded != tc.value {
				t.Errorf("Round-trip failed: original=%q, decoded=%q", tc.value, decoded)
			}
		})
	}
}

// TestUUIDEncodingJavaCompatibility verifies UUID encoding matches Java.
// Java writes UUID as two 64-bit big-endian longs (most significant bits first).
func TestUUIDEncodingJavaCompatibility(t *testing.T) {
	// UUID: 01234567-89ab-cdef-0123-456789abcdef
	// Most significant bits: 0x0123456789abcdef
	// Least significant bits: 0x0123456789abcdef
	uuid := [16]byte{
		0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
		0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
	}

	buf := NewPacketBuffer()
	if err := buf.WriteUUID(uuid); err != nil {
		t.Fatalf("WriteUUID failed: %v", err)
	}

	// Should be exactly 16 bytes
	if buf.Len() != 16 {
		t.Errorf("UUID should be 16 bytes, got %d", buf.Len())
	}

	// Verify bytes match
	if !bytes.Equal(buf.Bytes(), uuid[:]) {
		t.Errorf("UUID encoding mismatch:\n  got:      %s\n  expected: %s",
			hex.EncodeToString(buf.Bytes()), hex.EncodeToString(uuid[:]))
	}

	// Verify round-trip
	readBuf := NewPacketBufferFromBytes(buf.Bytes())
	decoded, err := readBuf.ReadUUID()
	if err != nil {
		t.Fatalf("ReadUUID failed: %v", err)
	}
	if decoded != uuid {
		t.Errorf("UUID round-trip failed")
	}
}

// TestInt64BigEndianCompatibility verifies int64 uses big-endian encoding like Java.
func TestInt64BigEndianCompatibility(t *testing.T) {
	testCases := []struct {
		name     string
		value    int64
		expected []byte
	}{
		{"zero", 0, []byte{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
		{"one", 1, []byte{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01}},
		{"max_int64", 9223372036854775807, []byte{0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},
		{"negative_one", -1, []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			buf := NewPacketBuffer()
			if err := buf.WriteInt64(tc.value); err != nil {
				t.Fatalf("WriteInt64 failed: %v", err)
			}

			if !bytes.Equal(buf.Bytes(), tc.expected) {
				t.Errorf("Int64 encoding mismatch for %d:\n  got:      %s\n  expected: %s",
					tc.value, hex.EncodeToString(buf.Bytes()), hex.EncodeToString(tc.expected))
			}
		})
	}
}

// TestHandshakePacketJavaCompatibility verifies HandshakePacket format matches Java.
func TestHandshakePacketJavaCompatibility(t *testing.T) {
	packet := &HandshakePacket{
		ProtocolVersion: 1,
		ClientID:        "test",
		PasswordHash:    "hash",
		Platform:        PlatformBukkit,
	}

	buf := NewPacketBuffer()
	if err := packet.Encode(buf); err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	// Expected format:
	// - VarInt: protocol version (1) = 0x01
	// - String: clientId "test" = 0x04 + "test"
	// - String: passwordHash "hash" = 0x04 + "hash"
	// - Byte: platform (1) = 0x01
	expected := []byte{
		0x01,                   // protocol version
		0x04, 't', 'e', 's', 't', // clientId
		0x04, 'h', 'a', 's', 'h', // passwordHash
		0x01, // platform
	}

	if !bytes.Equal(buf.Bytes(), expected) {
		t.Errorf("HandshakePacket encoding mismatch:\n  got:      %s\n  expected: %s",
			hex.EncodeToString(buf.Bytes()), hex.EncodeToString(expected))
	}
}

// TestKeepAlivePacketJavaCompatibility verifies KeepAlivePacket format matches Java.
func TestKeepAlivePacketJavaCompatibility(t *testing.T) {
	packet := &KeepAlivePacket{
		Timestamp: 1234567890123,
	}

	buf := NewPacketBuffer()
	if err := packet.Encode(buf); err != nil {
		t.Fatalf("Encode failed: %v", err)
	}

	// Expected: big-endian int64
	// 1234567890123 = 0x0000011F71FB04CB
	expected := []byte{0x00, 0x00, 0x01, 0x1F, 0x71, 0xFB, 0x04, 0xCB}

	if !bytes.Equal(buf.Bytes(), expected) {
		t.Errorf("KeepAlivePacket encoding mismatch:\n  got:      %s\n  expected: %s",
			hex.EncodeToString(buf.Bytes()), hex.EncodeToString(expected))
	}
}

// TestProtocolVersionMatchesJava verifies protocol version matches Java implementation.
// Requirements: 27.5 - Go and Java backends must use the same protocol version.
func TestProtocolVersionMatchesJava(t *testing.T) {
	// This value must match NovaProtocol.PROTOCOL_VERSION in Java
	const javaProtocolVersion int32 = 1
	
	if ProtocolVersion != javaProtocolVersion {
		t.Errorf("Protocol version mismatch: Go=%d, Java=%d. "+
			"Update both novalink-go/pkg/protocol/packet.go and "+
			"novachat-common/src/main/java/com/nova/chat/common/protocol/NovaProtocol.java",
			ProtocolVersion, javaProtocolVersion)
	}
}

// TestPacketIDsMatchJava verifies packet IDs match Java PacketIds.java
func TestPacketIDsMatchJava(t *testing.T) {
	// These values must match PacketIds.java exactly
	testCases := []struct {
		name     string
		goID     byte
		javaID   byte
	}{
		{"HANDSHAKE", PacketIDHandshake, 0x01},
		{"HANDSHAKE_RESPONSE", PacketIDHandshakeResponse, 0x02},
		{"CHAT_MESSAGE", PacketIDChatMessage, 0x03},
		{"CHANNEL_ACTION", PacketIDChannelAction, 0x04},
		{"CHANNEL_ACTION_RESPONSE", PacketIDChannelActionResponse, 0x05},
		{"CONFIG_SYNC", PacketIDConfigSync, 0x06},
		{"KEEP_ALIVE", PacketIDKeepAlive, 0x07},
		{"PLAYER_STATE", PacketIDPlayerState, 0x08},
		{"TITLE", PacketIDTitle, 0x09},
		{"ANNOUNCEMENT", PacketIDAnnouncement, 0x0A},
		{"ADMIN_ACTION", PacketIDAdminAction, 0x0B},
		{"ADMIN_ACTION_RESPONSE", PacketIDAdminActionResponse, 0x0C},
		{"CHANNEL_UPDATE", PacketIDChannelUpdate, 0x0D},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			if tc.goID != tc.javaID {
				t.Errorf("Packet ID mismatch for %s: Go=0x%02X, Java=0x%02X", tc.name, tc.goID, tc.javaID)
			}
		})
	}
}

// TestCrossLanguageVarIntProperty tests VarInt encoding produces consistent results
// that would be parseable by Java implementation.
func TestCrossLanguageVarIntProperty(t *testing.T) {
	f := func(value int32) bool {
		encoded := EncodeVarInt(value)

		// Verify encoding follows VarInt spec:
		// - Each byte uses 7 bits for data
		// - MSB indicates continuation
		// - Maximum 5 bytes for int32

		if len(encoded) > 5 {
			t.Logf("VarInt too long for value %d: %d bytes", value, len(encoded))
			return false
		}

		// All bytes except the last should have MSB set
		for i := 0; i < len(encoded)-1; i++ {
			if (encoded[i] & 0x80) == 0 {
				t.Logf("Missing continuation bit at byte %d for value %d", i, value)
				return false
			}
		}

		// Last byte should NOT have MSB set
		if (encoded[len(encoded)-1] & 0x80) != 0 {
			t.Logf("Unexpected continuation bit in last byte for value %d", value)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Cross-language VarInt property failed: %v", err)
	}
}

// TestCrossLanguagePacketProperty tests that all packets can be encoded and decoded
// in a format compatible with Java.
func TestCrossLanguagePacketProperty(t *testing.T) {
	codec := NewCodec()

	// Test with various packet types
	packets := []Packet{
		&HandshakePacket{
			ProtocolVersion: 1,
			ClientID:        "TestServer",
			PasswordHash:    "abc123hash",
			Platform:        PlatformBukkit,
		},
		&KeepAlivePacket{
			Timestamp: 1702300800000, // Example timestamp
		},
		&ChatMessagePacket{
			SenderID:     [16]byte{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10},
			SenderName:   "Player1",
			ClientID:     "server1",
			ChannelID:    "global",
			Content:      "Hello World!",
			Placeholders: map[string]string{},
		},
	}

	for _, original := range packets {
		// Encode
		encoded, err := codec.EncodePacket(original, WireFormatModernWithRequestID)
		if err != nil {
			t.Errorf("Failed to encode %T: %v", original, err)
			continue
		}

		// Verify packet structure:
		// [VarInt length][byte packetID][packet data]
		if len(encoded) < 2 {
			t.Errorf("Encoded packet too short: %d bytes", len(encoded))
			continue
		}

		// Read length prefix
		length, bytesRead, err := ReadVarIntFromBytes(encoded, 0)
		if err != nil {
			t.Errorf("Failed to read length prefix: %v", err)
			continue
		}

		// Verify length matches remaining data
		remainingBytes := len(encoded) - bytesRead
		if int(length) != remainingBytes {
			t.Errorf("Length mismatch: prefix=%d, remaining=%d", length, remainingBytes)
			continue
		}

		// Verify packet ID
		packetID := encoded[bytesRead]
		if packetID != original.ID() {
			t.Errorf("Packet ID mismatch: expected=0x%02X, got=0x%02X", original.ID(), packetID)
		}

		// Decode and verify round-trip
		format := WireFormatModernWithRequestID
		decoded, err := codec.DecodePacket(bytes.NewReader(encoded), &format)
		if err != nil {
			t.Errorf("Failed to decode %T: %v", original, err)
			continue
		}

		// Type should match
		if decoded.ID() != original.ID() {
			t.Errorf("Decoded packet ID mismatch: expected=0x%02X, got=0x%02X", original.ID(), decoded.ID())
		}
	}
}
