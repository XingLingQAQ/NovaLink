package storage

import (
	"reflect"
	"testing"
	"testing/quick"
)

// **Feature: novachat-platform-expansion, Property 10: Go Player State Persistence Round-Trip**
// **Validates: Requirements 16.1-16.5**
//
// For any valid player state, saving to storage and loading back should
// produce an equivalent state object.
func TestPlayerStatePersistenceRoundTrip(t *testing.T) {
	// Create a memory provider for testing
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	f := func(
		playerID string,
		playerName string,
		currentChannel string,
		chatEnabled bool,
		lastSeen int64,
	) bool {
		// Skip empty player IDs as they are invalid
		if playerID == "" {
			return true
		}

		// Create a player state
		original := &PlayerState{
			PlayerID:       playerID,
			PlayerName:     playerName,
			CurrentChannel: currentChannel,
			JoinedChannels: []string{}, // Will test separately
			ChatEnabled:    chatEnabled,
			LastSeen:       lastSeen,
		}

		// Save the state
		if err := provider.SavePlayerState(original); err != nil {
			t.Logf("SavePlayerState failed: %v", err)
			return false
		}

		// Load the state back
		loaded, err := provider.GetPlayerState(playerID)
		if err != nil {
			t.Logf("GetPlayerState failed: %v", err)
			return false
		}

		// Verify round-trip
		if loaded.PlayerID != original.PlayerID {
			t.Logf("PlayerID mismatch: original=%s, loaded=%s", original.PlayerID, loaded.PlayerID)
			return false
		}
		if loaded.PlayerName != original.PlayerName {
			t.Logf("PlayerName mismatch: original=%s, loaded=%s", original.PlayerName, loaded.PlayerName)
			return false
		}
		if loaded.CurrentChannel != original.CurrentChannel {
			t.Logf("CurrentChannel mismatch: original=%s, loaded=%s", original.CurrentChannel, loaded.CurrentChannel)
			return false
		}
		if loaded.ChatEnabled != original.ChatEnabled {
			t.Logf("ChatEnabled mismatch: original=%v, loaded=%v", original.ChatEnabled, loaded.ChatEnabled)
			return false
		}
		if loaded.LastSeen != original.LastSeen {
			t.Logf("LastSeen mismatch: original=%d, loaded=%d", original.LastSeen, loaded.LastSeen)
			return false
		}

		// Clean up
		provider.DeletePlayerState(playerID)

		return true
	}

	// Run property test with 100+ iterations
	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Player state persistence round-trip property failed: %v", err)
	}
}

// TestPlayerStateWithJoinedChannelsRoundTrip tests round-trip with joined channels.
func TestPlayerStateWithJoinedChannelsRoundTrip(t *testing.T) {
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	testCases := []struct {
		name           string
		joinedChannels []string
	}{
		{"empty", []string{}},
		{"single", []string{"global"}},
		{"multiple", []string{"global", "local", "private-123"}},
		{"with_special_chars", []string{"channel-1", "channel_2", "channel.3"}},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			original := &PlayerState{
				PlayerID:       "test-player-" + tc.name,
				PlayerName:     "TestPlayer",
				CurrentChannel: "global",
				JoinedChannels: tc.joinedChannels,
				ChatEnabled:    true,
				LastSeen:       1234567890,
			}

			// Save
			if err := provider.SavePlayerState(original); err != nil {
				t.Fatalf("SavePlayerState failed: %v", err)
			}

			// Load
			loaded, err := provider.GetPlayerState(original.PlayerID)
			if err != nil {
				t.Fatalf("GetPlayerState failed: %v", err)
			}

			// Verify joined channels
			if !reflect.DeepEqual(loaded.JoinedChannels, original.JoinedChannels) {
				t.Errorf("JoinedChannels mismatch: original=%v, loaded=%v", original.JoinedChannels, loaded.JoinedChannels)
			}

			// Clean up
			provider.DeletePlayerState(original.PlayerID)
		})
	}
}


// TestMuteRecordPersistenceRoundTrip tests mute record persistence.
func TestMuteRecordPersistenceRoundTrip(t *testing.T) {
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	f := func(
		playerID string,
		playerName string,
		reason string,
		mutedBy string,
		mutedAt int64,
	) bool {
		// Skip empty player IDs
		if playerID == "" {
			return true
		}

		// Use a future expiration time to avoid expiration during test
		expiresAt := mutedAt + 3600 // 1 hour from muted time
		if expiresAt < 0 {
			expiresAt = 0 // Permanent mute
		}

		original := &MuteRecord{
			PlayerID:   playerID,
			PlayerName: playerName,
			Reason:     reason,
			MutedBy:    mutedBy,
			MutedAt:    mutedAt,
			ExpiresAt:  expiresAt,
		}

		// Save
		if err := provider.SaveMute(original); err != nil {
			t.Logf("SaveMute failed: %v", err)
			return false
		}

		// Load
		loaded, err := provider.GetMute(playerID)
		if err != nil {
			t.Logf("GetMute failed: %v", err)
			return false
		}

		// Verify
		if loaded.PlayerID != original.PlayerID ||
			loaded.PlayerName != original.PlayerName ||
			loaded.Reason != original.Reason ||
			loaded.MutedBy != original.MutedBy ||
			loaded.MutedAt != original.MutedAt ||
			loaded.ExpiresAt != original.ExpiresAt {
			t.Logf("Mute record mismatch")
			return false
		}

		// Clean up
		provider.DeleteMute(playerID)

		return true
	}

	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Mute record persistence round-trip property failed: %v", err)
	}
}

