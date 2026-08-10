import React, { useState, useEffect, useRef } from 'react';
import { ChevronDown } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Select (lightweight, native HTML — no radix).
 * Mirrors logs/frontend/src/components/ui/select.tsx trigger styling:
 * h-8 rounded-md border-0 bg-secondary/55 px-3 text-xs.
 *
 * Props:
 * - options: Array<string | { value, label }>
 * - value / defaultValue: selected option value
 * - onChange(value)
 * - placeholder
 */
function Select({
  options = [],
  value,
  defaultValue,
  onChange,
  placeholder,
  className,
  theme, // legacy — ignored
  mode, // legacy — ignored
  ...props
}) {
  const [open, setOpen] = useState(false);
  const [internal, setInternal] = useState(defaultValue ?? value ?? '');
  const selected = value !== undefined ? value : internal;
  void theme; void mode;
  const ref = useRef(null);

  useEffect(() => {
    const onClickOutside = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const handleSelect = (opt) => {
    const v = typeof opt === 'object' ? opt.value : opt;
    if (value === undefined) setInternal(v);
    if (onChange) onChange(v);
    setOpen(false);
  };

  const labelFor = (opt) =>
    typeof opt === 'object' ? (opt.label ?? opt.value) : opt;
  const selectedLabel =
    selected !== '' && selected != null
      ? labelFor(
          options.find((o) =>
            typeof o === 'object' ? o.value === selected : o === selected
          ) ?? selected
        )
      : placeholder ?? '';

  return (
    <div className={cn('relative inline-flex w-full', className)} ref={ref} {...props}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex h-8 w-full items-center justify-between gap-2 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors hover:bg-secondary/70 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring [&>span]:line-clamp-1"
      >
        <span className={cn(selected === '' || selected == null ? 'text-muted-foreground' : 'text-foreground')}>
          {selectedLabel}
        </span>
        <ChevronDown size={14} className="shrink-0 opacity-50" />
      </button>
      <div
        className={cn(
          'absolute z-50 mt-1 w-full overflow-hidden rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md transition-all origin-top',
          open ? 'opacity-100 scale-100 translate-y-0' : 'opacity-0 scale-95 -translate-y-1 pointer-events-none'
        )}
      >
        {options.map((opt, i) => {
          const v = typeof opt === 'object' ? opt.value : opt;
          const isSel = v === selected;
          return (
            <button
              type="button"
              key={i}
              onClick={() => handleSelect(opt)}
              className={cn(
                'relative flex min-h-8 w-full cursor-default select-none items-center rounded-sm py-1 pl-2 pr-8 text-xs outline-none transition-colors hover:bg-accent hover:text-accent-foreground',
                isSel && 'bg-accent font-medium text-accent-foreground'
              )}
            >
              {labelFor(opt)}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export { Select as default };
