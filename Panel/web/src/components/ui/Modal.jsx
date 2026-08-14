import React, { useState, useEffect, useRef, useId } from 'react';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/cn';

/**
 * Modal — legacy alias kept for call sites that import Modal.
 * Restyled to match the shadcn/ui reference Dialog look: bg-background,
 * rounded-lg border, text-base title, bg-black/20 backdrop.
 * Legacy `theme`/`mode` props are accepted but ignored (tokens auto-switch).
 *
 * A11y: role="dialog" + aria-modal + aria-labelledby, ESC to close, and focus
 * moves into the dialog panel when it opens (minimal focus management).
 */
const Modal = ({ isOpen, onClose, title, children, theme: _theme, mode: _mode }) => {
  void _theme; void _mode;
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  const titleId = useId();
  const panelRef = useRef(null);

  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync mount with isOpen for enter animation
      setVisible(true);
    } else {
      const tm = setTimeout(() => setVisible(false), 300);
      return () => clearTimeout(tm);
    }
  }, [isOpen]);

  // ESC closes the dialog (same guard semantics as the backdrop/close button).
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && onClose) onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Move focus into the panel when the dialog opens.
  useEffect(() => {
    if (isOpen && panelRef.current) {
      panelRef.current.focus();
    }
  }, [isOpen]);

  if (!visible && !isOpen) return null;

  return (
    <div
      className={cn(
        'fixed inset-0 z-[60] flex items-center justify-center p-4 transition-all duration-300',
        isOpen ? 'opacity-100' : 'opacity-0',
        'bg-black/20 backdrop-blur-[1px]'
      )}
      onClick={onClose}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={cn(
          'relative w-[calc(100%-2rem)] max-w-[480px] rounded-lg border border-border bg-background p-5 shadow-xl transition-all duration-200 outline-none',
          isOpen ? 'scale-100 opacity-100' : 'scale-95 opacity-0'
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-col space-y-1.5 text-center sm:text-left mb-4">
          <h3 id={titleId} className="text-base font-medium leading-none">{title}</h3>
        </div>
        <div>{children}</div>
        <button
          type="button"
          onClick={onClose}
          className="absolute right-4 top-4 rounded-sm text-muted-foreground opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring"
          aria-label={t('common.close')}
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
};

export default Modal;
