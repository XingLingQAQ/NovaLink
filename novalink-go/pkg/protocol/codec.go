package protocol

import (
	"bytes"
	"fmt"
	"io"
)

// Codec handles encoding and decoding of packets.
type Codec struct{}

const maxFrameLength = 4 * 1024 * 1024 // 4 MiB (must match Java side limits)

// NewCodec creates a new Codec instance.
func NewCodec() *Codec {
	return &Codec{}
}

// EncodePacket encodes a packet into a byte slice with length prefix.
func (c *Codec) EncodePacket(packet Packet, format WireFormat) ([]byte, error) {
	// First, encode the packet content
	contentBuf := NewPacketBuffer()
	
	// Write packet ID
	if err := contentBuf.WriteByte(packet.ID()); err != nil {
		return nil, fmt.Errorf("failed to write packet ID: %w", err)
	}

	// Write requestId for modern framing.
	if format == WireFormatModernWithRequestID || format == WireFormatUnknown {
		requestID := packet.RequestID()
		if IsZeroUUID(requestID) {
			generated, err := NewUUID()
			if err != nil {
				return nil, fmt.Errorf("failed to generate requestId: %w", err)
			}
			packet.SetRequestID(generated)
			requestID = generated
		}
		if err := contentBuf.WriteUUID(requestID); err != nil {
			return nil, fmt.Errorf("failed to write requestId: %w", err)
		}
	}
	
	// Encode packet data
	if err := packet.Encode(contentBuf); err != nil {
		return nil, fmt.Errorf("failed to encode packet: %w", err)
	}
	
	content := contentBuf.Bytes()
	if len(content) > maxFrameLength {
		return nil, fmt.Errorf("%w: %d", ErrPacketTooLarge, len(content))
	}
	
	// Now create the final packet with length prefix
	finalBuf := &bytes.Buffer{}
	if err := WriteVarInt(finalBuf, int32(len(content))); err != nil {
		return nil, fmt.Errorf("failed to write packet length: %w", err)
	}
	finalBuf.Write(content)
	
	return finalBuf.Bytes(), nil
}

// DecodePacket reads and decodes a packet from the reader.
func (c *Codec) DecodePacket(reader io.Reader, format *WireFormat) (Packet, error) {
	// Read packet length
	length, err := ReadVarInt(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to read packet length: %w", err)
	}
	
	if length <= 0 {
		return nil, fmt.Errorf("invalid packet length: %d", length)
	}
	if length > maxFrameLength {
		return nil, fmt.Errorf("%w: %d", ErrPacketTooLarge, length)
	}
	
	// Read packet data
	data := make([]byte, length)
	if _, err := io.ReadFull(reader, data); err != nil {
		return nil, fmt.Errorf("failed to read packet data: %w", err)
	}

	if len(data) < 1 {
		return nil, fmt.Errorf("invalid packet length: %d", length)
	}

	packetID := data[0]
	payload := data[1:]

	wire := WireFormatModernWithRequestID
	if format != nil {
		if *format == WireFormatLegacyNoRequestID {
			wire = WireFormatLegacyNoRequestID
		} else if *format == WireFormatUnknown {
			wire = WireFormatUnknown
		}
	}

	// Unknown format: try modern first, then legacy. Handshake gets an additional plausibility check.
	if wire == WireFormatUnknown {
		if packetID == PacketIDHandshake {
			if p, ok := c.tryDecodeModernHandshake(packetID, payload); ok {
				if format != nil {
					*format = WireFormatModernWithRequestID
				}
				return p, nil
			}
			p, err := c.decodeLegacy(packetID, payload)
			if err != nil {
				return nil, err
			}
			if format != nil {
				*format = WireFormatLegacyNoRequestID
			}
			return p, nil
		}

		if p, err := c.decodeModern(packetID, payload); err == nil {
			if format != nil {
				*format = WireFormatModernWithRequestID
			}
			return p, nil
		}
		p, err := c.decodeLegacy(packetID, payload)
		if err != nil {
			return nil, err
		}
		if format != nil {
			*format = WireFormatLegacyNoRequestID
		}
		return p, nil
	}

	if wire == WireFormatLegacyNoRequestID {
		return c.decodeLegacy(packetID, payload)
	}
	return c.decodeModern(packetID, payload)
}

// createPacket creates a new packet instance based on the packet ID.
func (c *Codec) createPacket(id byte) (Packet, error) {
	switch id {
	case PacketIDHandshake:
		return &HandshakePacket{}, nil
	case PacketIDHandshakeResponse:
		return &HandshakeResponsePacket{}, nil
	case PacketIDChatMessage:
		return &ChatMessagePacket{}, nil
	case PacketIDChannelAction:
		return &ChannelActionPacket{}, nil
	case PacketIDChannelActionResponse:
		return &ChannelActionResponsePacket{}, nil
	case PacketIDConfigSync:
		return &ConfigSyncPacket{}, nil
	case PacketIDKeepAlive:
		return &KeepAlivePacket{}, nil
	case PacketIDTitle:
		return &TitleMessagePacket{}, nil
	case PacketIDAnnouncement:
		return &AnnouncementPacket{}, nil
	case PacketIDAdminAction:
		return &AdminActionPacket{}, nil
	case PacketIDAdminActionResponse:
		return &AdminActionResponsePacket{}, nil
	case PacketIDChannelUpdate:
		return &ChannelUpdatePacket{}, nil
	default:
		return nil, fmt.Errorf("%w: 0x%02X", ErrInvalidPacketID, id)
	}
}

func (c *Codec) decodeLegacy(packetID byte, payload []byte) (Packet, error) {
	packet, err := c.createPacket(packetID)
	if err != nil {
		return nil, err
	}
	packet.SetRequestID([16]byte{})
	if err := packet.Decode(NewPacketBufferFromBytes(payload)); err != nil {
		return nil, fmt.Errorf("failed to decode legacy packet 0x%02X: %w", packetID, err)
	}
	return packet, nil
}

func (c *Codec) decodeModern(packetID byte, payload []byte) (Packet, error) {
	if len(payload) < 16 {
		return nil, fmt.Errorf("modern packet 0x%02X missing requestId", packetID)
	}
	packet, err := c.createPacket(packetID)
	if err != nil {
		return nil, err
	}
	var requestID [16]byte
	copy(requestID[:], payload[:16])
	packet.SetRequestID(requestID)
	if err := packet.Decode(NewPacketBufferFromBytes(payload[16:])); err != nil {
		return nil, fmt.Errorf("failed to decode modern packet 0x%02X: %w", packetID, err)
	}
	return packet, nil
}

func (c *Codec) tryDecodeModernHandshake(packetID byte, payload []byte) (Packet, bool) {
	if len(payload) < 16 {
		return nil, false
	}

	packet, err := c.decodeModern(packetID, payload)
	if err != nil {
		return nil, false
	}

	hs, ok := packet.(*HandshakePacket)
	if !ok {
		return packet, true
	}

	// Heuristic validation to avoid mis-detecting legacy frames as modern.
	plausibleProtocol := hs.ProtocolVersion >= 0 && hs.ProtocolVersion <= 100
	plausibleClient := hs.ClientID != "" && len(hs.ClientID) <= 64
	plausibleHash := hs.PasswordHash != "" && len(hs.PasswordHash) <= 256
	plausiblePlatform := hs.Platform <= 0x20 // current known platform IDs are small

	return packet, plausibleProtocol && plausibleClient && plausibleHash && plausiblePlatform
}
