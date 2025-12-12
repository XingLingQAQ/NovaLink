import React from 'react';

const Card = ({ children, className = "", theme, mode }) => {
    let styles = "rounded-2xl transition-all duration-500 ease-out ";
    if (theme === 'clean') {
        styles += mode === 'dark'
            ? "bg-slate-800 border border-slate-700 shadow-lg text-white "
            : "bg-white border border-slate-100 shadow-lg shadow-slate-200/50 text-slate-800 ";
    } else {
        styles += mode === 'dark'
            ? "bg-black/40 border border-white/10 backdrop-blur-xl shadow-2xl text-white "
            : "bg-white/40 border border-white/40 backdrop-blur-xl shadow-xl text-slate-900 ";
    }
    return <div className={`${styles} ${className}`}>{children}</div>;
};

export default Card;
