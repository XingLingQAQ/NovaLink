import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';

const Modal = ({ isOpen, onClose, title, children, theme, mode }) => {
    const [visible, setVisible] = useState(false);
    useEffect(() => {
        if (isOpen) setVisible(true);
        else setTimeout(() => setVisible(false), 300);
    }, [isOpen]);
    if (!visible && !isOpen) return null;

    let backdropClass = "fixed inset-0 z-[60] flex items-center justify-center p-4 transition-all duration-300 ";
    let contentClass = "relative w-full max-w-md rounded-2xl shadow-2xl transform transition-all duration-300 ";
    backdropClass += isOpen ? "opacity-100" : "opacity-0";

    if (theme === 'clean') {
        backdropClass += " bg-slate-900/20 backdrop-blur-sm";
        contentClass += mode === 'dark' ? " bg-slate-800 border border-slate-700 text-white" : " bg-white border border-slate-100 text-slate-800";
    } else {
        backdropClass += " bg-black/40 backdrop-blur-md";
        contentClass += mode === 'dark' ? " bg-black/60 border border-white/10 text-white backdrop-blur-xl" : " bg-white/60 border border-white/40 text-slate-900 backdrop-blur-xl";
    }
    contentClass += isOpen ? " scale-100 opacity-100" : " scale-95 opacity-0";
    const txtSec = mode === 'dark' ? 'text-slate-400' : 'text-slate-500';

    return (
        <div className={backdropClass} onClick={onClose}>
            <div className={contentClass} onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between p-6 border-b border-gray-200/10">
                    <h3 className="text-xl font-bold">{title}</h3>
                    <button onClick={onClose} className={`p-1.5 rounded-full transition-colors hover:bg-current/10 ${txtSec}`}>
                        <X size={20} />
                    </button>
                </div>
                <div className="p-6">{children}</div>
            </div>
        </div>
    );
};

export default Modal;
