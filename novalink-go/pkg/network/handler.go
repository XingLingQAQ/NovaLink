package network

import (
	"fmt"
	"sync"

	"github.com/nova/novalink-go/pkg/protocol"
)

// PacketHandlerFunc is a function type for handling specific packet types.
type PacketHandlerFunc func(*ClientConnection, protocol.Packet) error

// DefaultHandler provides default packet handling logic with support for
// registering custom handlers for each packet type.
type DefaultHandler struct {
	server   *Server
	handlers map[byte]PacketHandlerFunc
	mutex    sync.RWMutex

	// Typed handlers for common packet types
	onHandshake       func(*ClientConnection, *protocol.HandshakePacket) error
	onChatMessage     func(*ClientConnection, *protocol.ChatMessagePacket) error
	onChannelAction   func(*ClientConnection, *protocol.ChannelActionPacket) error
	onKeepAlive       func(*ClientConnection, *protocol.KeepAlivePacket) error
	onAnnouncement    func(*ClientConnection, *protocol.AnnouncementPacket) error
	onTitleMessage    func(*ClientConnection, *protocol.TitleMessagePacket) error
	onChannelUpdate   func(*ClientConnection, *protocol.ChannelUpdatePacket) error
	onAdminAction     func(*ClientConnection, *protocol.AdminActionPacket) error

	// Connection lifecycle callbacks
	onConnect    func(*ClientConnection)
	onDisconnect func(*ClientConnection)
}

// NewDefaultHandler creates a new DefaultHandler.
func NewDefaultHandler(server *Server) *DefaultHandler {
	h := &DefaultHandler{
		server:   server,
		handlers: make(map[byte]PacketHandlerFunc),
	}

	// Register default handlers
	h.registerDefaultHandlers()

	return h
}

// registerDefaultHandlers sets up the default packet handlers.
func (h *DefaultHandler) registerDefaultHandlers() {
	h.RegisterHandler(protocol.PacketIDHandshake, h.dispatchHandshake)
	h.RegisterHandler(protocol.PacketIDChatMessage, h.dispatchChatMessage)
	h.RegisterHandler(protocol.PacketIDChannelAction, h.dispatchChannelAction)
	h.RegisterHandler(protocol.PacketIDKeepAlive, h.dispatchKeepAlive)
	h.RegisterHandler(protocol.PacketIDAnnouncement, h.dispatchAnnouncement)
	h.RegisterHandler(protocol.PacketIDTitle, h.dispatchTitleMessage)
	h.RegisterHandler(protocol.PacketIDChannelUpdate, h.dispatchChannelUpdate)
	h.RegisterHandler(protocol.PacketIDAdminAction, h.dispatchAdminAction)
}

// RegisterHandler registers a handler for a specific packet ID.
func (h *DefaultHandler) RegisterHandler(packetID byte, handler PacketHandlerFunc) {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	h.handlers[packetID] = handler
}

// UnregisterHandler removes a handler for a specific packet ID.
func (h *DefaultHandler) UnregisterHandler(packetID byte) {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	delete(h.handlers, packetID)
}

// HandlePacket processes an incoming packet by dispatching to the appropriate handler.
func (h *DefaultHandler) HandlePacket(client *ClientConnection, packet protocol.Packet) error {
	h.mutex.RLock()
	handler, ok := h.handlers[packet.ID()]
	h.mutex.RUnlock()

	if !ok {
		return fmt.Errorf("no handler registered for packet ID 0x%02X", packet.ID())
	}

	return handler(client, packet)
}

// OnClientConnect is called when a new client connects.
func (h *DefaultHandler) OnClientConnect(client *ClientConnection) {
	if h.onConnect != nil {
		h.onConnect(client)
	}
}

// OnClientDisconnect is called when a client disconnects.
func (h *DefaultHandler) OnClientDisconnect(client *ClientConnection) {
	if h.onDisconnect != nil {
		h.onDisconnect(client)
	}
}

// Dispatch methods for typed packet handling

func (h *DefaultHandler) dispatchHandshake(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.HandshakePacket)
	if h.onHandshake != nil {
		return h.onHandshake(client, p)
	}
	return h.defaultHandshake(client, p)
}

func (h *DefaultHandler) dispatchChatMessage(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.ChatMessagePacket)
	if h.onChatMessage != nil {
		return h.onChatMessage(client, p)
	}
	return h.defaultChatMessage(client, p)
}

func (h *DefaultHandler) dispatchChannelAction(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.ChannelActionPacket)
	if h.onChannelAction != nil {
		return h.onChannelAction(client, p)
	}
	return h.defaultChannelAction(client, p)
}

func (h *DefaultHandler) dispatchKeepAlive(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.KeepAlivePacket)
	if h.onKeepAlive != nil {
		return h.onKeepAlive(client, p)
	}
	return h.defaultKeepAlive(client, p)
}

func (h *DefaultHandler) dispatchAnnouncement(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.AnnouncementPacket)
	if h.onAnnouncement != nil {
		return h.onAnnouncement(client, p)
	}
	return h.defaultAnnouncement(client, p)
}

func (h *DefaultHandler) dispatchTitleMessage(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.TitleMessagePacket)
	if h.onTitleMessage != nil {
		return h.onTitleMessage(client, p)
	}
	return h.defaultTitleMessage(client, p)
}

func (h *DefaultHandler) dispatchChannelUpdate(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.ChannelUpdatePacket)
	if h.onChannelUpdate != nil {
		return h.onChannelUpdate(client, p)
	}
	return h.defaultChannelUpdate(client, p)
}

