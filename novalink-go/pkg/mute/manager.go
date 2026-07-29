// Package mute provides player muting functionality.
package mute

import (
	"sync"
	"time"

	"github.com/nova/novalink-go/pkg/storage"
)

// Manager handles player muting.
type Manager struct {
	storage storage.Provider
	cache   map[string]*MuteInfo
	mutex   sync.RWMutex
}

// MuteInfo represents mute information for a player.
type MuteInfo struct {
	PlayerID   string
	PlayerName string
	Reason     string
	MutedBy    string
	MutedAt    time.Time
	ExpiresAt  time.Time
}

// NewManager creates a new mute Manager.
func NewManager(storage storage.Provider) *Manager {
	return &Manager{
		storage: storage,
		cache:   make(map[string]*MuteInfo),
	}
}

// Mute mutes a player for the specified duration.
func (m *Manager) Mute(playerID, playerName, reason, mutedBy string, duration time.Duration) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	now := time.Now()
	var expiresAt time.Time
	if duration > 0 {
		expiresAt = now.Add(duration)
	}

	info := &MuteInfo{
		PlayerID:   playerID,
		PlayerName: playerName,
		Reason:     reason,
		MutedBy:    mutedBy,
		MutedAt:    now,
		ExpiresAt:  expiresAt,
	}

	m.cache[playerID] = info

	// Persist to storage
	if m.storage != nil {
		record := &storage.MuteRecord{
			PlayerID:   playerID,
			PlayerName: playerName,
			Reason:     reason,
			MutedBy:    mutedBy,
			MutedAt:    now.Unix(),
			ExpiresAt:  expiresAt.Unix(),
		}
		return m.storage.SaveMute(record)
	}

	return nil
}

// Unmute removes a mute from a player.
func (m *Manager) Unmute(playerID string) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	delete(m.cache, playerID)

	if m.storage != nil {
		return m.storage.DeleteMute(playerID)
	}

	return nil
}

// IsMuted checks if a player is currently muted.
func (m *Manager) IsMuted(playerID string) bool {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	info, exists := m.cache[playerID]
	if !exists {
		return false
	}

	// Check if mute has expired
	if !info.ExpiresAt.IsZero() && time.Now().After(info.ExpiresAt) {
		return false
	}

	return true
}

// GetMuteInfo returns mute information for a player.
func (m *Manager) GetMuteInfo(playerID string) *MuteInfo {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	info, exists := m.cache[playerID]
	if !exists {
		return nil
	}

	// Check if mute has expired
	if !info.ExpiresAt.IsZero() && time.Now().After(info.ExpiresAt) {
		return nil
	}

	return info
}

// GetAllMutes returns all active mutes.
func (m *Manager) GetAllMutes() []*MuteInfo {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	now := time.Now()
	var mutes []*MuteInfo

	for _, info := range m.cache {
		if info.ExpiresAt.IsZero() || info.ExpiresAt.After(now) {
			mutes = append(mutes, info)
		}
	}

	return mutes
}

// CleanExpired removes expired mutes from the cache.
func (m *Manager) CleanExpired() {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	now := time.Now()
	for playerID, info := range m.cache {
		if !info.ExpiresAt.IsZero() && info.ExpiresAt.Before(now) {
			delete(m.cache, playerID)
		}
	}
}

// LoadFromStorage loads mutes from storage into cache.
func (m *Manager) LoadFromStorage() error {
	if m.storage == nil {
		return nil
	}

	records, err := m.storage.GetActiveMutes()
	if err != nil {
		return err
	}

	m.mutex.Lock()
	defer m.mutex.Unlock()

	for _, record := range records {
		m.cache[record.PlayerID] = &MuteInfo{
			PlayerID:   record.PlayerID,
			PlayerName: record.PlayerName,
			Reason:     record.Reason,
			MutedBy:    record.MutedBy,
			MutedAt:    time.Unix(record.MutedAt, 0),
			ExpiresAt:  time.Unix(record.ExpiresAt, 0),
		}
	}

	return nil
}
