import React, { useState, useEffect, useRef, useId } from 'react';
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
 * - aria-label: forwarded to the trigger button for screen-reader naming
 *   when no visible <label> is associated (call sites may also pass
 *   aria-labelledby via ...props).
 *
 * PANEL-009: full WAI-ARIA combobox/listbox semantics — the trigger button
 * carries role="combobox", aria-haspopup="listbox", aria-expanded and
 * aria-controls; the popup carries role="listbox"; options carry role="option"
 * + aria-selected. Arrow-key navigation (Up/Down/Home/End), Enter to commit
 * and Esc to close are implemented with aria-activedescendant pointing at the
 * highlighted option id so screen readers follow DOM focus without the option
 * elements needing real .focus() (roving tabindex is unnecessary here).
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
  'aria-label': ariaLabel,
  'aria-labelledby': ariaLabelledBy,
  ...props
}) {
  const [open, setOpen] = useState(false);
  const [internal, setInternal] = useState(defaultValue ?? value ?? '');
  const [active, setActive] = useState(-1);
  const selected = value !== undefined ? value : internal;
  void theme; void mode;
  const ref = useRef(null);
  const triggerRef = useRef(null);
  const listboxId = useId();

  const normalized = (options || []).map((opt) =>
    typeof opt === 'object' && opt !== null ? opt : { value: opt, label: String(opt) }
  );

  useEffect(() => {
    const onClickOutside = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  // Keep the highlighted option in sync when the menu opens or the value
  // changes while open, so arrow navigation always starts from the selection.
  useEffect(() => {
    if (!open) return;
    const idx = normalized.findIndex((o) => o.value === selected);
    setActive(idx >= 0 ? idx : 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

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

  const moveActive = (next) => {
    if (normalized.length === 0) return;
    setActive((cur) => {
      const start = cur < 0 ? -1 : cur;
      const idx = (start + next + normalized.length) % normalized.length;
      return idx;
    });
  };

  const handleTriggerKeyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (!open) {
        setOpen(true);
        return;
      }
      if (e.key === 'ArrowDown') moveActive(1);
      else if (e.key === 'ArrowUp') moveActive(-1);
      else if (e.key === 'Enter' || e.key === ' ') {
        const opt = normalized[active];
        if (opt) handleSelect(opt);
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault();
        setOpen(false);
      }
    } else if (e.key === 'Home') {
      if (open) { e.preventDefault(); setActive(0); }
    } else if (e.key === 'End') {
      if (open) { e.preventDefault(); setActive(normalized.length - 1); }
    }
  };

  // Return focus to the trigger when the popup closes (focus restoration).
  useEffect(() => {
    if (!open && triggerRef.current && document.activeElement && ref.current &&
        ref.current.contains(document.activeElement) &&
        document.activeElement !== triggerRef.current) {
      triggerRef.current.focus();
    }
  }, [open]);

  const triggerA11y = {};
  if (ariaLabel) triggerA11y['aria-label'] = ariaLabel;
  if (ariaLabelledBy) triggerA11y['aria-labelledby'] = ariaLabelledBy;
  if (!ariaLabel && !ariaLabelledBy) {
    // Ensure the control has an accessible name even without an explicit
    // label association — screen readers read the selected/placeholder text.
    triggerA11y['aria-label'] = selectedLabel || placeholder || 'Select';
  }

  return (
    <div className={cn('relative inline-flex w-full', className)} ref={ref} {...props}>
      <button
        ref={triggerRef}
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-activedescendant={open && active >= 0 ? `${listboxId}-opt-${active}` : undefined}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={handleTriggerKeyDown}
        className="flex h-8 w-full items-center justify-between gap-2 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors hover:bg-secondary/70 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring [&>span]:line-clamp-1"
        {...triggerA11y}
      >
        <span className={cn(selected === '' || selected == null ? 'text-muted-foreground' : 'text-foreground')}>
          {selectedLabel}
        </span>
        <ChevronDown size={14} className="shrink-0 opacity-50" />
      </button>
      <div
        id={listboxId}
        role="listbox"
        aria-label={ariaLabel || placeholder || undefined}
        tabIndex={-1}
        className={cn(
          'absolute z-50 mt-1 w-full max-w-[calc(100vw-1rem)] overflow-y-auto max-h-60 rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md transition-all origin-top',
          open ? 'opacity-100 scale-100 translate-y-0' : 'opacity-0 scale-95 -translate-y-1 pointer-events-none'
        )}
      >
        {normalized.map((opt, i) => {
          const v = opt.value;
          const isSel = v === selected;
          return (
            <div
              key={i}
              id={`${listboxId}-opt-${i}`}
              role="option"
              aria-selected={isSel}
              onMouseDown={(e) => { e.preventDefault(); handleSelect(opt); }}
              onMouseEnter={() => setActive(i)}
              className={cn(
                'relative flex min-h-8 w-full cursor-default select-none items-center rounded-sm py-1 pl-2 pr-8 text-xs outline-none transition-colors hover:bg-accent hover:text-accent-foreground',
                isSel && 'bg-accent font-medium text-accent-foreground',
                active === i && !isSel && 'bg-accent/60 text-accent-foreground'
              )}
            >
              {labelFor(opt)}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export { Select as default };
