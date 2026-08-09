import React, { useState, useEffect, useRef } from 'react';
import { ChevronDown } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * CustomSelect — legacy dropdown kept for call sites that import it directly.
 * Restyled to the shadcn/ui token idiom (h-8, rounded-md, border-0, bg-secondary/55).
 * Legacy `theme`/`mode` props are accepted but ignored (tokens auto-switch with .dark).
 */
const CustomSelect = ({ options, defaultValue, theme, mode, onChange }) => {
  void theme; void mode;
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

  useEffect(() => {
    if (defaultValue) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync selected with controlled defaultValue prop
      setSelected(defaultValue);
    }
  }, [defaultValue]);

  const menuAnimation = isOpen
    ? 'opacity-100 scale-100 translate-y-0'
    : 'opacity-0 scale-95 -translate-y-1 pointer-events-none';

  return (
    <div className="relative w-full" ref={dropdownRef}>
      <div
        className="flex h-8 w-full items-center justify-between gap-2 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors hover:bg-secondary/70 cursor-pointer"
        onClick={() => setIsOpen(!isOpen)}
      >
        <span className="truncate text-foreground">{selected}</span>
        <ChevronDown size={14} className={cn('shrink-0 opacity-50 transition-transform duration-200', isOpen && 'rotate-180')} />
      </div>
      <div
        className={cn(
          'absolute top-full left-0 mt-1 w-full overflow-hidden rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md origin-top transition-all duration-200 z-50',
          menuAnimation
        )}
      >
        {options.map((opt, i) => (
          <div
            key={i}
            className={cn(
              'flex min-h-8 w-full cursor-default select-none items-center rounded-sm px-2 py-1 text-xs outline-none transition-colors hover:bg-accent hover:text-accent-foreground',
              selected === opt && 'bg-accent font-medium text-accent-foreground'
            )}
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
