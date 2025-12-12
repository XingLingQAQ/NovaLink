// Package invitation provides invitation code functionality for private channels.
package invitation

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

var (
	// ErrInvitationNotFound is returned when an invitation code is not found.
	ErrInvitationNotFound = errors.New("invitation not found")
	// ErrInvitationExpired is returned when an invitation code has expired.
	ErrInvitationExpired = errors.New("invitation expired")
	// ErrInvitationUsed is returned when an invitation code has already been used.
	ErrInvitationUsed = errors.New("invitation already used")
	// ErrMaxUsesReached is returned when an invitation has reached its max uses.
	ErrMaxUsesReached = errors.New("invitation max uses reached")
)

// Invitation represents an invitation code.
type Invitation struct {
	Code       string
	ChannelID  string
	CreatedBy  string
	CreatedAt  time.Time
	ExpiresAt  time.Time
	MaxUses    int // 0 for unlimited
	UseCount   int
	UsedBy     []string
	SingleUse  bool
}

// Manager handles invitation code operations.
type Manager struct {
	invitations map[string]*Invitation
	mutex       sync.RWMutex
}

// NewManager creates a new invitation Manager.
func NewManager() *Manager {
	return &Manager{
		invitations: make(map[string]*Invitation),
	}
}

// GenerateCode generates a random invitation code.
func GenerateCode(length int) (string, error) {
	if length <= 0 {
		length = 8
	}

	bytes := make([]byte, length/2+1)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}

	return hex.EncodeToString(bytes)[:length], nil
}

// CreateInvitation creates a new invitation for a channel.
func (m *Manager) CreateInvitation(channelID, createdBy string, duration time.Duration, maxUses int) (*Invitation, error) {
	code, err := GenerateCode(8)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	var expiresAt time.Time
	if duration > 0 {
		expiresAt = now.Add(duration)
	}

	invitation := &Invitation{
		Code:      code,
		ChannelID: channelID,
		CreatedBy: createdBy,
		CreatedAt: now,
		ExpiresAt: expiresAt,
		MaxUses:   maxUses,
		UseCount:  0,
		UsedBy:    make([]string, 0),
		SingleUse: maxUses == 1,
	}

	m.mutex.Lock()
	m.invitations[code] = invitation
	m.mutex.Unlock()

	return invitation, nil
}

// CreateInvitationWithCode creates an invitation with a specific code.
func (m *Manager) CreateInvitationWithCode(code, channelID, createdBy string, duration time.Duration, maxUses int) (*Invitation, error) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	// Check if code already exists
	if _, exists := m.invitations[code]; exists {
		return nil, errors.New("invitation code already exists")
	}

	now := time.Now()
	var expiresAt time.Time
	if duration > 0 {
		expiresAt = now.Add(duration)
	}

	invitation := &Invitation{
		Code:      code,
		ChannelID: channelID,
		CreatedBy: createdBy,
		CreatedAt: now,
		ExpiresAt: expiresAt,
		MaxUses:   maxUses,
		UseCount:  0,
		UsedBy:    make([]string, 0),
		SingleUse: maxUses == 1,
	}

	m.invitations[code] = invitation
	return invitation, nil
}

// UseInvitation attempts to use an invitation code.
func (m *Manager) UseInvitation(code, playerID string) (string, error) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	invitation, exists := m.invitations[code]
	if !exists {
		return "", ErrInvitationNotFound
	}

	// Check if expired
	if !invitation.ExpiresAt.IsZero() && time.Now().After(invitation.ExpiresAt) {
		return "", ErrInvitationExpired
	}

	// Check if max uses reached
	if invitation.MaxUses > 0 && invitation.UseCount >= invitation.MaxUses {
		return "", ErrMaxUsesReached
	}

	// Check if player already used this invitation
	for _, usedBy := range invitation.UsedBy {
		if usedBy == playerID {
			return "", ErrInvitationUsed
		}
	}

	// Use the invitation
	invitation.UseCount++
	invitation.UsedBy = append(invitation.UsedBy, playerID)

	// Remove single-use invitations after use
	if invitation.SingleUse {
		delete(m.invitations, code)
	}

	return invitation.ChannelID, nil
}

// GetInvitation returns an invitation by code.
func (m *Manager) GetInvitation(code string) *Invitation {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	return m.invitations[code]
}

// GetInvitationsForChannel returns all invitations for a channel.
func (m *Manager) GetInvitationsForChannel(channelID string) []*Invitation {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var result []*Invitation
	for _, inv := range m.invitations {
		if inv.ChannelID == channelID {
			result = append(result, inv)
		}
	}
	return result
}

// RevokeInvitation revokes an invitation code.
func (m *Manager) RevokeInvitation(code string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if _, exists := m.invitations[code]; exists {
		delete(m.invitations, code)
		return true
	}
	return false
}

// RevokeAllForChannel revokes all invitations for a channel.
func (m *Manager) RevokeAllForChannel(channelID string) int {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	count := 0
	for code, inv := range m.invitations {
		if inv.ChannelID == channelID {
			delete(m.invitations, code)
			count++
		}
	}
	return count
}

// IsValid checks if an invitation code is valid (exists and not expired).
func (m *Manager) IsValid(code string) bool {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	invitation, exists := m.invitations[code]
	if !exists {
		return false
	}

	// Check expiration
	if !invitation.ExpiresAt.IsZero() && time.Now().After(invitation.ExpiresAt) {
		return false
	}

	// Check max uses
	if invitation.MaxUses > 0 && invitation.UseCount >= invitation.MaxUses {
		return false
	}

	return true
}

// CleanExpired removes expired invitations.
func (m *Manager) CleanExpired() int {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	now := time.Now()
	count := 0

	for code, inv := range m.invitations {
		if !inv.ExpiresAt.IsZero() && inv.ExpiresAt.Before(now) {
			delete(m.invitations, code)
			count++
		}
	}

	return count
}

// GetAllInvitations returns all invitations.
func (m *Manager) GetAllInvitations() []*Invitation {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	result := make([]*Invitation, 0, len(m.invitations))
	for _, inv := range m.invitations {
		result = append(result, inv)
	}
	return result
}

// GetInvitationCount returns the total number of invitations.
func (m *Manager) GetInvitationCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.invitations)
}

// GetRemainingUses returns the remaining uses for an invitation.
func (m *Manager) GetRemainingUses(code string) int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	invitation, exists := m.invitations[code]
	if !exists {
		return 0
	}

	if invitation.MaxUses == 0 {
		return -1 // Unlimited
	}

	return invitation.MaxUses - invitation.UseCount
}
