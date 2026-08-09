/**
 * WebSocket Service for NovaPanel
 * Handles real-time communication with NovaLink backend
 * 
 * Requirements: 24.1, 24.4
 */

// WebSocket connection states
export const ConnectionState = {
  DISCONNECTED: 'disconnected',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  AUTHENTICATED: 'authenticated',
  RECONNECTING: 'reconnecting',
  ERROR: 'error'
};

// Message types based on design document
export const MessageType = {
  AUTH: 'auth',
  AUTH_RESPONSE: 'auth_response',
  SUBSCRIBE: 'subscribe',
  UNSUBSCRIBE: 'unsubscribe',
  CHAT: 'chat',
  SERVER_STATUS: 'server_status',
  PLAYER_UPDATE: 'player_update',
  CHANNEL_UPDATE: 'channel_update',
  NOTIFICATION: 'notification',
  ERROR: 'error',
  PING: 'ping',
  PONG: 'pong'
};

/**
 * WebSocket Service class for managing connection to NovaLink backend
 */
class WebSocketService {
  constructor() {
    this.socket = null;
    this.state = ConnectionState.DISCONNECTED;
    this.token = null;
    this.url = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectDelay = 1000; // Start with 1 second
    this.maxReconnectDelay = 30000; // Max 30 seconds
    this.pingInterval = null;
    this.pingTimeout = null;
    this.listeners = new Map();
    this.subscribedChannels = new Set();
    this.messageQueue = [];
  }

  /**
   * Connect to WebSocket server
   * @param {string} url - WebSocket server URL
   * @param {string} token - JWT authentication token
   * @returns {Promise<boolean>} - Connection success
   */
  connect(url, token) {
    return new Promise((resolve, reject) => {
      // Already connected (or connecting to the same URL): reuse the in-flight
      // connection instead of opening a second socket. Without this guard, React
      // StrictMode's dev double-invoke of the WS effect opens a duplicate socket
      // whose later close surfaces as a spurious "ws connection failed" error.
      if (this.socket && (this.state === ConnectionState.CONNECTED || this.state === ConnectionState.AUTHENTICATED)) {
        resolve(true);
        return;
      }
      if (this.socket && this.state === ConnectionState.CONNECTING && this.url === url) {
        // A connection to the same URL is already being established; don't open another.
        resolve(true);
        return;
      }

      this.url = url;
      this.token = token;
      this.state = ConnectionState.CONNECTING;
      this._notifyStateChange();

      try {
        this.socket = new WebSocket(url);

        this.socket.onopen = () => {
          console.log('[WebSocket] Connection established');
          this.state = ConnectionState.CONNECTED;
          this.reconnectAttempts = 0;
          this._notifyStateChange();
          
          // Authenticate immediately after connection
          this._authenticate(token)
            .then(() => {
              this._startPingInterval();
              this._flushMessageQueue();
              resolve(true);
            })
            .catch((error) => {
              console.error('[WebSocket] Authentication failed:', error);
              this.disconnect();
              reject(error);
            });
        };

        this.socket.onmessage = (event) => {
          this._handleMessage(event.data);
        };

        this.socket.onerror = (error) => {
          console.error('[WebSocket] Error:', error);
          this.state = ConnectionState.ERROR;
          this._notifyStateChange();
          reject(error);
        };

        this.socket.onclose = (event) => {
          console.log('[WebSocket] Connection closed:', event.code, event.reason);
          this._stopPingInterval();
          
          if (this.state !== ConnectionState.DISCONNECTED) {
            this.state = ConnectionState.DISCONNECTED;
            this._notifyStateChange();
            this._attemptReconnect();
          }
        };
      } catch (error) {
        console.error('[WebSocket] Failed to create connection:', error);
        this.state = ConnectionState.ERROR;
        this._notifyStateChange();
        reject(error);
      }
    });
  }

  /**
   * Disconnect from WebSocket server
   */
  disconnect() {
    this.state = ConnectionState.DISCONNECTED;
    this._stopPingInterval();
    
    if (this.socket) {
      this.socket.close(1000, 'Client disconnect');
      this.socket = null;
    }
    
    this.subscribedChannels.clear();
    this._notifyStateChange();
  }

  /**
   * Send authentication message with JWT token
   * @param {string} token - JWT token
   * @returns {Promise<object>} - Authentication response
   */
  _authenticate(token) {
    return new Promise((resolve, reject) => {
      const authMessage = {
        type: MessageType.AUTH,
        token: token,
        timestamp: Date.now()
      };

      // Set up one-time listener for auth response
      const authHandler = (data) => {
        if (data.type === MessageType.AUTH_RESPONSE) {
          this.off(MessageType.AUTH_RESPONSE, authHandler);
          
          if (data.success) {
            this.state = ConnectionState.AUTHENTICATED;
            this._notifyStateChange();
            resolve(data);
          } else {
            reject(new Error(data.error || 'Authentication failed'));
          }
        }
      };

      this.on(MessageType.AUTH_RESPONSE, authHandler);
      
      // Send auth message
      this._send(authMessage);

      // Timeout for auth response
      setTimeout(() => {
        this.off(MessageType.AUTH_RESPONSE, authHandler);
        if (this.state !== ConnectionState.AUTHENTICATED) {
          reject(new Error('Authentication timeout'));
        }
      }, 10000);
    });
  }

