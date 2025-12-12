// Package network provides TCP server and client connection management.
package network

import (
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/nova/novalink-go/pkg/config"
	"github.com/nova/novalink-go/pkg/protocol"
)

// Server represents the NovaLink TCP server.
// It manages client connections using goroutines and provides
// thread-safe access to connected clients.
type Server struct {
	config   *config.Config
	listener net.Listener
	clients  map[string]*ClientConnection
	mutex    sync.RWMutex
	codec    *protocol.Codec
	handler  PacketHandler
	running  atomic.Bool
	stopChan chan struct{}
	wg       sync.WaitGroup // WaitGroup for tracking active goroutines

	// Event channels for inter-goroutine communication
	connectChan    chan *ClientConnection
	disconnectChan chan *ClientConnection
	broadcastChan  chan protocol.Packet

	// Connection settings
	readTimeout  time.Duration
	writeTimeout time.Duration
	idleTimeout  time.Duration
}

// PacketHandler is the interface for handling incoming packets.
type PacketHandler interface {
	HandlePacket(client *ClientConnection, packet protocol.Packet) error
	OnClientConnect(client *ClientConnection)
	OnClientDisconnect(client *ClientConnection)
}

// ServerOption is a function that configures a Server.
type ServerOption func(*Server)

// WithReadTimeout sets the read timeout for client connections.
func WithReadTimeout(d time.Duration) ServerOption {
	return func(s *Server) {
		s.readTimeout = d
	}
}

// WithWriteTimeout sets the write timeout for client connections.
func WithWriteTimeout(d time.Duration) ServerOption {
	return func(s *Server) {
		s.writeTimeout = d
	}
}

// WithIdleTimeout sets the idle timeout for client connections.
func WithIdleTimeout(d time.Duration) ServerOption {
	return func(s *Server) {
		s.idleTimeout = d
	}
}

// NewServer creates a new Server instance.
func NewServer(cfg *config.Config, opts ...ServerOption) *Server {
	s := &Server{
		config:         cfg,
		clients:        make(map[string]*ClientConnection),
		codec:          protocol.NewCodec(),
		stopChan:       make(chan struct{}),
		connectChan:    make(chan *ClientConnection, 100),
		disconnectChan: make(chan *ClientConnection, 100),
		broadcastChan:  make(chan protocol.Packet, 1000),
		readTimeout:    30 * time.Second,
		writeTimeout:   10 * time.Second,
		idleTimeout:    60 * time.Second,
	}

	for _, opt := range opts {
		opt(s)
	}

	return s
}

// SetHandler sets the packet handler for the server.
func (s *Server) SetHandler(handler PacketHandler) {
	s.handler = handler
}

// Start begins listening for client connections.
func (s *Server) Start() error {
	addr := fmt.Sprintf("%s:%d", s.config.Server.Host, s.config.Server.Port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to start server: %w", err)
	}

	s.listener = listener
	s.running.Store(true)

	// Start the event processing goroutine
	s.wg.Add(1)
	go s.eventLoop()

	// Start the accept loop goroutine
	s.wg.Add(1)
	go s.acceptLoop()

	// Start the idle connection checker
	s.wg.Add(1)
	go s.idleChecker()

	return nil
}

// Stop gracefully shuts down the server.
func (s *Server) Stop() error {
	if !s.running.Load() {
		return nil
	}

	s.running.Store(false)
	close(s.stopChan)

	if s.listener != nil {
		s.listener.Close()
	}

	// Disconnect all clients
	s.mutex.Lock()
	for _, client := range s.clients {
		client.Close()
	}
	s.clients = make(map[string]*ClientConnection)
	s.mutex.Unlock()

	// Wait for all goroutines to finish with timeout
	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		// All goroutines finished
	case <-time.After(5 * time.Second):
		fmt.Println("[WARN] Timeout waiting for goroutines to finish")
	}

	return nil
}

// IsRunning returns whether the server is currently running.
func (s *Server) IsRunning() bool {
	return s.running.Load()
}

// eventLoop processes connection and broadcast events.
func (s *Server) eventLoop() {
	defer s.wg.Done()

	for {
		select {
		case <-s.stopChan:
			return
		case client := <-s.connectChan:
			s.handleClientConnect(client)
		case client := <-s.disconnectChan:
			s.handleClientDisconnect(client)
		case packet := <-s.broadcastChan:
			s.doBroadcast(packet)
		}
	}
}

// handleClientConnect processes a new client connection event.
func (s *Server) handleClientConnect(client *ClientConnection) {
	if s.handler != nil {
		s.handler.OnClientConnect(client)
	}
}

// handleClientDisconnect processes a client disconnection event.
func (s *Server) handleClientDisconnect(client *ClientConnection) {
	s.removeClient(client)
	if s.handler != nil {
		s.handler.OnClientDisconnect(client)
	}
}

