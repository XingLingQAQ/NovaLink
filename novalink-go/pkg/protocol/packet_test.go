package protocol

import (
	"bytes"
	"reflect"
	"testing"
	"testing/quick"
)

// **Feature: novachat-platform-expansion, Property 2: Packet Serialization Round-Trip (Cross-Language)**
// **Validates: Requirements 13.3**
//
// For any valid packet, serializing and deserializing should produce
// an equivalent packet object.

// TestHandshakePacketRoundTrip tests round-trip serialization for HandshakePacket.
func TestHandshakePacketRoundTrip(t *testing.T) {
	f := func(protocolVersion int32, clientID, passwordHash string, platform byte) bool {
		// Limit string lengths to avoid excessive memory usage
		if len(clientID) > 100 || len(passwordHash) > 100 {
			return true // Skip overly long strings
		}

		original := &HandshakePacket{
			ProtocolVersion: protocolVersion,
			ClientID:        clientID,
			PasswordHash:    passwordHash,
			Platform:        platform,
		}

		// Encode
		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			t.Logf("Encode failed: %v", err)
			return false
		}

		// Decode
		decoded := &HandshakePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			t.Logf("Decode failed: %v", err)
			return false
		}

		// Compare
		if !reflect.DeepEqual(original, decoded) {
			t.Logf("Round-trip failed: original=%+v, decoded=%+v", original, decoded)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("HandshakePacket round-trip property failed: %v", err)
	}
}

