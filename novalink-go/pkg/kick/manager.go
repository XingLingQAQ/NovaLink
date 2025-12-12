// Package kick provides player kick functionality.
package kick

import (
	"sync"
	"time"

	"github.com/nova/novalink-go/pkg/protocol"
)

// KickAction represents the action byte for kick packets.
const KickAction byte = 0x10

// Broadcaster is an interface for broadcasting packets to clients.
type Broadcaster interface {
	Broadcast(packet protocol.Packet)
	BroadcastToClient(clientID string, packet protocol.Packet)
	SendToPlayer(playerID string, packet protocol.Packet)
	DisconnectPlayer(playerID string, reason string)
}

// KickRecord represents a kick event.
type KickRecord struct {
	PlayerID   string
	PlayerName string
	Reason     string
	KickedBy   string
	KickedAt   time.Time
	ClientID   string
}

// Manager handles player kick operations.
type Manager struct {
	broadcaster Broadcaster
	history     []*KickRecord
	maxHistory  int
	mutex       sync.RWMutex
}

// NewManager creates a new kick Manager.
func NewManager(broadcaster Broadcaster) *Manager {
	return &Manager{
		broadcaster: broadcaster,
		history:     make([]*KickRecord, 0),
		maxHistory:  100, // Keep last 100 kicks
	}
}

// NewManagerWithHistory creates a new kick Manager with custom history size.
func NewManagerWithHistory(broadcaster Broadcaster, maxHistory int) *Manager {
	if maxHistory < 0 {
		maxHistory = 0
	}
	return &Manager{
		broadcaster: broadcaster,
		history:     make([]*KickRecord, 0),
		maxHistory:  maxHistory,
	}
}

// KickPlayer kicks a player from the chat network.
func (m *Manager) KickPlayer(playerID, playerName, reason, kickedBy string) *KickRecord {
	record := &KickRecord{
		PlayerID:   playerID,
		PlayerName: playerName,
		Reason:     reason,
		KickedBy:   kickedBy,
		KickedAt:   time.Now(),
	}

	m.addToHistory(record)

	if m.broadcaster != nil {
		m.broadcaster.DisconnectPlayer(playerID, reason)
	}

	return record
}

// KickPlayerFromClient kicks a player from a specific client.
func (m *Manager) KickPlayerFromClient(playerID, playerName, clientID, reason, kickedBy string) *KickRecord {
	record := &KickRecord{
		PlayerID:   playerID,
		PlayerName: playerName,
		Reason:     reason,
		KickedBy:   kickedBy,
		KickedAt:   time.Now(),
		ClientID:   clientID,
	}

	m.addToHistory(record)

	if m.broadcaster != nil {
		m.broadcaster.DisconnectPlayer(playerID, reason)
	}

	return record
}

// KickAllFromClient kicks all players from a specific client.
func (m *Manager) KickAllFromClient(clientID, reason, kickedBy string) {
	record := &KickRecord{
		PlayerID:   "*",
		PlayerName: "All Players",
		Reason:     reason,
		KickedBy:   kickedBy,
		KickedAt:   time.Now(),
		ClientID:   clientID,
	}

	m.addToHistory(record)

	// Note: The actual implementation would need to iterate through
	// all players on the client and kick them individually.
	// This is handled by the broadcaster implementation.
}

// addToHistory adds a kick record to history, maintaining max size.
func (m *Manager) addToHistory(record *KickRecord) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	m.history = append(m.history, record)

	// Trim history if it exceeds max size
	if len(m.history) > m.maxHistory {
		m.history = m.history[len(m.history)-m.maxHistory:]
	}
}

// GetHistory returns the kick history.
func (m *Manager) GetHistory() []*KickRecord {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	result := make([]*KickRecord, len(m.history))
	copy(result, m.history)
	return result
}

// GetHistoryForPlayer returns kick history for a specific player.
func (m *Manager) GetHistoryForPlayer(playerID string) []*KickRecord {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var result []*KickRecord
	for _, record := range m.history {
		if record.PlayerID == playerID {
			result = append(result, record)
		}
	}
	return result
}

// GetHistoryForClient returns kick history for a specific client.
func (m *Manager) GetHistoryForClient(clientID string) []*KickRecord {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	var result []*KickRecord
	for _, record := range m.history {
		if record.ClientID == clientID {
			result = append(result, record)
		}
	}
	return result
}

// GetRecentKicks returns kicks within the specified duration.
func (m *Manager) GetRecentKicks(duration time.Duration) []*KickRecord {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	cutoff := time.Now().Add(-duration)
	var result []*KickRecord
	for _, record := range m.history {
		if record.KickedAt.After(cutoff) {
			result = append(result, record)
		}
	}
	return result
}

// ClearHistory clears the kick history.
func (m *Manager) ClearHistory() {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.history = make([]*KickRecord, 0)
}

// GetKickCount returns the total number of kicks in history.
func (m *Manager) GetKickCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.history)
}

// GetKickCountForPlayer returns the number of times a player has been kicked.
func (m *Manager) GetKickCountForPlayer(playerID string) int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	count := 0
	for _, record := range m.history {
		if record.PlayerID == playerID {
			count++
		}
	}
	return count
}
