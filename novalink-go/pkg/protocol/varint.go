// Package protocol implements the NovaProtocol binary communication protocol.
package protocol

import (
	"bytes"
	"errors"
	"io"
)

// VarInt encoding constants
const (
	VarIntMaxBytes = 5
	VarIntSegment  = 0x7F
	VarIntContinue = 0x80
)

var (
	// ErrVarIntTooLarge is returned when a VarInt exceeds the maximum allowed size.
	ErrVarIntTooLarge = errors.New("varint is too large")
	// ErrUnexpectedEOF is returned when reading a VarInt encounters unexpected end of data.
	ErrUnexpectedEOF = errors.New("unexpected end of varint data")
)

// WriteVarInt encodes an int32 value as a VarInt and writes it to the buffer.
// VarInt uses 7 bits per byte with the MSB indicating continuation.
func WriteVarInt(buf *bytes.Buffer, value int32) error {
	uval := uint32(value)
	for {
		if (uval & ^uint32(VarIntSegment)) == 0 {
			buf.WriteByte(byte(uval))
			return nil
		}
		buf.WriteByte(byte((uval & VarIntSegment) | VarIntContinue))
		uval >>= 7
	}
}

// ReadVarInt reads a VarInt from the reader and returns the decoded int32 value.
func ReadVarInt(reader io.Reader) (int32, error) {
	var result uint32
	var shift uint
	buf := make([]byte, 1)

	for i := 0; i < VarIntMaxBytes; i++ {
		n, err := reader.Read(buf)
		if err != nil {
			return 0, err
		}
		if n == 0 {
			return 0, ErrUnexpectedEOF
		}

		b := buf[0]
		result |= uint32(b&VarIntSegment) << shift

		if (b & VarIntContinue) == 0 {
			return int32(result), nil
		}

		shift += 7
	}

	return 0, ErrVarIntTooLarge
}

// ReadVarIntFromBytes reads a VarInt from a byte slice starting at the given offset.
// Returns the decoded value and the number of bytes consumed.
func ReadVarIntFromBytes(data []byte, offset int) (int32, int, error) {
	var result uint32
	var shift uint
	bytesRead := 0

	for i := 0; i < VarIntMaxBytes; i++ {
		if offset+i >= len(data) {
			return 0, 0, ErrUnexpectedEOF
		}

		b := data[offset+i]
		result |= uint32(b&VarIntSegment) << shift
		bytesRead++

		if (b & VarIntContinue) == 0 {
			return int32(result), bytesRead, nil
		}

		shift += 7
	}

	return 0, 0, ErrVarIntTooLarge
}

// VarIntSize returns the number of bytes needed to encode the given value as a VarInt.
func VarIntSize(value int32) int {
	uval := uint32(value)
	size := 0
	for {
		size++
		if (uval & ^uint32(VarIntSegment)) == 0 {
			return size
		}
		uval >>= 7
	}
}

// EncodeVarInt encodes an int32 value as a VarInt and returns the byte slice.
func EncodeVarInt(value int32) []byte {
	buf := &bytes.Buffer{}
	WriteVarInt(buf, value)
	return buf.Bytes()
}
