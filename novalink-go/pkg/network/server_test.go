package network

import (
	"net"
	"sync"
	"testing"
	"time"

	"github.com/nova/novalink-go/pkg/config"
	"github.com/nova/novalink-go/pkg/protocol"
)

// TestServerStartStop tests basic server start and stop functionality.
func TestServerStartStop(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "127.0.0.1",
			Port: 0, // Use random available port
		},
	}

	server := NewServer(cfg)
	
	// Start server
	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start server: %v", err)
	}

	if !server.IsRunning() {
		t.Error("Server should be running after Start()")
	}

	// Stop server
	if err := server.Stop(); err != nil {
		t.Fatalf("Failed to stop server: %v", err)
	}

	if server.IsRunning() {
		t.Error("Server should not be running after Stop()")
	}
}

// TestServerAcceptsConnections tests that the server accepts client connections.
func TestServerAcceptsConnections(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "127.0.0.1",
			Port: 18888,
		},
	}

	server := NewServer(cfg,
		WithReadTimeout(5*time.Second),
		WithWriteTimeout(5*time.Second),
		WithIdleTimeout(30*time.Second),
	)

	// Create a mock handler
	handler := NewDefaultHandler(server)
	server.SetHandler(handler)

	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start server: %v", err)
	}
	defer server.Stop()

	// Give server time to start
	time.Sleep(100 * time.Millisecond)

	// Connect a client
	conn, err := net.Dial("tcp", "127.0.0.1:18888")
	if err != nil {
		t.Fatalf("Failed to connect to server: %v", err)
	}
	defer conn.Close()

	// Give server time to process connection
	time.Sleep(100 * time.Millisecond)
}

// TestClientConnectionSendReceive tests packet sending and receiving.
func TestClientConnectionSendReceive(t *testing.T) {
	// Create a pipe for testing
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)
	defer client.Close()

	// Send a packet in a goroutine
	go func() {
		packet := &protocol.KeepAlivePacket{Timestamp: 12345}
		if err := client.SendPacketSync(packet); err != nil {
			t.Errorf("Failed to send packet: %v", err)
		}
	}()

	// Read the packet on the other end
	format := protocol.WireFormatUnknown
	receivedPacket, err := codec.DecodePacket(clientConn, &format)
	if err != nil {
		t.Fatalf("Failed to decode packet: %v", err)
	}

	keepAlive, ok := receivedPacket.(*protocol.KeepAlivePacket)
	if !ok {
		t.Fatalf("Expected KeepAlivePacket, got %T", receivedPacket)
	}

	if keepAlive.Timestamp != 12345 {
		t.Errorf("Expected timestamp 12345, got %d", keepAlive.Timestamp)
	}
}

// TestClientConnectionMetadata tests metadata storage.
func TestClientConnectionMetadata(t *testing.T) {
	serverConn, _ := net.Pipe()
	defer serverConn.Close()

	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)
	defer client.Close()

	// Set metadata
	client.SetMetadata("key1", "value1")
	client.SetMetadata("key2", 42)

	// Get metadata
	val1, ok := client.GetMetadata("key1")
	if !ok || val1 != "value1" {
		t.Errorf("Expected 'value1', got %v", val1)
	}

	val2, ok := client.GetMetadata("key2")
	if !ok || val2 != 42 {
		t.Errorf("Expected 42, got %v", val2)
	}

	// Delete metadata
	client.DeleteMetadata("key1")
	_, ok = client.GetMetadata("key1")
	if ok {
		t.Error("Expected key1 to be deleted")
	}
}

// TestClientConnectionStats tests connection statistics.
func TestClientConnectionStats(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)
	defer client.Close()

	// Send a packet
	go func() {
		packet := &protocol.KeepAlivePacket{Timestamp: 12345}
		client.SendPacketSync(packet)
	}()

	// Read on the other end to complete the send
	format := protocol.WireFormatUnknown
	codec.DecodePacket(clientConn, &format)

	// Give time for stats to update
	time.Sleep(50 * time.Millisecond)

	stats := client.Stats()
	if stats.PacketsSent != 1 {
		t.Errorf("Expected 1 packet sent, got %d", stats.PacketsSent)
	}
}