// TestHandshakeResponsePacketRoundTrip tests round-trip serialization for HandshakeResponsePacket.
func TestHandshakeResponsePacketRoundTrip(t *testing.T) {
	f := func(success bool, errorCode, message string) bool {
		if len(errorCode) > 100 || len(message) > 500 {
			return true
		}

		original := &HandshakeResponsePacket{
			Success:    success,
			ErrorCode:  errorCode,
			Message:    message,
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &HandshakeResponsePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("HandshakeResponsePacket round-trip property failed: %v", err)
	}
}

// TestChatMessagePacketRoundTrip tests round-trip serialization for ChatMessagePacket.
func TestChatMessagePacketRoundTrip(t *testing.T) {
	f := func(senderID [16]byte, senderName, clientID, channelID, content string) bool {
		if len(senderName) > 50 || len(clientID) > 50 || len(channelID) > 50 || len(content) > 500 {
			return true
		}

		original := &ChatMessagePacket{
			SenderID:     senderID,
			SenderName:   senderName,
			ClientID:     clientID,
			ChannelID:    channelID,
			Content:      content,
			Placeholders: map[string]string{"key1": "value1", "key2": "value2"},
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &ChatMessagePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("ChatMessagePacket round-trip property failed: %v", err)
	}
}

// TestChannelActionPacketRoundTrip tests round-trip serialization for ChannelActionPacket.
func TestChannelActionPacketRoundTrip(t *testing.T) {
	f := func(action byte, channelID, password, extraValue string) bool {
		if len(channelID) > 50 || len(password) > 50 || len(extraValue) > 200 {
			return true
		}

		original := &ChannelActionPacket{
			Action:    action,
			ChannelID: channelID,
			Password:  password,
			Extra:     map[string]string{"extra": extraValue},
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &ChannelActionPacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("ChannelActionPacket round-trip property failed: %v", err)
	}
}

// TestChannelUpdatePacketRoundTrip tests round-trip serialization for ChannelUpdatePacket.
func TestChannelUpdatePacketRoundTrip(t *testing.T) {
	f := func(action byte, channelID, channelJSON string) bool {
		if len(channelID) > 50 || len(channelJSON) > 500 {
			return true
		}

		original := &ChannelUpdatePacket{
			Action:      action,
			ChannelID:   channelID,
			ChannelJSON: channelJSON,
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &ChannelUpdatePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("ChannelUpdatePacket round-trip property failed: %v", err)
	}
}

// TestAnnouncementPacketRoundTrip tests round-trip serialization for AnnouncementPacket.
func TestAnnouncementPacketRoundTrip(t *testing.T) {
	f := func(announcementType byte, content string) bool {
		if len(content) > 500 {
			return true
		}

		original := &AnnouncementPacket{
			Type:    announcementType,
			Content: content,
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &AnnouncementPacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("AnnouncementPacket round-trip property failed: %v", err)
	}
}

// TestKeepAlivePacketRoundTrip tests round-trip serialization for KeepAlivePacket.
func TestKeepAlivePacketRoundTrip(t *testing.T) {
	f := func(timestamp int64) bool {
		original := &KeepAlivePacket{
			Timestamp: timestamp,
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &KeepAlivePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("KeepAlivePacket round-trip property failed: %v", err)
	}
}

// TestTitleMessagePacketRoundTrip tests round-trip serialization for TitleMessagePacket.
func TestTitleMessagePacketRoundTrip(t *testing.T) {
	f := func(title, subtitle string, fadeIn, stay, fadeOut int32) bool {
		if len(title) > 100 || len(subtitle) > 100 {
			return true
		}

		original := &TitleMessagePacket{
			ChannelID: "global",
			Title:    title,
			Subtitle: subtitle,
			FadeIn:   fadeIn,
			Stay:     stay,
			FadeOut:  fadeOut,
		}

		buf := NewPacketBuffer()
		if err := original.Encode(buf); err != nil {
			return false
		}

		decoded := &TitleMessagePacket{}
		readBuf := NewPacketBufferFromBytes(buf.Bytes())
		if err := decoded.Decode(readBuf); err != nil {
			return false
		}

		return reflect.DeepEqual(original, decoded)
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("TitleMessagePacket round-trip property failed: %v", err)
	}
}

// TestCodecRoundTrip tests the full codec encode/decode cycle with length prefix.
func TestCodecRoundTrip(t *testing.T) {
	codec := NewCodec()

	testPackets := []Packet{
		&HandshakePacket{
			ProtocolVersion: 1,
			ClientID:        "test-client",
			PasswordHash:    "hash123",
			Platform:        PlatformBukkit,
		},
		&HandshakeResponsePacket{
			Success:    true,
			ErrorCode:  "",
			Message:    "OK",
		},
		&ChatMessagePacket{
			SenderID:     [16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
			SenderName:   "Player1",
			ClientID:     "server1",
			ChannelID:    "global",
			Content:      "Hello, world!",
			Placeholders: map[string]string{},
		},
		&ChannelActionPacket{
			Action:    ActionJoin,
			ChannelID: "global",
			Password:  "",
			Extra:     map[string]string{},
		},
		&KeepAlivePacket{
			Timestamp: 1234567890,
		},
		&TitleMessagePacket{
			ChannelID: "global",
			Title:    "Welcome",
			Subtitle: "to the server",
			FadeIn:   10,
			Stay:     70,
			FadeOut:  20,
		},
	}

	for _, original := range testPackets {
		t.Run(reflect.TypeOf(original).Elem().Name(), func(t *testing.T) {
			// Encode with codec
			encoded, err := codec.EncodePacket(original, WireFormatModernWithRequestID)
			if err != nil {
				t.Fatalf("EncodePacket failed: %v", err)
			}

			// Decode with codec
			format := WireFormatModernWithRequestID
			decoded, err := codec.DecodePacket(bytes.NewReader(encoded), &format)
			if err != nil {
				t.Fatalf("DecodePacket failed: %v", err)
			}

			// Compare
			if !reflect.DeepEqual(original, decoded) {
				t.Errorf("Codec round-trip failed:\noriginal=%+v\ndecoded=%+v", original, decoded)
			}
		})
	}
}

// TestPacketBufferDataTypes tests all data type read/write operations.
func TestPacketBufferDataTypes(t *testing.T) {
	t.Run("String", func(t *testing.T) {
		f := func(s string) bool {
			if len(s) > 1000 {
				return true
			}
			buf := NewPacketBuffer()
			if err := buf.WriteString(s); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadString()
			if err != nil {
				return false
			}
			return s == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("String round-trip failed: %v", err)
		}
	})

	t.Run("Int32", func(t *testing.T) {
		f := func(v int32) bool {
			buf := NewPacketBuffer()
			if err := buf.WriteInt32(v); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadInt32()
			if err != nil {
				return false
			}
			return v == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("Int32 round-trip failed: %v", err)
		}
	})

	t.Run("Int64", func(t *testing.T) {
		f := func(v int64) bool {
			buf := NewPacketBuffer()
			if err := buf.WriteInt64(v); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadInt64()
			if err != nil {
				return false
			}
			return v == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("Int64 round-trip failed: %v", err)
		}
	})

	t.Run("Bool", func(t *testing.T) {
		f := func(v bool) bool {
			buf := NewPacketBuffer()
			if err := buf.WriteBool(v); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadBool()
			if err != nil {
				return false
			}
			return v == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("Bool round-trip failed: %v", err)
		}
	})

	t.Run("UUID", func(t *testing.T) {
		f := func(uuid [16]byte) bool {
			buf := NewPacketBuffer()
			if err := buf.WriteUUID(uuid); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadUUID()
			if err != nil {
				return false
			}
			return uuid == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("UUID round-trip failed: %v", err)
		}
	})

	t.Run("Byte", func(t *testing.T) {
		f := func(v byte) bool {
			buf := NewPacketBuffer()
			if err := buf.WriteByte(v); err != nil {
				return false
			}
			readBuf := NewPacketBufferFromBytes(buf.Bytes())
			decoded, err := readBuf.ReadByte()
			if err != nil {
				return false
			}
			return v == decoded
		}
		if err := quick.Check(f, &quick.Config{MaxCount: 100}); err != nil {
			t.Errorf("Byte round-trip failed: %v", err)
		}
	})
}
