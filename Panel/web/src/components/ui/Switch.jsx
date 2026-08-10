import React from 'react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Switch (lightweight, native). Mirrors logs/frontend/src/components/ui/switch.tsx:
 * h-4 w-7 rounded-full, thumb size-3 translate-x-3. Uses the oklch primary token.
 * Legacy `theme`/`mode` props are accepted but ignored.
 */
const Switch = React.forwardRef(({ checked, onChange, theme: _theme, mode: _mode, className, ...props }, ref) => {
  void _theme; void _mode;
  return (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    ref={ref}
    onClick={() => onChange && onChange(!checked)}
    className={cn(
      'peer inline-flex h-4 w-7 shrink-0 cursor-pointer items-center rounded-full border border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/20 disabled:cursor-not-allowed disabled:opacity-50',
      checked ? 'bg-primary' : 'bg-input',
      className
    )}
    {...props}
  >
    <span
      className={cn(
        'pointer-events-none block size-3 rounded-full bg-background transition-transform',
        checked ? 'translate-x-3' : 'translate-x-0'
      )}
    />
  </button>
  );
});
Switch.displayName = 'Switch';

export { Switch as default };
