package network

import (
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/nova/novalink-go/pkg/protocol"
)

var (
	// ErrConnectionClosed is returned when attempting to use a closed connection.
	ErrConnectionClosed = errors.New("connection closed")
	// ErrSendQueueFull is returned when the send queue is full.
	ErrSendQueueFull = errors.New("send queue full")
)

// ClientConnection represents a connected client.
// It provides thread-safe packet sending via a channel-based queue
// and supports connection timeouts.
type ClientConnection struct {
	conn       net.Conn
	codec      *protocol.Codec
	clientID   string
	platform   byte
	// authenticated indicates whether this connection has completed a successful handshake.
	// It is intentionally separate from clientID to allow empty clientIDs during pre-auth.
	authenticated atomic.Bool
	mutex      sync.RWMutex
	lastActive atomic.Value // time.Time
	metadata   map[string]interface{}
	metaMutex  sync.RWMutex

	// Channel-based packet sending
	sendChan chan protocol.Packet
	doneChan chan struct{}
	closed   atomic.Bool

	// Timeout settings
	readTimeout  time.Duration
	writeTimeout time.Duration

	// Statistics
	packetsSent     atomic.Uint64
	packetsReceived atomic.Uint64
	bytesSent       atomic.Uint64
	bytesReceived   atomic.Uint64

	// Wire format negotiated for this connection (legacy vs modern requestId framing).
	wireFormat atomic.Int32
}

// NewClientConnection creates a new ClientConnection.
func NewClientConnection(conn net.Conn, codec *protocol.Codec) *ClientConnection {
	c := &ClientConnection{
		conn:         conn,
		codec:        codec,
		metadata:     make(map[string]interface{}),
		sendChan:     make(chan protocol.Packet, 256), // Buffered channel for async sends
		doneChan:     make(chan struct{}),
		readTimeout:  30 * time.Second,
		writeTimeout: 10 * time.Second,
	}
	c.lastActive.Store(time.Now())
	c.wireFormat.Store(int32(protocol.WireFormatUnknown))

	// Start the send goroutine
	go c.sendLoop()

	return c
}

// sendLoop processes packets from the send channel.
func (c *ClientConnection) sendLoop() {
	for {
		select {
		case <-c.doneChan:
			return
		case packet := <-c.sendChan:
			if err := c.doSend(packet); err != nil {
				// Log error but continue processing
				// The connection will be closed by the read loop if needed
			}
		}
	}
}

// doSend performs the actual packet send operation.
func (c *ClientConnection) doSend(packet protocol.Packet) error {
	c.mutex.Lock()
	defer c.mutex.Unlock()

	if c.closed.Load() {
		return ErrConnectionClosed
	}

	// Set write deadline
	if c.writeTimeout > 0 {
		c.conn.SetWriteDeadline(time.Now().Add(c.writeTimeout))
	}

	format := protocol.WireFormat(c.wireFormat.Load())
	data, err := c.codec.EncodePacket(packet, format)
	if err != nil {
		return err
	}

	n, err := c.conn.Write(data)
	if err != nil {
		return err
	}

	c.packetsSent.Add(1)
	c.bytesSent.Add(uint64(n))
	return nil
}

// ReadPacket reads and decodes a packet from the connection.
func (c *ClientConnection) ReadPacket() (protocol.Packet, error) {
	if c.closed.Load() {
		return nil, ErrConnectionClosed
	}

	// Set read deadline
	if c.readTimeout > 0 {
		c.conn.SetReadDeadline(time.Now().Add(c.readTimeout))
	}

	format := protocol.WireFormat(c.wireFormat.Load())
	packet, err := c.codec.DecodePacket(c.conn, &format)
	if err != nil {
		return nil, err
	}
	c.wireFormat.Store(int32(format))

	c.lastActive.Store(time.Now())
	c.packetsReceived.Add(1)
	return packet, nil
}

// SendPacket queues a packet for sending via the send channel.
// This is non-blocking if the send queue has space.
func (c *ClientConnection) SendPacket(packet protocol.Packet) error {
	if c.closed.Load() {
		return ErrConnectionClosed
	}

	select {
	case c.sendChan <- packet:
		return nil
	default:
		return ErrSendQueueFull
	}
}

// SendPacketSync sends a packet synchronously, bypassing the send queue.
// Use this for high-priority packets that need immediate delivery.
func (c *ClientConnection) SendPacketSync(packet protocol.Packet) error {
	return c.doSend(packet)
}

// Close closes the connection and stops the send goroutine.
func (c *ClientConnection) Close() error {
	if c.closed.Swap(true) {
		return nil // Already closed
	}

	close(c.doneChan)
	return c.conn.Close()
}

// IsClosed returns whether the connection is closed.
func (c *ClientConnection) IsClosed() bool {
	return c.closed.Load()
}

// IsAuthenticated reports whether this connection has completed authentication.
func (c *ClientConnection) IsAuthenticated() bool {
	return c.authenticated.Load()
}

// SetAuthenticated marks the connection as authenticated or not.
func (c *ClientConnection) SetAuthenticated(v bool) {
	c.authenticated.Store(v)
}

// ClientID returns the client's ID.
func (c *ClientConnection) ClientID() string {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.clientID
}

// SetClientID sets the client's ID.
func (c *ClientConnection) SetClientID(id string) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.clientID = id
}

// Platform returns the client's platform type.
func (c *ClientConnection) Platform() byte {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	return c.platform
}

// SetPlatform sets the client's platform type.
func (c *ClientConnection) SetPlatform(platform byte) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.platform = platform
}

// RemoteAddr returns the remote address of the connection.
func (c *ClientConnection) RemoteAddr() net.Addr {
	return c.conn.RemoteAddr()
}

// LastActive returns the time of the last activity.
func (c *ClientConnection) LastActive() time.Time {
	return c.lastActive.Load().(time.Time)
}

// SetReadTimeout sets the read timeout for the connection.
func (c *ClientConnection) SetReadTimeout(d time.Duration) {
	c.readTimeout = d
}

// SetWriteTimeout sets the write timeout for the connection.
func (c *ClientConnection) SetWriteTimeout(d time.Duration) {
	c.writeTimeout = d
}

// SetMetadata sets a metadata value.
func (c *ClientConnection) SetMetadata(key string, value interface{}) {
	c.metaMutex.Lock()
	defer c.metaMutex.Unlock()
	c.metadata[key] = value
}

// GetMetadata gets a metadata value.
func (c *ClientConnection) GetMetadata(key string) (interface{}, bool) {
	c.metaMutex.RLock()
	defer c.metaMutex.RUnlock()
	val, ok := c.metadata[key]
	return val, ok
}

// DeleteMetadata removes a metadata value.
func (c *ClientConnection) DeleteMetadata(key string) {
	c.metaMutex.Lock()
	defer c.metaMutex.Unlock()
	delete(c.metadata, key)
}

// Stats returns connection statistics.
func (c *ClientConnection) Stats() ConnectionStats {
	return ConnectionStats{
		PacketsSent:     c.packetsSent.Load(),
		PacketsReceived: c.packetsReceived.Load(),
		BytesSent:       c.bytesSent.Load(),
		BytesReceived:   c.bytesReceived.Load(),
		LastActive:      c.LastActive(),
	}
}

// ConnectionStats holds connection statistics.
type ConnectionStats struct {
	PacketsSent     uint64
	PacketsReceived uint64
	BytesSent       uint64
	BytesReceived   uint64
	LastActive      time.Time
}

// SendQueueSize returns the current size of the send queue.
func (c *ClientConnection) SendQueueSize() int {
	return len(c.sendChan)
}
