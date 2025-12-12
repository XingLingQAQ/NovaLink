// Package announcement provides announcement broadcasting functionality.
package announcement

import (
	"sync"
	"time"

	"github.com/nova/novalink-go/pkg/protocol"
)

// AnnouncementType defines the type of announcement.
type AnnouncementType byte

const (
	// TypeChat sends announcement as a chat message
	TypeChat AnnouncementType = 0
	// TypeActionBar sends announcement as an action bar message
	TypeActionBar AnnouncementType = 1
	// TypeBossBar sends announcement as a boss bar message
	TypeBossBar AnnouncementType = 2
)

// Broadcaster is an interface for broadcasting packets to clients.
type Broadcaster interface {
	Broadcast(packet protocol.Packet)
	BroadcastToChannel(channelID string, packet protocol.Packet)
	BroadcastToClient(clientID string, packet protocol.Packet)
}

// Announcement represents a scheduled or one-time announcement.
type Announcement struct {
	ID        string
	Content   string
	Type      AnnouncementType
	Interval  time.Duration // 0 for one-time announcements
	ChannelID string        // Empty for global announcements
	ClientID  string        // Empty for all clients
	CreatedAt time.Time
	CreatedBy string
	Enabled   bool
}

// Manager handles announcement broadcasting and scheduling.
type Manager struct {
	broadcaster   Broadcaster
	announcements map[string]*Announcement
	timers        map[string]*time.Timer
	mutex         sync.RWMutex
	stopChan      chan struct{}
}

// NewManager creates a new announcement Manager.
func NewManager(broadcaster Broadcaster) *Manager {
	return &Manager{
		broadcaster:   broadcaster,
		announcements: make(map[string]*Announcement),
		timers:        make(map[string]*time.Timer),
		stopChan:      make(chan struct{}),
	}
}

// Broadcast sends an immediate announcement to all connected clients.
func (m *Manager) Broadcast(content string, announcementType AnnouncementType) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.AnnouncementPacket{
		Type:    byte(announcementType),
		Content: content,
	}

	m.broadcaster.Broadcast(packet)
}

// BroadcastToChannel sends an announcement to a specific channel.
func (m *Manager) BroadcastToChannel(channelID, content string, announcementType AnnouncementType) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.AnnouncementPacket{
		Type:    byte(announcementType),
		Content: content,
	}

	m.broadcaster.BroadcastToChannel(channelID, packet)
}

// BroadcastToClient sends an announcement to a specific client.
func (m *Manager) BroadcastToClient(clientID, content string, announcementType AnnouncementType) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.AnnouncementPacket{
		Type:    byte(announcementType),
		Content: content,
	}

	m.broadcaster.BroadcastToClient(clientID, packet)
}

// AddScheduledAnnouncement adds a recurring announcement.
func (m *Manager) AddScheduledAnnouncement(announcement *Announcement) {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	// Stop existing timer if any
	if timer, exists := m.timers[announcement.ID]; exists {
		timer.Stop()
	}

	m.announcements[announcement.ID] = announcement

	if announcement.Enabled && announcement.Interval > 0 {
		m.startTimer(announcement)
	}
}

// RemoveAnnouncement removes a scheduled announcement.
func (m *Manager) RemoveAnnouncement(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	if timer, exists := m.timers[id]; exists {
		timer.Stop()
		delete(m.timers, id)
	}

	if _, exists := m.announcements[id]; exists {
		delete(m.announcements, id)
		return true
	}

	return false
}

// GetAnnouncement returns an announcement by ID.
func (m *Manager) GetAnnouncement(id string) *Announcement {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	return m.announcements[id]
}

// GetAllAnnouncements returns all announcements.
func (m *Manager) GetAllAnnouncements() []*Announcement {
	m.mutex.RLock()
	defer m.mutex.RUnlock()

	result := make([]*Announcement, 0, len(m.announcements))
	for _, a := range m.announcements {
		result = append(result, a)
	}
	return result
}

// EnableAnnouncement enables a scheduled announcement.
func (m *Manager) EnableAnnouncement(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	announcement, exists := m.announcements[id]
	if !exists {
		return false
	}

	announcement.Enabled = true

	if announcement.Interval > 0 {
		m.startTimer(announcement)
	}

	return true
}

// DisableAnnouncement disables a scheduled announcement.
func (m *Manager) DisableAnnouncement(id string) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	announcement, exists := m.announcements[id]
	if !exists {
		return false
	}

	announcement.Enabled = false

	if timer, exists := m.timers[id]; exists {
		timer.Stop()
		delete(m.timers, id)
	}

	return true
}

// UpdateAnnouncement updates an existing announcement.
func (m *Manager) UpdateAnnouncement(id string, content string, announcementType AnnouncementType, interval time.Duration) bool {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	announcement, exists := m.announcements[id]
	if !exists {
		return false
	}

	// Stop existing timer
	if timer, exists := m.timers[id]; exists {
		timer.Stop()
		delete(m.timers, id)
	}

	announcement.Content = content
	announcement.Type = announcementType
	announcement.Interval = interval

	// Restart timer if enabled and has interval
	if announcement.Enabled && announcement.Interval > 0 {
		m.startTimer(announcement)
	}

	return true
}

// startTimer starts the timer for a scheduled announcement.
// Must be called with mutex held.
func (m *Manager) startTimer(announcement *Announcement) {
	timer := time.AfterFunc(announcement.Interval, func() {
		m.executeAnnouncement(announcement.ID)
	})
	m.timers[announcement.ID] = timer
}

// executeAnnouncement executes an announcement and reschedules if needed.
func (m *Manager) executeAnnouncement(id string) {
	m.mutex.Lock()
	announcement, exists := m.announcements[id]
	if !exists || !announcement.Enabled {
		m.mutex.Unlock()
		return
	}

	// Copy values while holding lock
	content := announcement.Content
	announcementType := announcement.Type
	channelID := announcement.ChannelID
	clientID := announcement.ClientID
	interval := announcement.Interval

	// Reschedule if it's a recurring announcement
	if interval > 0 {
		m.startTimer(announcement)
	}
	m.mutex.Unlock()

	// Send the announcement
	if m.broadcaster != nil {
		packet := &protocol.AnnouncementPacket{
			Type:    byte(announcementType),
			Content: content,
		}

		if clientID != "" {
			m.broadcaster.BroadcastToClient(clientID, packet)
		} else if channelID != "" {
			m.broadcaster.BroadcastToChannel(channelID, packet)
		} else {
			m.broadcaster.Broadcast(packet)
		}
	}
}

// Stop stops all scheduled announcements.
func (m *Manager) Stop() {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	close(m.stopChan)

	for id, timer := range m.timers {
		timer.Stop()
		delete(m.timers, id)
	}
}

// GetScheduledCount returns the number of scheduled announcements.
func (m *Manager) GetScheduledCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.announcements)
}

// GetActiveTimerCount returns the number of active timers.
func (m *Manager) GetActiveTimerCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.timers)
}
