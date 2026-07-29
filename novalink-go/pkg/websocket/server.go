// Package websocket provides WebSocket gateway functionality for NovaLink-Go.
// It enables web-based clients (like the Vue.js admin panel) to connect
// and receive real-time updates from the chat system.
package websocket

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/nova/novalink-go/pkg/auth"
	"github.com/nova/novalink-go/pkg/channel"
	"github.com/nova/novalink-go/pkg/config"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		// Allow all origins for development; in production, this should be restricted
		return true
	},
}

// MessageType defines the type of WebSocket message.
type MessageType string

const (
	// Panel-compatible message types (match Java backend / nova-panel).
	TypeChat MessageType = "chat"

	// NOTE: legacy Go WS event types (chat_message/player_join/...) were removed from the WS gateway.
	// Webhook payload structs still exist (see webhook_payloads.go).
	// TypeError is an error message
	TypeError MessageType = "error"
	// TypeAuth is an authentication message
	TypeAuth MessageType = "auth"
	// TypeAuthResponse is an authentication response
	TypeAuthResponse MessageType = "auth_response"
	// TypePing is a ping message
	TypePing MessageType = "ping"
	// TypePong is a pong response
	TypePong MessageType = "pong"
)


// Message represents a WebSocket message.
type Message struct {
	Type      MessageType     `json:"type"`
	Timestamp int64           `json:"timestamp"`
	Data      json.RawMessage `json:"data,omitempty"`
}

// AuthData represents authentication request data.
type AuthData struct {
	Token string `json:"token"`
}


// WebSocketClient represents a connected WebSocket client.
type WebSocketClient struct {
	id            string
	conn          *websocket.Conn
	server        *Server
	sendChan      chan []byte
	authenticated bool
	role          string
	subject       string
	userID        string
	username      string
	lastPing      time.Time
	// subscribedChannels tracks which channel IDs the client is interested in (panel feature).
	subscribedChannels map[string]bool
	mutex         sync.RWMutex
}

// Server represents the WebSocket server.
type Server struct {
	config     *config.WebSocketConfig
	jwtService *auth.JWTService
	httpServer *http.Server
	mux        *http.ServeMux
	clients    map[string]*WebSocketClient
	mutex      sync.RWMutex
	running    atomic.Bool
	stopChan   chan struct{}
	wg         sync.WaitGroup

	// Optional data sources for Java-compatible panel payloads.
	channelManager *channel.Manager
	clientProvider ClientProvider

	// Event handlers
	onMessage func(client *WebSocketClient, msg *Message)

	// Settings
	pingInterval time.Duration
	pongWait     time.Duration
	writeWait    time.Duration
}

// ServerOption is a function that configures a Server.
type ServerOption func(*Server)

// WithPingInterval sets the ping interval.
func WithPingInterval(d time.Duration) ServerOption {
	return func(s *Server) {
		s.pingInterval = d
	}
}

// WithPongWait sets the pong wait timeout.
func WithPongWait(d time.Duration) ServerOption {
	return func(s *Server) {
		s.pongWait = d
	}
}

// WithWriteWait sets the write wait timeout.
func WithWriteWait(d time.Duration) ServerOption {
	return func(s *Server) {
		s.writeWait = d
	}
}

// NewServer creates a new WebSocket server.
func NewServer(cfg *config.WebSocketConfig, jwtService *auth.JWTService, opts ...ServerOption) *Server {
	s := &Server{
		config:       cfg,
		jwtService:   jwtService,
		clients:      make(map[string]*WebSocketClient),
		stopChan:     make(chan struct{}),
		pingInterval: 30 * time.Second,
		pongWait:     60 * time.Second,
		writeWait:    10 * time.Second,
	}

	for _, opt := range opts {
		opt(s)
	}

	return s
}

// SetChannelManager provides a channel manager for get_channels/channel_update/player_update payloads.
func (s *Server) SetChannelManager(m *channel.Manager) {
	s.channelManager = m
}

// SetClientProvider provides a TCP client provider for get_clients/server_status payloads.
func (s *Server) SetClientProvider(p ClientProvider) {
	s.clientProvider = p
}

