import React from 'react';

const NotificationDropdown = ({ isOpen, onClose, theme, mode, notifications, onMarkAllRead, onClearAll }) => {
    let containerStyles = "absolute top-full right-0 mt-3 w-80 sm:w-96 rounded-2xl shadow-2xl z-50 overflow-hidden origin-top-right transition-all duration-300 border ";

    if (theme === 'clean') {
        containerStyles += mode === 'dark' ? "bg-slate-800 border-slate-700" : "bg-white border-slate-100";
    } else {
        containerStyles += mode === 'dark' ? "bg-black/60 border-white/10 backdrop-blur-xl" : "bg-white/60 border-white/40 backdrop-blur-xl";
    }

    containerStyles += isOpen ? " opacity-100 scale-100 translate-y-0" : " opacity-0 scale-95 -translate-y-2 pointer-events-none";
    const txtMain = mode === 'dark' ? 'text-white' : 'text-slate-900';
    const txtSec = mode === 'dark' ? 'text-slate-400' : 'text-slate-500';
    const hoverBg = mode === 'dark' ? 'hover:bg-white/5' : 'hover:bg-slate-100/50';
    const divider = mode === 'dark' ? 'border-white/10' : 'border-slate-200/50';

    const unreadCount = notifications.filter(n => !n.read).length;

    return (
        <div className={containerStyles}>
            <div className={`px-4 py-3 border-b ${divider} flex justify-between items-center`}>
                <div className="flex items-center gap-2">
                    <h3 className={`font-semibold ${txtMain}`}>系统通知</h3>
                    {unreadCount > 0 && (
                        <span className="bg-sky-500 text-white text-[10px] px-1.5 py-0.5 rounded-full">{unreadCount}</span>
                    )}
                </div>
                <div className="flex gap-2">
                    <button onClick={onMarkAllRead} className={`text-xs ${theme === 'clean' ? 'text-sky-600 hover:text-sky-700' : 'text-white/70 hover:text-white'} transition-colors`}>
                        全部已读
                    </button>
                    <button onClick={onClearAll} className={`text-xs ${theme === 'clean' ? 'text-slate-400 hover:text-rose-500' : 'text-white/40 hover:text-rose-400'} transition-colors`}>
                        清空
                    </button>
                </div>
            </div>

            <div className="max-h-[320px] overflow-y-auto scrollbar-hide">
                {notifications.length === 0 ? (
                    <div className={`p-8 text-center ${txtSec} text-sm`}>暂无通知</div>
                ) : (
                    notifications.map((notif) => (
                        <div key={notif.id} className={`px-4 py-3 flex gap-3 transition-colors cursor-pointer relative ${hoverBg} ${!notif.read ? 'bg-sky-500/5' : ''}`}>
                            {!notif.read && (
                                <div className="absolute left-1.5 top-1/2 -translate-y-1/2 w-1.5 h-1.5 bg-sky-500 rounded-full"></div>
                            )}
                            <div className={`w-10 h-10 rounded-full flex items-center justify-center shrink-0 ${
                                notif.type === 'warning' ? 'bg-amber-500/20 text-amber-400' :
                                notif.type === 'success' ? 'bg-emerald-500/20 text-emerald-400' :
                                theme === 'clean' ? (mode === 'dark' ? 'bg-slate-700 text-slate-300' : 'bg-slate-100 text-slate-600') : 'bg-white/10 text-white'
                            }`}>
                                <notif.icon size={18} />
                            </div>
                            <div className="flex-1 min-w-0">
                                <p className={`text-sm font-medium truncate ${txtMain} ${!notif.read ? 'font-semibold' : ''}`}>{notif.title}</p>
                                <p className={`text-xs truncate ${txtSec} mt-0.5`}>{notif.desc}</p>
                                <p className={`text-[10px] ${txtSec} opacity-70 mt-1`}>{notif.time}</p>
                            </div>
                        </div>
                    ))
                )}
            </div>
            <div className={`p-3 border-t ${divider} text-center`}>
                <button className={`text-sm font-medium ${txtSec} hover:text-current transition-colors`}>查看全部</button>
            </div>
        </div>
    );
};

export default NotificationDropdown;
