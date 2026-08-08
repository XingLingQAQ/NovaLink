import React from 'react';

const Button = ({ children, variant = 'primary', theme, mode, onClick, className = "", disabled = false }) => {
    let base = "px-4 py-2 rounded-xl font-medium transition-all duration-300 active:scale-95 flex items-center justify-center gap-2 ";
    if (disabled) {
        base += "opacity-50 cursor-not-allowed ";
    }
    if (variant === 'primary') {
        if (theme === 'clean') {
            base += "bg-blue-600 hover:bg-blue-700 text-white shadow-md shadow-blue-600/20 ";
        } else {
            base += mode === 'dark'
                ? "bg-white/10 hover:bg-white/20 border border-white/20 text-white backdrop-blur-md "
                : "bg-black/5 hover:bg-black/10 border border-white/30 text-slate-900 backdrop-blur-md ";
        }
    } else if (variant === 'ghost') {
        base += "hover:bg-current/10 ";
    } else if (variant === 'outline') {
        if (theme === 'clean') {
            base += mode === 'dark' ? "border border-slate-600 hover:bg-slate-700 text-white " : "border border-slate-200 hover:bg-slate-50 text-slate-800 ";
        } else {
            base += "border border-white/20 hover:bg-white/10 text-current ";
        }
    } else if (variant === 'danger') {
        base += "bg-rose-500/10 text-rose-500 hover:bg-rose-500 hover:text-white ";
    }

    return <button onClick={disabled ? undefined : onClick} disabled={disabled} className={`${base} ${className}`}>{children}</button>;
};

export default Button;