// SetMux allows hosting the WebSocket endpoint on an externally managed mux (so API and WS can share one port).
func (s *Server) SetMux(mux *http.ServeMux) {
	s.mux = mux
}

// RegisterRoutes registers the WebSocket endpoint on the given mux.
func (s *Server) RegisterRoutes(mux *http.ServeMux) {
	if mux == nil || s.config == nil {
		return
	}
	path := s.config.Path
	if strings.TrimSpace(path) == "" {
		path = "/ws"
	}
	mux.HandleFunc(path, s.handleWebSocket)
}

// SetMessageHandler sets the message handler callback.
func (s *Server) SetMessageHandler(handler func(client *WebSocketClient, msg *Message)) {
	s.onMessage = handler
}


// Start begins the WebSocket server.
func (s *Server) Start() error {
	if !s.config.Enabled {
		return nil
	}

	mux := s.mux
	if mux == nil {
		mux = http.NewServeMux()
	}
	s.RegisterRoutes(mux)

	addr := fmt.Sprintf("%s:%d", s.config.Host, s.config.Port)
	s.httpServer = &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	s.running.Store(true)

	// Start the ping ticker
	s.wg.Add(1)
	go s.pingLoop()

	// Periodic status updates (match Java WebSocketGateway: every 30s).
	s.wg.Add(1)
	go s.statusLoop(30 * time.Second)

	// Start the HTTP server
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			fmt.Printf("[ERROR] WebSocket server error: %v\n", err)
		}
	}()

	fmt.Printf("[INFO] WebSocket server started on %s\n", addr)
	return nil
}

// Stop gracefully shuts down the WebSocket server.
func (s *Server) Stop() error {
	if !s.running.Load() {
		return nil
	}

	s.running.Store(false)
	close(s.stopChan)

	// Close all client connections
	s.mutex.Lock()
	for _, client := range s.clients {
		client.Close()
	}
	s.clients = make(map[string]*WebSocketClient)
	s.mutex.Unlock()

	// Shutdown HTTP server
	if s.httpServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := s.httpServer.Shutdown(ctx); err != nil {
			return fmt.Errorf("failed to shutdown HTTP server: %w", err)
		}
	}

	// Wait for goroutines
	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		fmt.Println("[WARN] Timeout waiting for WebSocket goroutines to finish")
	}

	return nil
}

// IsRunning returns whether the server is running.
func (s *Server) IsRunning() bool {
	return s.running.Load()
}


// handleWebSocket handles WebSocket upgrade requests.
func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		fmt.Printf("[WARN] WebSocket upgrade failed: %v\n", err)
		return
	}

	clientID := fmt.Sprintf("ws-%d", time.Now().UnixNano())
	client := &WebSocketClient{
		id:       clientID,
		conn:     conn,
		server:   s,
		sendChan: make(chan []byte, 256),
		lastPing: time.Now(),
		subscribedChannels: make(map[string]bool),
	}

	s.addClient(client)

	// Start read and write goroutines
	s.wg.Add(2)
	go client.readPump()
	go client.writePump()

	fmt.Printf("[INFO] WebSocket client connected: %s\n", clientID)
}

// addClient adds a client to the server.
func (s *Server) addClient(client *WebSocketClient) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.clients[client.id] = client
}

// removeClient removes a client from the server.
func (s *Server) removeClient(client *WebSocketClient) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	delete(s.clients, client.id)
}

// GetClient returns a client by ID.
func (s *Server) GetClient(id string) *WebSocketClient {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	return s.clients[id]
}

// GetAllClients returns all connected clients.
func (s *Server) GetAllClients() []*WebSocketClient {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	clients := make([]*WebSocketClient, 0, len(s.clients))
	for _, c := range s.clients {
		clients = append(clients, c)
	}
	return clients
}

// ClientCount returns the number of connected clients.
func (s *Server) ClientCount() int {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	return len(s.clients)
}

func (s *Server) authedClientCount() int {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	n := 0
	for _, c := range s.clients {
		c.mutex.RLock()
		ok := c.authenticated
		c.mutex.RUnlock()
		if ok {
			n++
		}
	}
	return n
}

