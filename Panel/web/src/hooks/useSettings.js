import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react';
import { api } from '../services/api';
import { createSettingsStore } from './settingsStore';

export function useSettings({ sessionKey, addToast, t }) {
  const store = useMemo(() => createSettingsStore(api, undefined, sessionKey), [sessionKey]);
  useEffect(() => {
    store.setNotify((key, type, values) => addToast(t(key, values), type));
  }, [store, addToast, t]);
  useEffect(() => {
    store.activate();
    return store.dispose;
  }, [store]);
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  const toggle = useCallback((key) => {
    globalThis.navigator?.vibrate?.(5);
    return store.toggle(key);
  }, [store]);
  return {
    settings: state.settings,
    settingsLoading: state.loading,
    fetchSettings: store.fetchSettings,
    handleSettingChange: store.change,
    handleSettingToggle: toggle,
    handleWsSettingsUpdate: store.receive,
  };
}
