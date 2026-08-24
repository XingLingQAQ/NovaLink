import React, { useState, useEffect, useRef, useId } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../../lib/cn';

/**
 * Modal — legacy alias kept for call sites that import Modal.
 * Restyled to match the shadcn/ui reference Dialog look: bg-background,
 * rounded-lg border, text-base title, bg-black/20 backdrop.
 * Legacy `theme`/`mode` props are accepted but ignored (tokens auto-switch).
 *
 * A11y (PANEL-009): role="dialog" + aria-modal + aria-labelledby, ESC to
 * close, focus moves into the dialog panel when it opens, a focus trap keeps
 * Tab/Shift+Tab cycling only among focusable elements inside the panel, focus
 * is restored to the trigger that opened the dialog on close, and the
 * application root is made inert (aria-hidden + inert attribute) while the
 * dialog is open so background content is unreachable to both keyboard and
 * assistive tech. The change is self-contained and additive — the props/
 * children contract is unchanged.
 */
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

const Modal = ({ isOpen, onClose, title, children, theme: _theme, mode: _mode }) => {
  void _theme; void _mode;
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  const titleId = useId();
  const panelRef = useRef(null);
  const triggerRef = useRef(null);
  const rootNodeRef = useRef(null);

  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync mount with isOpen for enter animation
      setVisible(true);
    } else {
      const tm = setTimeout(() => setVisible(false), 300);
      return () => clearTimeout(tm);
    }
  }, [isOpen]);

  // Capture the element that had focus when the dialog opened (the trigger)
  // so we can return focus to it when the dialog closes.
  useEffect(() => {
    if (isOpen) {
      triggerRef.current = document.activeElement;
    }
  }, [isOpen]);

  // Make the application root (the #root sibling tree) inert while the dialog
  // is open so background content is unreachable by keyboard + screen readers.
  // We also set aria-hidden on the same nodes as a belt-and-braces fallback for
  // browsers that support inert but don't fully hide from AT yet. Restored on
  // close. React 19 supports the inert attribute in JSX, but we apply it via a
  // ref so this stays a self-contained effect that doesn't touch App.jsx.
  useEffect(() => {
    if (!isOpen) return;
    const root = document.getElementById('root');
    if (!root) return;
    rootNodeRef.current = root;
    const previouslyHidden = root.getAttribute('aria-hidden') === 'true';
    root.setAttribute('aria-hidden', 'true');
    try { root.inert = true; } catch { /* older browsers: aria-hidden still applies */ }
    return () => {
      if (!rootNodeRef.current) return;
      if (!previouslyHidden) rootNodeRef.current.removeAttribute('aria-hidden');
      try { rootNodeRef.current.inert = false; } catch { /* noop */ }
      rootNodeRef.current = null;
    };
  }, [isOpen]);

  // ESC closes the dialog (same guard semantics as the backdrop/close button).
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && onClose) {
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      // Focus trap: keep Tab/Shift+Tab cycling inside the dialog panel.
      const panel = panelRef.current;
      if (!panel) return;
      const focusable = Array.from(panel.querySelectorAll(FOCUSABLE_SELECTOR))
        .filter((el) => el.offsetParent !== null || el === document.activeElement);
      if (focusable.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (e.shiftKey) {
        if (active === first || !panel.contains(active)) {
          e.preventDefault();
          last.focus();
        }
      } else {
        if (active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Move focus into the panel when the dialog opens — prefer the first
  // focusable control, fall back to the panel container. Depends on `visible`
  // (not just `isOpen`) because the panel only mounts after the enter-animation
  // state flips to true, which happens one render after isOpen.
  useEffect(() => {
    if (isOpen && visible && panelRef.current) {
      const focusable = panelRef.current.querySelector(FOCUSABLE_SELECTOR);
      if (focusable) {
        // Defer one tick so any transition/portal content is painted.
        const id = window.requestAnimationFrame(() => focusable.focus());
        return () => window.cancelAnimationFrame(id);
      }
      panelRef.current.focus();
    }
  }, [isOpen, visible]);

  // Restore focus to the trigger when the dialog closes.
  useEffect(() => {
    if (!isOpen && triggerRef.current && typeof triggerRef.current.focus === 'function') {
      // Defer so the trigger is interactive again after the unmount/transition.
      const id = window.requestAnimationFrame(() => {
        try { triggerRef.current.focus(); } catch { /* noop */ }
      });
      return () => window.cancelAnimationFrame(id);
    }
  }, [isOpen]);

  if (!visible && !isOpen) return null;

  return createPortal(
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
          'relative w-[calc(100%-2rem)] max-w-[480px] max-h-[calc(100vh-2rem)] overflow-y-auto rounded-lg border border-border bg-background p-5 shadow-xl transition-all duration-200 outline-none',
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
    </div>,
    document.body
  );
};

export default Modal;
