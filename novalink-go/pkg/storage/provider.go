// Package storage provides data persistence functionality.
package storage

import (
	"errors"
)

var (
	ErrNotFound     = errors.New("record not found")
	ErrDuplicate    = errors.New("duplicate record")
	ErrNotConnected = errors.New("not connected to database")
)

// Provider is the interface for storage backends.
type Provider interface {
	// Connection management
	Connect() error
	Close() error
	IsConnected() bool

	// Player state operations
	GetPlayerState(playerID string) (*PlayerState, error)
	SavePlayerState(state *PlayerState) error
	DeletePlayerState(playerID string) error

	// Mute operations
	GetMute(playerID string) (*MuteRecord, error)
	SaveMute(mute *MuteRecord) error
	DeleteMute(playerID string) error
	GetActiveMutes() ([]*MuteRecord, error)

	// Channel operations
	GetChannels() ([]*ChannelRecord, error)
	SaveChannel(channel *ChannelRecord) error
	DeleteChannel(channelID string) error
}

// PlayerState represents a player's persistent state.
type PlayerState struct {
	PlayerID       string
	PlayerName     string
	CurrentChannel string
	JoinedChannels []string
	ChatEnabled    bool
	LastSeen       int64
}

// MuteRecord represents a mute entry.
type MuteRecord struct {
	PlayerID   string
	PlayerName string
	Reason     string
	MutedBy    string
	MutedAt    int64
	ExpiresAt  int64
}

// ChannelRecord represents a persistent channel.
type ChannelRecord struct {
	ID            string
	DisplayName   string
	Scope         string
	ClientID      string
	Permission    string
	MaxCapacity   int
	AllowedWorlds []string
	Password      string
	OwnerID       string
	Format        string
}
