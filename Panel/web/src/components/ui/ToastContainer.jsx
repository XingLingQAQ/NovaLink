import React from 'react';
import { CheckCircle, AlertCircle, Loader2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/**
 * Toast container — restyled to match the reference's pill/rounded-full idiom.
 * Uses opaque token-tinted backgrounds + white text for readability on either theme.
 * aria-live="polite" so screen readers announce new toasts.
 */
const ToastContainer = ({ toasts, removeToast }) => {
  const { t } = useTranslation();
  return (
    <div aria-live="polite" className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`flex items-center gap-2.5 rounded-full border px-4 py-2 shadow-md pointer-events-auto transition-all duration-300 animate-in slide-in-from-right-10 fade-in text-xs font-medium ${
            toast.type === 'success'
              ? 'bg-emerald-600 text-white border-emerald-500/50'
              : toast.type === 'error'
                ? 'bg-destructive text-destructive-foreground border-destructive/50'
                : 'bg-primary text-primary-foreground border-primary/50'
          }`}
        >
          {toast.type === 'success' && <CheckCircle size={16} />}
          {toast.type === 'error' && <AlertCircle size={16} />}
          {toast.type === 'loading' && <Loader2 size={16} className="animate-spin" />}
          <span>{toast.message}</span>
          <button onClick={() => removeToast(toast.id)} className="opacity-70 hover:opacity-100 ml-1" aria-label={t('common.close')}>
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
};

export default ToastContainer;
