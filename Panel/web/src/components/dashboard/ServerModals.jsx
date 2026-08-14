/**
 * Server-related modals opened from ClientStatus: the details modal and the
 * disconnect confirm modal. Extracted from App.jsx unchanged in behavior.
 */

import React, { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';

import Modal from '../ui/Modal';
import Button from '../ui/Button';
import ServerDetailsContent from './ServerDetailsContent';

export function ServerDetailsModal({ server, mode, onClose }) {
  const { t } = useTranslation();
  return (
    <Modal
      isOpen={!!server}
      onClose={onClose}
      title={t('common.server_details_modal_title')}
      theme="clean"
      mode={mode}
    >
      {server && <ServerDetailsContent server={server} />}
      <div className="flex gap-2 mt-6 pt-4 border-t border-border">
        <Button variant="ghost" className="flex-1" theme="clean" mode={mode} onClick={onClose}>
          {t('common.confirm')}
        </Button>
      </div>
    </Modal>
  );
}

export function DisconnectConfirmModal({ target, mode, onClose, onDisconnect }) {
  const { t } = useTranslation();
  const [disconnecting, setDisconnecting] = useState(false);

  // Confirm + execute disconnect; closes only on success (failure toast is
  // shown by the disconnect handler and the modal stays open).
  const confirmDisconnect = useCallback(async () => {
    if (!target) return;
    setDisconnecting(true);
    try {
      await onDisconnect(target.id, target.name);
      onClose();
    } catch {
      // toast already shown by the disconnect handler
    } finally {
      setDisconnecting(false);
    }
  }, [target, onDisconnect, onClose]);

  return (
    <Modal
      isOpen={!!target}
      onClose={() => !disconnecting && onClose()}
      title={t('common.disconnect_modal_title')}
      theme="clean"
      mode={mode}
    >
      {target && (
        <p className="text-xs text-muted-foreground">
          {t('common.disconnect_confirm', { name: target.name || target.id })}
        </p>
      )}
      <div className="flex gap-2 mt-6 pt-4 border-t border-border">
        <Button
          variant="ghost"
          className="flex-1"
          theme="clean"
          mode={mode}
          onClick={onClose}
          disabled={disconnecting}
        >
          {t('common.cancel')}
        </Button>
        <Button
          variant="destructive"
          className="flex-1"
          theme="clean"
          mode={mode}
          onClick={confirmDisconnect}
          disabled={disconnecting}
        >
          {disconnecting ? <Loader2 size={14} className="animate-spin" /> : null}
          {t('common.disconnect')}
        </Button>
      </div>
    </Modal>
  );
}
