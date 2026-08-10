import React from 'react';
import { cn } from '../../lib/cn';

/**
 * shadcn/ui-style Input (lightweight). Mirrors logs/frontend/src/components/ui/input.tsx:
 * h-8 rounded-md border-0 bg-secondary/55 px-3 text-xs. Call sites that need the
 * h-9 card-bg form (e.g. login) pass className overrides like "h-9 bg-card".
 */
const Input = React.forwardRef(({ className, type = 'text', theme: _theme, mode: _mode, ...props }, ref) => {
  void _theme; void _mode;
  return (
  <input
    type={type}
    ref={ref}
    className={cn(
      'flex h-8 w-full rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs transition-colors file:border-0 file:bg-transparent file:text-xs file:font-medium file:text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
      className
    )}
    {...props}
  />
  );
});
Input.displayName = 'Input';

export { Input as default };