  /**
   * Subscribe to channel messages
   * @param {string[]} channels - Array of channel IDs to subscribe
   */
  subscribe(channels) {
    const newChannels = channels.filter(c => !this.subscribedChannels.has(c));
    
    if (newChannels.length === 0) return;

    const message = {
      type: MessageType.SUBSCRIBE,
      channels: newChannels,
      timestamp: Date.now()
    };

    this._send(message);
    newChannels.forEach(c => this.subscribedChannels.add(c));
  }

  /**
   * Unsubscribe from channel messages
   * @param {string[]} channels - Array of channel IDs to unsubscribe
   */
  unsubscribe(channels) {
    const existingChannels = channels.filter(c => this.subscribedChannels.has(c));
    
    if (existingChannels.length === 0) return;

    const message = {
      type: MessageType.UNSUBSCRIBE,
      channels: existingChannels,
      timestamp: Date.now()
    };

    this._send(message);
    existingChannels.forEach(c => this.subscribedChannels.delete(c));
  }

  /**
   * Send a message through WebSocket
   * @param {object} message - Message object to send
   */
  _send(message) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    } else {
      // Queue message for later
      this.messageQueue.push(message);
    }
  }

  /**
   * Flush queued messages after reconnection
   */
  _flushMessageQueue() {
    while (this.messageQueue.length > 0) {
      const message = this.messageQueue.shift();
      this._send(message);
    }
  }

  /**
   * Handle incoming WebSocket message
   * @param {string} data - Raw message data
   */
  _handleMessage(data) {
    try {
      const message = JSON.parse(data);
      
      // Handle pong response
      if (message.type === MessageType.PONG) {
        this._handlePong();
        return;
      }

      // Notify listeners
      this._notifyListeners(message.type, message);
      
      // Also notify 'all' listeners
      this._notifyListeners('all', message);
    } catch (error) {
      console.error('[WebSocket] Failed to parse message:', error);
    }
  }

  /**
   * Start ping interval for keep-alive
   */
  _startPingInterval() {
    this._stopPingInterval();
    
    this.pingInterval = setInterval(() => {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        this._send({ type: MessageType.PING, timestamp: Date.now() });
        
        // Set timeout for pong response
        this.pingTimeout = setTimeout(() => {
          console.warn('[WebSocket] Ping timeout, reconnecting...');
          this.socket.close();
        }, 5000);
      }
    }, 30000); // Ping every 30 seconds
  }

  /**
   * Stop ping interval
   */
  _stopPingInterval() {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
    if (this.pingTimeout) {
      clearTimeout(this.pingTimeout);
      this.pingTimeout = null;
    }
  }

  /**
   * Handle pong response
   */
  _handlePong() {
    if (this.pingTimeout) {
      clearTimeout(this.pingTimeout);
      this.pingTimeout = null;
    }
  }

  /**
   * Attempt to reconnect with exponential backoff
   */
  _attemptReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WebSocket] Max reconnect attempts reached');
      this.state = ConnectionState.ERROR;
      this._notifyStateChange();
      return;
    }

    this.state = ConnectionState.RECONNECTING;
    this._notifyStateChange();

    const delay = Math.min(
      this.reconnectDelay * Math.pow(2, this.reconnectAttempts),
      this.maxReconnectDelay
    );

    console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts + 1})`);

    setTimeout(() => {
      this.reconnectAttempts++;
      this.connect(this.url, this.token).catch(() => {
        // Will trigger another reconnect attempt via onclose
      });
    }, delay);
  }

  /**
   * Add event listener
   * @param {string} type - Message type to listen for
   * @param {function} callback - Callback function
   */
  on(type, callback) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type).add(callback);
  }

  /**
   * Remove event listener
   * @param {string} type - Message type
   * @param {function} callback - Callback function to remove
   */
  off(type, callback) {
    if (this.listeners.has(type)) {
      this.listeners.get(type).delete(callback);
    }
  }

  /**
   * Notify listeners of a message
   * @param {string} type - Message type
   * @param {object} data - Message data
   */
  _notifyListeners(type, data) {
    if (this.listeners.has(type)) {
      this.listeners.get(type).forEach(callback => {
        try {
          callback(data);
        } catch (error) {
          console.error('[WebSocket] Listener error:', error);
        }
      });
    }
  }

  /**
   * Notify state change listeners
   */
  _notifyStateChange() {
    this._notifyListeners('stateChange', { state: this.state });
  }

  /**
   * Get current connection state
   * @returns {string} - Current state
   */
  getState() {
    return this.state;
  }

  /**
   * Check if connected and authenticated
   * @returns {boolean}
   */
  isAuthenticated() {
    return this.state === ConnectionState.AUTHENTICATED;
  }

  /**
   * Get subscribed channels
   * @returns {string[]}
   */
  getSubscribedChannels() {
    return Array.from(this.subscribedChannels);
  }
}

// Export singleton instance
export const websocketService = new WebSocketService();
export default websocketService;
