/**
 * WebSocket Context Provider
 * Provides global WebSocket state and methods to all components
 * 
 * Requirements: 24.1, 24.4
 */

import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import websocketService, { ConnectionState, MessageType } from '../services/websocket';
import authService from '../services/auth';

// Create context
const WebSocketContext = createContext(null);

/**
 * WebSocket Provider Component
 */
export function WebSocketProvider({ children, wsUrl }) {
  const { t } = useTranslation();
  const tRef = useRef(t);
  useEffect(() => { tRef.current = t; }, [t]);
  const [connectionState, setConnectionState] = useState(ConnectionState.DISCONNECTED);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [error, setError] = useState(null);
  const [chatMessages, setChatMessages] = useState([]);
  const [servers, setServers] = useState([]);
  const [players, setPlayers] = useState([]);
  const [notifications, setNotifications] = useState([]);

  // Default WebSocket URL
  const url = wsUrl || import.meta.env.VITE_WS_URL || 'ws://localhost:8889';

  // Handle connection state changes
  useEffect(() => {
    const handleStateChange = ({ state }) => {
      setConnectionState(state);
      setIsAuthenticated(state === ConnectionState.AUTHENTICATED);
      
      if (state === ConnectionState.ERROR) {
        setError('WebSocket connection error');
      } else if (state === ConnectionState.AUTHENTICATED) {
        setError(null);
      }
    };

    websocketService.on('stateChange', handleStateChange);
    
    return () => {
      websocketService.off('stateChange', handleStateChange);
    };
  }, []);

  // Handle incoming messages
  useEffect(() => {
    // Chat messages
    const handleChat = (message) => {
      const chatMsg = {
        id: message.id || Date.now(),
        time: message.time || new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        server: message.server || message.clientId,
        player: message.senderName || message.sender,
        channel: message.channelId || message.channel,
        content: message.content,
        platform: message.platform || 'Java'
      };
      setChatMessages(prev => [...prev.slice(-99), chatMsg]);
    };

    // Server status updates
    const handleServerStatus = (message) => {
      if (message.servers) {
        setServers(message.servers);
      } else if (message.server) {
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
    };

    // Player updates
    const handlePlayerUpdate = (message) => {
      if (message.players) {
        setPlayers(message.players);
      } else if (message.player) {
        setPlayers(prev => {
          if (message.action === 'leave') {
            return prev.filter(p => p.uuid !== message.player.uuid);
          }
          const index = prev.findIndex(p => p.uuid === message.player.uuid);
          if (index >= 0) {
            const updated = [...prev];
            updated[index] = message.player;
            return updated;
          }
          return [...prev, message.player];
        });
      }
    };

    // Notifications
    const handleNotification = (message) => {
      const notification = {
        id: message.id || Date.now(),
        title: message.title,
        desc: message.desc || message.description,
        time: message.time || tRef.current('notifications.default_time'),
        type: message.type || 'info',
        read: false
      };
      setNotifications(prev => [notification, ...prev]);
    };

    websocketService.on(MessageType.CHAT, handleChat);
    websocketService.on(MessageType.SERVER_STATUS, handleServerStatus);
    websocketService.on(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
    websocketService.on(MessageType.NOTIFICATION, handleNotification);

    return () => {
      websocketService.off(MessageType.CHAT, handleChat);
      websocketService.off(MessageType.SERVER_STATUS, handleServerStatus);
      websocketService.off(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
      websocketService.off(MessageType.NOTIFICATION, handleNotification);
    };
  }, []);

  /**
   * Connect to WebSocket server
   */
  const connect = useCallback(async () => {
    const token = authService.getToken();
    
    if (!token) {
      setError(t('common.ws_error_login_first'));
      return false;
    }

    try {
      setError(null);
      await websocketService.connect(url, token);
      return true;
    } catch (err) {
      setError(err.message || t('common.ws_error_connect'));
      return false;
    }
  }, [url, t]);

  /**
   * Disconnect from WebSocket server
   */
  const disconnect = useCallback(() => {
    websocketService.disconnect();
  }, []);

  /**
   * Subscribe to channels
   */
  const subscribe = useCallback((channels) => {
    websocketService.subscribe(channels);
  }, []);

  /**
   * Unsubscribe from channels
   */
  const unsubscribe = useCallback((channels) => {
    websocketService.unsubscribe(channels);
  }, []);

  /**
   * Clear chat messages
   */
  const clearChatMessages = useCallback(() => {
    setChatMessages([]);
  }, []);

  /**
   * Mark notification as read
   */
  const markNotificationRead = useCallback((id) => {
    setNotifications(prev => 
      prev.map(n => n.id === id ? { ...n, read: true } : n)
    );
  }, []);

  /**
   * Mark all notifications as read
   */
  const markAllNotificationsRead = useCallback(() => {
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
  }, []);

  /**
   * Clear all notifications
   */
  const clearNotifications = useCallback(() => {
    setNotifications([]);
  }, []);

  const value = {
    // Connection state
    connectionState,
    isConnected: connectionState === ConnectionState.CONNECTED || 
                 connectionState === ConnectionState.AUTHENTICATED,
    isAuthenticated,
    isConnecting: connectionState === ConnectionState.CONNECTING || 
                  connectionState === ConnectionState.RECONNECTING,
    error,

    // Data
    chatMessages,
    servers,
    players,
    notifications,
    unreadNotificationCount: notifications.filter(n => !n.read).length,

    // Methods
    connect,
    disconnect,
    subscribe,
    unsubscribe,
    clearChatMessages,
    markNotificationRead,
    markAllNotificationsRead,
    clearNotifications
  };

  return (
    <WebSocketContext.Provider value={value}>
      {children}
    </WebSocketContext.Provider>
  );
}

/**
 * Hook to use WebSocket context
 */
export function useWebSocketContext() {
  const context = useContext(WebSocketContext);
  
  if (!context) {
    throw new Error('useWebSocketContext must be used within a WebSocketProvider');
  }
  
  return context;
}

export default WebSocketContext;
