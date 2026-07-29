import React from 'react';

const Switch = ({ checked, onChange, theme, mode }) => {
    return (
        <div
            className={`w-12 h-6 rounded-full p-1 cursor-pointer transition-colors duration-300 ${checked ? 'bg-sky-500' : (mode === 'dark' ? 'bg-slate-600' : 'bg-slate-300')}`}
            onClick={() => onChange(!checked)}
        >
            <div className={`w-4 h-4 bg-white rounded-full shadow-sm transition-transform duration-300 ${checked ? 'translate-x-6' : ''}`} />
        </div>
    );
};

export default Switch;
