import React, { useState, useEffect } from 'react';
import { X } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Dialog (lightweight, no radix). Mirrors
 * logs/frontend/src/components/ui/dialog.tsx: max-w-[480px], bg-background,
 * rounded-lg, bg-black/20 backdrop-blur-[1px] overlay, text-base title.
 * Legacy `theme`/`mode` props are accepted but ignored.
 */
function Dialog({ isOpen, onClose, title, children, theme: _theme, mode: _mode, className }) {
  void _theme; void _mode;
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync mount with isOpen for enter animation
      setVisible(true);
    } else {
      const tm = setTimeout(() => setVisible(false), 300);
      return () => clearTimeout(tm);
    }
  }, [isOpen]);
  if (!visible && !isOpen) return null;

  return (
    <div
      className={cn(
        'fixed inset-0 z-[60] flex items-center justify-center p-4 transition-all duration-300',
        isOpen ? 'opacity-100' : 'opacity-0',
        'bg-black/20 backdrop-blur-[1px]'
      )}
      onClick={onClose}
    >
      <div
        className={cn(
          'relative w-[calc(100%-2rem)] max-w-[480px] rounded-lg border border-border bg-background p-5 shadow-xl transition-all duration-200',
          isOpen ? 'scale-100 opacity-100' : 'scale-95 opacity-0',
          className
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-col space-y-1.5 text-center sm:text-left mb-4">
          <h3 className="text-base font-medium leading-none">{title}</h3>
        </div>
        <div>{children}</div>
        <button
          type="button"
          onClick={onClose}
          className="absolute right-4 top-4 rounded-sm text-muted-foreground opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring"
          aria-label="Close"
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
}

export { Dialog as default };
