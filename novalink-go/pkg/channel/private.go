package channel

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"
)

// Invitation represents an invitation to a private channel.
type Invitation struct {
	ID          string
	ChannelID   string
	InviterID   string
	InviterName string
	InviteeID   string
	InviteeName string
	CreatedAt   time.Time
	ExpiresAt   time.Time
	Used        bool
}

// InvitationCode represents a reusable invitation code.
type InvitationCode struct {
	Code        string
	ChannelID   string
	CreatorID   string
	MaxUses     int
	UsedCount   int
	CreatedAt   time.Time
	ExpiresAt   time.Time
}

// PrivateChannelManager handles private channel operations.
type PrivateChannelManager struct {
	manager         *Manager
	invitations     map[string]*Invitation     // invitationID -> Invitation
	invitationCodes map[string]*InvitationCode // code -> InvitationCode
	// pendingInvites tracks pending invitations by invitee
	pendingInvites map[string]map[string]*Invitation // inviteeID -> invitationID -> Invitation
	mutex          sync.RWMutex
}

var (
	ErrInvitationNotFound  = errors.New("invitation not found")
	ErrInvitationExpired   = errors.New("invitation expired")
	ErrInvitationUsed      = errors.New("invitation already used")
	ErrCodeNotFound        = errors.New("invitation code not found")
	ErrCodeExpired         = errors.New("invitation code expired")
	ErrCodeMaxUsesReached  = errors.New("invitation code max uses reached")
	ErrNotOwner            = errors.New("only channel owner can perform this action")
	ErrCannotInviteSelf    = errors.New("cannot invite yourself")
	ErrAlreadyInvited      = errors.New("player already has pending invitation")
)

// NewPrivateChannelManager creates a new PrivateChannelManager.
func NewPrivateChannelManager(manager *Manager) *PrivateChannelManager {
	return &PrivateChannelManager{
		manager:         manager,
		invitations:     make(map[string]*Invitation),
		invitationCodes: make(map[string]*InvitationCode),
		pendingInvites:  make(map[string]map[string]*Invitation),
	}
}

// CreatePrivateChannel creates a new private channel.
func (pm *PrivateChannelManager) CreatePrivateChannel(ownerID, ownerName, channelID, displayName string) (*Channel, error) {
	return pm.manager.CreateChannel(ChannelConfig{
		ID:          channelID,
		DisplayName: displayName,
		Scope:       ScopePrivate,
		OwnerID:     ownerID,
	})
}

// DeletePrivateChannel deletes a private channel (owner only).
func (pm *PrivateChannelManager) DeletePrivateChannel(channelID, requesterID string) error {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return err
	}

	if channel.Scope != ScopePrivate {
		return fmt.Errorf("channel %s is not a private channel", channelID)
	}

	if channel.OwnerID != requesterID {
		return ErrNotOwner
	}

	// Clean up invitations for this channel
	pm.mutex.Lock()
	for id, inv := range pm.invitations {
		if inv.ChannelID == channelID {
			delete(pm.invitations, id)
			if pm.pendingInvites[inv.InviteeID] != nil {
				delete(pm.pendingInvites[inv.InviteeID], id)
			}
		}
	}
	for code, ic := range pm.invitationCodes {
		if ic.ChannelID == channelID {
			delete(pm.invitationCodes, code)
		}
	}
	pm.mutex.Unlock()

	return pm.manager.DeleteChannel(channelID)
}


// InvitePlayer creates an invitation for a player to join a private channel.
func (pm *PrivateChannelManager) InvitePlayer(channelID, inviterID, inviterName, inviteeID, inviteeName string, duration time.Duration) (*Invitation, error) {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return nil, err
	}

	if channel.Scope != ScopePrivate {
		return nil, fmt.Errorf("channel %s is not a private channel", channelID)
	}

	// Only owner or members can invite
	if channel.OwnerID != inviterID && !pm.manager.IsMember(channelID, inviterID) {
		return nil, ErrPermissionDenied
	}

	if inviterID == inviteeID {
		return nil, ErrCannotInviteSelf
	}

	// Check if already a member
	if pm.manager.IsMember(channelID, inviteeID) {
		return nil, ErrAlreadyMember
	}

	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	// Check for existing pending invitation
	if pm.pendingInvites[inviteeID] != nil {
		for _, inv := range pm.pendingInvites[inviteeID] {
			if inv.ChannelID == channelID && !inv.Used && time.Now().Before(inv.ExpiresAt) {
				return nil, ErrAlreadyInvited
			}
		}
	}

	// Generate invitation ID
	invID, err := generateRandomID(16)
	if err != nil {
		return nil, fmt.Errorf("failed to generate invitation ID: %w", err)
	}

	invitation := &Invitation{
		ID:          invID,
		ChannelID:   channelID,
		InviterID:   inviterID,
		InviterName: inviterName,
		InviteeID:   inviteeID,
		InviteeName: inviteeName,
		CreatedAt:   time.Now(),
		ExpiresAt:   time.Now().Add(duration),
		Used:        false,
	}

	pm.invitations[invID] = invitation

	if pm.pendingInvites[inviteeID] == nil {
		pm.pendingInvites[inviteeID] = make(map[string]*Invitation)
	}
	pm.pendingInvites[inviteeID][invID] = invitation

	return invitation, nil
}

