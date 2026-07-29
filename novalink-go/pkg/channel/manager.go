// Package channel provides channel management functionality.
package channel

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

// Scope represents the visibility scope of a channel.
type Scope string

const (
	// ScopeGlobal - messages are delivered to all members across all clients
	ScopeGlobal Scope = "GLOBAL"
	// ScopeServer - messages are only delivered to members connected through the same client
	ScopeServer Scope = "SERVER"
	// ScopePrivate - messages are only delivered to explicit members (invite-only)
	ScopePrivate Scope = "PRIVATE"
)

// MemberInfo holds information about a channel member.
type MemberInfo struct {
	PlayerID   string    // Unique player identifier
	PlayerName string    // Display name
	ClientID   string    // The client (server) they connected through
	World      string    // Current world
	JoinedAt   time.Time // When they joined the channel
}

// Channel represents a chat channel.
type Channel struct {
	ID            string
	DisplayName   string
	Scope         Scope
	ClientID      string // For SERVER scope - the owning client
	Permission    string
	MaxCapacity   int
	AllowedWorlds []string
	Password      string
	OwnerID       string // For PRIVATE scope - the player who created it
	Members       map[string]*MemberInfo
	Format        string
	CreatedAt     time.Time
	TemplateID    string // Reference to template if created from one
	mutex         sync.RWMutex
}

// Manager handles channel operations.
type Manager struct {
	channels map[string]*Channel
	// playerChannels tracks which channels each player is in
	playerChannels map[string]map[string]bool // playerID -> channelID -> true
	mutex          sync.RWMutex
}

var (
	ErrChannelNotFound      = errors.New("channel not found")
	ErrChannelExists        = errors.New("channel already exists")
	ErrChannelFull          = errors.New("channel is full")
	ErrInvalidPassword      = errors.New("invalid password")
	ErrNotChannelOwner      = errors.New("not channel owner")
	ErrNotChannelMember     = errors.New("not a channel member")
	ErrWorldNotAllowed      = errors.New("world not allowed in this channel")
	ErrInvalidScope         = errors.New("invalid channel scope")
	ErrClientMismatch       = errors.New("client mismatch for server-scoped channel")
	ErrAlreadyMember        = errors.New("already a member of this channel")
	ErrCannotDeleteBuiltIn  = errors.New("cannot delete built-in channel")
	ErrPermissionDenied     = errors.New("permission denied")
)

// NewManager creates a new channel Manager.
func NewManager() *Manager {
	return &Manager{
		channels:       make(map[string]*Channel),
		playerChannels: make(map[string]map[string]bool),
	}
}

// UpsertChannel creates a channel if it doesn't exist, otherwise updates
// non-membership fields for the existing channel.
//
// Important behavior (parity with Java config reload):
// - Never mutates runtime PRIVATE channels from config reload.
// - Does not clear members or change CreatedAt.
func (m *Manager) UpsertChannel(config ChannelConfig) (*Channel, error) {
	if config.ID == "" {
		return nil, errors.New("channel id is required")
	}

	// Fast path: read existing
	m.mutex.RLock()
	existing, ok := m.channels[config.ID]
	m.mutex.RUnlock()
	if !ok {
		return m.CreateChannel(config)
	}

	// Never mutate private channels from config reload.
	existing.mutex.Lock()
	defer existing.mutex.Unlock()
	if existing.Scope == ScopePrivate {
		return existing, nil
	}

	if config.DisplayName != "" {
		existing.DisplayName = config.DisplayName
	}
	if config.Permission != "" {
		existing.Permission = config.Permission
	}
	if config.MaxCapacity >= 0 {
		existing.MaxCapacity = config.MaxCapacity
	}
	if config.AllowedWorlds != nil {
		existing.AllowedWorlds = config.AllowedWorlds
	}
	if config.Format != "" {
		existing.Format = config.Format
	}
	if config.TemplateID != "" {
		existing.TemplateID = config.TemplateID
	}

	// For non-global channels, allow updating client binding if explicitly provided.
	if existing.Scope != ScopeGlobal && config.ClientID != "" {
		existing.ClientID = config.ClientID
	}

	return existing, nil
}

// CreateChannel creates a new channel.
func (m *Manager) CreateChannel(config ChannelConfig) (*Channel, error) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if _, exists := m.channels[config.ID]; exists {
		return nil, ErrChannelExists
	}

	// Validate scope
	if !isValidScope(config.Scope) {
		return nil, ErrInvalidScope
	}

	// SERVER scope requires a ClientID
	if config.Scope == ScopeServer && config.ClientID == "" {
		return nil, fmt.Errorf("%w: SERVER scope requires ClientID", ErrInvalidScope)
	}

	// PRIVATE scope requires an OwnerID
	if config.Scope == ScopePrivate && config.OwnerID == "" {
		return nil, fmt.Errorf("%w: PRIVATE scope requires OwnerID", ErrInvalidScope)
	}

	channel := &Channel{
		ID:            config.ID,
		DisplayName:   config.DisplayName,
		Scope:         config.Scope,
		ClientID:      config.ClientID,
		Permission:    config.Permission,
		MaxCapacity:   config.MaxCapacity,
		AllowedWorlds: config.AllowedWorlds,
		Password:      config.Password,
		OwnerID:       config.OwnerID,
		Members:       make(map[string]*MemberInfo),
		Format:        config.Format,
		CreatedAt:     time.Now(),
		TemplateID:    config.TemplateID,
	}

	m.channels[config.ID] = channel
	return channel, nil
}

