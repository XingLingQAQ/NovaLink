package protocol

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
)

// ProtocolVersion is the current NovaProtocol version.
// This must match the Java implementation (NovaProtocol.PROTOCOL_VERSION = 1).
// Requirements: 27.5 - Go and Java backends must use the same protocol version.
// IMPORTANT: When updating this value, also update:
//   - novachat-common/src/main/java/com/nova/chat/common/protocol/NovaProtocol.java
//   - novachat-common/src/main/java/com/nova/chat/common/NovaConstants.java
//   - novachat-pmmp/src/NovaChat/Protocol/HandshakePacket.php
//   - novachat-endstone/novachat_endstone/network/client.py
const ProtocolVersion int32 = 1

// Packet IDs matching the Java implementation (PacketIds.java)
const (
	PacketIDHandshake               = 0x01
	PacketIDHandshakeResponse       = 0x02
	PacketIDChatMessage             = 0x03
	PacketIDChannelAction           = 0x04
	PacketIDChannelActionResponse   = 0x05
	PacketIDConfigSync              = 0x06
	PacketIDKeepAlive               = 0x07
	PacketIDPlayerState             = 0x08
	PacketIDTitle                   = 0x09
	PacketIDAnnouncement            = 0x0A
	PacketIDAdminAction             = 0x0B
	PacketIDAdminActionResponse     = 0x0C
	PacketIDChannelUpdate           = 0x0D
	// Aliases for backward compatibility
	PacketIDTitleMessage = PacketIDTitle
)

// Channel action types
const (
	// IDs MUST match novachat-common ChannelAction.java (Java canonical).
	ActionJoin   byte = 0x00
	ActionLeave  byte = 0x01
	ActionCreate byte = 0x02
	ActionDelete byte = 0x03
	ActionInvite byte = 0x04
	ActionAccept byte = 0x05
	ActionKick   byte = 0x06
	ActionMute   byte = 0x07
	ActionUnmute byte = 0x08
)

// Platform types
const (
	// IDs MUST match novachat-common PlatformType.java (Java canonical).
	PlatformBukkit     byte = 0
	PlatformVelocity   byte = 1
	PlatformBungeeCord byte = 2
	PlatformNukkit     byte = 3
	PlatformLeviLamina byte = 4
	PlatformFabric     byte = 5
	PlatformNeoForge   byte = 6
	PlatformQuilt      byte = 7
	PlatformForge      byte = 8
	PlatformPMMP       byte = 9
	PlatformEndstone   byte = 10
	PlatformPNX        byte = 11
	PlatformMultiPaper byte = 12
	PlatformFolia      byte = 13
	PlatformSponge     byte = 14
)

var (
	ErrInvalidPacketID = errors.New("invalid packet ID")
	ErrPacketTooLarge  = errors.New("packet too large")
)

// Packet is the interface that all packet types must implement.
type Packet interface {
	ID() byte
	Encode(buf *PacketBuffer) error
	Decode(buf *PacketBuffer) error
	RequestID() [16]byte
	SetRequestID(id [16]byte)
}

// BasePacket provides RequestID support for all packets.
// It is meant to be embedded in packet structs.
type BasePacket struct {
	requestID [16]byte
}

func (p *BasePacket) RequestID() [16]byte {
	return p.requestID
}

func (p *BasePacket) SetRequestID(id [16]byte) {
	p.requestID = id
}

// PacketBuffer provides methods for reading and writing packet data.
type PacketBuffer struct {
	buf *bytes.Buffer
}

// NewPacketBuffer creates a new empty PacketBuffer for writing.
func NewPacketBuffer() *PacketBuffer {
	return &PacketBuffer{buf: &bytes.Buffer{}}
}

// NewPacketBufferFromBytes creates a PacketBuffer from existing data for reading.
func NewPacketBufferFromBytes(data []byte) *PacketBuffer {
	return &PacketBuffer{buf: bytes.NewBuffer(data)}
}

// Bytes returns the underlying byte slice.
func (p *PacketBuffer) Bytes() []byte {
	return p.buf.Bytes()
}

// Len returns the number of bytes in the buffer.
func (p *PacketBuffer) Len() int {
	return p.buf.Len()
}

// WriteVarInt writes a VarInt to the buffer.
func (p *PacketBuffer) WriteVarInt(value int32) error {
	return WriteVarInt(p.buf, value)
}

// ReadVarInt reads a VarInt from the buffer.
func (p *PacketBuffer) ReadVarInt() (int32, error) {
	return ReadVarInt(p.buf)
}

// WriteString writes a length-prefixed string to the buffer.
func (p *PacketBuffer) WriteString(s string) error {
	data := []byte(s)
	if err := p.WriteVarInt(int32(len(data))); err != nil {
		return err
	}
	_, err := p.buf.Write(data)
	return err
}

// ReadString reads a length-prefixed string from the buffer.
func (p *PacketBuffer) ReadString() (string, error) {
	length, err := p.ReadVarInt()
	if err != nil {
		return "", err
	}
	if length < 0 {
		return "", errors.New("negative string length")
	}
	if length > int32(p.buf.Len()) {
		return "", errors.New("string length exceeds remaining bytes")
	}
	if length > int32(maxFrameLength) {
		return "", ErrPacketTooLarge
	}
	data := make([]byte, length)
	if _, err := io.ReadFull(p.buf, data); err != nil {
		return "", err
	}
	return string(data), nil
}

// WriteByte writes a single byte to the buffer.
func (p *PacketBuffer) WriteByte(b byte) error {
	return p.buf.WriteByte(b)
}

// ReadByte reads a single byte from the buffer.
func (p *PacketBuffer) ReadByte() (byte, error) {
	return p.buf.ReadByte()
}

// WriteBool writes a boolean as a single byte.
func (p *PacketBuffer) WriteBool(b bool) error {
	if b {
		return p.WriteByte(1)
	}
	return p.WriteByte(0)
}

// ReadBool reads a boolean from a single byte.
func (p *PacketBuffer) ReadBool() (bool, error) {
	b, err := p.ReadByte()
	if err != nil {
		return false, err
	}
	return b != 0, nil
}

// WriteInt32 writes a 32-bit integer in big-endian order.
func (p *PacketBuffer) WriteInt32(value int32) error {
	return binary.Write(p.buf, binary.BigEndian, value)
}

// ReadInt32 reads a 32-bit integer in big-endian order.
func (p *PacketBuffer) ReadInt32() (int32, error) {
	var value int32
	err := binary.Read(p.buf, binary.BigEndian, &value)
	return value, err
}

// WriteInt64 writes a 64-bit integer in big-endian order.
func (p *PacketBuffer) WriteInt64(value int64) error {
	return binary.Write(p.buf, binary.BigEndian, value)
}

// ReadInt64 reads a 64-bit integer in big-endian order.
func (p *PacketBuffer) ReadInt64() (int64, error) {
	var value int64
	err := binary.Read(p.buf, binary.BigEndian, &value)
	return value, err
}

// WriteUUID writes a UUID as two 64-bit integers (most significant bits first).
func (p *PacketBuffer) WriteUUID(uuid [16]byte) error {
	_, err := p.buf.Write(uuid[:])
	return err
}

// ReadUUID reads a UUID as 16 bytes.
func (p *PacketBuffer) ReadUUID() ([16]byte, error) {
	var uuid [16]byte
	_, err := io.ReadFull(p.buf, uuid[:])
	return uuid, err
}