// doBroadcast sends a packet to all connected clients.
func (s *Server) doBroadcast(packet protocol.Packet) {
	s.mutex.RLock()
	defer s.mutex.RUnlock()

	for _, client := range s.clients {
		if err := client.SendPacket(packet); err != nil {
			fmt.Printf("[WARN] Failed to send packet to %s: %v\n", client.clientID, err)
		}
	}
}

// idleChecker periodically checks for idle connections.
func (s *Server) idleChecker() {
	defer s.wg.Done()

	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			s.checkIdleConnections()
		}
	}
}

// checkIdleConnections disconnects clients that have been idle too long.
func (s *Server) checkIdleConnections() {
	s.mutex.RLock()
	idleClients := make([]*ClientConnection, 0)
	now := time.Now()

	for _, client := range s.clients {
		if now.Sub(client.LastActive()) > s.idleTimeout {
			idleClients = append(idleClients, client)
		}
	}
	s.mutex.RUnlock()

	for _, client := range idleClients {
		fmt.Printf("[INFO] Disconnecting idle client: %s\n", client.clientID)
		client.Close()
		s.disconnectChan <- client
	}
}

// acceptLoop continuously accepts new client connections.
func (s *Server) acceptLoop() {
	defer s.wg.Done()

	for s.running.Load() {
		conn, err := s.listener.Accept()
		if err != nil {
			if s.running.Load() {
				fmt.Printf("[WARN] Failed to accept connection: %v\n", err)
			}
			continue
		}

		// Start a new goroutine for each connection
		s.wg.Add(1)
		go s.handleConnection(conn)
	}
}

// handleConnection handles a new client connection.
func (s *Server) handleConnection(conn net.Conn) {
	defer s.wg.Done()

	client := NewClientConnection(conn, s.codec)
	client.SetReadTimeout(s.readTimeout)
	client.SetWriteTimeout(s.writeTimeout)

	fmt.Printf("[INFO] New connection from %s\n", conn.RemoteAddr())

	// Notify event loop of new connection
	select {
	case s.connectChan <- client:
	case <-s.stopChan:
		client.Close()
		return
	}

	// Create a context for this connection
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Start the packet reading loop
	s.readLoop(ctx, client)

	// Clean up
	select {
	case s.disconnectChan <- client:
	case <-s.stopChan:
	}
	client.Close()
}

// readLoop reads packets from a client connection.
func (s *Server) readLoop(ctx context.Context, client *ClientConnection) {
	for s.running.Load() {
		select {
		case <-ctx.Done():
			return
		case <-s.stopChan:
			return
		default:
			packet, err := client.ReadPacket()
			if err != nil {
				if s.running.Load() {
					fmt.Printf("[DEBUG] Connection closed from %s: %v\n", client.RemoteAddr(), err)
				}
				return
			}

			if s.handler != nil {
				if err := s.handler.HandlePacket(client, packet); err != nil {
					fmt.Printf("[WARN] Error handling packet: %v\n", err)
				}
			}
		}
	}
}

// AddClient registers a client with the server.
func (s *Server) AddClient(clientID string, client *ClientConnection) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.clients[clientID] = client
	client.clientID = clientID
}

// removeClient removes a client from the server.
func (s *Server) removeClient(client *ClientConnection) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if client.clientID != "" {
		delete(s.clients, client.clientID)
		fmt.Printf("[INFO] Client disconnected: %s\n", client.clientID)
	}
}

// GetClient returns a client by ID.
func (s *Server) GetClient(clientID string) *ClientConnection {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	return s.clients[clientID]
}

// GetAllClients returns all connected clients.
func (s *Server) GetAllClients() []*ClientConnection {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	clients := make([]*ClientConnection, 0, len(s.clients))
	for _, client := range s.clients {
		clients = append(clients, client)
	}
	return clients
}

// Broadcast sends a packet to all connected clients.
func (s *Server) Broadcast(packet protocol.Packet) {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	for _, client := range s.clients {
		if err := client.SendPacket(packet); err != nil {
			fmt.Printf("[WARN] Failed to send packet to %s: %v\n", client.clientID, err)
		}
	}
}

// BroadcastToClient sends a packet to a specific authenticated client by ID.
// This mirrors Java backend behavior where SERVER/PRIVATE scoped routing targets a single client.
func (s *Server) BroadcastToClient(clientID string, packet protocol.Packet) {
	if clientID == "" || packet == nil {
		return
	}

	s.mutex.RLock()
	client := s.clients[clientID]
	s.mutex.RUnlock()
	if client == nil {
		return
	}

	_ = client.SendPacket(packet)
}

// ClientCount returns the number of connected clients.
func (s *Server) ClientCount() int {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	return len(s.clients)
}