// isValidScope checks if a scope value is valid.
func isValidScope(scope Scope) bool {
	return scope == ScopeGlobal || scope == ScopeServer || scope == ScopePrivate
}

// DeleteChannel removes a channel.
func (m *Manager) DeleteChannel(id string) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if _, exists := m.channels[id]; !exists {
		return ErrChannelNotFound
	}

	delete(m.channels, id)
	return nil
}

// GetChannel returns a channel by ID.
func (m *Manager) GetChannel(id string) (*Channel, error) {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	channel, exists := m.channels[id]
	if !exists {
		return nil, ErrChannelNotFound
	}
	return channel, nil
}

// GetAllChannels returns all channels.
func (m *Manager) GetAllChannels() []*Channel {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	channels := make([]*Channel, 0, len(m.channels))
	for _, ch := range m.channels {
		channels = append(channels, ch)
	}
	return channels
}

// JoinRequest contains information needed to join a channel.
type JoinRequest struct {
	ChannelID  string
	PlayerID   string
	PlayerName string
	ClientID   string
	World      string
	Password   string
}

// JoinChannel adds a member to a channel.
func (m *Manager) JoinChannel(req JoinRequest) error {
	channel, err := m.GetChannel(req.ChannelID)
	if err != nil {
		return err
	}

	channel.mutex.Lock()
	defer channel.mutex.Unlock()

	// Check if already a member
	if _, exists := channel.Members[req.PlayerID]; exists {
		return ErrAlreadyMember
	}

	// Check password for password-protected channels
	if channel.Password != "" && channel.Password != req.Password {
		return ErrInvalidPassword
	}

	// Check capacity
	if channel.MaxCapacity > 0 && len(channel.Members) >= channel.MaxCapacity {
		return ErrChannelFull
	}

	// For SERVER scope, verify the client matches
	if channel.Scope == ScopeServer && channel.ClientID != req.ClientID {
		return ErrClientMismatch
	}

	// Check world restrictions
	if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, req.World) {
		return ErrWorldNotAllowed
	}

	// Add member
	channel.Members[req.PlayerID] = &MemberInfo{
		PlayerID:   req.PlayerID,
		PlayerName: req.PlayerName,
		ClientID:   req.ClientID,
		World:      req.World,
		JoinedAt:   time.Now(),
	}

	// Track player's channels
	m.mutex.Lock()
	if m.playerChannels[req.PlayerID] == nil {
		m.playerChannels[req.PlayerID] = make(map[string]bool)
	}
	m.playerChannels[req.PlayerID][req.ChannelID] = true
	m.mutex.Unlock()

	return nil
}

// JoinChannelSimple is a simplified join for backward compatibility.
func (m *Manager) JoinChannelSimple(channelID, memberID, password string) error {
	return m.JoinChannel(JoinRequest{
		ChannelID: channelID,
		PlayerID:  memberID,
		Password:  password,
	})
}

// LeaveChannel removes a member from a channel.
func (m *Manager) LeaveChannel(channelID, memberID string) error {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return err
	}

	channel.mutex.Lock()
	defer channel.mutex.Unlock()

	if _, exists := channel.Members[memberID]; !exists {
		return ErrNotChannelMember
	}

	delete(channel.Members, memberID)

	// Update player's channel tracking
	m.mutex.Lock()
	if m.playerChannels[memberID] != nil {
		delete(m.playerChannels[memberID], channelID)
		if len(m.playerChannels[memberID]) == 0 {
			delete(m.playerChannels, memberID)
		}
	}
	m.mutex.Unlock()

	return nil
}

// IsMember checks if a user is a member of a channel.
func (m *Manager) IsMember(channelID, memberID string) bool {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return false
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	_, exists := channel.Members[memberID]
	return exists
}

// GetMember returns member info for a player in a channel.
func (m *Manager) GetMember(channelID, memberID string) (*MemberInfo, error) {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return nil, err
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	member, exists := channel.Members[memberID]
	if !exists {
		return nil, ErrNotChannelMember
	}
	return member, nil
}

// GetPlayerChannels returns all channels a player is a member of.
func (m *Manager) GetPlayerChannels(playerID string) []*Channel {
	m.mutex.RLock()
	channelIDs := m.playerChannels[playerID]
	m.mutex.RUnlock()

	if channelIDs == nil {
		return nil
	}

	var channels []*Channel
	for channelID := range channelIDs {
		if ch, err := m.GetChannel(channelID); err == nil {
			channels = append(channels, ch)
		}
	}
	return channels
}