// TestDefaultHandlerPacketDispatch tests packet dispatch functionality.
func TestDefaultHandlerPacketDispatch(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "127.0.0.1",
			Port: 0,
		},
	}

	server := NewServer(cfg)
	handler := NewDefaultHandler(server)

	// Track which handlers were called
	var handlerCalled string
	var mu sync.Mutex

	handler.SetHandshakeHandler(func(c *ClientConnection, p *protocol.HandshakePacket) error {
		mu.Lock()
		handlerCalled = "handshake"
		mu.Unlock()
		return nil
	})

	handler.SetChatMessageHandler(func(c *ClientConnection, p *protocol.ChatMessagePacket) error {
		mu.Lock()
		handlerCalled = "chat"
		mu.Unlock()
		return nil
	})

	handler.SetKeepAliveHandler(func(c *ClientConnection, p *protocol.KeepAlivePacket) error {
		mu.Lock()
		handlerCalled = "keepalive"
		mu.Unlock()
		return nil
	})

	// Create a mock client
	serverConn, _ := net.Pipe()
	defer serverConn.Close()
	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)
	defer client.Close()

	// Test handshake dispatch
	handshakePacket := &protocol.HandshakePacket{
		ProtocolVersion: 1,
		ClientID:        "test",
		PasswordHash:    "hash",
		Platform:        protocol.PlatformBukkit,
	}
	handler.HandlePacket(client, handshakePacket)
	mu.Lock()
	if handlerCalled != "handshake" {
		t.Errorf("Expected handshake handler, got %s", handlerCalled)
	}
	mu.Unlock()

	// Test chat message dispatch
	chatPacket := &protocol.ChatMessagePacket{
		SenderName: "test",
		Content:    "hello",
	}
	handler.HandlePacket(client, chatPacket)
	mu.Lock()
	if handlerCalled != "chat" {
		t.Errorf("Expected chat handler, got %s", handlerCalled)
	}
	mu.Unlock()

	// Test keep-alive dispatch
	keepAlivePacket := &protocol.KeepAlivePacket{Timestamp: 12345}
	handler.HandlePacket(client, keepAlivePacket)
	mu.Lock()
	if handlerCalled != "keepalive" {
		t.Errorf("Expected keepalive handler, got %s", handlerCalled)
	}
	mu.Unlock()
}

// TestServerBroadcast tests broadcasting packets to all clients.
func TestServerBroadcast(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "127.0.0.1",
			Port: 0,
		},
	}

	server := NewServer(cfg)

	// Add mock clients
	serverConn1, clientConn1 := net.Pipe()
	serverConn2, clientConn2 := net.Pipe()
	defer serverConn1.Close()
	defer serverConn2.Close()
	defer clientConn1.Close()
	defer clientConn2.Close()

	codec := protocol.NewCodec()
	client1 := NewClientConnection(serverConn1, codec)
	client2 := NewClientConnection(serverConn2, codec)
	defer client1.Close()
	defer client2.Close()

	server.AddClient("client1", client1)
	server.AddClient("client2", client2)

	if server.ClientCount() != 2 {
		t.Errorf("Expected 2 clients, got %d", server.ClientCount())
	}

	// Broadcast a packet
	packet := &protocol.AnnouncementPacket{
		Type:    1,
		Content: "Test announcement",
	}

	// Start goroutines to receive the broadcast
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		format := protocol.WireFormatUnknown
		received, err := codec.DecodePacket(clientConn1, &format)
		if err != nil {
			t.Errorf("Client 1 failed to receive: %v", err)
			return
		}
		ann, ok := received.(*protocol.AnnouncementPacket)
		if !ok || ann.Content != "Test announcement" {
			t.Errorf("Client 1 received wrong packet")
		}
	}()

	go func() {
		defer wg.Done()
		format := protocol.WireFormatUnknown
		received, err := codec.DecodePacket(clientConn2, &format)
		if err != nil {
			t.Errorf("Client 2 failed to receive: %v", err)
			return
		}
		ann, ok := received.(*protocol.AnnouncementPacket)
		if !ok || ann.Content != "Test announcement" {
			t.Errorf("Client 2 received wrong packet")
		}
	}()

	server.Broadcast(packet)

	// Wait for both clients to receive
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		// Success
	case <-time.After(2 * time.Second):
		t.Error("Timeout waiting for broadcast")
	}
}

// TestServerClientManagement tests adding and removing clients.
func TestServerClientManagement(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host: "127.0.0.1",
			Port: 0,
		},
	}

	server := NewServer(cfg)

	// Create mock clients
	serverConn, _ := net.Pipe()
	defer serverConn.Close()

	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)
	defer client.Close()

	// Add client
	server.AddClient("test-client", client)

	if server.ClientCount() != 1 {
		t.Errorf("Expected 1 client, got %d", server.ClientCount())
	}

	// Get client
	retrieved := server.GetClient("test-client")
	if retrieved != client {
		t.Error("Retrieved client doesn't match added client")
	}

	// Get all clients
	allClients := server.GetAllClients()
	if len(allClients) != 1 {
		t.Errorf("Expected 1 client in list, got %d", len(allClients))
	}

	// Remove client
	server.removeClient(client)

	if server.ClientCount() != 0 {
		t.Errorf("Expected 0 clients after removal, got %d", server.ClientCount())
	}
}

// TestClientConnectionClosed tests behavior when connection is closed.
func TestClientConnectionClosed(t *testing.T) {
	serverConn, _ := net.Pipe()
	
	codec := protocol.NewCodec()
	client := NewClientConnection(serverConn, codec)

	// Close the connection
	client.Close()

	if !client.IsClosed() {
		t.Error("Client should be marked as closed")
	}

	// Sending should return error
	packet := &protocol.KeepAlivePacket{Timestamp: 12345}
	err := client.SendPacket(packet)
	if err != ErrConnectionClosed {
		t.Errorf("Expected ErrConnectionClosed, got %v", err)
	}
}
