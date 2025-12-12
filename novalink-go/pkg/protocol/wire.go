package protocol

// WireFormat represents the on-the-wire framing format.
//
// NovaProtocol (Java canonical):
//   | Length (VarInt) | PacketId (Byte) | RequestId (UUID, 16 bytes) | Payload |
//
// Legacy framing (some early non-Java clients):
//   | Length (VarInt) | PacketId (VarInt but < 128 => 1 byte) | Payload |
type WireFormat int32

const (
	WireFormatUnknown WireFormat = iota
	WireFormatLegacyNoRequestID
	WireFormatModernWithRequestID
)


