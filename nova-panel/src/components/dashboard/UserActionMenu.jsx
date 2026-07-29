import React, { useState, useEffect, useRef } from 'react';
import { MoreHorizontal, Mail, Edit, Trash2 } from 'lucide-react';

const UserActionMenu = ({ user, theme, mode, onDelete, onEmail }) => {
    const [isOpen, setIsOpen] = useState(false);
    const menuRef = useRef(null);

    useEffect(() => {
        const handleClickOutside = (event) => {
            if (menuRef.current && !menuRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    let menuStyles = "absolute top-full right-0 mt-1 w-32 rounded-xl overflow-hidden z-20 shadow-xl backdrop-blur-xl border origin-top-right transition-all duration-200 ";
    let itemStyles = "px-3 py-2 text-xs flex items-center gap-2 cursor-pointer transition-colors ";

    if (theme === 'clean') {
        menuStyles += mode === 'dark' ? "bg-slate-800 border-slate-700" : "bg-white border-slate-100";
        itemStyles += mode === 'dark' ? "hover:bg-slate-700 text-slate-200" : "hover:bg-slate-50 text-slate-600";
    } else {
        menuStyles += mode === 'dark' ? "bg-black/80 border-white/10" : "bg-white/80 border-white/40";
        itemStyles += mode === 'dark' ? "hover:bg-white/20 text-white" : "hover:bg-black/10 text-slate-800";
    }

    if (!isOpen) menuStyles += " opacity-0 scale-95 pointer-events-none";
    else menuStyles += " opacity-100 scale-100";

    return (
        <div className="relative" ref={menuRef}>
            <button
                onClick={() => setIsOpen(!isOpen)}
                className={`p-1.5 rounded-lg transition-colors ${mode === 'dark' ? 'text-slate-400 hover:text-white hover:bg-white/10' : 'text-slate-500 hover:text-slate-900 hover:bg-slate-100'}`}
            >
                <MoreHorizontal size={18} />
            </button>
            <div className={menuStyles}>
                <div className={itemStyles} onClick={() => { setIsOpen(false); onEmail(user); }}>
                    <Mail size={14} /> Email
                </div>
                <div className={itemStyles} onClick={() => { setIsOpen(false); /* Edit logic */ }}>
                    <Edit size={14} /> Edit
                </div>
                <div className={`${itemStyles} text-rose-500 hover:text-rose-600`} onClick={() => { setIsOpen(false); onDelete(user); }}>
                    <Trash2 size={14} /> Delete
                </div>
            </div>
        </div>
    );
};

export default UserActionMenu;
