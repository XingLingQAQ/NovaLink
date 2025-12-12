import React, { useState, useEffect, useRef } from 'react';
import { ChevronDown } from 'lucide-react';

const CustomSelect = ({ options, defaultValue, theme, mode, onChange }) => {
    const [isOpen, setIsOpen] = useState(false);
    const [selected, setSelected] = useState(defaultValue);
    const dropdownRef = useRef(null);

    useEffect(() => {
        const handleClickOutside = (event) => {
            if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    // Update selected when defaultValue changes (optional, but good practice)
    useEffect(() => {
        if (defaultValue) setSelected(defaultValue);
    }, [defaultValue]);

    let triggerStyles = "flex items-center justify-between w-full gap-2 text-sm font-medium cursor-pointer px-3 py-2.5 rounded-xl transition-all duration-300 border ";
    let menuStyles = "absolute top-full right-0 mt-2 w-full rounded-xl overflow-hidden z-50 shadow-xl backdrop-blur-xl transition-all duration-200 origin-top transform ";
    let itemStyles = "px-4 py-2 text-sm cursor-pointer transition-colors duration-200 ";

    if (theme === 'clean') {
        if (mode === 'dark') {
            triggerStyles += "bg-slate-700 border-slate-600 text-slate-200 hover:bg-slate-600";
            menuStyles += "bg-slate-800 border border-slate-700 text-slate-200";
            itemStyles += "hover:bg-slate-700 hover:text-white";
        } else {
            triggerStyles += "bg-white border-slate-200 text-slate-600 hover:border-blue-400";
            menuStyles += "bg-white border border-slate-100 text-slate-600";
            itemStyles += "hover:bg-slate-50 hover:text-blue-600";
        }
    } else {
        if (mode === 'dark') {
            triggerStyles += "bg-black/20 border-white/10 text-white hover:bg-black/30";
            menuStyles += "bg-black/60 border border-white/10 text-slate-200";
            itemStyles += "hover:bg-white/10 hover:text-white";
        } else {
            triggerStyles += "bg-white/40 border-white/40 text-slate-900 hover:bg-white/50";
            menuStyles += "bg-white/40 border border-white/40 text-slate-800";
            itemStyles += "hover:bg-white/50 hover:text-slate-900";
        }
    }

    const menuAnimation = isOpen
        ? "opacity-100 scale-100 translate-y-0"
        : "opacity-0 scale-95 -translate-y-2 pointer-events-none";

    return (
        <div className="relative w-full" ref={dropdownRef}>
            <div className={triggerStyles} onClick={() => setIsOpen(!isOpen)}>
                <span>{selected}</span>
                <ChevronDown size={14} className={`transition-transform duration-300 ${isOpen ? 'rotate-180' : ''}`} />
            </div>
            <div className={`${menuStyles} ${menuAnimation}`}>
                {options.map((opt, i) => (
                    <div
                        key={i}
                        className={`${itemStyles} ${selected === opt ? (theme === 'clean' && mode === 'light' ? 'text-blue-600 bg-blue-50 font-medium' : 'bg-white/5 font-medium') : ''}`}
                        onClick={() => {
                            setSelected(opt);
                            if (onChange) onChange(opt);
                            setIsOpen(false);
                        }}
                    >
                        {opt}
                    </div>
                ))}
            </div>
        </div>
    );
};

export default CustomSelect;