// pingLoop sends periodic pings to all clients.
func (s *Server) pingLoop() {
	defer s.wg.Done()

	ticker := time.NewTicker(s.pingInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			s.pingAllClients()
		}
	}
}

// pingAllClients sends a ping to all connected clients.
func (s *Server) pingAllClients() {
	s.mutex.RLock()
	clients := make([]*WebSocketClient, 0, len(s.clients))
	for _, c := range s.clients {
		clients = append(clients, c)
	}
	s.mutex.RUnlock()

	for _, client := range clients {
		if err := client.SendPing(); err != nil {
			fmt.Printf("[WARN] Failed to ping client %s: %v\n", client.id, err)
		}
	}
}


// Broadcast sends a message to all authenticated clients.
func (s *Server) Broadcast(msg *Message) {
	data, err := json.Marshal(msg)
	if err != nil {
		fmt.Printf("[WARN] Failed to marshal broadcast message: %v\n", err)
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()

	for _, client := range s.clients {
		if client.IsAuthenticated() {
			select {
			case client.sendChan <- data:
			default:
				fmt.Printf("[WARN] Client %s send buffer full, dropping message\n", client.id)
			}
		}
	}
}

// BroadcastToRole sends a message to all clients with a specific role.
func (s *Server) BroadcastToRole(role string, msg *Message) {
	data, err := json.Marshal(msg)
	if err != nil {
		fmt.Printf("[WARN] Failed to marshal broadcast message: %v\n", err)
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()

	for _, client := range s.clients {
		if client.IsAuthenticated() && client.GetRole() == role {
			select {
			case client.sendChan <- data:
			default:
				fmt.Printf("[WARN] Client %s send buffer full, dropping message\n", client.id)
			}
		}
	}
}

// BroadcastChatMessage broadcasts a chat message event.
func (s *Server) BroadcastChatMessage(senderID, senderName, clientID, channelID, content string) {
	// Java/nova-panel format (flat payload), only to subscribed sessions.
	payload := map[string]interface{}{
		"type":       "chat",
		"channelId":  channelID,
		"senderId":   senderID,
		"senderName": senderName,
		"content":    content,
		"timestamp":  time.Now().UnixMilli(),
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()
	for _, client := range s.clients {
		client.mutex.RLock()
		ok := client.authenticated && client.subscribedChannels[channelID]
		client.mutex.RUnlock()
		if !ok {
			continue
		}
		select {
		case client.sendChan <- data:
		default:
		}
	}
}

// BroadcastServerStatus broadcasts a Java-compatible "server_status" payload to all authenticated sessions.
func (s *Server) BroadcastServerStatus() {
	var clients []map[string]interface{}
	if s.clientProvider != nil {
		for _, c := range s.clientProvider.GetAllClients() {
			clients = append(clients, map[string]interface{}{
				"id":           c.ID,
				"connectionId": "", // Not available in Go TCP implementation; keep for shape compatibility.
				"remoteAddress": c.RemoteAddr,
				"connectedAt":  c.ConnectedAt.UnixMilli(),
				"active":       true,
			})
		}
	}

	payload := map[string]interface{}{
		"type":             "server_status",
		"clients":          clients,
		"totalConnections": len(clients),
		"timestamp":        time.Now().UnixMilli(),
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()
	for _, client := range s.clients {
		client.mutex.RLock()
		ok := client.authenticated
		client.mutex.RUnlock()
		if !ok {
			continue
		}
		select {
		case client.sendChan <- data:
		default:
		}
	}
}

// BroadcastChannelUpdate broadcasts a Java-compatible "channel_update" payload to all authenticated sessions.
func (s *Server) BroadcastChannelUpdate() {
	var channels []map[string]interface{}
	if s.channelManager != nil {
		for _, info := range s.channelManager.GetAllChannelInfos() {
			channels = append(channels, map[string]interface{}{
				"id":          info.ID,
				"displayName": info.DisplayName,
				"scope":       string(info.Scope),
				"clientId":    info.ClientID,
				"memberCount": info.MemberCount,
				"maxCapacity": info.MaxCapacity,
			})
		}
	}

	payload := map[string]interface{}{
		"type":      "channel_update",
		"channels":  channels,
		"timestamp": time.Now().UnixMilli(),
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()
	for _, client := range s.clients {
		client.mutex.RLock()
		ok := client.authenticated
		client.mutex.RUnlock()
		if !ok {
			continue
		}
		select {
		case client.sendChan <- data:
		default:
		}
	}
}

// BroadcastPlayerUpdate broadcasts a Java-compatible "player_update" payload to all authenticated sessions.
func (s *Server) BroadcastPlayerUpdate() {
	if s.channelManager == nil {
		return
	}

	playerChannels := make(map[string]map[string]bool) // playerUUID -> channelId set
	for _, ch := range s.channelManager.GetAllChannels() {
		members := s.channelManager.GetAllMembers(ch.ID)
		for _, m := range members {
			set := playerChannels[m.PlayerID]
			if set == nil {
				set = make(map[string]bool)
				playerChannels[m.PlayerID] = set
			}
			set[ch.ID] = true
		}
	}

	var players []map[string]interface{}
	for pid, set := range playerChannels {
		var chs []string
		for cid := range set {
			chs = append(chs, cid)
		}
		players = append(players, map[string]interface{}{
			"uuid":     pid,
			"channels": chs,
		})
	}

	payload := map[string]interface{}{
		"type":        "player_update",
		"players":     players,
		"totalPlayers": len(players),
		"timestamp":   time.Now().UnixMilli(),
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}

	s.mutex.RLock()
	defer s.mutex.RUnlock()
	for _, client := range s.clients {
		client.mutex.RLock()
		ok := client.authenticated
		client.mutex.RUnlock()
		if !ok {
			continue
		}
		select {
		case client.sendChan <- data:
		default:
		}
	}
}


// readPump reads messages from the WebSocket connection.
func (c *WebSocketClient) readPump() {
	defer func() {
		c.server.wg.Done()
		c.server.removeClient(c)
		c.conn.Close()
		close(c.sendChan)
		fmt.Printf("[INFO] WebSocket client disconnected: %s\n", c.id)
	}()

	c.conn.SetReadDeadline(time.Now().Add(c.server.pongWait))
	c.conn.SetPongHandler(func(string) error {
		c.conn.SetReadDeadline(time.Now().Add(c.server.pongWait))
		c.mutex.Lock()
		c.lastPing = time.Now()
		c.mutex.Unlock()
		return nil
	})

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				fmt.Printf("[WARN] WebSocket read error: %v\n", err)
			}
			return
		}

		// Accept both formats:
		// 1) Java/nova-panel format: {"type":"auth","token":"..."} / {"type":"subscribe","channels":[...]}
		// 2) Legacy Go format: {"type":"auth","data":{"token":"..."}} (envelope with "data")
		var base struct {
			Type      string   `json:"type"`
			Token     string   `json:"token,omitempty"`
			Channels  []string `json:"channels,omitempty"`
			Timestamp int64    `json:"timestamp,omitempty"`
		}
		if err := json.Unmarshal(data, &base); err != nil || strings.TrimSpace(base.Type) == "" {
			c.SendError("NC-400", "Invalid message format")
			continue
		}

		msg := &Message{
			Type:      MessageType(base.Type),
			Timestamp: base.Timestamp,
		}

		// Carry-through token/channels in Data for handlers that expect it.
		if strings.TrimSpace(base.Token) != "" || len(base.Channels) > 0 {
			payload := map[string]interface{}{}
			if strings.TrimSpace(base.Token) != "" {
				payload["token"] = base.Token
			}
			if len(base.Channels) > 0 {
				payload["channels"] = base.Channels
			}
			if b, err := json.Marshal(payload); err == nil {
				msg.Data = b
			}
		} else {
			// Legacy envelope might have msg.Data set by parsing into Message.
			var legacy Message
			if err := json.Unmarshal(data, &legacy); err == nil && legacy.Data != nil {
				msg.Data = legacy.Data
			}
		}

		c.handleMessage(msg)
	}
}

// writePump writes messages to the WebSocket connection.
func (c *WebSocketClient) writePump() {
	defer c.server.wg.Done()

	for {
		select {
		case data, ok := <-c.sendChan:
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			c.conn.SetWriteDeadline(time.Now().Add(c.server.writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, data); err != nil {
				return
			}
		case <-c.server.stopChan:
			return
		}
	}
}

// handleMessage processes an incoming message.
func (c *WebSocketClient) handleMessage(msg *Message) {
	switch msg.Type {
	case TypeAuth:
		c.handleAuth(msg)
	case TypePing:
		c.handlePing()
	case MessageType("subscribe"):
		c.handleSubscribe(msg)
	case MessageType("unsubscribe"):
		c.handleUnsubscribe(msg)
	case MessageType("get_channels"):
		c.handleGetChannels()
	case MessageType("get_clients"):
		c.handleGetClients()
	case MessageType("get_players"):
		c.handleGetPlayers()
	default:
		if !c.authenticated {
			c.SendError("NC-401", "Authentication required")
			return
		}
		// Forward to custom handler if set
		if c.server.onMessage != nil {
			c.server.onMessage(c, msg)
		}
	}
}

func (c *WebSocketClient) handleGetChannels() {
	if !c.authenticated {
		c.SendError("NC-401", "Authentication required")
		return
	}
	if c.server != nil {
		c.server.BroadcastChannelUpdate()
	}
}

func (c *WebSocketClient) handleGetClients() {
	if !c.authenticated {
		c.SendError("NC-401", "Authentication required")
		return
	}
	if c.server != nil {
		c.server.BroadcastServerStatus()
	}
}

func (c *WebSocketClient) handleGetPlayers() {
	if !c.authenticated {
		c.SendError("NC-401", "Authentication required")
		return
	}
	if c.server != nil {
		c.server.BroadcastPlayerUpdate()
	}
}

// handleAuth processes authentication messages.
func (c *WebSocketClient) handleAuth(msg *Message) {
	var authData AuthData
	if msg.Data == nil || json.Unmarshal(msg.Data, &authData) != nil || strings.TrimSpace(authData.Token) == "" {
		c.SendAuthResponse(false, "Missing token", "")
		return
	}

	if c.server.jwtService == nil {
		c.SendAuthResponse(false, "Authentication not configured", "")
		return
	}

	claims, err := c.server.jwtService.VerifyToken(authData.Token)
	if err != nil {
		c.SendAuthResponse(false, "Invalid or expired token", "")
		return
	}
	// Reject refresh tokens for WebSocket auth (match Java backend behavior).
	if claims.Type == "refresh" {
		c.SendAuthResponse(false, "Invalid or expired token", "")
		return
	}

	c.mutex.Lock()
	c.authenticated = true
	c.role = claims.Role
	c.userID = claims.Subject
	c.username = claims.Username
	// Keep legacy field "subject" for logs / older code paths.
	if strings.TrimSpace(claims.Username) != "" {
		c.subject = claims.Username
	} else {
		c.subject = claims.Subject
	}
	c.mutex.Unlock()

	c.SendAuthResponse(true, "", claims.Role)
	fmt.Printf("[INFO] WebSocket client authenticated: %s (role: %s)\n", c.id, claims.Role)
}

// handlePing processes ping messages.
func (c *WebSocketClient) handlePing() {
	c.mutex.Lock()
	c.lastPing = time.Now()
	c.mutex.Unlock()

	pongMsg := &Message{
		Type:      TypePong,
		Timestamp: time.Now().UnixMilli(),
	}
	data, _ := json.Marshal(pongMsg)
	select {
	case c.sendChan <- data:
	default:
	}
}

func (c *WebSocketClient) handleSubscribe(msg *Message) {
	if !c.authenticated {
		c.SendError("NC-401", "Authentication required")
		return
	}

	var payload struct {
		Channels []string `json:"channels"`
	}
	if msg.Data == nil || json.Unmarshal(msg.Data, &payload) != nil {
		c.SendError("NC-400", "Missing channels array")
		return
	}

	c.mutex.Lock()
	for _, ch := range payload.Channels {
		ch = strings.TrimSpace(ch)
		if ch != "" {
			c.subscribedChannels[ch] = true
		}
	}
	c.mutex.Unlock()

	resp := map[string]interface{}{
		"type":      "subscribed",
		"channels":  payload.Channels,
		"timestamp": time.Now().UnixMilli(),
	}
	data, _ := json.Marshal(resp)
	select {
	case c.sendChan <- data:
	default:
	}
}

func (c *WebSocketClient) handleUnsubscribe(msg *Message) {
	if !c.authenticated {
		c.SendError("NC-401", "Authentication required")
		return
	}

	var payload struct {
		Channels []string `json:"channels"`
	}
	if msg.Data == nil || json.Unmarshal(msg.Data, &payload) != nil {
		c.SendError("NC-400", "Missing channels array")
		return
	}

	c.mutex.Lock()
	for _, ch := range payload.Channels {
		ch = strings.TrimSpace(ch)
		if ch != "" {
			delete(c.subscribedChannels, ch)
		}
	}
	c.mutex.Unlock()

	resp := map[string]interface{}{
		"type":      "unsubscribed",
		"channels":  payload.Channels,
		"timestamp": time.Now().UnixMilli(),
	}
	data, _ := json.Marshal(resp)
	select {
	case c.sendChan <- data:
	default:
	}
}


// SendMessage sends a message to the client.
func (c *WebSocketClient) SendMessage(msg *Message) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	select {
	case c.sendChan <- data:
		return nil
	default:
		return fmt.Errorf("send buffer full")
	}
}

// SendError sends an error message to the client.
func (c *WebSocketClient) SendError(code, message string) {
	// Java/nova-panel format (flat payload)
	data, _ := json.Marshal(map[string]interface{}{
		"type":      "error",
		"error":     message,
		"code":      code,
		"timestamp": time.Now().UnixMilli(),
	})
	select {
	case c.sendChan <- data:
	default:
	}
}

// SendAuthResponse sends an authentication response.
func (c *WebSocketClient) SendAuthResponse(success bool, message, role string) {
	// Java/nova-panel format (flat payload)
	payload := map[string]interface{}{
		"type":      "auth_response",
		"success":   success,
		"timestamp": time.Now().UnixMilli(),
	}
	if success {
		payload["userId"] = c.userID
		payload["username"] = c.username
		payload["role"] = role
	} else {
		if strings.TrimSpace(message) == "" {
			message = "Authentication failed"
		}
		payload["error"] = message
	}
	data, _ := json.Marshal(payload)
	select {
	case c.sendChan <- data:
	default:
	}
}

func (s *Server) statusLoop(interval time.Duration) {
	defer s.wg.Done()
	if interval <= 0 {
		interval = 30 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			// Match Java: only broadcast if there is at least one connected session.
			if s.authedClientCount() == 0 {
				continue
			}
			s.BroadcastServerStatus()
			s.BroadcastChannelUpdate()
			s.BroadcastPlayerUpdate()
		}
	}
}

// SendPing sends a ping message to the client.
func (c *WebSocketClient) SendPing() error {
	c.conn.SetWriteDeadline(time.Now().Add(c.server.writeWait))
	return c.conn.WriteMessage(websocket.PingMessage, nil)
}

// Close closes the client connection.
func (c *WebSocketClient) Close() {
	c.conn.Close()
}

// ID returns the client ID.
func (c *WebSocketClient) ID() string {
	return c.id
}

// IsAuthenticated returns whether the client is authenticated.
func (c *WebSocketClient) IsAuthenticated() bool {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.authenticated
}

// GetRole returns the client's role.
func (c *WebSocketClient) GetRole() string {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.role
}

// GetSubject returns the client's subject (username).
func (c *WebSocketClient) GetSubject() string {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.subject
}

// LastPing returns the time of the last ping.
func (c *WebSocketClient) LastPing() time.Time {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.lastPing
}
