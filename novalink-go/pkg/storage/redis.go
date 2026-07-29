package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	playerStatePrefix = "novalink:player:"
	mutePrefix        = "novalink:mute:"
	channelPrefix     = "novalink:channel:"
	channelListKey    = "novalink:channels"
	muteListKey       = "novalink:mutes"
)

// RedisProvider implements Provider using Redis.
type RedisProvider struct {
	config    RedisConfig
	client    *redis.Client
	mutex     sync.RWMutex
	connected bool
	ctx       context.Context
}

// RedisConfig contains Redis connection settings.
type RedisConfig struct {
	Host     string
	Port     int
	Password string
	Database int
}

// NewRedisProvider creates a new RedisProvider.
func NewRedisProvider(config RedisConfig) *RedisProvider {
	return &RedisProvider{
		config: config,
		ctx:    context.Background(),
	}
}

func (p *RedisProvider) Connect() error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	addr := fmt.Sprintf("%s:%d", p.config.Host, p.config.Port)

	p.client = redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: p.config.Password,
		DB:       p.config.Database,
	})

	// Test connection
	if err := p.client.Ping(p.ctx).Err(); err != nil {
		return fmt.Errorf("failed to connect to Redis: %w", err)
	}

	p.connected = true
	return nil
}

func (p *RedisProvider) Close() error {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	if p.client != nil {
		if err := p.client.Close(); err != nil {
			return err
		}
	}
	p.connected = false
	return nil
}

func (p *RedisProvider) IsConnected() bool {
	p.mutex.RLock()
	defer p.mutex.RUnlock()
	return p.connected
}

// Player State Operations

func (p *RedisProvider) GetPlayerState(playerID string) (*PlayerState, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	key := playerStatePrefix + playerID
	data, err := p.client.Get(p.ctx, key).Bytes()
	if err == redis.Nil {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get player state: %w", err)
	}

	var state PlayerState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, fmt.Errorf("failed to unmarshal player state: %w", err)
	}

	return &state, nil
}

func (p *RedisProvider) SavePlayerState(state *PlayerState) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	data, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("failed to marshal player state: %w", err)
	}

	key := playerStatePrefix + state.PlayerID
	if err := p.client.Set(p.ctx, key, data, 0).Err(); err != nil {
		return fmt.Errorf("failed to save player state: %w", err)
	}

	return nil
}

func (p *RedisProvider) DeletePlayerState(playerID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	key := playerStatePrefix + playerID
	if err := p.client.Del(p.ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete player state: %w", err)
	}

	return nil
}


// Mute Operations

func (p *RedisProvider) GetMute(playerID string) (*MuteRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	key := mutePrefix + playerID
	data, err := p.client.Get(p.ctx, key).Bytes()
	if err == redis.Nil {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get mute: %w", err)
	}

	var mute MuteRecord
	if err := json.Unmarshal(data, &mute); err != nil {
		return nil, fmt.Errorf("failed to unmarshal mute: %w", err)
	}

	// Check if mute has expired
	if mute.ExpiresAt > 0 && time.Now().Unix() > mute.ExpiresAt {
		p.DeleteMute(playerID)
		return nil, ErrNotFound
	}

	return &mute, nil
}

func (p *RedisProvider) SaveMute(mute *MuteRecord) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	data, err := json.Marshal(mute)
	if err != nil {
		return fmt.Errorf("failed to marshal mute: %w", err)
	}

	key := mutePrefix + mute.PlayerID

	// Calculate TTL if mute has expiration
	var expiration time.Duration
	if mute.ExpiresAt > 0 {
		remaining := mute.ExpiresAt - time.Now().Unix()
		if remaining > 0 {
			expiration = time.Duration(remaining) * time.Second
		}
	}

	if err := p.client.Set(p.ctx, key, data, expiration).Err(); err != nil {
		return fmt.Errorf("failed to save mute: %w", err)
	}

	// Add to mute list for tracking
	if err := p.client.SAdd(p.ctx, muteListKey, mute.PlayerID).Err(); err != nil {
		return fmt.Errorf("failed to add to mute list: %w", err)
	}

	return nil
}

