package channel

import (
	"sync"
)

// MessageContext contains information about a message being routed.
type MessageContext struct {
	ChannelID      string
	SenderID       string
	SenderName     string
	SenderClientID string
	SenderWorld    string
	Content        string
}

// Recipient represents a message recipient.
type Recipient struct {
	PlayerID   string
	PlayerName string
	ClientID   string
	World      string
}

// Router handles message routing to appropriate channels.
type Router struct {
	manager *Manager
	mutex   sync.RWMutex
}

// NewRouter creates a new Router.
func NewRouter(manager *Manager) *Router {
	return &Router{manager: manager}
}

// RouteMessage determines which recipients should receive a message.
// This is the main routing function that enforces scope isolation.
func (r *Router) RouteMessage(ctx MessageContext) []Recipient {
	channel, err := r.manager.GetChannel(ctx.ChannelID)
	if err != nil {
		return nil
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	// First check if sender's world is allowed
	if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, ctx.SenderWorld) {
		return nil
	}

	var recipients []Recipient

	switch channel.Scope {
	case ScopeGlobal:
		// Global channels: all members receive the message regardless of client
		recipients = r.routeGlobal(channel, ctx)

	case ScopeServer:
		// Server channels: only members from the same client receive the message
		recipients = r.routeServer(channel, ctx)

	case ScopePrivate:
		// Private channels: only explicit members receive the message
		recipients = r.routePrivate(channel, ctx)
	}

	return recipients
}

// routeGlobal routes messages to all members in a global channel.
func (r *Router) routeGlobal(channel *Channel, ctx MessageContext) []Recipient {
	recipients := make([]Recipient, 0, len(channel.Members))

	for _, member := range channel.Members {
		// Apply world filter if configured
		if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, member.World) {
			continue
		}

		recipients = append(recipients, Recipient{
			PlayerID:   member.PlayerID,
			PlayerName: member.PlayerName,
			ClientID:   member.ClientID,
			World:      member.World,
		})
	}

	return recipients
}

// routeServer routes messages only to members connected through the same client.
// This is the key scope isolation for SERVER-scoped channels.
func (r *Router) routeServer(channel *Channel, ctx MessageContext) []Recipient {
	recipients := make([]Recipient, 0)

	for _, member := range channel.Members {
		// KEY ISOLATION: Only route to members from the same client
		if member.ClientID != ctx.SenderClientID {
			continue
		}

		// Apply world filter if configured
		if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, member.World) {
			continue
		}

		recipients = append(recipients, Recipient{
			PlayerID:   member.PlayerID,
			PlayerName: member.PlayerName,
			ClientID:   member.ClientID,
			World:      member.World,
		})
	}

	return recipients
}

// routePrivate routes messages only to explicit members of a private channel.
func (r *Router) routePrivate(channel *Channel, ctx MessageContext) []Recipient {
	recipients := make([]Recipient, 0, len(channel.Members))

	for _, member := range channel.Members {
		// Apply world filter if configured
		if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, member.World) {
			continue
		}

		recipients = append(recipients, Recipient{
			PlayerID:   member.PlayerID,
			PlayerName: member.PlayerName,
			ClientID:   member.ClientID,
			World:      member.World,
		})
	}

	return recipients
}

// RouteMessageSimple is a simplified routing function for backward compatibility.
// Returns a list of player IDs that should receive the message.
func (r *Router) RouteMessageSimple(channelID, senderClientID, senderWorld string) []string {
	recipients := r.RouteMessage(MessageContext{
		ChannelID:      channelID,
		SenderClientID: senderClientID,
		SenderWorld:    senderWorld,
	})

	playerIDs := make([]string, len(recipients))
	for i, recipient := range recipients {
		playerIDs[i] = recipient.PlayerID
	}
	return playerIDs
}

// GetRecipientsGroupedByClient groups recipients by their client ID.
// This is useful for efficient message delivery to multiple clients.
func (r *Router) GetRecipientsGroupedByClient(recipients []Recipient) map[string][]Recipient {
	grouped := make(map[string][]Recipient)
	for _, recipient := range recipients {
		grouped[recipient.ClientID] = append(grouped[recipient.ClientID], recipient)
	}
	return grouped
}

// GetChannelsForMember returns all channels a member belongs to.
func (r *Router) GetChannelsForMember(memberID string) []*Channel {
	return r.manager.GetPlayerChannels(memberID)
}

// GetAccessibleChannels returns channels accessible to a player based on their client and world.
func (r *Router) GetAccessibleChannels(playerID, clientID, world string) []*Channel {
	allChannels := r.manager.GetAllChannels()
	accessible := make([]*Channel, 0)

	for _, ch := range allChannels {
		ch.mutex.RLock()

		// Check scope-based access
		canAccess := false
		switch ch.Scope {
		case ScopeGlobal:
			canAccess = true
		case ScopeServer:
			canAccess = ch.ClientID == clientID
		case ScopePrivate:
			// Private channels require explicit membership or ownership
			_, isMember := ch.Members[playerID]
			canAccess = isMember || ch.OwnerID == playerID
		}

		// Check world restrictions
		if canAccess && len(ch.AllowedWorlds) > 0 {
			canAccess = isWorldAllowed(ch.AllowedWorlds, world)
		}

		ch.mutex.RUnlock()

		if canAccess {
			accessible = append(accessible, ch)
		}
	}

	return accessible
}

// CanSendToChannel checks if a player can send messages to a channel.
func (r *Router) CanSendToChannel(channelID, playerID, clientID, world string) bool {
	channel, err := r.manager.GetChannel(channelID)
	if err != nil {
		return false
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	// Must be a member
	_, isMember := channel.Members[playerID]
	if !isMember {
		return false
	}

	// Check scope restrictions
	switch channel.Scope {
	case ScopeServer:
		if channel.ClientID != clientID {
			return false
		}
	}

	// Check world restrictions
	if len(channel.AllowedWorlds) > 0 && !isWorldAllowed(channel.AllowedWorlds, world) {
		return false
	}

	return true
}

// BroadcastToChannel returns all recipients for a broadcast message (ignores sender filtering).
func (r *Router) BroadcastToChannel(channelID string) []Recipient {
	channel, err := r.manager.GetChannel(channelID)
	if err != nil {
		return nil
	}

	channel.mutex.RLock()
	defer channel.mutex.RUnlock()

	recipients := make([]Recipient, 0, len(channel.Members))
	for _, member := range channel.Members {
		recipients = append(recipients, Recipient{
			PlayerID:   member.PlayerID,
			PlayerName: member.PlayerName,
			ClientID:   member.ClientID,
			World:      member.World,
		})
	}

	return recipients
}