func (h *DefaultHandler) dispatchAdminAction(client *ClientConnection, packet protocol.Packet) error {
	p := packet.(*protocol.AdminActionPacket)
	if h.onAdminAction != nil {
		return h.onAdminAction(client, p)
	}
	return h.defaultAdminAction(client, p)
}

// Default handlers

func (h *DefaultHandler) defaultHandshake(client *ClientConnection, packet *protocol.HandshakePacket) error {
	// Validate protocol version first (Requirements: 27.4)
	if packet.ProtocolVersion != protocol.ProtocolVersion {
		response := &protocol.HandshakeResponsePacket{
			Success:   false,
			ErrorCode: "NC-420",
			Message: fmt.Sprintf("Protocol version mismatch: client=%d, server=%d. Please update your client.",
				packet.ProtocolVersion, protocol.ProtocolVersion),
		}
		response.SetRequestID(packet.RequestID())
		return client.SendPacket(response)
	}

	// Set client info
	client.SetClientID(packet.ClientID)
	client.SetPlatform(packet.Platform)
	h.server.AddClient(packet.ClientID, client)

	// Send success response
	response := &protocol.HandshakeResponsePacket{
		Success:    true,
		ErrorCode:  "",
		Message:    "Authentication successful",
	}
	response.SetRequestID(packet.RequestID())
	return client.SendPacket(response)
}

func (h *DefaultHandler) defaultChatMessage(client *ClientConnection, packet *protocol.ChatMessagePacket) error {
	// Default: broadcast to all clients
	h.server.Broadcast(packet)
	return nil
}

func (h *DefaultHandler) defaultChannelAction(client *ClientConnection, packet *protocol.ChannelActionPacket) error {
	// Default: no-op, should be overridden by channel manager
	return nil
}

func (h *DefaultHandler) defaultKeepAlive(client *ClientConnection, packet *protocol.KeepAlivePacket) error {
	// Echo back the keep-alive packet
	return client.SendPacket(packet)
}

func (h *DefaultHandler) defaultAnnouncement(client *ClientConnection, packet *protocol.AnnouncementPacket) error {
	// Default: broadcast to all clients
	h.server.Broadcast(packet)
	return nil
}

func (h *DefaultHandler) defaultTitleMessage(client *ClientConnection, packet *protocol.TitleMessagePacket) error {
	// Default: broadcast to all clients
	h.server.Broadcast(packet)
	return nil
}

func (h *DefaultHandler) defaultChannelUpdate(client *ClientConnection, packet *protocol.ChannelUpdatePacket) error {
	// Default: broadcast to all clients
	h.server.Broadcast(packet)
	return nil
}

func (h *DefaultHandler) defaultAdminAction(client *ClientConnection, packet *protocol.AdminActionPacket) error {
	// Minimal compatible behavior: return a structured response instead of dropping the packet.
	resp := &protocol.AdminActionResponsePacket{
		Action:    packet.Action,
		Success:   false,
		ErrorCode: "NC-501",
		Message:   "Admin actions are not implemented in the Go backend yet",
	}
	resp.SetRequestID(packet.RequestID())
	return client.SendPacket(resp)
}

// Setter methods for typed handlers

// SetHandshakeHandler sets a custom handshake handler.
func (h *DefaultHandler) SetHandshakeHandler(handler func(*ClientConnection, *protocol.HandshakePacket) error) {
	h.onHandshake = handler
}

// SetChatMessageHandler sets a custom chat message handler.
func (h *DefaultHandler) SetChatMessageHandler(handler func(*ClientConnection, *protocol.ChatMessagePacket) error) {
	h.onChatMessage = handler
}

// SetChannelActionHandler sets a custom channel action handler.
func (h *DefaultHandler) SetChannelActionHandler(handler func(*ClientConnection, *protocol.ChannelActionPacket) error) {
	h.onChannelAction = handler
}

// SetKeepAliveHandler sets a custom keep-alive handler.
func (h *DefaultHandler) SetKeepAliveHandler(handler func(*ClientConnection, *protocol.KeepAlivePacket) error) {
	h.onKeepAlive = handler
}

// SetAnnouncementHandler sets a custom announcement handler.
func (h *DefaultHandler) SetAnnouncementHandler(handler func(*ClientConnection, *protocol.AnnouncementPacket) error) {
	h.onAnnouncement = handler
}

// SetTitleMessageHandler sets a custom title message handler.
func (h *DefaultHandler) SetTitleMessageHandler(handler func(*ClientConnection, *protocol.TitleMessagePacket) error) {
	h.onTitleMessage = handler
}

// SetChannelUpdateHandler sets a custom channel update handler.
func (h *DefaultHandler) SetChannelUpdateHandler(handler func(*ClientConnection, *protocol.ChannelUpdatePacket) error) {
	h.onChannelUpdate = handler
}

// SetAdminActionHandler sets a custom admin action handler.
func (h *DefaultHandler) SetAdminActionHandler(handler func(*ClientConnection, *protocol.AdminActionPacket) error) {
	h.onAdminAction = handler
}

// SetConnectHandler sets a callback for when clients connect.
func (h *DefaultHandler) SetConnectHandler(handler func(*ClientConnection)) {
	h.onConnect = handler
}

// SetDisconnectHandler sets a callback for when clients disconnect.
func (h *DefaultHandler) SetDisconnectHandler(handler func(*ClientConnection)) {
	h.onDisconnect = handler
}

// Server returns the server instance.
func (h *DefaultHandler) Server() *Server {
	return h.server
}
