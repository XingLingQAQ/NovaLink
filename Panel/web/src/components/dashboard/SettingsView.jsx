/**
 * Settings view — appearance (local), connection status (read-only) and the
 * backend chat-feature toggles. Extracted from App.jsx unchanged in behavior;
 * the backend toggles become read-only when the role lacks `settings.edit`.
 */

import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Sun, Moon, Loader2 } from 'lucide-react';

import Card from '../ui/Card';
import Switch from '../ui/Switch';
import { ConnectionState } from '../../services/websocket';
import { can } from '../../lib/permissions';
import {
  isValidSettingsValue,
  MESSAGE_LOG_RETENTION_MAX_DAYS,
} from '../../lib/settingsContract';

function SettingSwitchRow({
  id,
  label,
  description,
  checked,
  onChange,
  disabled,
  supported = true,
  unsupportedLabel,
  theme,
  mode,
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <span id={`${id}-label`} className="text-sm text-foreground">{label}</span>
        <p id={`${id}-description`} className="text-xs text-muted-foreground mt-0.5">{description}</p>
      </div>
      {supported ? (
        <Switch
          checked={checked}
          onChange={onChange}
          theme={theme}
          mode={mode}
          disabled={disabled}
          aria-labelledby={`${id}-label`}
          aria-describedby={`${id}-description`}
        />
      ) : (
        <span className="shrink-0 text-[11px] text-muted-foreground">{unsupportedLabel}</span>
      )}
    </div>
  );
}

