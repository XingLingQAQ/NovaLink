package protocol

import (
	"crypto/rand"
	"github.com/google/uuid"
)

// IsZeroUUID returns true if the UUID is all zeros.
func IsZeroUUID(id [16]byte) bool {
	for _, b := range id {
		if b != 0 {
			return false
		}
	}
	return true
}

// NewUUID generates a random UUID (v4).
func NewUUID() ([16]byte, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return [16]byte{}, err
	}
	// RFC 4122 variant + version (v4)
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return id, nil
}

// UUIDToString converts a 16-byte UUID to its canonical string representation.
// Returns the zero UUID string on decode errors.
func UUIDToString(id [16]byte) string {
	u, err := uuid.FromBytes(id[:])
	if err != nil {
		return uuid.Nil.String()
	}
	return u.String()
}


