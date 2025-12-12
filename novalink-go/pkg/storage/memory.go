package storage

import (
	"sync"
	"time"
)

// MemoryProvider implements Provider using in-memory storage.
type MemoryProvider struct {
	players  map[string]*PlayerState
	mutes    map[string]*MuteRecord
	channels map[string]*ChannelRecord
	mutex    sync.RWMutex
	connected bool
}

// NewMemoryProvider creates a new MemoryProvider.
func NewMemoryProvider() *MemoryProvider {
	return &MemoryProvider{
		players:  make(map[string]*PlayerState),
		mutes:    make(map[string]*MuteRecord),
		channels: make(map[string]*ChannelRecord),
	}
}

func (p *MemoryProvider) Connect() error {
	p.connected = true
	return nil
}

func (p *MemoryProvider) Close() error {
	p.connected = false
	return nil
}

func (p *MemoryProvider) IsConnected() bool {
	return p.connected
}

func (p *MemoryProvider) GetPlayerState(playerID string) (*PlayerState, error) {
	p.mutex.RLock()
	defer p.mutex.RUnlock()

	state, exists := p.players[playerID]
	if !exists {
		return nil, ErrNotFound
	}
	return state, nil
}

func (p *MemoryProvider) SavePlayerState(state *PlayerState) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	p.players[state.PlayerID] = state
	return nil
}

func (p *MemoryProvider) DeletePlayerState(playerID string) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	delete(p.players, playerID)
	return nil
}

func (p *MemoryProvider) GetMute(playerID string) (*MuteRecord, error) {
	p.mutex.RLock()
	defer p.mutex.RUnlock()

	mute, exists := p.mutes[playerID]
	if !exists {
		return nil, ErrNotFound
	}

	// Check if mute has expired
	if mute.ExpiresAt > 0 && time.Now().Unix() > mute.ExpiresAt {
		return nil, ErrNotFound
	}

	return mute, nil
}

func (p *MemoryProvider) SaveMute(mute *MuteRecord) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	p.mutes[mute.PlayerID] = mute
	return nil
}

func (p *MemoryProvider) DeleteMute(playerID string) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	delete(p.mutes, playerID)
	return nil
}

func (p *MemoryProvider) GetActiveMutes() ([]*MuteRecord, error) {
	p.mutex.RLock()
	defer p.mutex.RUnlock()

	now := time.Now().Unix()
	var active []*MuteRecord

	for _, mute := range p.mutes {
		if mute.ExpiresAt == 0 || mute.ExpiresAt > now {
			active = append(active, mute)
		}
	}

	return active, nil
}

func (p *MemoryProvider) GetChannels() ([]*ChannelRecord, error) {
	p.mutex.RLock()
	defer p.mutex.RUnlock()

	channels := make([]*ChannelRecord, 0, len(p.channels))
	for _, ch := range p.channels {
		channels = append(channels, ch)
	}
	return channels, nil
}

func (p *MemoryProvider) SaveChannel(channel *ChannelRecord) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	p.channels[channel.ID] = channel
	return nil
}

func (p *MemoryProvider) DeleteChannel(channelID string) error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	delete(p.channels, channelID)
	return nil
}
