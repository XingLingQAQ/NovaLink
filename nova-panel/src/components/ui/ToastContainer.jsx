import React from 'react';
import { CheckCircle, AlertCircle, Loader2, X } from 'lucide-react';

const ToastContainer = ({ toasts, removeToast }) => {
    return (
        <div className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 pointer-events-none">
            {toasts.map((toast) => (
                <div
                    key={toast.id}
                    className={`
    flex items-center gap-3 px-4 py-3 rounded-xl shadow-2xl backdrop-blur-md border pointer-events-auto transition-all duration-500 animate-in slide-in-from-right-10 fade-in
    ${toast.type === 'success' ? 'bg-emerald-500/90 text-white border-emerald-400/50' : ''}
    ${toast.type === 'error' ? 'bg-rose-500/90 text-white border-rose-400/50' : ''}
    ${toast.type === 'loading' ? 'bg-blue-500/90 text-white border-blue-400/50' : ''}
  `}
                >
                    {toast.type === 'success' && <CheckCircle size={18} />}
                    {toast.type === 'error' && <AlertCircle size={18} />}
                    {toast.type === 'loading' && <Loader2 size={18} className="animate-spin" />}
                    <span className="text-sm font-medium">{toast.message}</span>
                    <button onClick={() => removeToast(toast.id)} className="opacity-70 hover:opacity-100 ml-2">
                        <X size={14} />
                    </button>
                </div>
            ))}
        </div>
    );
};

export default ToastContainer;
