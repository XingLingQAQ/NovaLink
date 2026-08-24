import React, { useState, useEffect, useRef, useId } from 'react';
import { ChevronDown } from 'lucide-react';
import { cn } from '../../lib/cn';

/**
 * CustomSelect — legacy dropdown kept for call sites that import it directly.
 * Restyled to the shadcn/ui token idiom (h-8, rounded-md, border-0, bg-secondary/55).
 * Legacy `theme`/`mode` props are accepted but ignored (tokens auto-switch with .dark).
 * Options may be plain strings or { value, label } objects; the menu shows the
 * label while onChange (and the selected state) always carries the raw value.
 *
 * A11y: real <button> trigger with aria-haspopup/aria-expanded/aria-controls
 * and keyboard support — Enter/Space toggles, Esc closes, ArrowUp/ArrowDown move
 * the highlight, Enter selects the highlighted option. The listbox carries
 * aria-activedescendant pointing at the highlighted option id so screen readers
 * follow the highlight without moving DOM focus. PANEL-009: added aria-label
 * fallback, aria-controls, aria-activedescendant and option ids; dropdown is
 * viewport-constrained with internal scroll for small screens.
 */
const CustomSelect = ({ options, defaultValue, theme, mode, onChange, 'aria-label': ariaLabel }) => {
  void theme; void mode;
  const [isOpen, setIsOpen] = useState(false);
  const [selected, setSelected] = useState(defaultValue);
  const [highlighted, setHighlighted] = useState(-1);
  const dropdownRef = useRef(null);
  const triggerRef = useRef(null);
  const listboxId = useId();

  const normalizedOptions = (options || []).map((opt) =>
    opt !== null && typeof opt === 'object' ? opt : { value: opt, label: String(opt) }
  );
  const selectedOption = normalizedOptions.find((o) => o.value === selected);
  const selectedLabel = selectedOption ? selectedOption.label : selected;

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
    setSelected(defaultValue);
  }, [defaultValue]);

  const openMenu = () => {
    const idx = normalizedOptions.findIndex((o) => o.value === selected);
    setHighlighted(idx >= 0 ? idx : 0);
    setIsOpen(true);
  };

  const selectOption = (opt) => {
    setSelected(opt.value);
    if (onChange) onChange(opt.value);
    setIsOpen(false);
  };

  const handleKeyDown = (e) => {
    if (!isOpen) {
      // Enter/Space fall through to the native button click (which toggles).
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        openMenu();
      }
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      setIsOpen(false);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlighted((i) => Math.min((i < 0 ? -1 : i) + 1, normalizedOptions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlighted((i) => Math.max((i < 0 ? normalizedOptions.length : i) - 1, 0));
    } else if (e.key === 'Home') {
      e.preventDefault();
      setHighlighted(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setHighlighted(normalizedOptions.length - 1);
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      const opt = normalizedOptions[highlighted];
      if (opt) selectOption(opt);
      else setIsOpen(false);
    }
  };

  // Restore focus to the trigger when the menu closes (focus restoration).
  useEffect(() => {
    if (!isOpen && triggerRef.current && dropdownRef.current &&
        document.activeElement && dropdownRef.current.contains(document.activeElement) &&
        document.activeElement !== triggerRef.current) {
      triggerRef.current.focus();
    }
  }, [isOpen]);

  const menuAnimation = isOpen
    ? 'opacity-100 scale-100 translate-y-0'
    : 'opacity-0 scale-95 -translate-y-1 pointer-events-none';

  const triggerA11y = {
    'aria-haspopup': 'listbox',
    'aria-expanded': isOpen,
    'aria-controls': listboxId,
    'aria-activedescendant': isOpen && highlighted >= 0 ? `${listboxId}-opt-${highlighted}` : undefined,
  };
  if (ariaLabel) triggerA11y['aria-label'] = ariaLabel;
  else triggerA11y['aria-label'] = selectedLabel || 'Select option';

  return (
    <div className="relative w-full" ref={dropdownRef}>
      <button
        ref={triggerRef}
        type="button"
        {...triggerA11y}
        className="flex h-8 w-full items-center justify-between gap-2 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs text-left transition-colors hover:bg-secondary/70 cursor-pointer"
        onClick={() => (isOpen ? setIsOpen(false) : openMenu())}
        onKeyDown={handleKeyDown}
      >
        <span className="truncate text-foreground">{selectedLabel}</span>
        <ChevronDown size={14} className={cn('shrink-0 opacity-50 transition-transform duration-200', isOpen && 'rotate-180')} />
      </button>
      <div
        id={listboxId}
        role="listbox"
        aria-label={ariaLabel || 'Options'}
        tabIndex={-1}
        className={cn(
          'absolute top-full left-0 mt-1 w-full max-w-[calc(100vw-1rem)] max-h-60 overflow-y-auto rounded-md border border-border bg-popover p-1 text-popover-foreground shadow-md origin-top transition-all duration-200 z-50',
          menuAnimation
        )}
      >
        {normalizedOptions.map((opt, i) => (
          <div
            key={i}
            id={`${listboxId}-opt-${i}`}
            role="option"
            aria-selected={selected === opt.value}
            className={cn(
              'flex min-h-8 w-full cursor-default select-none items-center rounded-sm px-2 py-1 text-xs outline-none transition-colors hover:bg-accent hover:text-accent-foreground',
              highlighted === i && 'bg-accent text-accent-foreground',
              selected === opt.value && 'bg-accent font-medium text-accent-foreground'
            )}
            onMouseEnter={() => setHighlighted(i)}
            onClick={() => selectOption(opt)}
          >
            {opt.label}
          </div>
        ))}
      </div>
    </div>
  );
};

export default CustomSelect;
