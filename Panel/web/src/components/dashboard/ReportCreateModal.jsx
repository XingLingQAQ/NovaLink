import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Send, AlertCircle } from 'lucide-react';
import Modal from '../ui/Modal';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Label from '../ui/Label';
import CustomSelect from '../ui/CustomSelect';
import { api } from '../../services/api';

const MAX_REASON_TEXT = 1024;
const MAX_EVIDENCE_SNAPSHOT = 1024;

// Stable reason-code list for the dropdown. Kept in sync with the backend
// moderation enum. Unknown codes added later by the backend still POST fine
// (the dropdown just won't have a friendly label for them).
const REASON_CODES = [
  { value: '', label: 'report.reason_code_placeholder' },
  { value: 'SPAM', label: 'report.reason_code_spam' },
  { value: 'ABUSE', label: 'report.reason_code_abuse' },
  { value: 'HARASSMENT', label: 'report.reason_code_harassment' },
  { value: 'CHEATING', label: 'report.reason_code_cheating' },
  { value: 'ADVERTISING', label: 'report.reason_code_advertising' },
  { value: 'OTHER', label: 'report.reason_code_other' },
];

const EMPTY_DRAFT = {
  reportedPlayerId: '',
  reasonCode: '',
  reasonText: '',
  originChannelId: '',
  evidenceSnapshot: '',
};

/**
 * Report Create Modal (PANEL-007).
 *
 * Form for creating a moderation case via POST /api/reports. Requires a
 * reported player id + a reason code + a reason text. originChannelId and
 * evidenceSnapshot are optional. Frontend length validation mirrors the
 * backend caps (reasonText / evidenceSnapshot ≤ 1024) so we never send an
 * over-length payload that the backend would reject.
 *
 * On success the modal closes and onToast surfaces the new caseId. On failure
 * the backend error message is surfaced inline + via toast.
 */
function ReportCreateModal({ isOpen, onClose, theme, mode, onToast }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [pending, setPending] = useState(false);
  const [formError, setFormError] = useState(null);

  // Reset the form whenever the modal opens.
  useEffect(() => {
    if (isOpen) {
      setDraft(EMPTY_DRAFT);
      setFormError(null);
    }
  }, [isOpen]);

  const setField = useCallback((key) => (value) => {
    setDraft((prev) => ({ ...prev, [key]: value }));
    setFormError(null);
  }, []);

  const handleReasonTextChange = useCallback((e) => {
    const v = e.target.value.slice(0, MAX_REASON_TEXT);
    setDraft((prev) => ({ ...prev, reasonText: v }));
    setFormError(null);
  }, []);

  const handleEvidenceChange = useCallback((e) => {
    const v = e.target.value.slice(0, MAX_EVIDENCE_SNAPSHOT);
    setDraft((prev) => ({ ...prev, evidenceSnapshot: v }));
    setFormError(null);
  }, []);

  const canSubmit =
    draft.reportedPlayerId.trim() &&
    draft.reasonCode &&
    draft.reasonText.trim().length > 0 &&
    draft.reasonText.length <= MAX_REASON_TEXT &&
    draft.evidenceSnapshot.length <= MAX_EVIDENCE_SNAPSHOT;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    setPending(true);
    setFormError(null);
    try {
      const body = {
        reportedPlayerId: draft.reportedPlayerId.trim(),
        reasonCode: draft.reasonCode,
        reasonText: draft.reasonText.trim(),
      };
      if (draft.originChannelId.trim()) body.originChannelId = draft.originChannelId.trim();
      if (draft.evidenceSnapshot.trim()) body.evidenceSnapshot = draft.evidenceSnapshot.trim();
      const res = await api.createReport(body);
      if (onToast) onToast(t('report.toast_created', { caseId: res && res.caseId || '' }), 'success');
      onClose();
    } catch (err) {
      setFormError(err.message || String(err));
      if (onToast) onToast(t('report.toast_create_failed', { error: err.message }), 'error');
    } finally {
      setPending(false);
    }
  }, [canSubmit, draft, onToast, t, onClose]);

  return (
    <Modal
      isOpen={isOpen}
      onClose={() => !pending && onClose()}
      title={t('report.create_title')}
      theme={theme}
      mode={mode}
    >
      <div className="space-y-3">
        {formError && (
          <div className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-2 text-destructive">
            <AlertCircle size={14} className="shrink-0" />
            <p className="text-xs">{formError}</p>
          </div>
        )}

        <div className="space-y-1.5">
          <Label>{t('report.reported_player_id')}</Label>
          <Input
            value={draft.reportedPlayerId}
            onChange={(e) => setField('reportedPlayerId')(e.target.value)}
            placeholder={t('report.reported_player_id_placeholder')}
          />
        </div>

        <div className="space-y-1.5">
          <Label>{t('report.reason_code')}</Label>
          <CustomSelect
            theme={theme}
            mode={mode}
            options={REASON_CODES.map((o) => ({ value: o.value, label: t(o.label) }))}
            defaultValue={draft.reasonCode}
            onChange={setField('reasonCode')}
            aria-label={t('report.reason_code')}
          />
        </div>

        <div className="space-y-1.5">
          <Label>{t('report.reason_text')}</Label>
          <textarea
            className="flex w-full rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            rows={4}
            value={draft.reasonText}
            onChange={handleReasonTextChange}
            placeholder={t('report.reason_text_placeholder')}
            maxLength={MAX_REASON_TEXT}
          />
          <p className="text-[10px] text-muted-foreground">{draft.reasonText.length}/{MAX_REASON_TEXT}</p>
        </div>

        <div className="space-y-1.5">
          <Label>{t('report.origin_channel_id_optional')}</Label>
          <Input
            value={draft.originChannelId}
            onChange={(e) => setField('originChannelId')(e.target.value)}
            placeholder={t('report.origin_channel_id_placeholder')}
          />
        </div>

        <div className="space-y-1.5">
          <Label>{t('report.evidence_snapshot_optional')}</Label>
          <textarea
            className="flex w-full rounded-md border-0 bg-secondary/55 px-3 py-2 text-xs transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            rows={3}
            value={draft.evidenceSnapshot}
            onChange={handleEvidenceChange}
            placeholder={t('report.evidence_snapshot_placeholder')}
            maxLength={MAX_EVIDENCE_SNAPSHOT}
          />
          <p className="text-[10px] text-muted-foreground">{draft.evidenceSnapshot.length}/{MAX_EVIDENCE_SNAPSHOT}</p>
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" theme={theme} mode={mode} onClick={onClose} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button theme={theme} mode={mode} onClick={handleSubmit} disabled={pending || !canSubmit}>
            {pending ? <Loader2 size={12} className="animate-spin" /> : <Send size={12} />}
            {t('report.submit')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

export default ReportCreateModal;
