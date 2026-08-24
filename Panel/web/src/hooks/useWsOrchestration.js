/**
 * WebSocket orchestration — connection, auth, channel subscription, snapshot
 * requests, message-handler registration and disconnect-state tracking.
 * Extracted from App.jsx unchanged in behavior.
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';

import authService from '../services/auth';
import websocketService, {
  ConnectionState,
  MessageType,
  isWebSocketLifecycleCancellation,
} from '../services/websocket';
import { getApiBaseUrl, getWsUrl } from '../services/api';
import { desiredChannelSubscriptions } from '../lib/channelSubscriptions';
import { adaptChannel, adaptClient, adaptChatMessage, adaptWsPlayer } from '../utils/adapters';

export function useWsOrchestration({
  channels,
  activeTab,
  selectedChannelId,
  setServers,
  setChannels,
  setPlayers,
  setChatMessages,
  onNotification,
  onSettingsUpdate,
  addToast,
}) {
  // WS connection state (for showing a small indicator).
  const [wsState, setWsState] = useState(websocketService.getState());

  // Defers WS disconnect on effect cleanup so React StrictMode's dev-only
  // mount→unmount→remount cycle doesn't tear down a connection that's about to
  // be reused. A real unmount (logout / leave page) still disconnects, just
  // delayed by a tick; a remount cancels the timer and keeps the socket alive.
  const wsDisconnectTimerRef = useRef(null);
  const desiredChannelIdsRef = useRef(new Set());

  // Keep a live ref to the translation function so long-lived WS handlers
  // (registered once on mount) emit locale-aware toast text without re-running
  // the WS effect on every language change.
  const { t } = useTranslation();
  const tRef = useRef(t);
  useEffect(() => { tRef.current = t; }, [t]);

  // Same live-ref pattern for the notification callback (it depends on addToast
  // which is stable, but keeping a ref avoids re-running the connect effect if
  // a caller ever passes a non-stable callback).
  const onNotificationRef = useRef(onNotification);
  useEffect(() => { onNotificationRef.current = onNotification; }, [onNotification]);

  // §11.6 提案 10 / item 20 缺口 B: SETTINGS_UPDATE live-ref. Kept as a ref so
  // the WS effect (registered once on mount) always calls the latest handler
  // without re-running the connect effect on every render. The handler itself
  // is owned by useDashboardData (it knows whether the SettingsView form is
  // dirty and decides refresh-vs-toast).
  const onSettingsUpdateRef = useRef(onSettingsUpdate);
  useEffect(() => { onSettingsUpdateRef.current = onSettingsUpdate; }, [onSettingsUpdate]);

  // --- WebSocket connection + real-time handlers ---
  useEffect(() => {
    // Cancel any pending disconnect from a prior cleanup (StrictMode remount).
    if (wsDisconnectTimerRef.current) {
      clearTimeout(wsDisconnectTimerRef.current);
      wsDisconnectTimerRef.current = null;
    }
    let cancelled = false;
    const token = authService.getToken();
    const wsUrl = getWsUrl();

    if (!token) {
      console.warn('[WS] no auth token, skipping connect');
      return;
    }

    // State-change listener.
    const handleStateChange = ({ state }) => {
      if (cancelled) return;
      setWsState(state);
      if (state === ConnectionState.ERROR) {
        addToast(tRef.current('common.ws_toast_error'), 'error');
      }
    };
    websocketService.on('stateChange', handleStateChange);

    // Message handlers.
    const handleChat = (message) => {
      if (!desiredChannelIdsRef.current.has(message.channelId)) return;
      const adapted = adaptChatMessage(message);
      if (adapted) {
        setChatMessages((prev) => [...prev.slice(-199), adapted]);
      }
    };

    const handleServerStatus = (message) => {
      if (Array.isArray(message.clients)) {
        const adapted = message.clients.map(adaptClient).filter(Boolean);
        setServers(adapted);
      } else if (message.server) {
        const adapted = adaptClient(message.server);
        if (adapted) {
          setServers((prev) => {
            const idx = prev.findIndex((s) => s.id === adapted.id);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = adapted;
              return next;
            }
            return [...prev, adapted];
          });
        }
      }
    };

    const handleChannelUpdate = (message) => {
      if (Array.isArray(message.channels)) {
        setChannels(message.channels.map(adaptChannel).filter(Boolean));
      }
    };

    const handlePlayerUpdate = (message) => {
      if (Array.isArray(message.players)) {
        setPlayers(message.players.map(adaptWsPlayer).filter(Boolean));
      } else if (message.player) {
        const adapted = adaptWsPlayer(message.player);
        if (adapted) {
          setPlayers((prev) => {
            if (message.action === 'leave') {
              return prev.filter((p) => p.uuid !== adapted.uuid);
            }
            const idx = prev.findIndex((p) => p.uuid === adapted.uuid);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = adapted;
              return next;
            }
            return [...prev, adapted];
          });
        }
      }
    };

    const handleNotification = (message) => {
      if (onNotificationRef.current) onNotificationRef.current(message);
    };

    // §11.6 提案 10 / item 20 缺口 B: settings_update listener.
    // The revision guard in websocket.js already discarded any stale-revision
    // emit before it reaches here, so `message` is always the newest applied
    // settings snapshot. We forward it to the useDashboardData-owned handler,
    // which knows the local settings state + dirty flag and decides whether to
    // refresh the displayed form or surface a "config updated by someone else"
    // toast (to avoid clobbering an in-progress manual edit).
    const handleSettingsUpdate = (message) => {
      if (onSettingsUpdateRef.current) onSettingsUpdateRef.current(message);
    };

    websocketService.on(MessageType.CHAT, handleChat);
    websocketService.on(MessageType.SERVER_STATUS, handleServerStatus);
    websocketService.on(MessageType.CHANNEL_UPDATE, handleChannelUpdate);
    websocketService.on(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
    websocketService.on(MessageType.NOTIFICATION, handleNotification);
    websocketService.on(MessageType.SETTINGS_UPDATE, handleSettingsUpdate);

    // Connect (non-blocking — failures are surfaced via toasts/state).
    websocketService.connect(wsUrl, token).catch((err) => {
      if (isWebSocketLifecycleCancellation(err)) return;
      console.error('[WS] connect failed:', err);
    });

    return () => {
      cancelled = true;
      websocketService.off(MessageType.CHAT, handleChat);
      websocketService.off(MessageType.SERVER_STATUS, handleServerStatus);
      websocketService.off(MessageType.CHANNEL_UPDATE, handleChannelUpdate);
      websocketService.off(MessageType.PLAYER_UPDATE, handlePlayerUpdate);
      websocketService.off(MessageType.NOTIFICATION, handleNotification);
      websocketService.off(MessageType.SETTINGS_UPDATE, handleSettingsUpdate);
      websocketService.off('stateChange', handleStateChange);
      // Defer disconnect: StrictMode remounts the effect immediately after
      // cleanup in dev. If a remount follows, its setup clears this timer and
      // reuses the live socket. If no remount comes (real unmount), the socket
      // is closed after 150ms.
      wsDisconnectTimerRef.current = setTimeout(() => {
        websocketService.disconnect();
        wsDisconnectTimerRef.current = null;
      }, 150);
    };
  }, [addToast, setServers, setChannels, setPlayers, setChatMessages]);

  // Keep an exact subscription intent even while disconnected. The service
  // applies only the latest set after authentication/reconnection.
  useEffect(() => {
    const channelIds = desiredChannelSubscriptions(channels, activeTab, selectedChannelId);
    const desired = new Set(channelIds);
    desiredChannelIdsRef.current = desired;
    setChatMessages((previous) => {
      const filtered = previous.filter((message) => desired.has(message.channel));
      return filtered.length === previous.length ? previous : filtered;
    });
    websocketService.setSubscriptions(channelIds);
  }, [channels, activeTab, selectedChannelId, setChatMessages]);

  // After WS authenticates, request immediate snapshots (get_clients /
  // get_players / get_channels) so servers/players/channels populate right
  // away instead of waiting for the 30s periodic broadcast.
  useEffect(() => {
    if (wsState !== ConnectionState.AUTHENTICATED) return;
    websocketService.requestSnapshot();
  }, [wsState]);

  // Periodically refresh the access token before it expires so both REST and
  // WS reconnects always have a valid token. maybeRefreshToken no-ops while
  // the token is still fresh.
  useEffect(() => {
    const timer = setInterval(() => {
      authService.maybeRefreshToken(getApiBaseUrl());
    }, 60 * 1000);
    return () => clearInterval(timer);
  }, []);

  // Manual WS reconnect from the ERROR terminal state (resets retry counter).
  const handleManualReconnect = useCallback(() => {
    websocketService.manualReconnect().catch((err) => {
      addToast(tRef.current('common.ws_toast_failed', { error: err.message || err }), 'error');
    });
  }, [addToast]);

  return { wsState, handleManualReconnect };
}

export default useWsOrchestration;
