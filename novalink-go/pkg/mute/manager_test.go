package mute

import (
	"testing"
	"testing/quick"
	"time"
)

// **Feature: novachat-platform-expansion, Property 11: Go Mute Duration Enforcement**
// **Validates: Requirements 17.1**
//
// For any muted player in NovaLink-Go, they should be unable to send messages
// until the mute expires.
func TestMuteDurationEnforcement(t *testing.T) {
	type testInput struct {
		PlayerID   string
		PlayerName string
		Reason     string
		MutedBy    string
		Duration   uint16 // Duration in milliseconds (0-65535)
	}

	f := func(input testInput) bool {
		// Skip empty player IDs
		if input.PlayerID == "" {
			return true
		}

		manager := NewManager(nil)

		// Calculate duration (use milliseconds for fast testing)
		duration := time.Duration(input.Duration) * time.Millisecond

		// Mute the player
		err := manager.Mute(input.PlayerID, input.PlayerName, input.Reason, input.MutedBy, duration)
		if err != nil {
			t.Logf("Failed to mute player: %v", err)
			return false
		}

		// Player should be muted immediately after muting
		if !manager.IsMuted(input.PlayerID) {
			t.Logf("Player should be muted immediately after muting")
			return false
		}

		// If duration is 0, it's a permanent mute - should stay muted
		if duration == 0 {
			// Wait a bit and verify still muted
			time.Sleep(10 * time.Millisecond)
			if !manager.IsMuted(input.PlayerID) {
				t.Logf("Permanent mute should not expire")
				return false
			}
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Mute duration enforcement property failed: %v", err)
	}
}

// TestMuteExpirationProperty verifies that mutes expire after the specified duration.
func TestMuteExpirationProperty(t *testing.T) {
	// Use a short duration for testing
	muteDuration := 50 * time.Millisecond
	manager := NewManager(nil)

	playerID := "test-player-123"

	// Mute the player
	err := manager.Mute(playerID, "TestPlayer", "Testing", "Admin", muteDuration)
	if err != nil {
		t.Fatalf("Failed to mute player: %v", err)
	}

	// Should be muted immediately
	if !manager.IsMuted(playerID) {
		t.Error("Player should be muted immediately")
	}

	// Wait for mute to expire
	time.Sleep(muteDuration + 20*time.Millisecond)

	// Should no longer be muted
	if manager.IsMuted(playerID) {
		t.Error("Player should not be muted after expiration")
	}
}

// TestMuteInfoConsistency verifies that GetMuteInfo returns consistent data.
func TestMuteInfoConsistency(t *testing.T) {
	type testInput struct {
		PlayerID   string
		PlayerName string
		Reason     string
		MutedBy    string
	}

	f := func(input testInput) bool {
		if input.PlayerID == "" {
			return true
		}

		manager := NewManager(nil)
		duration := time.Hour // Long duration to ensure it doesn't expire during test

		err := manager.Mute(input.PlayerID, input.PlayerName, input.Reason, input.MutedBy, duration)
		if err != nil {
			t.Logf("Failed to mute player: %v", err)
			return false
		}

		info := manager.GetMuteInfo(input.PlayerID)
		if info == nil {
			t.Logf("GetMuteInfo returned nil for muted player")
			return false
		}

		// Verify all fields match
		if info.PlayerID != input.PlayerID {
			t.Logf("PlayerID mismatch: expected %s, got %s", input.PlayerID, info.PlayerID)
			return false
		}
		if info.PlayerName != input.PlayerName {
			t.Logf("PlayerName mismatch: expected %s, got %s", input.PlayerName, info.PlayerName)
			return false
		}
		if info.Reason != input.Reason {
			t.Logf("Reason mismatch: expected %s, got %s", input.Reason, info.Reason)
			return false
		}
		if info.MutedBy != input.MutedBy {
			t.Logf("MutedBy mismatch: expected %s, got %s", input.MutedBy, info.MutedBy)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Mute info consistency property failed: %v", err)
	}
}

// TestUnmuteRemovesMute verifies that unmuting a player removes the mute.
func TestUnmuteRemovesMute(t *testing.T) {
	f := func(playerID string) bool {
		if playerID == "" {
			return true
		}

		manager := NewManager(nil)

		// Mute the player
		err := manager.Mute(playerID, "Player", "Test", "Admin", time.Hour)
		if err != nil {
			t.Logf("Failed to mute player: %v", err)
			return false
		}

		// Verify muted
		if !manager.IsMuted(playerID) {
			t.Logf("Player should be muted")
			return false
		}

		// Unmute
		err = manager.Unmute(playerID)
		if err != nil {
			t.Logf("Failed to unmute player: %v", err)
			return false
		}

		// Verify not muted
		if manager.IsMuted(playerID) {
			t.Logf("Player should not be muted after unmute")
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Unmute removes mute property failed: %v", err)
	}
}

// TestMuteIsolation verifies that muting one player doesn't affect others.
func TestMuteIsolation(t *testing.T) {
	f := func(player1, player2 string) bool {
		if player1 == "" || player2 == "" || player1 == player2 {
			return true
		}

		manager := NewManager(nil)

		// Mute player1
		err := manager.Mute(player1, "Player1", "Test", "Admin", time.Hour)
		if err != nil {
			t.Logf("Failed to mute player1: %v", err)
			return false
		}

		// player1 should be muted
		if !manager.IsMuted(player1) {
			t.Logf("player1 should be muted")
			return false
		}

		// player2 should not be muted
		if manager.IsMuted(player2) {
			t.Logf("player2 should not be muted")
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Mute isolation property failed: %v", err)
	}
}

// TestGetAllMutesReturnsActiveMutes verifies that GetAllMutes returns only active mutes.
func TestGetAllMutesReturnsActiveMutes(t *testing.T) {
	manager := NewManager(nil)

	// Mute some players
	players := []string{"player1", "player2", "player3"}
	for _, p := range players {
		err := manager.Mute(p, p, "Test", "Admin", time.Hour)
		if err != nil {
			t.Fatalf("Failed to mute %s: %v", p, err)
		}
	}

	// Get all mutes
	mutes := manager.GetAllMutes()
	if len(mutes) != len(players) {
		t.Errorf("Expected %d mutes, got %d", len(players), len(mutes))
	}

	// Unmute one player
	err := manager.Unmute("player2")
	if err != nil {
		t.Fatalf("Failed to unmute player2: %v", err)
	}

	// Get all mutes again
	mutes = manager.GetAllMutes()
	if len(mutes) != len(players)-1 {
		t.Errorf("Expected %d mutes after unmute, got %d", len(players)-1, len(mutes))
	}
}

// TestCleanExpiredRemovesExpiredMutes verifies that CleanExpired removes expired mutes.
func TestCleanExpiredRemovesExpiredMutes(t *testing.T) {
	manager := NewManager(nil)

	// Mute with short duration
	err := manager.Mute("short-mute", "Player", "Test", "Admin", 10*time.Millisecond)
	if err != nil {
		t.Fatalf("Failed to mute: %v", err)
	}

	// Mute with long duration
	err = manager.Mute("long-mute", "Player", "Test", "Admin", time.Hour)
	if err != nil {
		t.Fatalf("Failed to mute: %v", err)
	}

	// Wait for short mute to expire
	time.Sleep(20 * time.Millisecond)

	// Clean expired
	manager.CleanExpired()

	// Short mute should be removed from cache
	mutes := manager.GetAllMutes()
	if len(mutes) != 1 {
		t.Errorf("Expected 1 mute after cleanup, got %d", len(mutes))
	}

	// Long mute should still exist
	if !manager.IsMuted("long-mute") {
		t.Error("Long mute should still be active")
	}
}

// TestPermanentMuteNeverExpires verifies that permanent mutes (duration=0) never expire.
func TestPermanentMuteNeverExpires(t *testing.T) {
	manager := NewManager(nil)

	// Mute permanently (duration = 0)
	err := manager.Mute("permanent", "Player", "Test", "Admin", 0)
	if err != nil {
		t.Fatalf("Failed to mute: %v", err)
	}

	// Should be muted
	if !manager.IsMuted("permanent") {
		t.Error("Player should be muted")
	}

	// Wait a bit
	time.Sleep(50 * time.Millisecond)

	// Should still be muted
	if !manager.IsMuted("permanent") {
		t.Error("Permanent mute should not expire")
	}

	// Clean expired should not remove permanent mutes
	manager.CleanExpired()
	if !manager.IsMuted("permanent") {
		t.Error("Permanent mute should not be cleaned")
	}
}
