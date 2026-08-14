/**
 * Word Filter Management — chat sensitive-word / regex filter configuration.
 *
 * Backed by GET/PUT /api/filter (locked contract). The `enabled` flag is the
 * same backend state as the "filter enabled" toggle on the Settings page.
 * Words and regex patterns are edited as one-entry-per-line textareas (the
 * simple, reliable option) and submitted as full-replacement arrays on Save.
 * Regex patterns are displayed and submitted verbatim — never escaped or
 * executed client-side.
 *
 * Page is gated by the `filter.manage` capability (ADMIN / SUPER_ADMIN).
 * Degrades gracefully when the backend endpoint is not deployed yet: a load
 * failure shows an inline error hint over an empty form — never a blank page.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Save, Loader2, AlertCircle } from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Switch from '../ui/Switch';
import { api } from '../../services/api';

const textareaClass =
  'flex w-full min-h-40 rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs font-mono transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring text-foreground resize-y';

// One entry per line; blank lines are dropped.
function toLines(text) {
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}

function looksLikeFilterState(res) {
  return !!res && (typeof res.enabled === 'boolean' || Array.isArray(res.words) || Array.isArray(res.patterns));
}

function FilterManagement({ theme, mode, onToast }) {
  const { t } = useTranslation();

  const [enabled, setEnabled] = useState(false);
  const [wordsText, setWordsText] = useState('');
  const [patternsText, setPatternsText] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const applyState = (res) => {
    setEnabled(!!res.enabled);
    setWordsText(Array.isArray(res.words) ? res.words.join('\n') : '');
    setPatternsText(Array.isArray(res.patterns) ? res.patterns.join('\n') : '');
  };

  const fetchFilter = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getFilter();
      if (looksLikeFilterState(res)) applyState(res);
    } catch (err) {
      // Endpoint missing (404) or network failure: keep the empty form and
      // show one inline error hint instead of a blank page.
      setError(err.message || String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchFilter();
  }, [fetchFilter]);

  // Full-replacement PUT: enabled + both arrays in one submit.
  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await api.updateFilter({
        enabled,
        words: toLines(wordsText),
        patterns: toLines(patternsText),
      });
      // Sync back the authoritative state returned by the backend.
      if (looksLikeFilterState(res)) applyState(res);
      setError(null);
      if (onToast) onToast(t('filter.toast_save_success'), 'success');
    } catch (err) {
      if (onToast) onToast(t('filter.toast_save_failed', { error: err.message }), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('filter.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('filter.subtitle')}</p>
        </div>
        <Button theme={theme} mode={mode} variant="default" onClick={handleSave} disabled={saving || loading}>
          {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
          {t('common.save')}
        </Button>
      </div>

      {/* Error hint (endpoint missing / network failure) */}
      {error && (
        <Card className="p-3 border-destructive/30 bg-destructive/5">
          <div className="flex items-center gap-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{t('filter.load_failed', { error })}</p>
          </div>
        </Card>
      )}

      {loading ? (
        <Card className="p-12 text-center">
          <Loader2 size={24} className="mx-auto animate-spin text-muted-foreground" />
        </Card>
      ) : (
        <>
          {/* Enabled toggle */}
          <Card className="p-4">
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-sm font-medium text-foreground">{t('filter.enabled_label')}</p>
                <p className="text-xs text-muted-foreground mt-1">{t('filter.enabled_desc')}</p>
              </div>
              <Switch checked={enabled} onChange={setEnabled} />
            </div>
          </Card>

          {/* Custom words */}
          <Card className="p-4 space-y-2">
            <div>
              <p className="text-sm font-medium text-foreground">{t('filter.words_label')}</p>
              <p className="text-xs text-muted-foreground mt-1">{t('filter.words_desc')}</p>
            </div>
            <textarea
              value={wordsText}
              onChange={(e) => setWordsText(e.target.value)}
              placeholder={t('filter.words_placeholder')}
              className={textareaClass}
              spellCheck={false}
            />
          </Card>

          {/* Regex patterns — shown verbatim, never escaped or executed */}
          <Card className="p-4 space-y-2">
            <div>
              <p className="text-sm font-medium text-foreground">{t('filter.patterns_label')}</p>
              <p className="text-xs text-muted-foreground mt-1">{t('filter.patterns_desc')}</p>
            </div>
            <textarea
              value={patternsText}
              onChange={(e) => setPatternsText(e.target.value)}
              placeholder={t('filter.patterns_placeholder')}
              className={textareaClass}
              spellCheck={false}
            />
          </Card>
        </>
      )}
    </div>
  );
}

export default FilterManagement;
