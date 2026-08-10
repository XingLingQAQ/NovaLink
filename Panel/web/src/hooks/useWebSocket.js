/**
 * React Hook for WebSocket connection
 * Provides easy integration with React components
 * 
 * Requirements: 24.1, 24.4
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import websocketService, { ConnectionState, MessageType } from '../services/websocket';
import authService from '../services/auth';

/**
 * Custom hook for WebSocket connection management
 * @param {object} options - Hook options
 * @param {string} options.url - WebSocket server URL
 * @param {boolean} options.autoConnect - Auto connect on mount
 * @param {string[]} options.subscribeChannels - Channels to subscribe on connect
 * @returns {object} - WebSocket state and methods
 */
export function useWebSocket(options = {}) {
  const {
    url = import.meta.env.VITE_WS_URL || 'ws://localhost:8889',
    autoConnect = false,
    subscribeChannels = []
  } = options;

  const [connectionState, setConnectionState] = useState(websocketService.getState());
  const [lastMessage, setLastMessage] = useState(null);
  const [error, setError] = useState(null);
  const messageHandlersRef = useRef(new Map());

  // Handle connection state changes
  useEffect(() => {
    const handleStateChange = ({ state }) => {
      setConnectionState(state);
      
      if (state === ConnectionState.ERROR) {
        setError('Connection error');
      } else if (state === ConnectionState.AUTHENTICATED) {
        setError(null);
        // Subscribe to channels after authentication
        if (subscribeChannels.length > 0) {
          websocketService.subscribe(subscribeChannels);
        }
      }
    };

    websocketService.on('stateChange', handleStateChange);
    
    return () => {
      websocketService.off('stateChange', handleStateChange);
    };
  }, [subscribeChannels]);

  // Handle all incoming messages
  useEffect(() => {
    const handleMessage = (message) => {
      setLastMessage(message);
    };

    websocketService.on('all', handleMessage);
    
    return () => {
      websocketService.off('all', handleMessage);
    };
  }, []);

  // Auto connect on mount if enabled
  useEffect(() => {
    if (autoConnect && authService.isAuthenticated()) {
      connect();
    }
    
    return () => {
      // Don't disconnect on unmount to maintain connection across components
    };
  }, [autoConnect]);

  /**
   * Connect to WebSocket server
   */
  const connect = useCallback(async () => {
    const token = authService.getToken();
    
    if (!token) {
      setError('No authentication token');
      return false;
    }

    try {
      setError(null);
      await websocketService.connect(url, token);
      return true;
    } catch (err) {
      setError(err.message);
      return false;
    }
  }, [url]);

  /**
   * Disconnect from WebSocket server
   */
  const disconnect = useCallback(() => {
    websocketService.disconnect();
  }, []);

  /**
   * Subscribe to specific message type
   * @param {string} type - Message type
   * @param {function} handler - Handler function
   */
  const onMessage = useCallback((type, handler) => {
    websocketService.on(type, handler);
    
    // Track handler for cleanup
    if (!messageHandlersRef.current.has(type)) {
      messageHandlersRef.current.set(type, new Set());
    }
    messageHandlersRef.current.get(type).add(handler);

    // Return cleanup function
    return () => {
      websocketService.off(type, handler);
      messageHandlersRef.current.get(type)?.delete(handler);
    };
  }, []);

  /**
   * Subscribe to channels
   * @param {string[]} channels - Channel IDs
   */
  const subscribe = useCallback((channels) => {
    websocketService.subscribe(channels);
  }, []);

  /**
   * Unsubscribe from channels
   * @param {string[]} channels - Channel IDs
   */
  const unsubscribe = useCallback((channels) => {
    websocketService.unsubscribe(channels);
  }, []);

  // Cleanup handlers on unmount
  useEffect(() => {
    return () => {
      messageHandlersRef.current.forEach((handlers, type) => {
        handlers.forEach(handler => {
          websocketService.off(type, handler);
        });
      });
      messageHandlersRef.current.clear();
    };
  }, []);

  return {
    // State
    connectionState,
    isConnected: connectionState === ConnectionState.CONNECTED || 
                 connectionState === ConnectionState.AUTHENTICATED,
    isAuthenticated: connectionState === ConnectionState.AUTHENTICATED,
    isConnecting: connectionState === ConnectionState.CONNECTING || 
                  connectionState === ConnectionState.RECONNECTING,
    lastMessage,
    error,
    subscribedChannels: websocketService.getSubscribedChannels(),
    
    // Methods
    connect,
    disconnect,
    subscribe,
    unsubscribe,
    onMessage
  };
}

