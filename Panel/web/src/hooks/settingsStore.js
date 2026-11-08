import { adaptSettingsResponse, buildSettingsUpdateBody, createInitialSettings, isValidSettingsValue } from '../lib/settingsContract';

export function createSettingsStore(api, notify = () => {}, sessionKey) {
  let state = { settings: createInitialSettings(), loading: false };
  let revision;
  let disposed = false;
  let saving = false;
  let fetches = 0;
  const listeners = new Set();
  const publish = (changes) => {
    if (disposed) return;
    state = { ...state, ...changes };
    listeners.forEach((listener) => listener());
  };
  const adopt = (response, incomingRevision = response?.revision) => {
    if (disposed || !response || typeof response !== 'object') return false;
    if (revision != null && (incomingRevision == null || incomingRevision < revision)) return false;
    const settings = adaptSettingsResponse(response);
    settings.revision = incomingRevision;
    revision = incomingRevision;
    publish({ settings });
    return true;
  };
  const fetchSettings = async () => {
    if (disposed) return;
    fetches++;
    publish({ loading: true });
    try { adopt(await api.getSettings()); }
    catch (error) {
      if (!disposed) notify('common.settings_load_failed', 'error', { error: error.message });
    } finally {
      fetches--;
      publish({ loading: fetches > 0 || saving });
    }
  };
  const change = async (key, value) => {
    const previous = state.settings;
    if (disposed || saving || !previous.supported?.[key] || !isValidSettingsValue(key, value)) return false;
    const body = buildSettingsUpdateBody({ ...previous, [key]: value }, [key]);
    saving = true;
    const optimistic = { ...previous, [key]: value };
    publish({ settings: optimistic, loading: true });
    try {
      const response = await api.updateSettings(body, previous.revision);
      if (disposed) return false;
      adopt(response);
      notify('common.settings_save_success', 'success');
      return true;
    } catch (error) {
      if (disposed) return false;
      if (error.status === 409) {
        if (error.data && typeof error.data === 'object') adopt(error.data);
        else await fetchSettings();
        notify('common.settings_conflict', 'error');
      } else {
        if (state.settings === optimistic) publish({ settings: previous });
        notify('common.settings_save_failed', 'error', { error: error.message });
      }
      return false;
    } finally {
      saving = false;
      publish({ loading: fetches > 0 });
    }
  };
  return {
    sessionKey,
    setNotify: (listener) => { notify = listener; },
    getSnapshot: () => state,
    subscribe: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
    dispose: () => { disposed = true; listeners.clear(); },
    activate: () => { disposed = false; },
    fetchSettings,
    change,
    toggle: (key) => typeof state.settings[key] === 'boolean' ? change(key, !state.settings[key]) : Promise.resolve(false),
    receive: (message) => {
      if (disposed || !message || typeof message !== 'object') return;
      const incoming = message.settingsRevision;
      if (typeof incoming === 'number' && revision != null && incoming <= revision) return;
      if (message.settings && typeof message.settings === 'object') {
        if (!adopt(message.settings, incoming)) return;
      } else {
        if (typeof incoming === 'number') revision = incoming;
        void fetchSettings();
      }
      notify('common.settings_update_remote', 'info', { revision: incoming ?? '?' });
    },
  };
}
