package storage

import (
	"sync"
	"time"
)

// PlayerStateManager manages player states with caching and persistence.
type PlayerStateManager struct {
	provider Provider
	cache    map[string]*PlayerState
	mutex    sync.RWMutex
	
	// Configuration
	cacheEnabled bool
	cacheTTL     time.Duration
	autoSave     bool
	saveInterval time.Duration
	
	// Cache metadata
	cacheTimestamps map[string]time.Time
	
	// Shutdown channel
	stopChan chan struct{}
}

// PlayerStateManagerConfig contains configuration for PlayerStateManager.
type PlayerStateManagerConfig struct {
	CacheEnabled bool
	CacheTTL     time.Duration
	AutoSave     bool
	SaveInterval time.Duration
}

// DefaultPlayerStateManagerConfig returns default configuration.
func DefaultPlayerStateManagerConfig() PlayerStateManagerConfig {
	return PlayerStateManagerConfig{
		CacheEnabled: true,
		CacheTTL:     5 * time.Minute,
		AutoSave:     true,
		SaveInterval: 30 * time.Second,
	}
}

// NewPlayerStateManager creates a new PlayerStateManager.
func NewPlayerStateManager(provider Provider, config PlayerStateManagerConfig) *PlayerStateManager {
	return &PlayerStateManager{
		provider:        provider,
		cache:           make(map[string]*PlayerState),
		cacheTimestamps: make(map[string]time.Time),
		cacheEnabled:    config.CacheEnabled,
		cacheTTL:        config.CacheTTL,
		autoSave:        config.AutoSave,
		saveInterval:    config.SaveInterval,
		stopChan:        make(chan struct{}),
	}
}

// Start begins background tasks like auto-save.
func (m *PlayerStateManager) Start() {
	if m.autoSave {
		go m.autoSaveLoop()
	}
	if m.cacheEnabled {
		go m.cacheCleanupLoop()
	}
}

// Stop stops background tasks.
func (m *PlayerStateManager) Stop() {
	close(m.stopChan)
	// Flush all cached states to storage
	m.FlushAll()
}

// autoSaveLoop periodically saves dirty states to storage.
func (m *PlayerStateManager) autoSaveLoop() {
	ticker := time.NewTicker(m.saveInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			m.FlushAll()
		case <-m.stopChan:
			return
		}
	}
}

// cacheCleanupLoop periodically removes expired cache entries.
func (m *PlayerStateManager) cacheCleanupLoop() {
	ticker := time.NewTicker(m.cacheTTL)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			m.cleanupCache()
		case <-m.stopChan:
			return
		}
	}
}

// cleanupCache removes expired entries from cache.
func (m *PlayerStateManager) cleanupCache() {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	now := time.Now()
	for playerID, timestamp := range m.cacheTimestamps {
		if now.Sub(timestamp) > m.cacheTTL {
			delete(m.cache, playerID)
			delete(m.cacheTimestamps, playerID)
		}
	}
}

// GetPlayerState retrieves a player's state, using cache if available.
func (m *PlayerStateManager) GetPlayerState(playerID string) (*PlayerState, error) {
	// Check cache first
	if m.cacheEnabled {
		m.mutex.RLock()
		if state, exists := m.cache[playerID]; exists {
			m.mutex.RUnlock()
			return state, nil
		}
		m.mutex.RUnlock()
	}

	// Load from storage
	state, err := m.provider.GetPlayerState(playerID)
	if err != nil {
		return nil, err
	}

	// Update cache
	if m.cacheEnabled {
		m.mutex.Lock()
		m.cache[playerID] = state
		m.cacheTimestamps[playerID] = time.Now()
		m.mutex.Unlock()
	}

	return state, nil
}


// GetOrCreatePlayerState retrieves or creates a player's state.
func (m *PlayerStateManager) GetOrCreatePlayerState(playerID, playerName string) (*PlayerState, error) {
	state, err := m.GetPlayerState(playerID)
	if err == ErrNotFound {
		// Create new state
		state = &PlayerState{
			PlayerID:       playerID,
			PlayerName:     playerName,
			CurrentChannel: "",
			JoinedChannels: []string{},
			ChatEnabled:    true,
			LastSeen:       time.Now().Unix(),
		}
		if err := m.SavePlayerState(state); err != nil {
			return nil, err
		}
		return state, nil
	}
	if err != nil {
		return nil, err
	}

	// Update last seen and name if changed
	state.LastSeen = time.Now().Unix()
	if state.PlayerName != playerName {
		state.PlayerName = playerName
	}

	return state, nil
}

// SavePlayerState saves a player's state to cache and optionally to storage.
func (m *PlayerStateManager) SavePlayerState(state *PlayerState) error {
	// Update cache
	if m.cacheEnabled {
		m.mutex.Lock()
		m.cache[state.PlayerID] = state
		m.cacheTimestamps[state.PlayerID] = time.Now()
		m.mutex.Unlock()
	}

	// If auto-save is disabled, save immediately
	if !m.autoSave {
		return m.provider.SavePlayerState(state)
	}

	return nil
}