// AcceptInvitation accepts an invitation and joins the channel.
func (pm *PrivateChannelManager) AcceptInvitation(invitationID, playerID, playerName, clientID, world string) error {
	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	invitation, ok := pm.invitations[invitationID]
	if !ok {
		return ErrInvitationNotFound
	}

	if invitation.InviteeID != playerID {
		return ErrPermissionDenied
	}

	if invitation.Used {
		return ErrInvitationUsed
	}

	if time.Now().After(invitation.ExpiresAt) {
		return ErrInvitationExpired
	}

	// Mark as used
	invitation.Used = true

	// Remove from pending
	if pm.pendingInvites[playerID] != nil {
		delete(pm.pendingInvites[playerID], invitationID)
	}

	// Join the channel
	return pm.manager.JoinChannel(JoinRequest{
		ChannelID:  invitation.ChannelID,
		PlayerID:   playerID,
		PlayerName: playerName,
		ClientID:   clientID,
		World:      world,
	})
}

// DeclineInvitation declines an invitation.
func (pm *PrivateChannelManager) DeclineInvitation(invitationID, playerID string) error {
	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	invitation, ok := pm.invitations[invitationID]
	if !ok {
		return ErrInvitationNotFound
	}

	if invitation.InviteeID != playerID {
		return ErrPermissionDenied
	}

	// Mark as used (declined)
	invitation.Used = true

	// Remove from pending
	if pm.pendingInvites[playerID] != nil {
		delete(pm.pendingInvites[playerID], invitationID)
	}

	return nil
}

// GetPendingInvitations returns all pending invitations for a player.
func (pm *PrivateChannelManager) GetPendingInvitations(playerID string) []*Invitation {
	pm.mutex.RLock()
	defer pm.mutex.RUnlock()

	pending := pm.pendingInvites[playerID]
	if pending == nil {
		return nil
	}

	result := make([]*Invitation, 0, len(pending))
	now := time.Now()
	for _, inv := range pending {
		if !inv.Used && now.Before(inv.ExpiresAt) {
			result = append(result, inv)
		}
	}
	return result
}

// CreateInvitationCode creates a reusable invitation code.
func (pm *PrivateChannelManager) CreateInvitationCode(channelID, creatorID string, maxUses int, duration time.Duration) (*InvitationCode, error) {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return nil, err
	}

	if channel.Scope != ScopePrivate {
		return nil, fmt.Errorf("channel %s is not a private channel", channelID)
	}

	// Align with Java behavior (channel admin can invite): allow owner OR any current member.
	// We don't have a dedicated channel-admin table in Go, so membership is used as a safe approximation.
	if channel.OwnerID != creatorID && !pm.manager.IsMember(channelID, creatorID) {
		return nil, ErrNotOwner
	}

	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	// Bukkit AcceptCommand validates 6 chars: ^[A-Z0-9]{6}$, keep consistent.
	code, err := generateRandomCode(6)
	if err != nil {
		return nil, fmt.Errorf("failed to generate code: %w", err)
	}

	ic := &InvitationCode{
		Code:      code,
		ChannelID: channelID,
		CreatorID: creatorID,
		MaxUses:   maxUses,
		UsedCount: 0,
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(duration),
	}

	pm.invitationCodes[code] = ic
	return ic, nil
}

// ResolveInvitationCode returns the channel ID for an invitation code if present.
// This is used by the NovaProtocol ACCEPT action to reply with the real channelId (Java parity).
func (pm *PrivateChannelManager) ResolveInvitationCode(code string) (string, error) {
	pm.mutex.RLock()
	defer pm.mutex.RUnlock()

	ic, ok := pm.invitationCodes[code]
	if !ok {
		return "", ErrCodeNotFound
	}
	if time.Now().After(ic.ExpiresAt) {
		return ic.ChannelID, ErrCodeExpired
	}
	if ic.MaxUses > 0 && ic.UsedCount >= ic.MaxUses {
		// Still return channelID for better error context at higher layers.
		return ic.ChannelID, ErrCodeMaxUsesReached
	}
	return ic.ChannelID, nil
}

