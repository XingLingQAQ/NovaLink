package channel

import (
	"fmt"
	"testing"
	"testing/quick"
)

// **Feature: novachat-platform-expansion, Property 5: Go Message Routing Scope Isolation**
// **Validates: Requirements 14.1-14.5**
//
// For any SERVER-scoped channel in NovaLink-Go, messages should only be
// delivered to players connected through the same client.
func TestServerScopeIsolation(t *testing.T) {
	f := func(
		channelID string,
		senderClientID string,
		numMembers uint8,
		memberClientSeed uint8,
	) bool {
		// Skip empty inputs
		if channelID == "" || senderClientID == "" || numMembers == 0 {
			return true
		}

		// Limit members to reasonable number
		memberCount := int(numMembers%20) + 1

		// Create manager and router
		manager := NewManager()
		router := NewRouter(manager)

		// Create a SERVER-scoped channel
		_, err := manager.CreateChannel(ChannelConfig{
			ID:          channelID,
			DisplayName: "Test Server Channel",
			Scope:       ScopeServer,
			ClientID:    senderClientID,
		})
		if err != nil {
			// Channel ID collision is acceptable
			return true
		}

		// Add members from different clients
		clientIDs := []string{senderClientID, "other-client-1", "other-client-2"}
		expectedSameClientCount := 0

		for i := 0; i < memberCount; i++ {
			// Deterministically assign client based on seed
			clientIndex := (int(memberClientSeed) + i) % len(clientIDs)
			clientID := clientIDs[clientIndex]
			playerID := fmt.Sprintf("player-%d", i)

			err := manager.JoinChannel(JoinRequest{
				ChannelID:  channelID,
				PlayerID:   playerID,
				PlayerName: playerID,
				ClientID:   clientID,
				World:      "world",
			})
			if err != nil {
				continue
			}

			if clientID == senderClientID {
				expectedSameClientCount++
			}
		}

		// Route a message from the sender's client
		recipients := router.RouteMessage(MessageContext{
			ChannelID:      channelID,
			SenderID:       "sender",
			SenderClientID: senderClientID,
			SenderWorld:    "world",
		})

		// PROPERTY: All recipients must be from the same client as the sender
		for _, recipient := range recipients {
			if recipient.ClientID != senderClientID {
				t.Logf("Scope isolation violated: recipient %s has clientID %s, expected %s",
					recipient.PlayerID, recipient.ClientID, senderClientID)
				return false
			}
		}

		// PROPERTY: Number of recipients should match members from same client
		if len(recipients) != expectedSameClientCount {
			t.Logf("Recipient count mismatch: got %d, expected %d",
				len(recipients), expectedSameClientCount)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Server scope isolation property failed: %v", err)
	}
}

// TestGlobalScopeDelivery verifies that GLOBAL-scoped channels deliver to all members.
func TestGlobalScopeDelivery(t *testing.T) {
	f := func(
		channelID string,
		numMembers uint8,
	) bool {
		// Skip empty inputs
		if channelID == "" || numMembers == 0 {
			return true
		}

		memberCount := int(numMembers%20) + 1

		manager := NewManager()
		router := NewRouter(manager)

		// Create a GLOBAL-scoped channel
		_, err := manager.CreateChannel(ChannelConfig{
			ID:          channelID,
			DisplayName: "Test Global Channel",
			Scope:       ScopeGlobal,
		})
		if err != nil {
			return true
		}

		// Add members from different clients
		clientIDs := []string{"client-1", "client-2", "client-3"}
		addedMembers := 0

		for i := 0; i < memberCount; i++ {
			clientID := clientIDs[i%len(clientIDs)]
			playerID := fmt.Sprintf("player-%d", i)

			err := manager.JoinChannel(JoinRequest{
				ChannelID:  channelID,
				PlayerID:   playerID,
				PlayerName: playerID,
				ClientID:   clientID,
				World:      "world",
			})
			if err == nil {
				addedMembers++
			}
		}

		// Route a message
		recipients := router.RouteMessage(MessageContext{
			ChannelID:      channelID,
			SenderID:       "sender",
			SenderClientID: "client-1",
			SenderWorld:    "world",
		})

		// PROPERTY: All members should receive the message regardless of client
		if len(recipients) != addedMembers {
			t.Logf("Global scope delivery failed: got %d recipients, expected %d",
				len(recipients), addedMembers)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Global scope delivery property failed: %v", err)
	}
}

// TestPrivateScopeDelivery verifies that PRIVATE-scoped channels deliver only to members.
func TestPrivateScopeDelivery(t *testing.T) {
	f := func(
		channelID string,
		ownerID string,
		numMembers uint8,
	) bool {
		// Skip empty inputs
		if channelID == "" || ownerID == "" || numMembers == 0 {
			return true
		}

		memberCount := int(numMembers%20) + 1

		manager := NewManager()
		router := NewRouter(manager)

		// Create a PRIVATE-scoped channel
		_, err := manager.CreateChannel(ChannelConfig{
			ID:          channelID,
			DisplayName: "Test Private Channel",
			Scope:       ScopePrivate,
			OwnerID:     ownerID,
		})
		if err != nil {
			return true
		}

		// Add members
		addedMembers := 0
		for i := 0; i < memberCount; i++ {
			playerID := fmt.Sprintf("player-%d", i)
			clientID := fmt.Sprintf("client-%d", i%3)

			err := manager.JoinChannel(JoinRequest{
				ChannelID:  channelID,
				PlayerID:   playerID,
				PlayerName: playerID,
				ClientID:   clientID,
				World:      "world",
			})
			if err == nil {
				addedMembers++
			}
		}

		// Route a message
		recipients := router.RouteMessage(MessageContext{
			ChannelID:      channelID,
			SenderID:       "sender",
			SenderClientID: "client-0",
			SenderWorld:    "world",
		})

		// PROPERTY: Only explicit members should receive the message
		if len(recipients) != addedMembers {
			t.Logf("Private scope delivery failed: got %d recipients, expected %d",
				len(recipients), addedMembers)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("Private scope delivery property failed: %v", err)
	}
}

// TestWorldFilterIsolation verifies that world filters are properly applied.
func TestWorldFilterIsolation(t *testing.T) {
	f := func(
		channelID string,
		allowedWorld string,
		numMembers uint8,
	) bool {
		// Skip empty inputs
		if channelID == "" || allowedWorld == "" || numMembers == 0 {
			return true
		}

		memberCount := int(numMembers%20) + 1

		manager := NewManager()
		router := NewRouter(manager)

		// Create a channel with world restriction
		_, err := manager.CreateChannel(ChannelConfig{
			ID:            channelID,
			DisplayName:   "Test World Filter Channel",
			Scope:         ScopeGlobal,
			AllowedWorlds: []string{allowedWorld},
		})
		if err != nil {
			return true
		}

		// Add members in different worlds
		worlds := []string{allowedWorld, "other-world-1", "other-world-2"}
		expectedInWorldCount := 0

		for i := 0; i < memberCount; i++ {
			world := worlds[i%len(worlds)]
			playerID := fmt.Sprintf("player-%d", i)

			err := manager.JoinChannel(JoinRequest{
				ChannelID:  channelID,
				PlayerID:   playerID,
				PlayerName: playerID,
				ClientID:   "client-1",
				World:      world,
			})
			if err == nil && world == allowedWorld {
				expectedInWorldCount++
			}
		}

		// Route a message from the allowed world
		recipients := router.RouteMessage(MessageContext{
			ChannelID:      channelID,
			SenderID:       "sender",
			SenderClientID: "client-1",
			SenderWorld:    allowedWorld,
		})

		// PROPERTY: Only members in allowed worlds should receive the message
		for _, recipient := range recipients {
			if recipient.World != allowedWorld {
				t.Logf("World filter violated: recipient %s in world %s, expected %s",
					recipient.PlayerID, recipient.World, allowedWorld)
				return false
			}
		}

		// PROPERTY: Count should match members in allowed world
		if len(recipients) != expectedInWorldCount {
			t.Logf("World filter count mismatch: got %d, expected %d",
				len(recipients), expectedInWorldCount)
			return false
		}

		return true
	}

	config := &quick.Config{MaxCount: 100}
	if err := quick.Check(f, config); err != nil {
		t.Errorf("World filter isolation property failed: %v", err)
	}
}

// TestSenderWorldRestriction verifies that senders in non-allowed worlds cannot send.
func TestSenderWorldRestriction(t *testing.T) {
	manager := NewManager()
	router := NewRouter(manager)

	// Create a channel with world restriction
	_, err := manager.CreateChannel(ChannelConfig{
		ID:            "restricted-channel",
		DisplayName:   "Restricted Channel",
		Scope:         ScopeGlobal,
		AllowedWorlds: []string{"allowed-world"},
	})
	if err != nil {
		t.Fatalf("Failed to create channel: %v", err)
	}

	// Add a member in the allowed world
	err = manager.JoinChannel(JoinRequest{
		ChannelID:  "restricted-channel",
		PlayerID:   "player-1",
		PlayerName: "Player 1",
		ClientID:   "client-1",
		World:      "allowed-world",
	})
	if err != nil {
		t.Fatalf("Failed to join channel: %v", err)
	}

	// Try to route from a non-allowed world
	recipients := router.RouteMessage(MessageContext{
		ChannelID:      "restricted-channel",
		SenderID:       "sender",
		SenderClientID: "client-1",
		SenderWorld:    "forbidden-world",
	})

	// PROPERTY: No recipients when sender is in non-allowed world
	if len(recipients) != 0 {
		t.Errorf("Expected 0 recipients for sender in forbidden world, got %d", len(recipients))
	}
}