function SettingsView({
  theme,
  mode,
  settings,
  onToggle,
  onChange,
  settingsLoading,
  setMode,
  modeState,
  wsState,
  apiUrl,
  wsUrl,
  role,
}) {
  const { t } = useTranslation();
  const canEdit = can(role, 'settings.edit');
  const privateMessagesSupported = settings.supported?.privateMessagesEnabled === true;
  const retentionSupported = settings.supported?.messageLogRetentionDays === true;
  const [retentionDraft, setRetentionDraft] = useState('');
  const [retentionSaving, setRetentionSaving] = useState(false);

  useEffect(() => {
    setRetentionDraft(
      retentionSupported && Number.isInteger(settings.messageLogRetentionDays)
        ? String(settings.messageLogRetentionDays)
        : '',
    );
  }, [retentionSupported, settings.messageLogRetentionDays]);

  const retentionValue = Number(retentionDraft);
  const retentionValid = retentionDraft.trim() !== ''
    && isValidSettingsValue('messageLogRetentionDays', retentionValue);
  const retentionDirty = retentionValid && retentionValue !== settings.messageLogRetentionDays;

  const saveRetention = async () => {
    if (!canEdit || !retentionSupported || !retentionDirty || !onChange) return;
    setRetentionSaving(true);
    try {
      await onChange('messageLogRetentionDays', retentionValue);
    } finally {
      setRetentionSaving(false);
    }
  };

  const wsLabel = (() => {
    switch (wsState) {
      case ConnectionState.AUTHENTICATED: return t('common.ws_state_authenticated');
      case ConnectionState.CONNECTED: return t('common.ws_state_connected');
      case ConnectionState.CONNECTING: return t('common.ws_state_connecting');
      case ConnectionState.RECONNECTING: return t('common.ws_state_reconnecting');
      case ConnectionState.ERROR: return t('common.ws_state_error');
      default: return t('common.ws_state_disconnected');
    }
  })();

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div>
        <h2 className="text-xl font-medium text-foreground">{t('common.settings_title')}</h2>
        <p className="text-xs text-muted-foreground mt-1">{t('common.settings_subtitle')}</p>
      </div>

      <Card className="p-6 space-y-6">
        <div>
          <h3 className="text-sm font-medium mb-3 text-foreground">{t('common.settings_appearance')}</h3>
          <div className="flex items-center justify-between">
            <div>
              <span className="text-sm text-foreground">{t('common.settings_theme') || 'Theme'}</span>
              <p className="text-xs text-muted-foreground mt-0.5">{t('common.settings_local_only')}</p>
            </div>
            <div className="flex items-center p-0.5 rounded-full gap-0.5 border border-border bg-muted/60">
              <button type="button" aria-pressed={modeState === 'light'} onClick={() => setMode('light')} className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${modeState === 'light' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}>
                <Sun size={12} className="inline mr-1" />Light
              </button>
              <button type="button" aria-pressed={modeState === 'dark'} onClick={() => setMode('dark')} className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${modeState === 'dark' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}>
                <Moon size={12} className="inline mr-1" />Dark
              </button>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-border">
          <h3 className="text-sm font-medium mb-3 text-foreground">{t('common.settings_connection')}</h3>
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_api_address')}</span>
              <span className="text-xs font-mono text-muted-foreground">{apiUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_ws_address')}</span>
              <span className="text-xs font-mono text-muted-foreground">{wsUrl}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-foreground">{t('common.settings_ws_state')}</span>
              <span className="text-xs text-muted-foreground">{wsLabel}</span>
            </div>
          </div>
        </div>

        <div className="pt-6 border-t border-border">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-medium text-foreground">{t('common.settings_chat_features')}</h3>
            <div className="flex items-center gap-2">
              {!canEdit && (
                <span className="text-[11px] text-muted-foreground">{t('common.settings_read_only')}</span>
              )}
              {settingsLoading && (
                <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
                  <Loader2 size={11} className="animate-spin" />
                  {t('common.settings_loading')}
                </span>
              )}
            </div>
          </div>
          <div className="space-y-3">
            <SettingSwitchRow
              id="settings-filter"
              label={t('common.settings_filter')}
              description={t('common.settings_filter_desc')}
              checked={settings.enableFilter === true}
              onChange={() => onToggle('enableFilter')}
              disabled={settingsLoading || !canEdit}
              theme={theme}
              mode={mode}
            />
            <SettingSwitchRow
              id="settings-log"
              label={t('common.settings_log')}
              description={t('common.settings_log_desc')}
              checked={settings.logMessages === true}
              onChange={() => onToggle('logMessages')}
              disabled={settingsLoading || !canEdit}
              theme={theme}
              mode={mode}
            />
            <SettingSwitchRow
              id="settings-cross-server"
              label={t('common.settings_cross_server')}
              description={t('common.settings_cross_server_desc')}
              checked={settings.crossServerChat === true}
              onChange={() => onToggle('crossServerChat')}
              disabled={settingsLoading || !canEdit}
              theme={theme}
              mode={mode}
            />
            <SettingSwitchRow
              id="settings-private-messages"
              label={t('common.settings_private_messages')}
              description={t('common.settings_private_messages_desc')}
              checked={settings.privateMessagesEnabled === true}
              onChange={() => onToggle('privateMessagesEnabled')}
              disabled={settingsLoading || !canEdit}
              supported={privateMessagesSupported}
              unsupportedLabel={t('common.settings_backend_unsupported')}
              theme={theme}
              mode={mode}
            />
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <label
                  htmlFor="settings-message-retention"
                  className="text-sm text-foreground"
                >
                  {t('common.settings_retention')}
                </label>
                <p id="settings-message-retention-description" className="text-xs text-muted-foreground mt-0.5">
                  {t('common.settings_retention_desc', { max: MESSAGE_LOG_RETENTION_MAX_DAYS })}
                </p>
                {retentionSupported && !retentionValid && (
                  <p id="settings-message-retention-error" className="text-xs text-destructive mt-1" role="alert">
                    {t('common.settings_retention_invalid', { max: MESSAGE_LOG_RETENTION_MAX_DAYS })}
                  </p>
                )}
              </div>
              {retentionSupported ? (
                <div className="flex shrink-0 items-center gap-2">
                  <input
                    id="settings-message-retention"
                    type="number"
                    min="0"
                    max={MESSAGE_LOG_RETENTION_MAX_DAYS}
                    step="1"
                    value={retentionDraft}
                    onChange={(event) => setRetentionDraft(event.target.value)}
                    disabled={settingsLoading || retentionSaving || !canEdit}
                    aria-describedby={`settings-message-retention-description${retentionValid ? '' : ' settings-message-retention-error'}`}
                    aria-invalid={retentionValid ? undefined : true}
                    className="h-8 w-24 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs text-foreground transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  />
                  {canEdit && (
                    <button
                      type="button"
                      onClick={saveRetention}
                      disabled={settingsLoading || retentionSaving || !retentionDirty}
                      className="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/20 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {retentionSaving && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
                      {t('common.save')}
                    </button>
                  )}
                </div>
              ) : (
                <span className="shrink-0 text-[11px] text-muted-foreground">
                  {t('common.settings_backend_unsupported')}
                </span>
              )}
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}

export default SettingsView;
