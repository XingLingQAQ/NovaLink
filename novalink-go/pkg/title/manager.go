// Package title provides title message functionality.
package title

import (
	"sync"

	"github.com/nova/novalink-go/pkg/protocol"
)

// DefaultFadeIn is the default fade-in time in ticks (20 ticks = 1 second).
const DefaultFadeIn = 10

// DefaultStay is the default stay time in ticks.
const DefaultStay = 70

// DefaultFadeOut is the default fade-out time in ticks.
const DefaultFadeOut = 20

// Broadcaster is an interface for broadcasting packets to clients.
type Broadcaster interface {
	Broadcast(packet protocol.Packet)
	BroadcastToChannel(channelID string, packet protocol.Packet)
	BroadcastToClient(clientID string, packet protocol.Packet)
	SendToPlayer(playerID string, packet protocol.Packet)
}

// TitleMessage represents a title message with timing.
type TitleMessage struct {
	Title    string
	Subtitle string
	FadeIn   int32 // Ticks (20 ticks = 1 second)
	Stay     int32 // Ticks
	FadeOut  int32 // Ticks
}

// Manager handles title message sending.
type Manager struct {
	broadcaster Broadcaster
	mutex       sync.RWMutex
}

// NewManager creates a new title Manager.
func NewManager(broadcaster Broadcaster) *Manager {
	return &Manager{
		broadcaster: broadcaster,
	}
}

// SendToAll sends a title message to all connected players.
func (m *Manager) SendToAll(title, subtitle string, fadeIn, stay, fadeOut int32) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.TitleMessagePacket{
		ChannelID: "",
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   fadeIn,
		Stay:     stay,
		FadeOut:  fadeOut,
	}

	m.broadcaster.Broadcast(packet)
}

// SendToAllWithDefaults sends a title message with default timing.
func (m *Manager) SendToAllWithDefaults(title, subtitle string) {
	m.SendToAll(title, subtitle, DefaultFadeIn, DefaultStay, DefaultFadeOut)
}

// SendToChannel sends a title message to all players in a channel.
func (m *Manager) SendToChannel(channelID, title, subtitle string, fadeIn, stay, fadeOut int32) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.TitleMessagePacket{
		ChannelID: channelID,
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   fadeIn,
		Stay:     stay,
		FadeOut:  fadeOut,
	}

	m.broadcaster.BroadcastToChannel(channelID, packet)
}

// SendToChannelWithDefaults sends a title message to a channel with default timing.
func (m *Manager) SendToChannelWithDefaults(channelID, title, subtitle string) {
	m.SendToChannel(channelID, title, subtitle, DefaultFadeIn, DefaultStay, DefaultFadeOut)
}

// SendToClient sends a title message to all players on a specific client.
func (m *Manager) SendToClient(clientID, title, subtitle string, fadeIn, stay, fadeOut int32) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.TitleMessagePacket{
		ChannelID: "",
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   fadeIn,
		Stay:     stay,
		FadeOut:  fadeOut,
	}

	m.broadcaster.BroadcastToClient(clientID, packet)
}

// SendToClientWithDefaults sends a title message to a client with default timing.
func (m *Manager) SendToClientWithDefaults(clientID, title, subtitle string) {
	m.SendToClient(clientID, title, subtitle, DefaultFadeIn, DefaultStay, DefaultFadeOut)
}

// SendToPlayer sends a title message to a specific player.
func (m *Manager) SendToPlayer(playerID, title, subtitle string, fadeIn, stay, fadeOut int32) {
	if m.broadcaster == nil {
		return
	}

	packet := &protocol.TitleMessagePacket{
		ChannelID: "",
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   fadeIn,
		Stay:     stay,
		FadeOut:  fadeOut,
	}

	m.broadcaster.SendToPlayer(playerID, packet)
}

// SendToPlayerWithDefaults sends a title message to a player with default timing.
func (m *Manager) SendToPlayerWithDefaults(playerID, title, subtitle string) {
	m.SendToPlayer(playerID, title, subtitle, DefaultFadeIn, DefaultStay, DefaultFadeOut)
}

// CreateTitleMessage creates a TitleMessage struct with the given parameters.
func CreateTitleMessage(title, subtitle string, fadeIn, stay, fadeOut int32) *TitleMessage {
	return &TitleMessage{
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   fadeIn,
		Stay:     stay,
		FadeOut:  fadeOut,
	}
}

// CreateTitleMessageWithDefaults creates a TitleMessage with default timing.
func CreateTitleMessageWithDefaults(title, subtitle string) *TitleMessage {
	return &TitleMessage{
		Title:    title,
		Subtitle: subtitle,
		FadeIn:   DefaultFadeIn,
		Stay:     DefaultStay,
		FadeOut:  DefaultFadeOut,
	}
}

// ToPacket converts a TitleMessage to a protocol packet.
func (t *TitleMessage) ToPacket() *protocol.TitleMessagePacket {
	return &protocol.TitleMessagePacket{
		ChannelID: "",
		Title:    t.Title,
		Subtitle: t.Subtitle,
		FadeIn:   t.FadeIn,
		Stay:     t.Stay,
		FadeOut:  t.FadeOut,
	}
}

// ValidateTiming validates that timing values are non-negative.
func ValidateTiming(fadeIn, stay, fadeOut int32) bool {
	return fadeIn >= 0 && stay >= 0 && fadeOut >= 0
}