func (p *RedisProvider) DeleteMute(playerID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	key := mutePrefix + playerID
	if err := p.client.Del(p.ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete mute: %w", err)
	}

	// Remove from mute list
	p.client.SRem(p.ctx, muteListKey, playerID)

	return nil
}

func (p *RedisProvider) GetActiveMutes() ([]*MuteRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	// Get all mute player IDs from the set
	playerIDs, err := p.client.SMembers(p.ctx, muteListKey).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get mute list: %w", err)
	}

	var mutes []*MuteRecord
	for _, playerID := range playerIDs {
		mute, err := p.GetMute(playerID)
		if err == nil {
			mutes = append(mutes, mute)
		} else if err == ErrNotFound {
			// Remove expired/deleted mute from list
			p.client.SRem(p.ctx, muteListKey, playerID)
		}
	}

	return mutes, nil
}


// Channel Operations

func (p *RedisProvider) GetChannels() ([]*ChannelRecord, error) {
	if !p.IsConnected() {
		return nil, ErrNotConnected
	}

	// Get all channel IDs from the set
	channelIDs, err := p.client.SMembers(p.ctx, channelListKey).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to get channel list: %w", err)
	}

	var channels []*ChannelRecord
	for _, channelID := range channelIDs {
		key := channelPrefix + channelID
		data, err := p.client.Get(p.ctx, key).Bytes()
		if err == redis.Nil {
			// Remove stale entry from list
			p.client.SRem(p.ctx, channelListKey, channelID)
			continue
		}
		if err != nil {
			continue
		}

		var ch ChannelRecord
		if err := json.Unmarshal(data, &ch); err != nil {
			continue
		}
		channels = append(channels, &ch)
	}

	return channels, nil
}

func (p *RedisProvider) SaveChannel(channel *ChannelRecord) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	data, err := json.Marshal(channel)
	if err != nil {
		return fmt.Errorf("failed to marshal channel: %w", err)
	}

	key := channelPrefix + channel.ID
	if err := p.client.Set(p.ctx, key, data, 0).Err(); err != nil {
		return fmt.Errorf("failed to save channel: %w", err)
	}

	// Add to channel list for tracking
	if err := p.client.SAdd(p.ctx, channelListKey, channel.ID).Err(); err != nil {
		return fmt.Errorf("failed to add to channel list: %w", err)
	}

	return nil
}

func (p *RedisProvider) DeleteChannel(channelID string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	key := channelPrefix + channelID
	if err := p.client.Del(p.ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete channel: %w", err)
	}

	// Remove from channel list
	p.client.SRem(p.ctx, channelListKey, channelID)

	return nil
}

// Utility methods for direct Redis operations (caching)

// Get retrieves a value from Redis.
func (p *RedisProvider) Get(key string) (string, error) {
	if !p.IsConnected() {
		return "", ErrNotConnected
	}

	val, err := p.client.Get(p.ctx, key).Result()
	if err == redis.Nil {
		return "", ErrNotFound
	}
	if err != nil {
		return "", fmt.Errorf("failed to get key: %w", err)
	}

	return val, nil
}

// Set stores a value in Redis with optional expiration (in seconds).
func (p *RedisProvider) Set(key, value string, expiration int) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	var exp time.Duration
	if expiration > 0 {
		exp = time.Duration(expiration) * time.Second
	}

	if err := p.client.Set(p.ctx, key, value, exp).Err(); err != nil {
		return fmt.Errorf("failed to set key: %w", err)
	}

	return nil
}

// Delete removes a value from Redis.
func (p *RedisProvider) Delete(key string) error {
	if !p.IsConnected() {
		return ErrNotConnected
	}

	if err := p.client.Del(p.ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete key: %w", err)
	}

	return nil
}

// Exists checks if a key exists in Redis.
func (p *RedisProvider) Exists(key string) (bool, error) {
	if !p.IsConnected() {
		return false, ErrNotConnected
	}

	count, err := p.client.Exists(p.ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check key existence: %w", err)
	}

	return count > 0, nil
}