// UpdateMemberWorld updates a member's current world.
func (m *Manager) UpdateMemberWorld(channelID, memberID, world string) error {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return err
	}

	channel.mutex.Lock()
	defer channel.mutex.Unlock()

	member, exists := channel.Members[memberID]
	if !exists {
		return ErrNotChannelMember
	}

	member.World = world
	return nil
}

// GetChannelsByScope returns all channels with the given scope.
func (m *Manager) GetChannelsByScope(scope Scope) []*Channel {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var channels []*Channel
	for _, ch := range m.channels {
		if ch.Scope == scope {
			channels = append(channels, ch)
		}
	}
	return channels
}

// GetChannelsByClient returns all channels owned by a specific client (for SERVER scope).
func (m *Manager) GetChannelsByClient(clientID string) []*Channel {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var channels []*Channel
	for _, ch := range m.channels {
		if ch.Scope == ScopeServer && ch.ClientID == clientID {
			channels = append(channels, ch)
		}
	}
	return channels
}

// GetPrivateChannelsByOwner returns all private channels owned by a player.
func (m *Manager) GetPrivateChannelsByOwner(ownerID string) []*Channel {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var channels []*Channel
	for _, ch := range m.channels {
		if ch.Scope == ScopePrivate && ch.OwnerID == ownerID {
			channels = append(channels, ch)
		}
	}
	return channels
}

// GetMemberCount returns the number of members in a channel.
func (m *Manager) GetMemberCount(channelID string) int {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return 0
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	return len(channel.Members)
}

// GetAllMembers returns all members of a channel.
func (m *Manager) GetAllMembers(channelID string) []*MemberInfo {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return nil
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	members := make([]*MemberInfo, 0, len(channel.Members))
	for _, member := range channel.Members {
		members = append(members, member)
	}
	return members
}

// isWorldAllowed checks if a world is in the allowed list.
func isWorldAllowed(allowedWorlds []string, world string) bool {
	if len(allowedWorlds) == 0 {
		return true
	}
	for _, w := range allowedWorlds {
		if w == world || w == "*" {
			return true
		}
	}
	return false
}

// RemovePlayerFromAllChannels removes a player from all channels they're in.
func (m *Manager) RemovePlayerFromAllChannels(playerID string) {
	m.mutex.Lock()
	channelIDs := m.playerChannels[playerID]
	delete(m.playerChannels, playerID)
	m.mutex.Unlock()

	for channelID := range channelIDs {
		if channel, err := m.GetChannel(channelID); err == nil {
			channel.mutex.Lock()
			delete(channel.Members, playerID)
			channel.mutex.Unlock()
		}
	}
}

// ChannelConfig is used to create a new channel.
type ChannelConfig struct {
	ID            string
	DisplayName   string
	Scope         Scope
	ClientID      string
	Permission    string
	MaxCapacity   int
	AllowedWorlds []string
	Password      string
	OwnerID       string
	Format        string
	TemplateID    string // Reference to template if created from one
}

// ChannelInfo provides a read-only view of channel information.
type ChannelInfo struct {
	ID            string
	DisplayName   string
	Scope         Scope
	ClientID      string
	Permission    string
	MaxCapacity   int
	MemberCount   int
	AllowedWorlds []string
	HasPassword   bool
	OwnerID       string
	CreatedAt     time.Time
}

// GetChannelInfo returns a read-only view of channel information.
func (m *Manager) GetChannelInfo(channelID string) (*ChannelInfo, error) {
	channel, err := m.GetChannel(channelID)
	if err != nil {
		return nil, err
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	return &ChannelInfo{
		ID:            channel.ID,
		DisplayName:   channel.DisplayName,
		Scope:         channel.Scope,
		ClientID:      channel.ClientID,
		Permission:    channel.Permission,
		MaxCapacity:   channel.MaxCapacity,
		MemberCount:   len(channel.Members),
		AllowedWorlds: channel.AllowedWorlds,
		HasPassword:   channel.Password != "",
		OwnerID:       channel.OwnerID,
		CreatedAt:     channel.CreatedAt,
	}, nil
}

// GetAllChannelInfos returns info for all channels.
func (m *Manager) GetAllChannelInfos() []*ChannelInfo {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	infos := make([]*ChannelInfo, 0, len(m.channels))
	for _, ch := range m.channels {
		ch.mutex.RLock()
		infos = append(infos, &ChannelInfo{
			ID:            ch.ID,
			DisplayName:   ch.DisplayName,
			Scope:         ch.Scope,
			ClientID:      ch.ClientID,
			Permission:    ch.Permission,
			MaxCapacity:   ch.MaxCapacity,
			MemberCount:   len(ch.Members),
			AllowedWorlds: ch.AllowedWorlds,
			HasPassword:   ch.Password != "",
			OwnerID:       ch.OwnerID,
			CreatedAt:     ch.CreatedAt,
		})
		ch.mutex.RUnlock()
	}
	return infos
}