// TestChannelRecordPersistenceRoundTrip tests channel record persistence.
func TestChannelRecordPersistenceRoundTrip(t *testing.T) {
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	f := func(
		id string,
		displayName string,
		scope string,
		clientID string,
		permission string,
		maxCapacity int,
		password string,
		ownerID string,
		format string,
	) bool {
		// Skip empty IDs
		if id == "" {
			return true
		}

		original := &ChannelRecord{
			ID:            id,
			DisplayName:   displayName,
			Scope:         scope,
			ClientID:      clientID,
			Permission:    permission,
			MaxCapacity:   maxCapacity,
			AllowedWorlds: []string{}, // Will test separately
			Password:      password,
			OwnerID:       ownerID,
			Format:        format,
		}

		// Save
		if err := provider.SaveChannel(original); err != nil {
			t.Logf("SaveChannel failed: %v", err)
			return false
		}

		// Load all channels and find ours
		channels, err := provider.GetChannels()
		if err != nil {
			t.Logf("GetChannels failed: %v", err)
			return false
		}

		var loaded *ChannelRecord
		for _, ch := range channels {
			if ch.ID == id {
				loaded = ch
				break
			}
		}

		if loaded == nil {
			t.Logf("Channel not found after save")
			return false
		}

		// Verify
		if loaded.ID != original.ID ||
			loaded.DisplayName != original.DisplayName ||
			loaded.Scope != original.Scope ||
			loaded.ClientID != original.ClientID ||
			loaded.Permission != original.Permission ||
			loaded.MaxCapacity != original.MaxCapacity ||
			loaded.Password != original.Password ||
			loaded.OwnerID != original.OwnerID ||
			loaded.Format != original.Format {
			t.Logf("Channel record mismatch")
			return false
		}

		// Clean up
		provider.DeleteChannel(id)

		return true
	}

	config := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Channel record persistence round-trip property failed: %v", err)
	}
}


// TestPlayerStateManagerRoundTrip tests the PlayerStateManager round-trip.
func TestPlayerStateManagerRoundTrip(t *testing.T) {
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	config := DefaultPlayerStateManagerConfig()
	config.AutoSave = false // Disable auto-save for testing
	manager := NewPlayerStateManager(provider, config)

	f := func(
		playerID string,
		playerName string,
		currentChannel string,
		chatEnabled bool,
	) bool {
		// Skip empty player IDs
		if playerID == "" {
			return true
		}

		// Create or get player state
		state, err := manager.GetOrCreatePlayerState(playerID, playerName)
		if err != nil {
			t.Logf("GetOrCreatePlayerState failed: %v", err)
			return false
		}

		// Update state
		state.CurrentChannel = currentChannel
		state.ChatEnabled = chatEnabled

		// Save immediately
		if err := manager.SavePlayerStateImmediate(state); err != nil {
			t.Logf("SavePlayerStateImmediate failed: %v", err)
			return false
		}

		// Clear cache to force reload from storage
		manager.InvalidateCache(playerID)

		// Load again
		loaded, err := manager.GetPlayerState(playerID)
		if err != nil {
			t.Logf("GetPlayerState failed: %v", err)
			return false
		}

		// Verify
		if loaded.PlayerID != playerID ||
			loaded.PlayerName != playerName ||
			loaded.CurrentChannel != currentChannel ||
			loaded.ChatEnabled != chatEnabled {
			t.Logf("State mismatch after round-trip")
			return false
		}

		// Clean up
		manager.DeletePlayerState(playerID)

		return true
	}

	quickConfig := &quick.Config{MaxCount: 200}
	if err := quick.Check(f, quickConfig); err != nil {
		t.Errorf("PlayerStateManager round-trip property failed: %v", err)
	}
}

// TestPlayerStateManagerChannelOperations tests channel join/leave operations.
func TestPlayerStateManagerChannelOperations(t *testing.T) {
	provider := NewMemoryProvider()
	if err := provider.Connect(); err != nil {
		t.Fatalf("Failed to connect to memory provider: %v", err)
	}
	defer provider.Close()

	config := DefaultPlayerStateManagerConfig()
	config.AutoSave = false
	manager := NewPlayerStateManager(provider, config)

	playerID := "test-player-channels"
	playerName := "TestPlayer"

	// Create player
	_, err := manager.GetOrCreatePlayerState(playerID, playerName)
	if err != nil {
		t.Fatalf("GetOrCreatePlayerState failed: %v", err)
	}

	// Join channels
	channels := []string{"global", "local", "private-123"}
	for _, ch := range channels {
		if err := manager.JoinChannel(playerID, ch); err != nil {
			t.Fatalf("JoinChannel failed: %v", err)
		}
	}

	// Verify joined channels
	joined, err := manager.GetJoinedChannels(playerID)
	if err != nil {
		t.Fatalf("GetJoinedChannels failed: %v", err)
	}

	if len(joined) != len(channels) {
		t.Errorf("Expected %d channels, got %d", len(channels), len(joined))
	}

	// Leave a channel
	if err := manager.LeaveChannel(playerID, "local"); err != nil {
		t.Fatalf("LeaveChannel failed: %v", err)
	}

	// Verify
	joined, _ = manager.GetJoinedChannels(playerID)
	if len(joined) != 2 {
		t.Errorf("Expected 2 channels after leave, got %d", len(joined))
	}

	// Clean up
	manager.DeletePlayerState(playerID)
}

// TestProviderInterface verifies all providers implement the Provider interface.
func TestProviderInterface(t *testing.T) {
	// This test ensures compile-time interface compliance
	var _ Provider = (*MemoryProvider)(nil)
	var _ Provider = (*MySQLProvider)(nil)
	var _ Provider = (*RedisProvider)(nil)
}