// UseInvitationCode uses an invitation code to join a channel.
func (pm *PrivateChannelManager) UseInvitationCode(code, playerID, playerName, clientID, world string) error {
	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	ic, ok := pm.invitationCodes[code]
	if !ok {
		return ErrCodeNotFound
	}

	if time.Now().After(ic.ExpiresAt) {
		return ErrCodeExpired
	}

	if ic.MaxUses > 0 && ic.UsedCount >= ic.MaxUses {
		return ErrCodeMaxUsesReached
	}

	// Check if already a member
	if pm.manager.IsMember(ic.ChannelID, playerID) {
		return ErrAlreadyMember
	}

	// Increment use count
	ic.UsedCount++

	// Join the channel
	return pm.manager.JoinChannel(JoinRequest{
		ChannelID:  ic.ChannelID,
		PlayerID:   playerID,
		PlayerName: playerName,
		ClientID:   clientID,
		World:      world,
	})
}

// RevokeInvitationCode revokes an invitation code.
func (pm *PrivateChannelManager) RevokeInvitationCode(code, requesterID string) error {
	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	ic, ok := pm.invitationCodes[code]
	if !ok {
		return ErrCodeNotFound
	}

	channel, err := pm.manager.GetChannel(ic.ChannelID)
	if err != nil {
		return err
	}

	if channel.OwnerID != requesterID {
		return ErrNotOwner
	}

	delete(pm.invitationCodes, code)
	return nil
}

// GetChannelInvitationCodes returns all invitation codes for a channel.
func (pm *PrivateChannelManager) GetChannelInvitationCodes(channelID, requesterID string) ([]*InvitationCode, error) {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return nil, err
	}

	if channel.OwnerID != requesterID {
		return nil, ErrNotOwner
	}

	pm.mutex.RLock()
	defer pm.mutex.RUnlock()

	var codes []*InvitationCode
	now := time.Now()
	for _, ic := range pm.invitationCodes {
		if ic.ChannelID == channelID && now.Before(ic.ExpiresAt) {
			codes = append(codes, ic)
		}
	}
	return codes, nil
}

// KickMember removes a member from a private channel (owner only).
func (pm *PrivateChannelManager) KickMember(channelID, requesterID, memberID string) error {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return err
	}

	if channel.Scope != ScopePrivate {
		return fmt.Errorf("channel %s is not a private channel", channelID)
	}

	if channel.OwnerID != requesterID {
		return ErrNotOwner
	}

	if memberID == channel.OwnerID {
		return errors.New("cannot kick channel owner")
	}

	return pm.manager.LeaveChannel(channelID, memberID)
}

// TransferOwnership transfers ownership of a private channel.
func (pm *PrivateChannelManager) TransferOwnership(channelID, currentOwnerID, newOwnerID string) error {
	channel, err := pm.manager.GetChannel(channelID)
	if err != nil {
		return err
	}

	if channel.Scope != ScopePrivate {
		return fmt.Errorf("channel %s is not a private channel", channelID)
	}

	if channel.OwnerID != currentOwnerID {
		return ErrNotOwner
	}

	if !pm.manager.IsMember(channelID, newOwnerID) {
		return ErrNotChannelMember
	}

	channel.mutex.Lock()
	channel.OwnerID = newOwnerID
	channel.mutex.Unlock()

	return nil
}

// CleanupExpired removes expired invitations and codes.
func (pm *PrivateChannelManager) CleanupExpired() {
	pm.mutex.Lock()
	defer pm.mutex.Unlock()

	now := time.Now()

	// Clean up expired invitations
	for id, inv := range pm.invitations {
		if now.After(inv.ExpiresAt) || inv.Used {
			delete(pm.invitations, id)
			if pm.pendingInvites[inv.InviteeID] != nil {
				delete(pm.pendingInvites[inv.InviteeID], id)
			}
		}
	}

	// Clean up expired codes
	for code, ic := range pm.invitationCodes {
		if now.After(ic.ExpiresAt) {
			delete(pm.invitationCodes, code)
		}
	}
}

// generateRandomID generates a random hex ID.
func generateRandomID(length int) (string, error) {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

// generateRandomCode generates a random alphanumeric code.
func generateRandomCode(length int) (string, error) {
	const charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	for i := range bytes {
		bytes[i] = charset[int(bytes[i])%len(charset)]
	}
	return string(bytes), nil
}