// SavePlayerStateImmediate saves a player's state immediately to storage.
func (m *PlayerStateManager) SavePlayerStateImmediate(state *PlayerState) error {
	// Update cache
	if m.cacheEnabled {
		m.mutex.Lock()
		m.cache[state.PlayerID] = state
		m.cacheTimestamps[state.PlayerID] = time.Now()
		m.mutex.Unlock()
	}

	return m.provider.SavePlayerState(state)
}

// DeletePlayerState removes a player's state from cache and storage.
func (m *PlayerStateManager) DeletePlayerState(playerID string) error {
	// Remove from cache
	if m.cacheEnabled {
		m.mutex.Lock()
		delete(m.cache, playerID)
		delete(m.cacheTimestamps, playerID)
		m.mutex.Unlock()
	}

	return m.provider.DeletePlayerState(playerID)
}

// FlushAll saves all cached states to storage.
func (m *PlayerStateManager) FlushAll() error {
	m.mutex.RLock()
	states := make([]*PlayerState, 0, len(m.cache))
	for _, state := range m.cache {
		states = append(states, state)
	}
	m.mutex.RUnlock()

	var lastErr error
	for _, state := range states {
		if err := m.provider.SavePlayerState(state); err != nil {
			lastErr = err
		}
	}

	return lastErr
}

// FlushPlayer saves a specific player's cached state to storage.
func (m *PlayerStateManager) FlushPlayer(playerID string) error {
	m.mutex.RLock()
	state, exists := m.cache[playerID]
	m.mutex.RUnlock()

	if !exists {
		return nil
	}

	return m.provider.SavePlayerState(state)
}


// UpdateCurrentChannel updates a player's current channel.
func (m *PlayerStateManager) UpdateCurrentChannel(playerID, channelID string) error {
	state, err := m.GetPlayerState(playerID)
	if err != nil {
		return err
	}

	state.CurrentChannel = channelID
	return m.SavePlayerState(state)
}

// JoinChannel adds a channel to a player's joined channels list.
func (m *PlayerStateManager) JoinChannel(playerID, channelID string) error {
	state, err := m.GetPlayerState(playerID)
	if err != nil {
		return err
	}

	// Check if already joined
	for _, ch := range state.JoinedChannels {
		if ch == channelID {
			return nil
		}
	}

	state.JoinedChannels = append(state.JoinedChannels, channelID)
	return m.SavePlayerState(state)
}

// LeaveChannel removes a channel from a player's joined channels list.
func (m *PlayerStateManager) LeaveChannel(playerID, channelID string) error {
	state, err := m.GetPlayerState(playerID)
	if err != nil {
		return err
	}

	// Find and remove channel
	for i, ch := range state.JoinedChannels {
		if ch == channelID {
			state.JoinedChannels = append(state.JoinedChannels[:i], state.JoinedChannels[i+1:]...)
			break
		}
	}

	// Clear current channel if it was the one being left
	if state.CurrentChannel == channelID {
		state.CurrentChannel = ""
	}

	return m.SavePlayerState(state)
}

// SetChatEnabled enables or disables chat for a player.
func (m *PlayerStateManager) SetChatEnabled(playerID string, enabled bool) error {
	state, err := m.GetPlayerState(playerID)
	if err != nil {
		return err
	}

	state.ChatEnabled = enabled
	return m.SavePlayerState(state)
}

// IsChatEnabled checks if chat is enabled for a player.
func (m *PlayerStateManager) IsChatEnabled(playerID string) (bool, error) {
	state, err := m.GetPlayerState(playerID)
	if err == ErrNotFound {
		return true, nil // Default to enabled
	}
	if err != nil {
		return false, err
	}

	return state.ChatEnabled, nil
}

// GetJoinedChannels returns the list of channels a player has joined.
func (m *PlayerStateManager) GetJoinedChannels(playerID string) ([]string, error) {
	state, err := m.GetPlayerState(playerID)
	if err == ErrNotFound {
		return []string{}, nil
	}
	if err != nil {
		return nil, err
	}

	return state.JoinedChannels, nil
}

// GetCurrentChannel returns the player's current active channel.
func (m *PlayerStateManager) GetCurrentChannel(playerID string) (string, error) {
	state, err := m.GetPlayerState(playerID)
	if err == ErrNotFound {
		return "", nil
	}
	if err != nil {
		return "", err
	}

	return state.CurrentChannel, nil
}

// GetCachedPlayerCount returns the number of players in cache.
func (m *PlayerStateManager) GetCachedPlayerCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.cache)
}

// InvalidateCache clears the cache for a specific player.
func (m *PlayerStateManager) InvalidateCache(playerID string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	delete(m.cache, playerID)
	delete(m.cacheTimestamps, playerID)
}

// ClearCache clears all cached player states.
func (m *PlayerStateManager) ClearCache() {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.cache = make(map[string]*PlayerState)
	m.cacheTimestamps = make(map[string]time.Time)
}