/**
 * Hook for subscribing to chat messages
 * @param {string[]} channels - Channels to subscribe
 * @returns {object} - Chat messages and methods
 */
export function useChatMessages(channels = []) {
  const [messages, setMessages] = useState([]);
  const { isAuthenticated, subscribe, unsubscribe, onMessage } = useWebSocket();

  useEffect(() => {
    if (isAuthenticated && channels.length > 0) {
      subscribe(channels);
    }

    return () => {
      if (channels.length > 0) {
        unsubscribe(channels);
      }
    };
  }, [isAuthenticated, channels, subscribe, unsubscribe]);

  useEffect(() => {
    const cleanup = onMessage(MessageType.CHAT, (message) => {
      setMessages(prev => [...prev.slice(-99), message]);
    });

    return cleanup;
  }, [onMessage]);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    clearMessages
  };
}

/**
 * Hook for server status updates
 * @returns {object} - Server status and updates
 */
export function useServerStatus() {
  const [servers, setServers] = useState([]);
  const { isAuthenticated, onMessage } = useWebSocket();

  useEffect(() => {
    if (!isAuthenticated) return;

    const cleanup = onMessage(MessageType.SERVER_STATUS, (message) => {
      if (message.servers) {
        setServers(message.servers);
      } else if (message.server) {
        // Single server update
        setServers(prev => {
          const index = prev.findIndex(s => s.id === message.server.id);
          if (index >= 0) {
            const updated = [...prev];
            updated[index] = message.server;
            return updated;
          }
          return [...prev, message.server];
        });
      }
    });

    return cleanup;
  }, [isAuthenticated, onMessage]);

  return { servers };
}

/**
 * Hook for player updates
 * @returns {object} - Player list and updates
 */
export function usePlayerUpdates() {
  const [players, setPlayers] = useState([]);
  const { isAuthenticated, onMessage } = useWebSocket();

  useEffect(() => {
    if (!isAuthenticated) return;

    const cleanup = onMessage(MessageType.PLAYER_UPDATE, (message) => {
      if (message.players) {
        setPlayers(message.players);
      } else if (message.player) {
        setPlayers(prev => {
          const index = prev.findIndex(p => p.uuid === message.player.uuid);
          if (message.action === 'leave') {
            return prev.filter(p => p.uuid !== message.player.uuid);
          } else if (index >= 0) {
            const updated = [...prev];
            updated[index] = message.player;
            return updated;
          }
          return [...prev, message.player];
        });
      }
    });

    return cleanup;
  }, [isAuthenticated, onMessage]);

  return { players };
}

/**
 * Hook for notifications
 * @returns {object} - Notifications and methods
 */
export function useNotifications() {
  const [notifications, setNotifications] = useState([]);
  const { isAuthenticated, onMessage } = useWebSocket();

  useEffect(() => {
    if (!isAuthenticated) return;

    const cleanup = onMessage(MessageType.NOTIFICATION, (message) => {
      const notification = {
        id: message.id || Date.now(),
        ...message,
        read: false,
        timestamp: message.timestamp || Date.now()
      };
      setNotifications(prev => [notification, ...prev]);
    });

    return cleanup;
  }, [isAuthenticated, onMessage]);

  const markAsRead = useCallback((id) => {
    setNotifications(prev => 
      prev.map(n => n.id === id ? { ...n, read: true } : n)
    );
  }, []);

  const markAllAsRead = useCallback(() => {
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
  }, []);

  const clearAll = useCallback(() => {
    setNotifications([]);
  }, []);

  return {
    notifications,
    unreadCount: notifications.filter(n => !n.read).length,
    markAsRead,
    markAllAsRead,
    clearAll
  };
}

export default useWebSocket;
